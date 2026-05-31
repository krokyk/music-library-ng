<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useLibraryStore } from '@/stores/library'
import type { Album, Artist, CollectionFolderCandidate, CollectionTitleItem, MusicCollection } from '@/types'

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
  collectionTitleItems,
  selectedCollectionId,
  selectedArtistId,
  providerLinks,
  scanJob,
  uiSettings,
  error,
} = storeToRefs(store)

const artistDialog = ref(false)
const deleteDialog = ref(false)
const addCollectionDropdownOpen = ref(false)
const deleteCollectionDialog = ref(false)
const albumToDelete = ref<Album | null>(null)
const collectionToDelete = ref<MusicCollection | null>(null)
const refreshingArtistId = ref<number | null>(null)
const savingArtist = ref(false)
const collectionEditOpenId = ref<string | null>(null)
const threePaneElement = ref<HTMLElement | null>(null)
const addCollectionAnchor = ref<HTMLElement | null>(null)
const addCollectionDropdown = ref<HTMLElement | null>(null)
const panePercents = ref([27, 30, 43])
const paneLayoutSaveTimer = ref<number | null>(null)
const paneNames = ['collections', 'artists', 'albums'] as const
const titleItemDialog = ref(false)
const titleItemSaving = ref(false)
const titleItemToEdit = ref<CollectionTitleItem | null>(null)

const collectionEditForm = reactive({
  name: '',
  type: 'ARTIST' as MusicCollection['type'],
})

const titleItemForm = reactive({
  title: '',
  artistName: '',
  year: null as number | null,
})

const artistColumnWidths = reactive({
  name: 280,
  actions: 64,
})

const albumColumnWidths = reactive({
  name: 360,
  year: 100,
  checked: 120,
  actions: 56,
})

const titleColumnWidths = reactive({
  title: 460,
  artist: 220,
  year: 90,
  status: 120,
  actions: 64,
})

const columnWidthSaveTimers = new Map<string, number>()
const columnWidthPreferenceKeys = {
  artist: 'collections.columns.artist',
  album: 'collections.columns.album',
  title: 'collections.columns.title',
} as const

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

const selectedCollectionIsTitle = computed(() => selectedCollection.value?.type === 'TITLE')

const selectedArtist = computed(() =>
  collectionArtists.value.find((artist) => artist.id === selectedArtistId.value) ?? null,
)

const collectionOptions = computed(() =>
  collections.value.map((collection) => ({ title: collection.name, value: collection.id })),
)

const scanIsRunning = computed(() => scanJob.value?.status === 'RUNNING')
const paneLayoutPreferenceKey = 'collections.paneLayout'

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

function collectionTypeIcon(collection: MusicCollection) {
  return collection.type === 'TITLE' ? 'mdi-album' : 'mdi-account-music'
}

