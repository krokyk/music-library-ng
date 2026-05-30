<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useLibraryStore } from '@/stores/library'
import type { Album, Artist, CollectionFolderCandidate, MusicCollection } from '@/types'

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
  collectionCandidates,
  collectionArtists,
  collectionAlbums,
  selectedCollectionId,
  selectedArtistId,
  providerLinks,
  scanJob,
  uiSettings,
  error,
} = storeToRefs(store)

const artistDialog = ref(false)
const deleteDialog = ref(false)
const addCollectionDialog = ref(false)
const deleteCollectionDialog = ref(false)
const albumToDelete = ref<Album | null>(null)
const collectionToDelete = ref<MusicCollection | null>(null)
const refreshingArtistId = ref<number | null>(null)
const savingArtist = ref(false)
const scanPoller = ref<number | null>(null)
const editingCollectionId = ref<string | null>(null)
const editingCollectionName = ref('')
const collectionPage = ref(1)
const threePaneElement = ref<HTMLElement | null>(null)
const panePercents = ref([27, 30, 43])
const paneLayoutSaveTimer = ref<number | null>(null)
const paneNames = ['collections', 'artists', 'albums'] as const

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

const scanIsRunning = computed(() => scanJob.value?.status === 'RUNNING')
const paneLayoutPreferenceKey = 'collections.paneLayout'
const collectionPageSize = 10
const collectionPageCount = computed(() => Math.max(1, Math.ceil(collectionCandidates.value.length / collectionPageSize)))
const collectionPageStart = computed(() => (collectionPage.value - 1) * collectionPageSize)
const collectionPageEnd = computed(() =>
  Math.min(collectionPageStart.value + collectionPageSize, collectionCandidates.value.length),
)
const pagedCollectionCandidates = computed(() =>
  collectionCandidates.value.slice(collectionPageStart.value, collectionPageEnd.value),
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

function scanProgress(collection: MusicCollection) {
  if (!uiSettings.value.collectionScanProgressEnabled || !scanIsRunning.value || scanJob.value?.activeCollectionId !== collection.id) {
    return 0
  }
  if (scanJob.value.artistTotal <= 0) {
    return 0
  }
  return Math.min(100, (scanJob.value.artistProcessed / scanJob.value.artistTotal) * 100)
}

function askDeleteCollection(collection: MusicCollection) {
  collectionToDelete.value = collection
  deleteCollectionDialog.value = true
}

async function deleteCollection() {
  if (!collectionToDelete.value) {
    return
  }
  await store.deleteCollection(collectionToDelete.value.id)
  deleteCollectionDialog.value = false
  collectionToDelete.value = null
}

async function openAddCollectionDialog() {
  await store.loadCollectionCandidates()
  collectionPage.value = 1
  addCollectionDialog.value = true
}

async function addCollection(candidate: CollectionFolderCandidate) {
  await store.createCollection(candidate.relativePath)
  if (collectionPage.value > collectionPageCount.value) {
    collectionPage.value = collectionPageCount.value
  }
}

function previousCollectionPage() {
  collectionPage.value = Math.max(1, collectionPage.value - 1)
}

function nextCollectionPage() {
  collectionPage.value = Math.min(collectionPageCount.value, collectionPage.value + 1)
}

function startInlineCollectionEdit(collection: MusicCollection) {
  editingCollectionId.value = collection.id
  editingCollectionName.value = collection.name
}

async function saveInlineCollectionEdit(collection: MusicCollection) {
  const name = editingCollectionName.value.trim()
  editingCollectionId.value = null
  if (!name || name === collection.name) {
    return
  }
  await store.updateCollection(collection.id, { name })
}

function cancelInlineCollectionEdit() {
  editingCollectionId.value = null
  editingCollectionName.value = ''
}

async function startScan(collectionId: string) {
  await store.startScanJob(collectionId)
  startScanPolling()
}

function startScanPolling() {
  if (scanPoller.value !== null) {
    return
  }
  const intervalMs = Math.min(2000, Math.max(100, uiSettings.value.scanPollIntervalMs))
  scanPoller.value = window.setInterval(async () => {
    const status = await store.loadScanJob()
    if (!status || status.status !== 'RUNNING') {
      stopScanPolling()
      await store.loadCollections()
      await store.refreshCollectionArtistsOnly(true)
    }
  }, intervalMs)
}

function collectionIsScanning(collection: MusicCollection) {
  return scanIsRunning.value && scanJob.value?.activeCollectionId === collection.id
}

function selectCollection(collection: MusicCollection) {
  if (collectionIsScanning(collection)) {
    return
  }
  void store.selectCollection(collection.id)
}

function paneStyle(index: number) {
  const resizerWidth = 20
  const resizerShare = (resizerWidth * panePercents.value[index]) / 100
  return {
    flex: `0 0 calc(${panePercents.value[index]}% - ${resizerShare}px)`,
  }
}

function startPaneResize(index: number, event: PointerEvent) {
  event.preventDefault()
  if (!threePaneElement.value) {
    return
  }
  const startX = event.clientX
  const startPercents = [...panePercents.value]
  const paneAreaWidth = Math.max(1, threePaneElement.value.clientWidth - 20)
  const minimums = [320, 280, 380]

  function move(pointerEvent: PointerEvent) {
    const deltaPercent = ((pointerEvent.clientX - startX) / paneAreaWidth) * 100
    const combined = startPercents[index] + startPercents[index + 1]
    const leftMinimum = (minimums[index] / paneAreaWidth) * 100
    const rightMinimum = (minimums[index + 1] / paneAreaWidth) * 100
    const left = Math.min(Math.max(leftMinimum, startPercents[index] + deltaPercent), combined - rightMinimum)
    const right = combined - left

    panePercents.value = normalizePanePercents(
      panePercents.value.map((percent, percentIndex) => {
        if (percentIndex === index) return left
        if (percentIndex === index + 1) return right
        return percent
      }),
    )
    schedulePaneLayoutSave()
  }

  function stop() {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', stop)
    savePaneLayout()
  }

  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', stop)
}

async function loadPaneLayout() {
  const preference = await store.loadPreference(paneLayoutPreferenceKey)
  if (!preference?.value) {
    return
  }
  try {
    const parsed = JSON.parse(preference.value)
    if (isPaneLayoutObject(parsed)) {
      panePercents.value = normalizePanePercents([
        parsed.collections,
        parsed.artists,
        parsed.albums,
      ])
    } else if (Array.isArray(parsed) && parsed.length === 3 && parsed.every((value) => typeof value === 'number')) {
      panePercents.value = normalizePanePercents(parsed)
    }
  } catch (error) {
    // Ignore invalid stored UI state and keep the default layout.
  }
}

function schedulePaneLayoutSave() {
  if (paneLayoutSaveTimer.value !== null) {
    window.clearTimeout(paneLayoutSaveTimer.value)
  }
  paneLayoutSaveTimer.value = window.setTimeout(savePaneLayout, 200)
}

function savePaneLayout() {
  if (paneLayoutSaveTimer.value !== null) {
    window.clearTimeout(paneLayoutSaveTimer.value)
    paneLayoutSaveTimer.value = null
  }
  const rounded = normalizePanePercents(panePercents.value).map((value) => Math.round(value * 100) / 100)
  panePercents.value = rounded
  void store.savePreference(paneLayoutPreferenceKey, JSON.stringify(paneLayoutObject(rounded)))
}

function normalizePanePercents(values: number[]) {
  const fallback = [27, 30, 43]
  const cleaned = values.map((value) => (Number.isFinite(value) && value > 0 ? value : 0))
  const total = cleaned.reduce((sum, value) => sum + value, 0)
  if (total <= 0) {
    return fallback
  }
  const normalized = cleaned.map((value) => (value / total) * 100)
  const rounded = normalized.map((value) => Math.round(value * 10000) / 10000)
  rounded[2] = Math.round((100 - rounded[0] - rounded[1]) * 10000) / 10000
  return rounded
}

function paneLayoutObject(values: number[]) {
  return {
    [paneNames[0]]: values[0],
    [paneNames[1]]: values[1],
    [paneNames[2]]: values[2],
  }
}

function isPaneLayoutObject(value: unknown): value is Record<(typeof paneNames)[number], number> {
  if (!value || typeof value !== 'object') {
    return false
  }
  const layout = value as Record<string, unknown>
  return paneNames.every((name) => typeof layout[name] === 'number')
}

function stopScanPolling() {
  if (scanPoller.value === null) {
    return
  }
  window.clearInterval(scanPoller.value)
  scanPoller.value = null
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
  try {
    await store.checkArtistProvider(artist.id)
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

onMounted(async () => {
  await store.loadUiSettings()
  await loadPaneLayout()
  await store.loadCollections()
  await store.loadScanJob()
  if (scanIsRunning.value) {
    startScanPolling()
  }
})

onBeforeUnmount(() => {
  stopScanPolling()
  if (paneLayoutSaveTimer.value !== null) {
    savePaneLayout()
  }
})
</script>

<template>
  <v-container fluid class="app-page collections-workspace">
    <v-alert v-if="error" type="error" variant="tonal" class="mb-3">{{ error }}</v-alert>

    <div ref="threePaneElement" class="three-pane">
      <v-sheet class="pane collections-pane" :style="paneStyle(0)">
        <div class="pane-header">
          <span>Collections</span>
          <div class="pane-header__actions">
            <v-btn prepend-icon="mdi-plus" size="small" variant="flat" color="primary" @click="openAddCollectionDialog">
              Add
            </v-btn>
          </div>
        </div>

        <div class="collection-list">
          <div
            v-for="collection in collections"
            :key="collection.id"
            class="nav-row"
            :class="{
              'is-selected': collection.id === selectedCollectionId,
              'is-scanning': collectionIsScanning(collection),
            }"
            :style="{ '--scan-progress': `${scanProgress(collection)}%` }"
            role="button"
            tabindex="0"
            @click="selectCollection(collection)"
            @keydown.enter="selectCollection(collection)"
          >
            <input
              v-if="editingCollectionId === collection.id"
              v-model="editingCollectionName"
              class="nav-row__edit"
              @click.stop
              @keydown.enter.stop="saveInlineCollectionEdit(collection)"
              @keydown.esc.stop="cancelInlineCollectionEdit"
              @blur="saveInlineCollectionEdit(collection)"
            />
            <span v-else class="nav-row__title">
              <v-progress-circular
                v-if="uiSettings.collectionScanSpinnerEnabled && collectionIsScanning(collection)"
                indeterminate
                size="14"
                width="2"
                class="nav-row__spinner"
              ></v-progress-circular>
              <span>{{ collection.name }}</span>
            </span>
            <span class="nav-row__actions">
              <v-tooltip text="Edit collection" location="top">
                <template #activator="{ props }">
                  <v-btn
                    v-bind="props"
                    icon="mdi-pencil"
                    size="x-small"
                    variant="text"
                    :disabled="collectionIsScanning(collection)"
                    @click.stop="startInlineCollectionEdit(collection)"
                  ></v-btn>
                </template>
              </v-tooltip>
              <v-tooltip text="Scan collection" location="top">
                <template #activator="{ props }">
                  <v-btn
                    v-bind="props"
                    icon="mdi-refresh"
                    size="x-small"
                    variant="text"
                    :disabled="scanIsRunning"
                    @click.stop="startScan(collection.id)"
                  ></v-btn>
                </template>
              </v-tooltip>
              <v-tooltip text="Delete collection" location="top">
                <template #activator="{ props }">
                  <v-btn
                    v-bind="props"
                    icon="mdi-trash-can-outline"
                    size="x-small"
                    variant="text"
                    color="error"
                    :disabled="collectionIsScanning(collection)"
                    @click.stop="askDeleteCollection(collection)"
                  ></v-btn>
                </template>
              </v-tooltip>
            </span>
          </div>
        </div>
      </v-sheet>

      <div class="pane-resizer" @pointerdown="startPaneResize(0, $event)"></div>

      <v-sheet class="pane artists-pane" :style="paneStyle(1)">
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

      <div class="pane-resizer" @pointerdown="startPaneResize(1, $event)"></div>

      <v-sheet class="pane albums-pane" :style="paneStyle(2)">
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

    <v-dialog v-model="addCollectionDialog" max-width="420">
      <v-card class="dialog-card add-collection-dialog">
        <v-card-title>Add Collection</v-card-title>
        <v-card-text>
          <div class="cell-muted mb-3">Add folder to Collections by clicking on it</div>
          <div class="folder-candidate-list">
            <button
              v-for="candidate in pagedCollectionCandidates"
              :key="candidate.relativePath"
              class="folder-candidate"
              type="button"
              @click="addCollection(candidate)"
            >
              <span>{{ candidate.collectionName }}</span>
              <span class="folder-candidate__path">{{ candidate.folderName }}</span>
            </button>
            <div v-if="collectionCandidates.length === 0" class="pane-empty pane-empty--compact">
              No available folders.
            </div>
          </div>
        </v-card-text>
        <v-card-actions class="folder-candidate-pager">
          <v-spacer></v-spacer>
          <v-btn
            icon="mdi-chevron-left"
            size="x-small"
            variant="text"
            :disabled="collectionPage === 1"
            @click="previousCollectionPage"
          ></v-btn>
          <span>{{ collectionPageEnd }}/{{ collectionCandidates.length }}</span>
          <v-btn
            icon="mdi-chevron-right"
            size="x-small"
            variant="text"
            :disabled="collectionPage === collectionPageCount"
            @click="nextCollectionPage"
          ></v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog v-model="deleteCollectionDialog" max-width="460">
      <v-card class="dialog-card">
        <v-card-title>Delete Collection</v-card-title>
        <v-card-text>
          Delete {{ collectionToDelete?.name }}?
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn variant="text" @click="deleteCollectionDialog = false">Cancel</v-btn>
          <v-btn color="error" @click="deleteCollection">Delete</v-btn>
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
