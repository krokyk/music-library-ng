<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useLibraryStore } from '@/stores/library'
import type { Album, Artist } from '@/types'

interface ArtistForm {
  id: number | null
  name: string
  sortName: string
  notes: string
  collectionIds: string[]
}

interface ProviderLinkForm {
  id?: number
  providerId: string
  providerUrl: string
  enabled: boolean
}

const store = useLibraryStore()
const {
  collections,
  collectionArtists,
  collectionAlbums,
  selectedCollectionId,
  selectedArtistId,
  providerLinks,
  loading,
  error,
} = storeToRefs(store)

const artistDialog = ref(false)
const deleteDialog = ref(false)
const albumToDelete = ref<Album | null>(null)
const providerMessage = ref('')
const refreshingArtistId = ref<number | null>(null)
const savingArtist = ref(false)

const artistForm = reactive<ArtistForm>({
  id: null,
  name: '',
  sortName: '',
  notes: '',
  collectionIds: [],
})

const providerLinkForms = ref<ProviderLinkForm[]>([])
const newProviderLink = reactive<ProviderLinkForm>({
  providerId: 'spirit_of_metal',
  providerUrl: '',
  enabled: true,
})

const providerIds = [
  { title: 'Spirit of Metal', value: 'spirit_of_metal' },
  { title: 'Metal Archives', value: 'metal_archives' },
]

const selectedCollection = computed(() =>
  collections.value.find((collection) => collection.id === selectedCollectionId.value) ?? null,
)

const selectedArtist = computed(() =>
  collectionArtists.value.find((artist) => artist.id === selectedArtistId.value) ?? null,
)

const collectionOptions = computed(() =>
  collections.value.map((collection) => ({ title: collection.name, value: collection.id })),
)

function artistIssueLabel(artist: Artist) {
  if (artist.uncheckedAlbumCount > 0) {
    return `${artist.uncheckedAlbumCount} unchecked`
  }
  return ''
}

function albumDiskTitle(album: Album) {
  const activePaths = album.localPaths.filter((path) => !path.missingSince)
  if (activePaths.length === 0) {
    return 'No local folder'
  }
  return activePaths.map((path) => path.resolvedPath ?? path.relativePath).join('\n')
}

async function openArtistDialog(artist?: Artist) {
  artistForm.id = artist?.id ?? null
  artistForm.name = artist?.name ?? ''
  artistForm.sortName = artist?.sortName ?? ''
  artistForm.notes = artist?.notes ?? ''
  artistForm.collectionIds = artist?.collectionIds?.length
    ? [...artist.collectionIds]
    : selectedCollectionId.value
      ? [selectedCollectionId.value]
      : []
  providerLinkForms.value = []
  newProviderLink.providerId = 'spirit_of_metal'
  newProviderLink.providerUrl = ''
  newProviderLink.enabled = true
  artistDialog.value = true

  if (artist?.id) {
    await reloadProviderLinkForms(artist.id)
  }
}

async function reloadProviderLinkForms(artistId: number) {
  await store.loadProviderLinks(artistId)
  providerLinkForms.value = (providerLinks.value[artistId] ?? []).map((link) => ({
    id: link.id,
    providerId: link.providerId,
    providerUrl: link.providerUrl,
    enabled: link.enabled,
  }))
}

async function saveArtistDetails() {
  if (!artistForm.name.trim()) {
    return
  }
  savingArtist.value = true
  try {
    const wasNew = artistForm.id === null
    const artist = await store.saveArtist({
      id: artistForm.id ?? undefined,
      name: artistForm.name.trim(),
      sortName: artistForm.sortName.trim() || null,
      notes: artistForm.notes.trim() || null,
      collectionIds: artistForm.collectionIds,
    })
    artistForm.id = artist.id
    if (wasNew && selectedCollectionId.value && artist.collectionIds.includes(selectedCollectionId.value)) {
      await store.selectArtist(artist.id)
    }
    await reloadProviderLinkForms(artist.id)
  } finally {
    savingArtist.value = false
  }
}

async function saveProviderLink(link: ProviderLinkForm) {
  if (!artistForm.id || !link.providerUrl.trim()) {
    return
  }
  await store.saveProviderLink(artistForm.id, {
    ...link,
    providerUrl: link.providerUrl.trim(),
  })
  await reloadProviderLinkForms(artistForm.id)
}

async function addProviderLink() {
  if (!artistForm.id || !newProviderLink.providerUrl.trim()) {
    return
  }
  await store.saveProviderLink(artistForm.id, {
    providerId: newProviderLink.providerId,
    providerUrl: newProviderLink.providerUrl.trim(),
    enabled: newProviderLink.enabled,
  })
  newProviderLink.providerUrl = ''
  await reloadProviderLinkForms(artistForm.id)
}

async function deleteProviderLink(link: ProviderLinkForm) {
  if (!artistForm.id || !link.id) {
    return
  }
  await store.deleteProviderLink(artistForm.id, link.id)
  await reloadProviderLinkForms(artistForm.id)
}

