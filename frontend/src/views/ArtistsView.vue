<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useLibraryStore } from '@/stores/library'
import type { Artist } from '@/types'

const store = useLibraryStore()
const { artists, collections, providerLinks, providerCheckRuns, loading, error } = storeToRefs(store)

const search = ref('')
const selectedArtistId = ref<number | null>(null)
const providerId = ref('spirit_of_metal')
const providerUrl = ref('')
const lastMessage = ref('')
const artistToDelete = ref<Artist | null>(null)
const deleteArtistDialog = ref(false)
const deleteArtistWarningDialog = ref(false)
const deletingArtist = ref(false)
const deletingArtistId = ref<number | null>(null)

const filteredArtists = computed(() => {
  const needle = search.value.trim().toLowerCase()
  if (!needle) return artists.value
  return artists.value.filter((artist) => artist.name.toLowerCase().includes(needle))
})

const selectedArtist = computed(() => artists.value.find((artist) => artist.id === selectedArtistId.value) ?? null)

async function selectArtist(artist: Artist) {
  selectedArtistId.value = artist.id
  providerUrl.value = ''
  await store.loadProviderLinks(artist.id)
}

async function addProviderLink() {
  if (!selectedArtistId.value || !providerUrl.value.trim()) return
  await store.addProviderLink(selectedArtistId.value, providerId.value, providerUrl.value.trim())
  providerUrl.value = ''
}

async function checkArtist(artistId: number) {
  const summary = await store.checkArtistProvider(artistId)
  lastMessage.value = summary.messages.join(' ')
}

async function checkAll() {
  const summary = await store.checkAllProviders()
  lastMessage.value = summary.messages.join(' ')
}

function askDeleteArtist(artist: Artist) {
  artistToDelete.value = artist
  deleteArtistDialog.value = true
}

function artistDeleteNeedsWarning(artist: Artist | null) {
  return Boolean(artist && (artist.collectionIds.length > 0 || artist.localAlbumCount > 0))
}

function artistDeleteWarningLines(artist: Artist | null) {
  if (!artist) {
    return []
  }
  const lines: string[] = []
  if (artist.localAlbumCount > 0) {
    lines.push(`Local albums in the library DB: ${artist.localAlbumCount}`)
  }
  return lines
}

function artistDeleteCollections(artist: Artist | null) {
  if (!artist) {
    return []
  }
  return artist.collectionIds.map((collectionId) => ({
    id: collectionId,
    name: collections.value.find((collection) => collection.id === collectionId)?.name ?? collectionId,
  }))
}

function confirmDeleteArtist() {
  deleteArtistDialog.value = false
  if (artistDeleteNeedsWarning(artistToDelete.value)) {
    deleteArtistWarningDialog.value = true
    return
  }
  void deleteArtist()
}

async function deleteArtist() {
  if (!artistToDelete.value) {
    return
  }
  deletingArtist.value = true
  deletingArtistId.value = artistToDelete.value.id
  try {
    const artistId = artistToDelete.value.id
    await store.deleteArtist(artistId)
    if (selectedArtistId.value === artistId) {
      selectedArtistId.value = null
    }
    deleteArtistDialog.value = false
    deleteArtistWarningDialog.value = false
    artistToDelete.value = null
  } catch (error) {
    store.error = error instanceof Error ? error.message : String(error)
  } finally {
    deletingArtist.value = false
    deletingArtistId.value = null
  }
}

onMounted(() => store.loadAll())
</script>