function collectionTypeLabel(collection: MusicCollection) {
  return collection.type === 'TITLE' ? 'Title collection' : 'Artist collection'
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

async function toggleAddCollectionDropdown() {
  if (addCollectionDropdownOpen.value) {
    addCollectionDropdownOpen.value = false
    return
  }
  await store.loadCollectionCandidates()
  addCollectionDropdownOpen.value = true
}

async function addCollection(candidate: CollectionFolderCandidate) {
  await store.createCollection(candidate.relativePath)
}

function closeAddCollectionDropdown() {
  addCollectionDropdownOpen.value = false
}

function openCollectionEdit(collection: MusicCollection) {
  collectionEditForm.name = collection.name
  collectionEditForm.type = collection.type
  collectionEditOpenId.value = collection.id
  void nextTick(() => {
    const input = document.querySelector<HTMLInputElement>('.collection-edit-popover input')
    input?.focus()
    input?.select()
  })
}

async function saveCollectionEdit(collection: MusicCollection) {
  const name = collectionEditForm.name.trim()
  if (!name) {
    return
  }
  collectionEditOpenId.value = null
  if (name === collection.name && collectionEditForm.type === collection.type) {
    return
  }
  await store.updateCollection(collection.id, {
    name,
    type: collectionEditForm.type,
  })
}

function closeCollectionEdit() {
  collectionEditOpenId.value = null
}

async function startScan(collectionId: string) {
  await store.runScanJob(collectionId)
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

function titlePaneStyle() {
  const titlePercent = panePercents.value[1] + panePercents.value[2]
  return {
    flex: `1 1 calc(${titlePercent}% - 10px)`,
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

function applyColumnWidthDefaults() {
  const defaults = uiSettings.value.workspaceColumnDefaults
  Object.assign(artistColumnWidths, defaults.artist)
  Object.assign(albumColumnWidths, defaults.album)
  Object.assign(titleColumnWidths, defaults.title)
}

async function loadColumnWidths() {
  await Promise.all([
    loadColumnWidthPreference('artist'),
    loadColumnWidthPreference('album'),
    loadColumnWidthPreference('title'),
  ])
}

async function loadColumnWidthPreference(table: keyof typeof columnWidthPreferenceKeys) {
  const preference = await store.loadPreference(columnWidthPreferenceKeys[table])
  if (!preference?.value) {
    return
  }
  try {
    const parsed = JSON.parse(preference.value)
    if (!parsed || typeof parsed !== 'object') {
      return
    }
    const widths = columnWidthState(table)
    Object.entries(parsed as Record<string, unknown>).forEach(([key, value]) => {
      if (typeof value === 'number' && Number.isFinite(value) && key in widths) {
        widths[key] = Math.max(columnMinimumWidth(table, key), Math.round(value))
      }
    })
  } catch (error) {
    // Ignore invalid stored UI state and keep the default widths.
  }
}

function columnWidthState(table: keyof typeof columnWidthPreferenceKeys) {
  if (table === 'artist') return artistColumnWidths as Record<string, number>
  if (table === 'album') return albumColumnWidths as Record<string, number>
  return titleColumnWidths as Record<string, number>
}

function columnGridStyle(table: keyof typeof columnWidthPreferenceKeys) {
  const widths = columnWidthState(table)
  return {
    '--workspace-grid-columns': Object.values(widths).map((width) => `${width}px`).join(' '),
  }
}

function startColumnResize(table: keyof typeof columnWidthPreferenceKeys, key: string, event: PointerEvent) {
  event.preventDefault()
  event.stopPropagation()
  if (event.detail > 1) {
    return
  }
  const widths = columnWidthState(table)
  const startWidth = widths[key]
  const startX = event.clientX
  document.body.classList.add('is-column-resizing')

  function move(pointerEvent: PointerEvent) {
    widths[key] = Math.max(columnMinimumWidth(table, key), Math.round(startWidth + pointerEvent.clientX - startX))
    scheduleColumnWidthSave(table)
  }

  function stop() {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', stop)
    window.removeEventListener('pointercancel', stop)
    document.body.classList.remove('is-column-resizing')
    saveColumnWidths(table)
  }

  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', stop)
  window.addEventListener('pointercancel', stop)
}

function autosizeColumn(table: keyof typeof columnWidthPreferenceKeys, key: string, event?: MouseEvent) {
  event?.preventDefault()
  event?.stopPropagation()
  const cells = Array.from(document.querySelectorAll<HTMLElement>(`[data-column="${table}.${key}"]`))
  const measured = measureColumnText(cells) + columnAutosizePadding(table, key)
  columnWidthState(table)[key] = Math.max(columnMinimumWidth(table, key), measured)
  saveColumnWidths(table)
}

function measureColumnText(cells: HTMLElement[]) {
  const measurer = document.createElement('span')
  measurer.className = 'column-autosize-measurer'
  document.body.appendChild(measurer)
  try {
    return Math.ceil(cells.reduce((max, cell) => {
      const text = (cell.textContent ?? '').replace(/\s+/g, ' ').trim()
      if (!text) {
        return max
      }
      measurer.style.font = window.getComputedStyle(cell).font
      measurer.textContent = text
      return Math.max(max, measurer.getBoundingClientRect().width)
    }, 0))
  } finally {
    measurer.remove()
  }
}

function columnAutosizePadding(table: keyof typeof columnWidthPreferenceKeys, key: string) {
  if (key === 'actions') return 16
  if (table === 'title' && key === 'status') return 44
  if (table === 'album' && key === 'checked') return 34
  return 42
}

function scheduleColumnWidthSave(table: keyof typeof columnWidthPreferenceKeys) {
  const existing = columnWidthSaveTimers.get(table)
  if (existing !== undefined) {
    window.clearTimeout(existing)
  }
  columnWidthSaveTimers.set(table, window.setTimeout(() => saveColumnWidths(table), 200))
}

function saveColumnWidths(table: keyof typeof columnWidthPreferenceKeys) {
  const existing = columnWidthSaveTimers.get(table)
  if (existing !== undefined) {
    window.clearTimeout(existing)
    columnWidthSaveTimers.delete(table)
  }
  void store.savePreference(columnWidthPreferenceKeys[table], JSON.stringify(columnWidthState(table)))
}

function columnMinimumWidth(table: keyof typeof columnWidthPreferenceKeys, key: string) {
  if (key === 'actions') return 56
  if (table === 'title' && key === 'title') return 220
  if (table === 'album' && key === 'name') return 180
  if (key === 'year') return 70
  if (key === 'checked' || key === 'status') return 95
  return 140
}

function handleDocumentPointerDown(event: PointerEvent) {
  if (!addCollectionDropdownOpen.value) {
    return
  }
  const target = event.target
  if (!(target instanceof Node)) {
    return
  }
  if (addCollectionAnchor.value?.contains(target) || addCollectionDropdown.value?.contains(target)) {
    return
  }
  closeAddCollectionDropdown()
}

function handleDocumentKeyDown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    closeAddCollectionDropdown()
    closeCollectionEdit()
  }
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

function openTitleItemDialog(item: CollectionTitleItem) {
  titleItemToEdit.value = item
  titleItemForm.title = item.title
  titleItemForm.artistName = item.artistName ?? ''
  titleItemForm.year = item.year ?? null
  titleItemDialog.value = true
}

async function saveTitleItem() {
  if (!titleItemToEdit.value || !titleItemForm.title.trim()) {
    return
  }
  titleItemSaving.value = true
  try {
    await store.updateTitleItem(titleItemToEdit.value, {
      title: titleItemForm.title.trim(),
      artistName: titleItemForm.artistName.trim() || null,
      year: titleItemForm.year,
    })
    titleItemDialog.value = false
    titleItemToEdit.value = null
  } finally {
    titleItemSaving.value = false
  }
}

onMounted(async () => {
  document.addEventListener('pointerdown', handleDocumentPointerDown)
  document.addEventListener('keydown', handleDocumentKeyDown)
  await store.loadUiSettings()
  applyColumnWidthDefaults()
  await loadColumnWidths()
  await loadPaneLayout()
  await store.loadCollections()
  await store.loadScanJob()
  if (scanIsRunning.value) {
    store.startScanJobPolling()
  }
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', handleDocumentPointerDown)
  document.removeEventListener('keydown', handleDocumentKeyDown)
  if (paneLayoutSaveTimer.value !== null) {
    savePaneLayout()
  }
  columnWidthSaveTimers.forEach((timer) => window.clearTimeout(timer))
  columnWidthSaveTimers.clear()
})
</script>

<template>
  <v-container fluid class="app-page collections-workspace">
    <v-alert v-if="error" type="error" variant="tonal" class="mb-3">{{ error }}</v-alert>

    <div ref="threePaneElement" class="three-pane">
      <v-sheet class="pane collections-pane" :style="paneStyle(0)">
        <div class="pane-header">
          <span>Collections</span>
          <div ref="addCollectionAnchor" class="pane-header__actions">
            <v-btn prepend-icon="mdi-plus" size="small" variant="flat" color="primary" @click="toggleAddCollectionDropdown">
              Add
            </v-btn>
          </div>
        </div>

        <div
          v-if="addCollectionDropdownOpen"
          ref="addCollectionDropdown"
          class="add-collection-dropdown"
        >
          <div class="add-collection-dropdown__hint">Add folder to Collections by clicking on it</div>
          <div class="folder-candidate-list">
            <button
              v-for="candidate in collectionCandidates"
              :key="candidate.relativePath"
              class="folder-candidate"
              type="button"
              @click="addCollection(candidate)"
            >
              <span>{{ candidate.folderName }}</span>
            </button>
            <div v-if="collectionCandidates.length === 0" class="pane-empty pane-empty--compact">
              No available folders.
            </div>
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
            <span class="nav-row__title">
              <v-progress-circular
                v-if="uiSettings.collectionScanSpinnerEnabled && collectionIsScanning(collection)"
                indeterminate
                size="14"
                width="2"
                class="nav-row__spinner"
              ></v-progress-circular>
              <v-tooltip :text="collectionTypeLabel(collection)" location="top">
                <template #activator="{ props }">
                  <v-icon
                    v-bind="props"
                    :icon="collectionTypeIcon(collection)"
                    size="16"
                    class="collection-type-icon"
                    :class="collection.type === 'TITLE' ? 'collection-type-icon--title' : 'collection-type-icon--artist'"
                  ></v-icon>
                </template>
              </v-tooltip>
              <span>{{ collection.name }}</span>
            </span>
            <span class="nav-row__actions">
              <v-menu
                :model-value="collectionEditOpenId === collection.id"
                location="end"
                :close-on-content-click="false"
                @update:model-value="(value) => value ? openCollectionEdit(collection) : closeCollectionEdit()"
              >
                <template #activator="{ props }">
                  <v-tooltip text="Edit collection" location="top">
                    <template #activator="{ props: tooltipProps }">
                      <v-btn
                        v-bind="{ ...props, ...tooltipProps }"
                        icon="mdi-pencil"
                        size="x-small"
                        variant="text"
                        :disabled="collectionIsScanning(collection)"
                        @click.stop="openCollectionEdit(collection)"
                      ></v-btn>
                    </template>
                  </v-tooltip>
                </template>
                <v-sheet class="collection-edit-popover">
                  <div class="collection-edit-popover__location">
                    <span class="collection-edit-popover__label">Folder</span>
                    <span class="collection-edit-popover__path">{{ collection.relativePath }}</span>
                  </div>
                  <v-text-field
                    v-model="collectionEditForm.name"
                    label="Name"
                    class="collection-edit-popover__name"
                    density="compact"
                    hide-details
                    @click.stop
                    @keydown.enter.stop="saveCollectionEdit(collection)"
                  ></v-text-field>
                  <div class="collection-edit-popover__type">
                    <div class="collection-edit-popover__label">Type</div>
                    <v-btn-toggle
                      v-model="collectionEditForm.type"
                      mandatory
                      density="compact"
                      class="collection-type-toggle"
                    >
                      <v-btn value="ARTIST">
                        <v-icon
                          icon="mdi-account-music"
                          size="16"
                          class="collection-type-icon collection-type-icon--artist"
                        ></v-icon>
                        <span>Artist</span>
                      </v-btn>
                      <v-btn value="TITLE">
                        <v-icon
                          icon="mdi-album"
                          size="16"
                          class="collection-type-icon collection-type-icon--title"
                        ></v-icon>
                        <span>Title</span>
                      </v-btn>
                    </v-btn-toggle>
                  </div>
                  <div class="collection-edit-popover__actions">
                    <v-btn size="small" variant="text" @click.stop="closeCollectionEdit">Cancel</v-btn>
                    <v-btn size="small" color="primary" variant="flat" @click.stop="saveCollectionEdit(collection)">
                      Save
                    </v-btn>
                  </div>
                </v-sheet>
              </v-menu>
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

      <template v-if="selectedCollectionIsTitle">
        <v-sheet class="pane titles-pane" :style="titlePaneStyle()">
          <div class="pane-header">
            <span>Titles</span>
            <span v-if="selectedCollection" class="pane-header__meta">{{ selectedCollection.name }}</span>
          </div>

          <div v-if="!selectedCollection" class="pane-empty">Select a collection.</div>
          <div v-else class="workspace-grid" :style="columnGridStyle('title')">
            <div class="workspace-grid__row workspace-grid__header">
              <div class="workspace-grid__cell workspace-grid__header-cell" data-column="title.title">
                Title
                  <span
                    class="column-resize-handle"
                    @pointerdown="startColumnResize('title', 'title', $event)"
                    @dblclick="autosizeColumn('title', 'title', $event)"
                  ></span>
              </div>
              <div class="workspace-grid__cell workspace-grid__header-cell" data-column="title.artist">
                Artist
                  <span
                    class="column-resize-handle"
                    @pointerdown="startColumnResize('title', 'artist', $event)"
                    @dblclick="autosizeColumn('title', 'artist', $event)"
                  ></span>
              </div>
              <div class="workspace-grid__cell workspace-grid__header-cell" data-column="title.year">
                Year
                  <span
                    class="column-resize-handle"
                    @pointerdown="startColumnResize('title', 'year', $event)"
                    @dblclick="autosizeColumn('title', 'year', $event)"
                  ></span>
              </div>
              <div class="workspace-grid__cell workspace-grid__header-cell" data-column="title.status">
                Status
                  <span
                    class="column-resize-handle"
                    @pointerdown="startColumnResize('title', 'status', $event)"
                    @dblclick="autosizeColumn('title', 'status', $event)"
                  ></span>
              </div>
              <div class="workspace-grid__cell workspace-grid__header-cell" data-column="title.actions">
                  <span
                    class="column-resize-handle"
                    @pointerdown="startColumnResize('title', 'actions', $event)"
                    @dblclick="autosizeColumn('title', 'actions', $event)"
                  ></span>
              </div>
            </div>
            <div v-for="item in collectionTitleItems" :key="item.id" class="workspace-grid__row workspace-row">
                <div data-column="title.title" class="workspace-grid__cell truncate-cell">
                  <span class="cell-strong">{{ item.title }}</span>
                </div>
                <div data-column="title.artist" class="workspace-grid__cell truncate-cell">{{ item.artistName ?? '' }}</div>
                <div data-column="title.year" class="workspace-grid__cell cell-muted">{{ item.year ?? '' }}</div>
                <div data-column="title.status" class="workspace-grid__cell">
                  <v-chip size="x-small" :color="item.metadataSource === 'MANUAL' ? 'primary' : 'default'" variant="tonal">
                    {{ item.parseStatus.toLowerCase().replace('_', ' ') }}
                  </v-chip>
                </div>
                <div data-column="title.actions" class="workspace-grid__cell row-action-cell">
                  <div class="row-actions">
                    <v-tooltip text="Edit title metadata" location="top">
                      <template #activator="{ props }">
                        <v-btn
                          v-bind="props"
                          icon="mdi-pencil"
                          size="x-small"
                          variant="text"
                          @click.stop="openTitleItemDialog(item)"
                        ></v-btn>
                      </template>
                    </v-tooltip>
                  </div>
                </div>
            </div>
          </div>
        </v-sheet>
      </template>

      <template v-else>
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
        <div v-else class="workspace-grid workspace-grid--no-header" :style="columnGridStyle('artist')">
          <div
            v-for="artist in collectionArtists"
            :key="artist.id"
            class="workspace-grid__row workspace-row"
            :class="{ 'is-selected': artist.id === selectedArtistId }"
            @click="store.selectArtist(artist.id)"
          >
              <div data-column="artist.name" class="workspace-grid__cell">
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
              </div>
              <div data-column="artist.actions" class="workspace-grid__cell row-action-cell">
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
              </div>
          </div>
        </div>
      </v-sheet>

      <div class="pane-resizer" @pointerdown="startPaneResize(1, $event)"></div>

      <v-sheet class="pane albums-pane" :style="paneStyle(2)">
        <div class="pane-header">
          <span>Albums</span>
          <span v-if="selectedArtist" class="pane-header__meta">{{ selectedArtist.name }}</span>
        </div>

        <div v-if="!selectedArtist" class="pane-empty">Select an artist.</div>
        <div v-else class="workspace-grid" :style="columnGridStyle('album')">
          <div class="workspace-grid__row workspace-grid__header">
            <div class="workspace-grid__cell workspace-grid__header-cell" data-column="album.name">
              Name
                <span
                  class="column-resize-handle"
                  @pointerdown="startColumnResize('album', 'name', $event)"
                  @dblclick="autosizeColumn('album', 'name', $event)"
                ></span>
            </div>
            <div class="workspace-grid__cell workspace-grid__header-cell" data-column="album.year">
              Year
                <span
                  class="column-resize-handle"
                  @pointerdown="startColumnResize('album', 'year', $event)"
                  @dblclick="autosizeColumn('album', 'year', $event)"
                ></span>
            </div>
            <div class="workspace-grid__cell workspace-grid__header-cell" data-column="album.checked">
              Checked
                <span
                  class="column-resize-handle"
                  @pointerdown="startColumnResize('album', 'checked', $event)"
                  @dblclick="autosizeColumn('album', 'checked', $event)"
                ></span>
            </div>
            <div class="workspace-grid__cell workspace-grid__header-cell" data-column="album.actions">
                <span
                  class="column-resize-handle"
                  @pointerdown="startColumnResize('album', 'actions', $event)"
                  @dblclick="autosizeColumn('album', 'actions', $event)"
                ></span>
            </div>
          </div>
          <div v-for="album in collectionAlbums" :key="album.id" class="workspace-grid__row workspace-row">
              <div data-column="album.name" class="workspace-grid__cell truncate-cell">
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
              </div>
              <div data-column="album.year" class="workspace-grid__cell cell-muted">{{ album.releaseYear ?? '' }}</div>
              <div data-column="album.checked" class="workspace-grid__cell">
                <v-checkbox
                  :model-value="album.checked"
                  density="compact"
                  hide-details
                  @click.stop
                  @update:model-value="(value) => updateAlbumChecked(album, Boolean(value))"
                ></v-checkbox>
              </div>
              <div data-column="album.actions" class="workspace-grid__cell row-action-cell">
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
              </div>
          </div>
        </div>
      </v-sheet>
      </template>
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

    <v-dialog v-model="titleItemDialog" max-width="640">
      <v-card class="dialog-card">
        <v-card-title>Title Metadata</v-card-title>
        <v-card-text>
          <div class="cell-muted mb-3">{{ titleItemToEdit?.relativePath }}</div>
          <v-row dense>
            <v-col cols="12">
              <v-text-field v-model="titleItemForm.title" label="Title" hide-details="auto"></v-text-field>
            </v-col>
            <v-col cols="12" md="8">
              <v-text-field v-model="titleItemForm.artistName" label="Artist" hide-details="auto"></v-text-field>
            </v-col>
            <v-col cols="12" md="4">
              <v-number-input
                v-model="titleItemForm.year"
                label="Year"
                control-variant="hidden"
                hide-details="auto"
              ></v-number-input>
            </v-col>
          </v-row>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn variant="text" @click="titleItemDialog = false">Cancel</v-btn>
          <v-btn color="primary" :loading="titleItemSaving" @click="saveTitleItem">Save</v-btn>
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