async function refreshArtist(artist: Artist) {
  refreshingArtistId.value = artist.id
  providerMessage.value = ''
  try {
    const summary = await store.checkArtistProvider(artist.id)
    providerMessage.value = summary.messages.join(' ')
  } catch (error) {
    store.error = error instanceof Error ? error.message : String(error)
  } finally {
    refreshingArtistId.value = null
  }
}

async function updateAlbumChecked(album: Album, checked: boolean) {
  await store.updateAlbum({ ...album, checked })
}

function askDeleteAlbum(album: Album) {
  albumToDelete.value = album
  deleteDialog.value = true
}

async function deleteAlbum() {
  if (!albumToDelete.value) {
    return
  }
  await store.deleteAlbum(albumToDelete.value.id)
  deleteDialog.value = false
  albumToDelete.value = null
}

onMounted(() => store.loadCollections())
</script>

<template>
  <v-container fluid class="app-page collections-workspace">
    <v-alert v-if="error" type="error" variant="tonal" class="mb-3">{{ error }}</v-alert>
    <v-alert v-if="providerMessage" type="info" variant="tonal" class="mb-3">{{ providerMessage }}</v-alert>

    <div class="three-pane">
      <v-sheet class="pane collections-pane">
        <div class="pane-header">
          <span>Collections</span>
          <div class="pane-header__actions">
            <v-tooltip text="Scan selected collection" location="top">
              <template #activator="{ props }">
                <v-btn
                  v-bind="props"
                  icon="mdi-database-search"
                  size="small"
                  variant="text"
                  :disabled="!selectedCollectionId"
                  :loading="loading"
                  @click="store.scan(selectedCollectionId ?? undefined)"
                ></v-btn>
              </template>
            </v-tooltip>
            <v-tooltip text="Settings" location="top">
              <template #activator="{ props }">
                <v-btn v-bind="props" icon="mdi-cog" size="small" variant="text" to="/settings"></v-btn>
              </template>
            </v-tooltip>
          </div>
        </div>

        <div class="collection-list">
          <button
            v-for="collection in collections"
            :key="collection.id"
            class="nav-row"
            :class="{ 'is-selected': collection.id === selectedCollectionId }"
            :disabled="!collection.enabled"
            @click="store.selectCollection(collection.id)"
          >
            <span class="nav-row__title">{{ collection.name }}</span>
            <span v-if="collection.lastScanStatus" class="nav-row__meta">{{ collection.lastScanStatus }}</span>
          </button>
        </div>
      </v-sheet>

      <v-sheet class="pane artists-pane">
        <div class="pane-header">
          <span>Artists</span>
          <div class="pane-header__actions">
            <v-tooltip text="Add artist" location="top">
              <template #activator="{ props }">
                <v-btn
                  v-bind="props"
                  icon="mdi-account-plus"
                  size="small"
                  variant="text"
                  :disabled="!selectedCollectionId"
                  @click="openArtistDialog()"
                ></v-btn>
              </template>
            </v-tooltip>
          </div>
        </div>

        <div v-if="!selectedCollection" class="pane-empty">Select a collection.</div>
        <v-table v-else class="music-table workspace-table" density="compact" fixed-header>
          <thead>
            <tr>
              <th>Name</th>
              <th class="text-right"></th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="artist in collectionArtists"
              :key="artist.id"
              class="workspace-row"
              :class="{ 'is-selected': artist.id === selectedArtistId }"
              @click="store.selectArtist(artist.id)"
            >
              <td>
                <div class="artist-cell">
                  <span class="cell-strong">{{ artist.name }}</span>
                  <v-chip
                    v-if="artistIssueLabel(artist)"
                    color="warning"
                    size="x-small"
                    variant="tonal"
                  >
                    {{ artistIssueLabel(artist) }}
                  </v-chip>
                </div>
              </td>
              <td class="row-action-cell">
                <div class="row-actions">
                  <v-tooltip text="Edit artist" location="top">
                    <template #activator="{ props }">
                      <v-btn
                        v-bind="props"
                        icon="mdi-pencil"
                        size="x-small"
                        variant="text"
                        @click.stop="openArtistDialog(artist)"
                      ></v-btn>
                    </template>
                  </v-tooltip>
                  <v-tooltip text="Refresh discography" location="top">
                    <template #activator="{ props }">
                      <v-btn
                        v-bind="props"
                        icon="mdi-refresh"
                        size="x-small"
                        variant="text"
                        :loading="refreshingArtistId === artist.id"
                        @click.stop="refreshArtist(artist)"
                      ></v-btn>
                    </template>
                  </v-tooltip>
                </div>
              </td>
            </tr>
          </tbody>
        </v-table>
      </v-sheet>

      <v-sheet class="pane albums-pane">
        <div class="pane-header">
          <span>Albums</span>
          <span v-if="selectedArtist" class="pane-header__meta">{{ selectedArtist.name }}</span>
        </div>

        <div v-if="!selectedArtist" class="pane-empty">Select an artist.</div>
        <v-table v-else class="music-table workspace-table" density="compact" fixed-header>
          <thead>
            <tr>
              <th>Name</th>
              <th style="width: 100px">Year</th>
              <th style="width: 120px">Checked</th>
              <th style="width: 56px"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="album in collectionAlbums" :key="album.id" class="workspace-row">
              <td>
                <div class="album-cell">
                  <span>{{ album.title }}</span>
                  <v-tooltip :text="albumDiskTitle(album)" location="top">
                    <template #activator="{ props }">
                      <v-icon
                        v-if="album.onDisk || album.hasLocalPath"
                        v-bind="props"
                        :icon="album.onDisk ? 'mdi-harddisk' : 'mdi-folder-alert'"
                        :color="album.onDisk ? 'success' : 'warning'"
                        size="16"
                      ></v-icon>
                    </template>
                  </v-tooltip>
                </div>
              </td>
              <td class="cell-muted">{{ album.releaseYear ?? '' }}</td>
              <td>
                <v-checkbox
                  :model-value="album.checked"
                  density="compact"
                  hide-details
                  @click.stop
                  @update:model-value="(value) => updateAlbumChecked(album, Boolean(value))"
                ></v-checkbox>
              </td>
              <td class="row-action-cell">
                <div class="row-actions">
                  <v-tooltip text="Delete album" location="top">
                    <template #activator="{ props }">
                      <v-btn
                        v-bind="props"
                        icon="mdi-trash-can-outline"
                        size="x-small"
                        variant="text"
                        color="error"
                        @click.stop="askDeleteAlbum(album)"
                      ></v-btn>
                    </template>
                  </v-tooltip>
                </div>
              </td>
            </tr>
          </tbody>
        </v-table>
      </v-sheet>
    </div>

    <v-dialog v-model="artistDialog" max-width="860">
      <v-card class="dialog-card">
        <v-card-title>{{ artistForm.id ? 'Artist Details' : 'Add Artist' }}</v-card-title>
        <v-card-text>
          <v-row dense>
            <v-col cols="12" md="6">
              <v-text-field v-model="artistForm.name" label="Name" hide-details="auto"></v-text-field>
            </v-col>
            <v-col cols="12" md="6">
              <v-text-field v-model="artistForm.sortName" label="Sort name" hide-details="auto"></v-text-field>
            </v-col>
            <v-col cols="12">
              <v-select
                v-model="artistForm.collectionIds"
                :items="collectionOptions"
                label="Collections"
                multiple
                chips
                hide-details="auto"
              ></v-select>
            </v-col>
            <v-col cols="12">
              <v-textarea
                v-model="artistForm.notes"
                label="Notes"
                rows="3"
                auto-grow
                hide-details="auto"
                variant="outlined"
              ></v-textarea>
            </v-col>
          </v-row>

          <div class="modal-section-title">Provider Links</div>
          <div v-if="!artistForm.id" class="cell-muted mb-3">Save the artist before adding provider links.</div>
          <div v-else class="provider-editor">
            <div v-for="link in providerLinkForms" :key="link.id" class="provider-editor-row">
              <v-select
                v-model="link.providerId"
                :items="providerIds"
                label="Provider"
                hide-details
              ></v-select>
              <v-text-field v-model="link.providerUrl" label="URL" hide-details></v-text-field>
              <v-checkbox v-model="link.enabled" label="Enabled" density="compact" hide-details></v-checkbox>
              <v-btn icon="mdi-content-save" size="small" variant="text" @click="saveProviderLink(link)"></v-btn>
              <v-btn
                icon="mdi-trash-can-outline"
                size="small"
                variant="text"
                color="error"
                @click="deleteProviderLink(link)"
              ></v-btn>
            </div>

            <div class="provider-editor-row provider-editor-row--new">
              <v-select
                v-model="newProviderLink.providerId"
                :items="providerIds"
                label="Provider"
                hide-details
              ></v-select>
              <v-text-field
                v-model="newProviderLink.providerUrl"
                label="URL"
                hide-details
                @keyup.enter="addProviderLink"
              ></v-text-field>
              <v-checkbox v-model="newProviderLink.enabled" label="Enabled" density="compact" hide-details></v-checkbox>
              <v-btn icon="mdi-plus" size="small" color="primary" variant="text" @click="addProviderLink"></v-btn>
              <span></span>
            </div>
          </div>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn variant="text" @click="artistDialog = false">Close</v-btn>
          <v-btn color="primary" :loading="savingArtist" @click="saveArtistDetails">Save</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog v-model="deleteDialog" max-width="420">
      <v-card class="dialog-card">
        <v-card-title>Delete Album</v-card-title>
        <v-card-text>
          Delete {{ albumToDelete?.title }}?
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn variant="text" @click="deleteDialog = false">Cancel</v-btn>
          <v-btn color="error" @click="deleteAlbum">Delete</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </v-container>
</template>