<template>
  <v-container fluid class="app-page artists-page">
    <v-alert v-if="error" type="error" variant="tonal" class="mb-4">{{ error }}</v-alert>
    <v-alert v-if="lastMessage" type="info" variant="tonal" class="mb-4">{{ lastMessage }}</v-alert>

    <div class="page-header">
      <div>
        <div class="page-title">Artists</div>
        <div class="stat-strip">
          <span>{{ artists.length }} artists</span>
          <span>{{ artists.reduce((sum, artist) => sum + artist.uncheckedAlbumCount, 0) }} unchecked albums</span>
          <span>{{ artists.reduce((sum, artist) => sum + artist.providerLinkCount, 0) }} provider links</span>
        </div>
      </div>
      <v-btn :loading="loading" color="primary" prepend-icon="mdi-cloud-search" @click="checkAll">
        Check all
      </v-btn>
    </div>

    <v-row dense>
      <v-col cols="12" lg="8">
        <v-sheet class="panel table-wrap">
          <div class="data-toolbar">
            <v-text-field
              v-model="search"
              class="data-toolbar__search"
              prepend-inner-icon="mdi-magnify"
              density="compact"
              label="Search artists"
              hide-details
            ></v-text-field>
          </div>

          <v-table class="music-table" density="compact" fixed-header height="calc(var(--app-vh) - 230px)">
            <thead>
              <tr>
                <th>Artist</th>
                <th>Albums</th>
                <th>Unchecked</th>
                <th>Local</th>
                <th>Providers</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="artist in filteredArtists"
                :key="artist.id"
                :class="{ 'music-table-row--deleting': deletingArtistId === artist.id }"
              >
                <td class="cell-strong">
                  <span class="artist-delete-cell">
                    <v-progress-circular
                      v-if="deletingArtistId === artist.id"
                      indeterminate
                      size="14"
                      width="2"
                    ></v-progress-circular>
                    <span>{{ artist.name }}</span>
                  </span>
                </td>
                <td>{{ artist.albumCount }}</td>
                <td>
                  <v-chip :color="artist.uncheckedAlbumCount > 0 ? 'warning' : 'default'" size="small" variant="tonal">
                    {{ artist.uncheckedAlbumCount }}
                  </v-chip>
                </td>
                <td>{{ artist.localAlbumCount }}</td>
                <td>{{ artist.providerLinkCount }}</td>
                <td class="text-right">
                  <v-btn
                    size="small"
                    variant="text"
                    :disabled="deletingArtistId === artist.id"
                    @click="selectArtist(artist)"
                  >
                    Links
                  </v-btn>
                  <v-btn
                    size="small"
                    variant="text"
                    prepend-icon="mdi-cloud-search"
                    :disabled="deletingArtistId === artist.id"
                    @click="checkArtist(artist.id)"
                  >
                    Check
                  </v-btn>
                  <v-btn
                    size="small"
                    variant="text"
                    color="error"
                    prepend-icon="mdi-trash-can-outline"
                    :loading="deletingArtistId === artist.id"
                    :disabled="deletingArtistId !== null && deletingArtistId !== artist.id"
                    @click="askDeleteArtist(artist)"
                  >
                    Delete
                  </v-btn>
                </td>
              </tr>
            </tbody>
          </v-table>
        </v-sheet>
      </v-col>

      <v-col cols="12" lg="4">
        <v-sheet class="panel pa-4 mb-4">
          <div class="panel-title">Provider Links</div>
          <div v-if="!selectedArtist" class="cell-muted">Select an artist to manage provider links.</div>
          <template v-else>
            <div class="mb-3 cell-strong">{{ selectedArtist.name }}</div>
            <v-list density="compact" class="provider-list mb-3">
              <v-list-item v-for="link in providerLinks[selectedArtist.id] ?? []" :key="link.id">
                <v-list-item-title>{{ link.providerId }}</v-list-item-title>
                <v-list-item-subtitle class="mono-path">{{ link.providerUrl }}</v-list-item-subtitle>
              </v-list-item>
            </v-list>
            <v-select
              v-model="providerId"
              :items="['spirit_of_metal', 'metal_archives']"
              density="compact"
              label="Provider"
              hide-details
              class="mb-3"
            ></v-select>
            <v-text-field
              v-model="providerUrl"
              density="compact"
              label="Provider URL"
              hide-details
              class="mb-3"
              @keyup.enter="addProviderLink"
            ></v-text-field>
            <v-btn color="primary" prepend-icon="mdi-link-plus" @click="addProviderLink">Add link</v-btn>
          </template>
        </v-sheet>

        <v-sheet class="panel pa-4">
          <div class="panel-title">Recent Checks</div>
          <v-list density="compact" class="provider-list">
            <v-list-item v-for="run in providerCheckRuns" :key="run.id">
              <template #prepend>
                <v-chip
                  size="x-small"
                  :color="run.status === 'DONE' ? 'success' : run.status === 'FAILED' ? 'error' : 'warning'"
                >
                  {{ run.status }}
                </v-chip>
              </template>
              <v-list-item-title>{{ run.message ?? `${run.newAlbumCount} new albums` }}</v-list-item-title>
              <v-list-item-subtitle>{{ run.startedAt }}</v-list-item-subtitle>
            </v-list-item>
          </v-list>
        </v-sheet>
      </v-col>
    </v-row>

    <v-dialog v-model="deleteArtistDialog" max-width="460">
      <v-card class="dialog-card">
        <v-card-title>Delete Artist</v-card-title>
        <v-card-text>
          Delete <span class="dialog-entity-name">{{ artistToDelete?.name }}</span> from the library database?
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn variant="text" @click="deleteArtistDialog = false">Cancel</v-btn>
          <v-btn color="error" :loading="deletingArtist" @click="confirmDeleteArtist">Delete</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog v-model="deleteArtistWarningDialog" max-width="520">
      <v-card class="dialog-card">
        <v-card-title>Delete Artist With Library Data</v-card-title>
        <v-card-text class="edit-form">
          <div>
            <span class="dialog-entity-name">{{ artistToDelete?.name }}</span> is linked to existing library data.
            Deleting it removes related database records only; files on disk are not touched.
          </div>
          <div class="cell-muted">
            <div v-if="artistDeleteCollections(artistToDelete).length" class="dialog-chip-row">
              <v-chip
                v-for="collection in artistDeleteCollections(artistToDelete)"
                :key="collection.id"
                size="small"
                color="primary"
                variant="tonal"
              >
                {{ collection.name }}
              </v-chip>
            </div>
            <div v-for="line in artistDeleteWarningLines(artistToDelete)" :key="line">{{ line }}</div>
          </div>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn variant="text" @click="deleteArtistWarningDialog = false">Cancel</v-btn>
          <v-btn color="error" :loading="deletingArtist" @click="deleteArtist">Delete anyway</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </v-container>
</template>
