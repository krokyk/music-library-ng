<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useLibraryStore } from '@/stores/library'
import AppSpinner from '@/components/AppSpinner.vue'
import ActionColumn from '@/components/ActionColumn.vue'
import InlineRowActions from '@/components/InlineRowActions.vue'
import ProviderChip from '@/components/ProviderChip.vue'
import ProviderMatchDialog from '@/components/ProviderMatchDialog.vue'
import RowActionButton from '@/components/RowActionButton.vue'
import { fitFixedPaneWidths } from '@/paneWidths'
import { useWorkspaceGridColumns } from '@/workspaceGridColumns'
import { providerDefinition, validateProviderUrl, type ProviderId } from '@/providers'
import type { Album, Artist, ArtistProviderCandidate, CollectionDeletePreview, CollectionFolderCandidate, MusicCollection } from '@/types'
import type { CSSProperties } from 'vue'

interface ArtistRowMeasurement {
  contentWidth: number
  issueCompactWidth: number
  issueLabeledWidth: number
  nameWidth: number
}

interface ArtistRowFit {
  issueLabel: boolean
}

interface ActionMeasurement {
  icon: number
  labeled: number
}

interface CollectionRowMeasurement {
  contentWidth: number
  leadingWidth: number
  nameWidth: number
}

type CollectionRow =
  | { key: string, name: string, pending: true }
  | { key: string, name: string, pending: false, collection: MusicCollection }

type SortDirection = 'asc' | 'desc'
type ArtistSortKey = 'name'
type AlbumSortKey = 'name' | 'releaseYear'
type TitleSortKey = 'title' | 'artist' | 'releaseYear'
type TitleSortMode = 'title' | 'sortName'
type PresenceFilter = 'local' | 'nonLocal'
type ArtistUncheckedFilter = 'unchecked'
type AlbumShowAllFilter = 'showAll'
type AlbumColumnKey = 'name' | 'releaseYear' | 'checked' | 'home' | 'action'
type TitleColumnKey = 'title' | 'artist' | 'releaseYear' | 'spacer'

const store = useLibraryStore()
const {
  collections,
  collectionCandidates,
  pendingCollectionCandidates,
  deletingCollectionId,
  collectionArtists,
  collectionAlbums,
  collectionTitleItems,
  collectionArtistsLoading,
  collectionAlbumsLoading,
  collectionTitleItemsLoading,
  collectionMetadata,
  collectionMetadataLoading,
  selectedCollectionId,
  selectedArtistId,
  providerJob,
  providerStatus,
  scanJob,
  uiSettings,
} = storeToRefs(store)

const providerSetupDialog = ref(false)
const viewReady = ref(false)
const providerSetupSaving = ref(false)
const providerSetupMatching = ref(false)
const providerSetupArtist = ref<Artist | null>(null)
const providerSetupProviderId = ref<ProviderId>('musicbrainz')
const providerSetupUrl = ref('')
const providerCandidates = ref<ArtistProviderCandidate[]>([])
const addCollectionDropdownOpen = ref(false)
const deleteCollectionDialog = ref(false)
const collectionToDelete = ref<MusicCollection | null>(null)
const collectionDeletePreview = ref<CollectionDeletePreview | null>(null)
const collectionDeletePreviewLoading = ref(false)
const collectionEditOpenId = ref<number | null>(null)
const collectionEditTarget = ref<HTMLElement | undefined>(undefined)
const threePaneElement = ref<HTMLElement | null>(null)
const collectionsPaneElement = ref<unknown>(null)
const artistsPaneElement = ref<unknown>(null)
const albumsPaneElement = ref<unknown>(null)
const titlesPaneElement = ref<unknown>(null)
const artistGridElement = ref<HTMLElement | null>(null)
const albumGridElement = ref<HTMLElement | null>(null)
const titleGridElement = ref<HTMLElement | null>(null)
const addCollectionAnchor = ref<HTMLElement | null>(null)
const addCollectionDropdown = ref<HTMLElement | null>(null)
const paneResizerWidth = 10
const titleGridHeaderHeight = 38
const titleGridRowHeight = 42
const titleGridBufferRows = 12
const titleGridFallbackViewportHeight = 900
const artistGridRowHeight = 42
const artistGridBufferRows = 12
const artistGridFallbackViewportHeight = 900
const albumGridHeaderHeight = 38
const albumGridRowHeight = 42
const albumGridBufferRows = 12
const albumGridFallbackViewportHeight = 900
const collectionPanePreferredWidth = ref(uiSettings.value.paneWidthDefaults.collections)
const collectionArtistPanePreferredWidth = ref(uiSettings.value.paneWidthDefaults.collectionArtists)
const collectionWorkspaceWidth = ref(window.innerWidth)
let paneWidthObserver: ResizeObserver | null = null
const collectionListElement = ref<HTMLElement | null>(null)

const paneWidths = reactive({
  collections: 0,
  artists: 0,
  albums: 0,
  titles: 0,
})

const collectionEditForm = reactive({
  name: '',
  type: 'ARTIST' as MusicCollection['type'],
})

const albumColumnWidths = reactive({
  ...uiSettings.value.workspaceColumnDefaults.album,
})

const titleColumnWidths = reactive({
  ...uiSettings.value.workspaceColumnDefaults.title,
})

const artistSort = reactive<{ key: ArtistSortKey; direction: SortDirection }>({
  key: 'name',
  direction: 'asc',
})

const albumSort = reactive<{ key: AlbumSortKey; direction: SortDirection }>({
  key: 'releaseYear',
  direction: 'asc',
})

const titleSort = reactive<{ key: TitleSortKey; direction: SortDirection }>({
  key: 'title',
  direction: 'asc',
})

const titleSortMode = ref<TitleSortMode>('sortName')
const artistPresence = ref<PresenceFilter[]>(['local', 'nonLocal'])
const artistUnchecked = ref<ArtistUncheckedFilter[]>([])
const defaultAlbumShowAllFilter: AlbumShowAllFilter[] = ['showAll']
const albumShowAll = ref<AlbumShowAllFilter[]>([...defaultAlbumShowAllFilter])
const titlePresence = ref<PresenceFilter[]>(['local'])
const hoveredCollectionId = ref<number | null>(null)
const focusedCollectionId = ref<number | null>(null)
const artistGridScrollTop = ref(0)
const artistGridViewportHeight = ref(0)
const hoveredArtistRowId = ref<number | null>(null)
const focusedArtistRowId = ref<number | null>(null)
const selectedAlbumRowId = ref<number | null>(null)
const selectedTitleRowId = ref<number | null>(null)
const albumMoveMenuOpenId = ref<number | null>(null)
const albumGridScrollTop = ref(0)
const albumGridViewportHeight = ref(0)
const titleGridScrollTop = ref(0)
const titleGridViewportHeight = ref(0)
let presencePreferencesLoaded = false
let paneResizeActive = false
const suppressHeaderSortUntil = ref(0)
const artistRowMeasurements = reactive<Record<number, ArtistRowMeasurement>>({})
const artistActionMeasurements = reactive<Record<number, ActionMeasurement>>({})
const collectionActionMeasurement = ref<ActionMeasurement>({ icon: 94, labeled: 198 })
const artistRowElements = new Map<number, HTMLElement>()
let artistRowMeasureFrame: number | null = null
let artistNameMeasureCanvas: HTMLCanvasElement | null = null
let collectionNameMeasureCanvas: HTMLCanvasElement | null = null

const columnWidthPreferenceKeys = {
  album: {
    name: 'collections-screen.albums-pane.name',
    releaseYear: 'collections-screen.albums-pane.release-year',
    checked: 'collections-screen.albums-pane.checked',
    home: 'collections-screen.albums-pane.home',
  },
  title: {
    title: 'collections-screen.titles-pane.title',
    artist: 'collections-screen.titles-pane.artist',
    releaseYear: 'collections-screen.titles-pane.release-year',
  },
} as const

const presencePreferenceKeys = {
  artist: 'collections-screen.artists-pane.presence-filter',
  title: 'collections-screen.titles-pane.presence-filter',
} as const
const artistUncheckedPreferenceKey = 'collections-screen.artists-pane.unchecked-filter'
const albumShowAllPreferenceKey = 'collections-screen.albums-pane.show-all-filter'

const paneHeaderMinimumWidths = {
  collections: 250,
  artists: 380,
  albums: 220,
  titles: 430,
} as const

const sortableColumnMinimumWidth = 62
const releaseYearColumnMinimumWidth = 64
const titleCompoundHeaderMinimumWidth = 96
const checkboxColumnMinimumWidth = 44
const albumActionColumnMinimumWidths = {
  move: 46,
  moveWithInfo: 70,
} as const

const rowActionButtonWidths = {
  provider: 118,
  providerChip: 126,
  providerChipCompact: 58,
} as const

const collectionAddLabelMinimumWidth = 260
const artistHeaderActionLabelMinimumWidth = 430
const titleHeaderActionLabelMinimumWidth = 260
const collectionReadableNameMinimumWidth = 72
const collectionListHorizontalPadding = 16
const collectionRowHorizontalPadding = 18
const collectionRowNameTrailingGap = 20
const collectionRowInfoWidth = 18
const collectionRowInfoActionGap = 6
const collectionTypeIconWidth = 16
const collectionTitleItemGap = 6
const artistReadableNameMinimumWidth = 128
const artistRowCellHorizontalPadding = 24
const artistRowNameTrailingGap = 20
const artistRowVisibleItemGap = 6
const artistGridScrollbarGutterWidth = 16
const artistIconActionButtonWidth = 30
const artistFailureIconWidth = 18

const artistIssueColumnWidths = {
  compact: 34,
  labeled: 90,
} as const

const tableColumnOrders = {
  artist: ['name'],
  album: ['name', 'releaseYear', 'checked', 'home', 'action'],
  title: ['title', 'artist', 'releaseYear', 'spacer'],
} as const

const albumGridColumns = useWorkspaceGridColumns<AlbumColumnKey>({
  columnKeys: () => tableColumnKeys('album') as readonly AlbumColumnKey[],
  widths: albumColumnWidths,
  minimumWidth: (key) => columnMinimumWidth('album', key),
  gridElement: () => albumGridElement.value,
  saveWidth: (key, width) => saveColumnWidth('album', key, width),
  suppressHeaderClick: suppressHeaderSortClick,
})

const titleGridColumns = useWorkspaceGridColumns<TitleColumnKey>({
  columnKeys: () => tableColumnKeys('title') as readonly TitleColumnKey[],
  widths: titleColumnWidths,
  minimumWidth: (key) => columnMinimumWidth('title', key),
  gridElement: () => titleGridElement.value,
  saveWidth: (key, width) => saveColumnWidth('title', key, width),
  suppressHeaderClick: suppressHeaderSortClick,
})

const selectedCollection = computed(() =>
  collections.value.find((collection) => collection.id === selectedCollectionId.value) ?? null,
)

const collectionRows = computed<CollectionRow[]>(() => [
  ...collections.value.map((collection) => ({
    key: `collection:${collection.id}`,
    name: collection.name,
    pending: false as const,
    collection,
  })),
  ...pendingCollectionCandidates.value.map((candidate) => ({
    key: `pending:${candidate.relativePath}`,
    name: candidate.collectionName,
    pending: true as const,
  })),
].sort((left, right) => left.name.localeCompare(right.name)))

const collectionToEdit = computed(() =>
  collections.value.find((collection) => collection.id === collectionEditOpenId.value) ?? null,
)

const collectionEditDialogOpen = computed({
  get: () => collectionEditOpenId.value !== null,
  set: (open: boolean) => {
    if (!open) {
      closeCollectionEdit()
    }
  },
})

const collectionTypeEditable = computed(() => {
  const collection = collectionToEdit.value
  if (!collection) return false
  return collectionMetadata.value[collection.id]?.knownAlbumCount === 0
})

const selectedCollectionIsTitle = computed(() => selectedCollection.value?.type === 'TITLE')
const selectedCollectionIsArtist = computed(() => selectedCollection.value?.type === 'ARTIST')
const selectedCollectionArtistsLoading = computed(() =>
  Boolean(
    selectedCollectionId.value
    && collectionArtistsLoading.value[selectedCollectionId.value]
    && collectionArtists.value.length === 0,
  ),
)
const selectedCollectionTitlesLoading = computed(() =>
  Boolean(
    selectedCollectionId.value
    && collectionTitleItemsLoading.value[selectedCollectionId.value]
    && collectionTitleItems.value.length === 0,
  ),
)
const selectedArtistAlbumsLoading = computed(() =>
  Boolean(
    selectedArtistId.value !== null
    && collectionAlbumsLoading.value[String(selectedArtistId.value)]
    && collectionAlbums.value.length === 0,
  ),
)

const selectedArtist = computed(() =>
  collectionArtists.value.find((artist) => artist.id === selectedArtistId.value) ?? null,
)

const providerSetupUrlValidation = computed(() =>
  validateProviderUrl(providerSetupProviderId.value, providerSetupUrl.value),
)

const providerSetupDefinition = computed(() =>
  providerDefinition(providerSetupProviderId.value),
)

const scanIsRunning = computed(() => scanJob.value?.status === 'RUNNING')
const providerJobIsRunning = computed(() => providerJob.value?.status === 'RUNNING')
const providerIsRunning = computed(() => providerJobIsRunning.value || providerStatus.value.running)
const scanActionsDisabled = computed(() => scanIsRunning.value || providerIsRunning.value || Boolean(deletingCollectionId.value))
const writeActionsDisabled = computed(() => scanIsRunning.value || providerIsRunning.value || Boolean(deletingCollectionId.value))
const collectionPaneWidthPreferenceKey = 'collections-screen.collections-pane.width'
const collectionArtistPaneWidthPreferenceKey = 'collections-screen.artists-pane.width'

const artistUncheckedEnabled = computed(() => artistUnchecked.value.includes('unchecked'))
const albumShowAllEnabled = computed(() => albumShowAll.value.includes('showAll'))
const sortedCollectionArtists = computed(() =>
  collectionArtists.value
    .filter((artist) => matchesPresenceFilter(artistIsLocalToSelectedCollection(artist), artistPresence.value))
    .filter((artist) => !artistUncheckedEnabled.value || artist.uncheckedAlbumCount > 0)
    .sort((left, right) => applyDirection(compareText(left.name, right.name), artistSort.direction)),
)

const artistVirtualViewportHeight = computed(() =>
  Math.max(artistGridViewportHeight.value, artistGridFallbackViewportHeight),
)

const artistVirtualRowCount = computed(() =>
  Math.ceil(artistVirtualViewportHeight.value / artistGridRowHeight) + artistGridBufferRows * 2,
)

const artistVirtualStartIndex = computed(() => {
  const total = sortedCollectionArtists.value.length
  const maximumStart = Math.max(0, total - artistVirtualRowCount.value)
  const visibleStart = Math.floor(Math.max(0, artistGridScrollTop.value) / artistGridRowHeight)
  return Math.min(Math.max(0, visibleStart - artistGridBufferRows), maximumStart)
})

const artistVirtualEndIndex = computed(() =>
  Math.min(sortedCollectionArtists.value.length, artistVirtualStartIndex.value + artistVirtualRowCount.value),
)

const visibleArtistRows = computed(() =>
  sortedCollectionArtists.value.slice(artistVirtualStartIndex.value, artistVirtualEndIndex.value),
)

const artistVirtualTopSpacerHeight = computed(() =>
  artistVirtualStartIndex.value * artistGridRowHeight,
)

const artistVirtualBottomSpacerHeight = computed(() =>
  Math.max(0, sortedCollectionArtists.value.length - artistVirtualEndIndex.value) * artistGridRowHeight,
)

const filteredCollectionAlbums = computed(() =>
  collectionAlbums.value.filter((album) =>
    albumShowAllEnabled.value || album.collection.id === selectedCollectionId.value,
  ),
)

const sortedCollectionAlbums = computed(() =>
  [...filteredCollectionAlbums.value].sort((left, right) => {
    const result = albumSort.key === 'releaseYear'
      ? compareReleaseYears(
        left.releaseYear,
        right.releaseYear,
        albumSort.direction,
      )
      : compareText(left.title, right.title)
    return albumSort.key === 'releaseYear'
      ? result || compareText(left.title, right.title)
      : applyDirection(result || compareReleaseYears(
        left.releaseYear,
        right.releaseYear,
        'asc',
      ), albumSort.direction)
  }),
)

const showAlbumCollectionsColumn = computed(() => albumShowAllEnabled.value)

const albumVirtualViewportHeight = computed(() =>
  Math.max(albumGridViewportHeight.value, albumGridFallbackViewportHeight),
)

const albumVirtualRowCount = computed(() =>
  Math.ceil(albumVirtualViewportHeight.value / albumGridRowHeight) + albumGridBufferRows * 2,
)

const albumVirtualStartIndex = computed(() => {
  const total = sortedCollectionAlbums.value.length
  const maximumStart = Math.max(0, total - albumVirtualRowCount.value)
  const visibleStart = Math.floor(Math.max(0, albumGridScrollTop.value - albumGridHeaderHeight) / albumGridRowHeight)
  return Math.min(Math.max(0, visibleStart - albumGridBufferRows), maximumStart)
})

const albumVirtualEndIndex = computed(() =>
  Math.min(sortedCollectionAlbums.value.length, albumVirtualStartIndex.value + albumVirtualRowCount.value),
)

const visibleAlbumRows = computed(() =>
  sortedCollectionAlbums.value.slice(albumVirtualStartIndex.value, albumVirtualEndIndex.value),
)

const albumVirtualTopSpacerHeight = computed(() =>
  albumVirtualStartIndex.value * albumGridRowHeight,
)

const albumVirtualBottomSpacerHeight = computed(() =>
  Math.max(0, sortedCollectionAlbums.value.length - albumVirtualEndIndex.value) * albumGridRowHeight,
)

const visibleCollectionTitleItems = computed(() => {
  return collectionTitleItems.value.filter((item) =>
    matchesPresenceFilter(albumIsLocalToSelectedCollection(item), titlePresence.value),
  )
})

const sortedCollectionTitleItems = computed(() =>
  [...visibleCollectionTitleItems.value].sort((left, right) => {
    let result = 0
    if (titleSort.key === 'title') {
      result = titleSortMode.value === 'sortName'
        ? compareText(left.sortName, right.sortName)
        : compareText(left.title, right.title)
    } else if (titleSort.key === 'artist') {
      result = compareText(left.artistName ?? '', right.artistName ?? '')
    } else if (titleSort.key === 'releaseYear') {
      result = compareReleaseYears(
        left.releaseYear,
        right.releaseYear,
        titleSort.direction,
      )
    }
    return titleSort.key === 'releaseYear'
      ? result || compareText(left.sortName, right.sortName) || compareText(left.title, right.title)
      : applyDirection(result || compareText(left.sortName, right.sortName) || compareText(left.title, right.title), titleSort.direction)
  }),
)

const titleVirtualViewportHeight = computed(() =>
  Math.max(titleGridViewportHeight.value, titleGridFallbackViewportHeight),
)

const titleVirtualRowCount = computed(() =>
  Math.ceil(titleVirtualViewportHeight.value / titleGridRowHeight) + titleGridBufferRows * 2,
)

const titleVirtualStartIndex = computed(() => {
  const total = sortedCollectionTitleItems.value.length
  const maximumStart = Math.max(0, total - titleVirtualRowCount.value)
  const visibleStart = Math.floor(Math.max(0, titleGridScrollTop.value - titleGridHeaderHeight) / titleGridRowHeight)
  return Math.min(Math.max(0, visibleStart - titleGridBufferRows), maximumStart)
})

const titleVirtualEndIndex = computed(() =>
  Math.min(sortedCollectionTitleItems.value.length, titleVirtualStartIndex.value + titleVirtualRowCount.value),
)

const visibleTitleRows = computed(() =>
  sortedCollectionTitleItems.value.slice(titleVirtualStartIndex.value, titleVirtualEndIndex.value),
)

const titleVirtualTopSpacerHeight = computed(() =>
  titleVirtualStartIndex.value * titleGridRowHeight,
)

const titleVirtualBottomSpacerHeight = computed(() =>
  Math.max(0, sortedCollectionTitleItems.value.length - titleVirtualEndIndex.value) * titleGridRowHeight,
)

function artistIssueLabel(artist: Artist) {
  if (artist.uncheckedAlbumCount > 0) {
    return `${artist.uncheckedAlbumCount} unchecked`
  }
  return ''
}

function artistFailureTooltip(artist: Artist) {
  const providerFailed = Boolean(providerForArtist(artist)?.lastErrorMessage)
  if (providerFailed) {
    return 'Provider scan failed'
  }
  return ''
}

function providerForArtist(artist: Artist) {
  return artist.providerLinks[0] ?? null
}

function providerChipText(artist: Artist) {
  const provider = providerForArtist(artist)
  return provider ? providerDefinition(provider.providerId).label : 'Add provider'
}

function compareText(left: string | null | undefined, right: string | null | undefined) {
  return (left ?? '').localeCompare(right ?? '', undefined, { numeric: true, sensitivity: 'base' })
}

function compareReleaseYears(left: number | null | undefined, right: number | null | undefined, direction: SortDirection) {
  left ??= null
  right ??= null
  if (left === null && right === null) return 0
  if (left === null) return 1
  if (right === null) return -1
  return applyDirection(left - right, direction)
}

function applyDirection(result: number, direction: SortDirection) {
  return direction === 'asc' ? result : -result
}

function toggleArtistSort(key: ArtistSortKey) {
  if (artistSort.key === key) {
    artistSort.direction = oppositeDirection(artistSort.direction)
    return
  }
  artistSort.key = key
  artistSort.direction = 'asc'
}

function toggleAlbumSort(key: AlbumSortKey) {
  if (albumSort.key === key) {
    albumSort.direction = oppositeDirection(albumSort.direction)
    return
  }
  albumSort.key = key
  albumSort.direction = 'asc'
}

function toggleTitleSort(key: TitleSortKey) {
  if (titleSort.key === key) {
    titleSort.direction = oppositeDirection(titleSort.direction)
    return
  }
  titleSort.key = key
  titleSort.direction = 'asc'
}

function toggleTitleSortMode() {
  titleSortMode.value = titleSortMode.value === 'sortName' ? 'title' : 'sortName'
  titleSort.key = 'title'
  titleSort.direction = 'asc'
}

function handleAlbumHeaderClick(key: AlbumSortKey, event: MouseEvent) {
  if (shouldSuppressHeaderSort(event)) {
    return
  }
  toggleAlbumSort(key)
}

function handleTitleHeaderClick(key: TitleSortKey, event: MouseEvent) {
  if (shouldSuppressHeaderSort(event)) {
    return
  }
  toggleTitleSort(key)
}

function handleArtistGridScroll(event: Event) {
  const element = event.currentTarget
  if (!(element instanceof HTMLElement)) {
    return
  }
  updateArtistGridViewport(element)
}

function handleAlbumGridScroll(event: Event) {
  const element = event.currentTarget
  if (!(element instanceof HTMLElement)) {
    return
  }
  updateAlbumGridViewport(element)
}

function handleTitleGridScroll(event: Event) {
  const element = event.currentTarget
  if (!(element instanceof HTMLElement)) {
    return
  }
  updateTitleGridViewport(element)
}

function resetArtistGridScroll() {
  artistGridScrollTop.value = 0
  void nextTick(() => {
    if (!artistGridElement.value) {
      artistGridViewportHeight.value = 0
      return
    }
    artistGridElement.value.scrollTop = 0
    updateArtistGridViewport(artistGridElement.value)
  })
}

function resetAlbumGridScroll() {
  albumGridScrollTop.value = 0
  void nextTick(() => {
    if (!albumGridElement.value) {
      albumGridViewportHeight.value = 0
      return
    }
    albumGridElement.value.scrollTop = 0
    updateAlbumGridViewport(albumGridElement.value)
  })
}

function resetTitleGridScroll() {
  titleGridScrollTop.value = 0
  void nextTick(() => {
    if (!titleGridElement.value) {
      titleGridViewportHeight.value = 0
      return
    }
    titleGridElement.value.scrollTop = 0
    updateTitleGridViewport(titleGridElement.value)
  })
}

function updateArtistGridViewport(element: HTMLElement) {
  artistGridScrollTop.value = element.scrollTop
  artistGridViewportHeight.value = element.clientHeight
  scheduleArtistRowMeasurement()
}

function setArtistRowElement(artistId: number, value: unknown) {
  const element = resolveElement(value)
  if (element) {
    artistRowElements.set(artistId, element)
  } else {
    artistRowElements.delete(artistId)
    delete artistRowMeasurements[artistId]
    delete artistActionMeasurements[artistId]
  }
  scheduleArtistRowMeasurement()
}

function scheduleArtistRowMeasurement() {
  if (typeof window === 'undefined' || artistRowMeasureFrame !== null) {
    return
  }
  artistRowMeasureFrame = window.requestAnimationFrame(measureArtistRows)
}

function measureArtistRows() {
  artistRowMeasureFrame = null
  const visibleIds = new Set<number>()

  artistRowElements.forEach((rowElement, artistId) => {
    if (!rowElement.isConnected) {
      artistRowElements.delete(artistId)
      delete artistRowMeasurements[artistId]
      return
    }

    visibleIds.add(artistId)
    const cell = rowElement.querySelector<HTMLElement>('[data-column="artist.name"]')
    const artistCell = rowElement.querySelector<HTMLElement>('.artist-cell')
    const name = rowElement.querySelector<HTMLElement>('.artist-cell > .cell-strong')
    if (!cell || !artistCell || !name) {
      return
    }

    const cellStyle = getComputedStyle(cell)
    const artistCellStyle = getComputedStyle(artistCell)
    const padding = cssPixelValue(cellStyle.paddingLeft) + cssPixelValue(cellStyle.paddingRight)
    const gap = cssPixelValue(artistCellStyle.columnGap || artistCellStyle.gap)
    const spinner = rowElement.querySelector<HTMLElement>('.artist-cell__spinner')
    const spinnerWidth = spinner ? Math.ceil(spinner.getBoundingClientRect().width + gap) : 0
    const contentWidth = Math.max(0, Math.floor(cell.clientWidth - padding - spinnerWidth))
    const nameWidth = Math.ceil(name.scrollWidth)
    const issueChip = rowElement.querySelector<HTMLElement>('.artist-issue-chip')
    const issueLabeledWidth = issueChip ? measuredAdaptiveControlWidth(issueChip, true) : 0
    const issueCompactWidth = issueChip ? measuredAdaptiveControlWidth(issueChip, false) : 0
    if (contentWidth <= 0 || nameWidth <= 0) {
      scheduleArtistRowMeasurement()
      return
    }
    const current = artistRowMeasurements[artistId]
    if (
      !current
      || current.contentWidth !== contentWidth
      || current.nameWidth !== nameWidth
      || current.issueLabeledWidth !== issueLabeledWidth
      || current.issueCompactWidth !== issueCompactWidth
    ) {
      artistRowMeasurements[artistId] = {
        contentWidth,
        issueCompactWidth,
        issueLabeledWidth,
        nameWidth,
      }
    }
  })

  Object.keys(artistRowMeasurements).forEach((key) => {
    const artistId = Number(key)
    if (!visibleIds.has(artistId)) {
      delete artistRowMeasurements[artistId]
      delete artistActionMeasurements[artistId]
    }
  })
}

function measuredAdaptiveControlWidth(element: HTMLElement, labeled: boolean) {
  const clone = element.cloneNode(true) as HTMLElement
  clone.dataset.showLabel = String(labeled)
  Object.assign(clone.style, {
    left: '-10000px',
    maxWidth: 'none',
    position: 'fixed',
    visibility: 'hidden',
    width: 'max-content',
  })
  document.body.append(clone)
  const width = Math.ceil(clone.getBoundingClientRect().width)
  clone.remove()
  return width
}

function cssPixelValue(value: string) {
  const parsed = Number.parseFloat(value)
  return Number.isFinite(parsed) ? parsed : 0
}

function updateAlbumGridViewport(element: HTMLElement) {
  albumGridScrollTop.value = element.scrollTop
  albumGridViewportHeight.value = element.clientHeight
}

function updateTitleGridViewport(element: HTMLElement) {
  titleGridScrollTop.value = element.scrollTop
  titleGridViewportHeight.value = element.clientHeight
}

function shouldSuppressHeaderSort(event: MouseEvent) {
  if (Date.now() < suppressHeaderSortUntil.value) {
    event.preventDefault()
    event.stopPropagation()
    return true
  }
  const target = event.target
  if (target instanceof Element && target.closest('.column-resize-handle')) {
    event.preventDefault()
    event.stopPropagation()
    return true
  }
  return false
}

function suppressHeaderSortClick(event?: Event) {
  event?.preventDefault()
  event?.stopPropagation()
  suppressHeaderSortUntil.value = Date.now() + 250
}

function oppositeDirection(direction: SortDirection) {
  return direction === 'asc' ? 'desc' : 'asc'
}

function sortIcon(direction: SortDirection) {
  return direction === 'asc' ? 'mdi-arrow-up' : 'mdi-arrow-down'
}

function titleSortModeIcon() {
  return titleSortMode.value === 'sortName' ? 'mdi-calendar-clock' : 'mdi-sort-alphabetical-ascending'
}

function titleSortModeTooltip() {
  return titleSortMode.value === 'sortName'
    ? 'Sort related titles by series name, then release year and subtitle'
    : 'Sort alphabetically by the displayed title'
}

function albumDiskTitle(album: Album) {
  if (album.localRelativePath) {
    return album.resolvedPath ?? album.localRelativePath
  }
  return 'No local folder'
}

function albumIsInSelectedCollection(album: Album) {
  return album.collection.id === selectedCollectionId.value
}

function albumCollectionsColumnLabel() {
  return 'Collection'
}

async function rehomeAlbum(album: Album, collectionId: number | null) {
  if (!collectionId || collectionId === album.collection.id || album.onDisk || writeActionsDisabled.value) {
    return
  }
  try {
    const updated = await store.rehomeAlbum(album, collectionId)
    store.showStatus(`Moved ${album.title} to ${collections.value.find((collection) => collection.id === collectionId)?.name ?? 'collection'}.`, 'done')
    return updated
  } catch (error) {
    store.showErrorStatus(error, 'Unable to change album home collection')
  }
}

function albumMoveDestinations(album: Album) {
  const type = collections.value.find((collection) => collection.id === album.collection.id)?.type
  return collections.value.filter((collection) => collection.type === type && collection.id !== album.collection.id)
}

function albumMoveDisabled(album: Album) {
  return writeActionsDisabled.value || album.onDisk || albumMoveDestinations(album).length === 0
}

function albumMoveTooltip(album: Album) {
  if (album.onDisk) {
    return 'Album present on disk; move the folder to change its collection.'
  }
  return albumMoveDestinations(album).length === 0
    ? 'No other collections available.'
    : 'Move to another collection'
}

async function moveAlbumTo(album: Album, collectionId: number) {
  albumMoveMenuOpenId.value = null
  await rehomeAlbum(album, collectionId)
}

function scrollVirtualGridToIndex(element: HTMLElement | null, index: number, rowHeight: number, headerHeight = 0) {
  if (!element || index < 0) return
  element.scrollTop = headerHeight + index * rowHeight
}

async function navigateToAlbumHome(album: Album) {
  if (album.collection.id === selectedCollectionId.value) return
  const artistId = selectedArtistId.value
  if (!artistId) return
  await store.selectCollection(album.collection.id)
  const artistIndex = sortedCollectionArtists.value.findIndex((artist) => artist.id === artistId)
  artistGridScrollTop.value = Math.max(0, artistIndex * artistGridRowHeight)
  scrollVirtualGridToIndex(artistGridElement.value, artistIndex, artistGridRowHeight)
  await nextTick()
  await store.selectArtist(artistId)
  selectedAlbumRowId.value = album.id
  const albumIndex = sortedCollectionAlbums.value.findIndex((item) => item.id === album.id)
  albumGridScrollTop.value = Math.max(0, albumGridHeaderHeight + albumIndex * albumGridRowHeight)
  scrollVirtualGridToIndex(albumGridElement.value, albumIndex, albumGridRowHeight, albumGridHeaderHeight)
  await nextTick()
}

function albumShowAllTooltip() {
  return albumShowAllEnabled.value
    ? 'Showing all albums. Turn off to show only albums in this collection.'
    : 'Show all albums for this artist, including provider-only albums and albums local to other collections.'
}

function albumPaneEmptyMessage() {
  if (collectionAlbums.value.length === 0 || albumShowAllEnabled.value) {
    return 'No albums loaded for this artist.'
  }
  return 'No albums linked to this collection for this artist.'
}

function albumIsLocalToSelectedCollection(album: Album) {
  if (!selectedCollectionId.value) {
    return false
  }
  return album.collection.id === selectedCollectionId.value && album.onDisk
}

function artistIsLocalToSelectedCollection(artist: Artist) {
  return selectedCollectionIsArtist.value && artist.localAlbumCount > 0
}

function artistRowClass(artist: Artist) {
  return {
    'is-selected': artist.id === selectedArtistId.value,
    'workspace-row--nonlocal-artist': !artistIsLocalToSelectedCollection(artist),
  }
}

function selectAlbumRow(album: Album) {
  selectedAlbumRowId.value = album.id
}

function selectTitleRow(item: Album) {
  selectedTitleRowId.value = item.id
}

function selectArtistRow(artist: Artist) {
  if (selectedArtistId.value === artist.id) {
    return
  }
  void store.selectArtist(artist.id)
}

function artistRowActionsVisible(artist: Artist) {
  return artist.id === selectedArtistId.value
    || artist.id === hoveredArtistRowId.value
    || artist.id === focusedArtistRowId.value
}

function handleArtistRowFocusOut(artist: Artist, event: FocusEvent) {
  const nextTarget = event.relatedTarget
  if (nextTarget instanceof Node && event.currentTarget instanceof HTMLElement && event.currentTarget.contains(nextTarget)) {
    return
  }
  if (focusedArtistRowId.value === artist.id) {
    focusedArtistRowId.value = null
  }
}

function matchesPresenceFilter(isLocal: boolean, filter: PresenceFilter[]) {
  return (isLocal && filter.includes('local'))
    || (!isLocal && filter.includes('nonLocal'))
}

function includeNonLocal(filter: { value: PresenceFilter[] }) {
  if (!filter.value.includes('nonLocal')) {
    filter.value = [...filter.value, 'nonLocal']
  }
}

function albumPresenceClass(album: Album) {
  const inSelectedCollection = albumIsInSelectedCollection(album)
  const local = albumIsLocalToSelectedCollection(album)
  const otherCollection = album.onDisk && !inSelectedCollection
  const libraryOnly = !local && !otherCollection
  return {
    'album-presence-text--local': local,
    'album-presence-text--current-collection': inSelectedCollection,
    'album-presence-text--other-collection': otherCollection,
    'album-presence-text--nonlocal-checked': libraryOnly && album.checked,
    'album-presence-text--nonlocal-unchecked': libraryOnly && !album.checked,
  }
}

function albumTitleClasses(album: Album) {
  return {
    ...albumPresenceClass(album),
  }
}

function albumReleaseYearChipClasses(_album: Album) {
  return {}
}

function albumRowClass(album: Album) {
  return {
    'is-selected': album.id === selectedAlbumRowId.value,
  }
}

function titleRowClass(item: Album) {
  return {
    'is-selected': item.id === selectedTitleRowId.value,
  }
}

function collectionTypeIcon(collection: MusicCollection) {
  return collection.type === 'TITLE' ? 'mdi-album' : 'mdi-account-music'
}

function collectionTypeLabel(collection: MusicCollection) {
  return collection.type === 'TITLE' ? 'Title collection' : 'Artist collection'
}

function loadCollectionInfo(collection: MusicCollection, open: boolean) {
  if (open) {
    void store.loadCollectionMetadata(collection.id)
  }
}

function collectionInfoPath(collection: MusicCollection) {
  return collection.resolvedPath ?? collection.relativePath
}

function collectionInfoLines(collection: MusicCollection) {
  const metadata = collectionMetadata.value[collection.id]
  if (!metadata) {
    return []
  }
  if (collection.type === 'TITLE') {
    return [
      `Path: ${collectionInfoPath(collection)}`,
      `Artists: ${metadata.contributorArtistCount}`,
      `Local titles: ${metadata.localAlbumCount}`,
      `Checked titles: ${metadata.checkedAlbumCount}`,
    ]
  }
  return [
    `Path: ${collectionInfoPath(collection)}`,
    `Artists: ${metadata.artistCount}`,
    `Local albums: ${metadata.localAlbumCount}`,
    `Known albums: ${metadata.knownAlbumCount}`,
    `Unchecked albums: ${metadata.uncheckedAlbumCount}`,
  ]
}

function showActionLabels(pane: keyof typeof paneWidths) {
  if (pane === 'artists') {
    return paneWidths.artists >= artistHeaderActionLabelMinimumWidth
  }
  if (pane === 'titles') {
    return paneWidths.titles >= titleHeaderActionLabelMinimumWidth
  }
  return true
}

function collectionRowMinimumWidth() {
  return collectionListHorizontalPadding
    + collectionRowHorizontalPadding
    + collectionTypeIconWidth
    + collectionTitleItemGap
    + collectionReadableNameMinimumWidth
    + collectionRowNameTrailingGap
    + collectionRowInfoWidth
    + collectionRowInfoActionGap
    + collectionActionMeasurement.value.icon
}

function collectionPaneMinimumWidth() {
  return Math.max(paneHeaderMinimumWidths.collections, collectionRowMinimumWidth())
}

function handleCollectionRowFocusOut(collection: MusicCollection, event: FocusEvent) {
  const nextTarget = event.relatedTarget
  if (nextTarget instanceof Node && event.currentTarget instanceof HTMLElement && event.currentTarget.contains(nextTarget)) {
    return
  }
  if (focusedCollectionId.value === collection.id) {
    focusedCollectionId.value = null
  }
}

function collectionActionAvailableWidth(collection: MusicCollection) {
  const measurement = currentCollectionRowMeasurement(collection)
  if (!measurement) {
    return 0
  }
  return Math.max(
    0,
    measurement.contentWidth
      - measurement.leadingWidth
      - measurement.nameWidth
      - collectionRowNameTrailingGap
      - collectionRowInfoWidth
      - collectionRowInfoActionGap,
  )
}

function recordCollectionActionMeasurement(measurement: ActionMeasurement) {
  collectionActionMeasurement.value = measurement
}

function currentCollectionRowMeasurement(collection: MusicCollection) {
  const observedPaneWidth = paneWidths.collections
  const listWidth = collectionListElement.value?.clientWidth
  const availableWidth = listWidth ?? observedPaneWidth
  const contentWidth = Math.max(0, availableWidth - collectionListHorizontalPadding - collectionRowHorizontalPadding)
  if (contentWidth <= 0) {
    return null
  }
  return {
    contentWidth,
    leadingWidth: collectionRowLeadingWidth(),
    nameWidth: measuredCollectionNameWidth(collection.name),
  }
}

function collectionRowLeadingWidth() {
  return collectionTypeIconWidth
    + collectionTitleItemGap
}

function measuredCollectionNameWidth(name: string) {
  if (typeof document === 'undefined') {
    return Math.ceil(name.length * 8.5)
  }
  collectionNameMeasureCanvas ??= document.createElement('canvas')
  const context = collectionNameMeasureCanvas.getContext('2d')
  if (!context) {
    return Math.ceil(name.length * 8.5)
  }
  const sample = document.querySelector<HTMLElement>('.collection-list .nav-row__name')
  if (sample) {
    const style = getComputedStyle(sample)
    context.font = `${style.fontStyle} ${style.fontVariant} ${style.fontWeight} ${style.fontSize} / ${style.lineHeight} ${style.fontFamily}`
  }
  return Math.ceil(context.measureText(name).width)
}

function artistRowMinimumWidth(actionWidth: number, issueWidth: number, statusWidth: number) {
  return actionWidth
    + issueWidth
    + statusWidth
    + artistReadableNameMinimumWidth
    + artistRowCellHorizontalPadding
    + artistRowNameTrailingGap
    + artistGridScrollbarGutterWidth
    + (issueWidth > 0 ? artistRowVisibleItemGap : 0)
}

function artistPaneMinimumWidth() {
  return Math.max(
    paneHeaderMinimumWidths.artists,
    artistRowMinimumWidth(
      Math.max(
        artistIconActionButtonWidth,
        ...Object.values(artistActionMeasurements).map((measurement) => measurement.icon),
      ),
      artistIssueColumnWidthForCollection(),
      artistStatusIconWidthForCollection(),
    ),
  )
}

function showArtistIssueLabel(artist: Artist) {
  return artistRowFit(artist).issueLabel
}

function artistRowFit(artist: Artist): ArtistRowFit {
  const measurement = currentArtistRowMeasurement(artist)
  if (!measurement) {
    return { issueLabel: false }
  }

  const active = artistRowActionsVisible(artist)
  const expandedIssueWidth = measurement.issueLabeledWidth
  const compactIssueWidth = measurement.issueCompactWidth
  const actions = currentArtistActionMeasurement(artist)

  if (!active) {
    return {
      issueLabel: expandedIssueWidth > 0 && artistRowFits(measurement, 0, expandedIssueWidth),
    }
  }

  if (artistRowFits(measurement, actions.labeled, expandedIssueWidth)) {
    return { issueLabel: expandedIssueWidth > 0 }
  }
  if (artistRowFits(measurement, actions.icon, expandedIssueWidth)) {
    return { issueLabel: expandedIssueWidth > 0 }
  }
  if (artistRowFits(measurement, actions.icon, compactIssueWidth)) {
    return { issueLabel: false }
  }
  return { issueLabel: false }
}

function artistRowFits(measurement: ArtistRowMeasurement, actionWidth: number, issueWidth: number) {
  const trailingWidth = actionWidth
    + issueWidth
    + (actionWidth > 0 && issueWidth > 0 ? artistRowVisibleItemGap : 0)
  const trailingGap = trailingWidth > 0 ? artistRowNameTrailingGap : 0
  return measurement.nameWidth + trailingGap + trailingWidth <= measurement.contentWidth
}

function currentArtistRowMeasurement(artist: Artist) {
  const measured = artistRowMeasurements[artist.id]
  if (measured) {
    return measured
  }
  const gridWidth = artistGridElement.value?.clientWidth
  const availableWidth = gridWidth ?? Math.max(0, paneWidths.artists - artistGridScrollbarGutterWidth)
  const contentWidth = Math.max(0, availableWidth - artistRowCellHorizontalPadding)
  if (contentWidth <= 0) {
    return null
  }
  return {
    contentWidth,
    issueCompactWidth: artist.uncheckedAlbumCount > 0 ? artistIssueColumnWidths.compact : 0,
    issueLabeledWidth: artist.uncheckedAlbumCount > 0 ? artistIssueColumnWidths.labeled : 0,
    nameWidth: measuredArtistNameWidth(artist.name) + artistLeadingStatusWidth(artist),
  }
}

function currentArtistActionMeasurement(artist: Artist) {
  return artistActionMeasurements[artist.id] ?? {
    icon: providerForArtist(artist) ? rowActionButtonWidths.providerChipCompact : artistIconActionButtonWidth,
    labeled: providerForArtist(artist) ? rowActionButtonWidths.providerChip : rowActionButtonWidths.provider,
  }
}

function recordArtistActionMeasurement(artistId: number, measurement: ActionMeasurement) {
  artistActionMeasurements[artistId] = measurement
  scheduleArtistRowMeasurement()
}

function artistActionAvailableWidth(artist: Artist) {
  const measurement = currentArtistRowMeasurement(artist)
  if (!measurement) {
    return 0
  }
  const issueWidth = showArtistIssueLabel(artist)
    ? measurement.issueLabeledWidth
    : measurement.issueCompactWidth
  return Math.max(
    0,
    measurement.contentWidth
      - measurement.nameWidth
      - artistRowNameTrailingGap
      - issueWidth
      - (issueWidth > 0 ? artistRowVisibleItemGap : 0),
  )
}

function artistLeadingStatusWidth(artist: Artist) {
  return artistFailureTooltip(artist) ? artistFailureIconWidth : 0
}

function measuredArtistNameWidth(name: string) {
  if (typeof document === 'undefined') {
    return Math.ceil(name.length * 8.5)
  }
  artistNameMeasureCanvas ??= document.createElement('canvas')
  const context = artistNameMeasureCanvas.getContext('2d')
  if (!context) {
    return Math.ceil(name.length * 8.5)
  }
  const sample = document.querySelector<HTMLElement>('.artists-pane .artist-cell > .cell-strong')
  if (sample) {
    const style = getComputedStyle(sample)
    context.font = `${style.fontStyle} ${style.fontVariant} ${style.fontWeight} ${style.fontSize} / ${style.lineHeight} ${style.fontFamily}`
  }
  return Math.ceil(context.measureText(name).width)
}

function artistIssueColumnWidthForCollection() {
  if (!collectionArtists.value.some((artist) => artist.uncheckedAlbumCount > 0)) {
    return 0
  }
  return artistIssueColumnWidths.compact
}

function artistStatusIconWidthForCollection() {
  return collectionArtists.value.some((artist) => artistFailureTooltip(artist)) ? artistFailureIconWidth : 0
}

function showCollectionAddLabel() {
  return paneWidths.collections >= collectionAddLabelMinimumWidth
}

function actionLabelClass(pane: keyof typeof paneWidths) {
  return actionLabelClassFor(showActionLabels(pane))
}

function artistRowTrailingClass(artist: Artist) {
  return {
    'artist-row-trailing--active': artistRowActionsVisible(artist),
    'artist-row-trailing--with-chip': artist.uncheckedAlbumCount > 0,
  }
}

function actionLabelClassFor(showLabels: boolean) {
  return {
    'action-button--labeled': showLabels,
    'action-button--icon-only': !showLabels,
  }
}

function setupPaneWidthObserver() {
  paneWidthObserver?.disconnect()
  paneWidthObserver = new ResizeObserver((entries) => {
    entries.forEach((entry) => {
      if (entry.target === threePaneElement.value) {
        collectionWorkspaceWidth.value = Math.round(entry.contentRect.width)
        return
      }
      if (paneResizeActive) {
        return
      }
      const pane = (entry.target as HTMLElement).dataset.paneKey as keyof typeof paneWidths | undefined
      if (pane) {
        paneWidths[pane] = Math.round(entry.contentRect.width)
      }
    })
  })
  if (threePaneElement.value) {
    collectionWorkspaceWidth.value = threePaneElement.value.clientWidth
    paneWidthObserver.observe(threePaneElement.value)
  }
  observePaneWidth('collections', collectionsPaneElement.value)
  observePaneWidth('artists', artistsPaneElement.value)
  observePaneWidth('albums', albumsPaneElement.value)
  observePaneWidth('titles', titlesPaneElement.value)
}

function observePaneWidth(pane: keyof typeof paneWidths, paneRef: unknown) {
  const element = resolveElement(paneRef)
  if (!element || !paneWidthObserver) {
    return
  }
  element.dataset.paneKey = pane
  paneWidths[pane] = Math.round(element.getBoundingClientRect().width)
  paneWidthObserver.observe(element)
}

function updatePaneWidthsDuringResize(isTitleLayout: boolean) {
  const entries: Array<[keyof typeof paneWidths, HTMLElement | null]> = [
    ['collections', resolveElement(collectionsPaneElement.value)],
  ]
  if (isTitleLayout) {
    entries.push(['titles', resolveElement(titlesPaneElement.value)])
  } else {
    entries.push(['artists', resolveElement(artistsPaneElement.value)])
    entries.push(['albums', resolveElement(albumsPaneElement.value)])
  }

  let changed = false
  entries.forEach(([pane, element]) => {
    if (!element) {
      return
    }
    const width = Math.round(element.getBoundingClientRect().width)
    if (paneWidths[pane] !== width) {
      paneWidths[pane] = width
      changed = true
    }
  })
  return changed
}

function resolveElement(value: unknown) {
  if (value instanceof HTMLElement) {
    return value
  }
  if (value && typeof value === 'object' && '$el' in value) {
    const candidate = (value as { $el?: unknown }).$el
    if (candidate instanceof HTMLElement) {
      return candidate
    }
  }
  return null
}

async function askDeleteCollection(collection: MusicCollection) {
  selectCollection(collection)
  if (writeActionsDisabled.value) {
    return
  }
  collectionToDelete.value = collection
  collectionDeletePreview.value = null
  deleteCollectionDialog.value = true
  collectionDeletePreviewLoading.value = true
  try {
    collectionDeletePreview.value = await store.loadCollectionDeletePreview(collection.id)
  } catch (error) {
    deleteCollectionDialog.value = false
    store.showErrorStatus(error, 'Unable to preview collection deletion')
  } finally {
    collectionDeletePreviewLoading.value = false
  }
}

async function deleteCollection() {
  if (writeActionsDisabled.value) {
    return
  }
  if (!collectionToDelete.value) {
    return
  }
  const collectionId = collectionToDelete.value.id
  const collectionName = collectionToDelete.value.name
  deleteCollectionDialog.value = false
  collectionToDelete.value = null
  collectionDeletePreview.value = null
  try {
    const result = await store.deleteCollection(collectionId)
    store.showStatus(`Deleted collection "${collectionName}" (${result.albumsDeleted} album${result.albumsDeleted === 1 ? '' : 's'} and ${result.artistsDeleted} artist${result.artistsDeleted === 1 ? '' : 's'}).`, 'done')
  } catch (error) {
    store.showErrorStatus(error, 'Unable to delete collection')
  }
}

async function toggleAddCollectionDropdown() {
  if (addCollectionDropdownOpen.value) {
    addCollectionDropdownOpen.value = false
    return
  }
  try {
    await store.loadCollectionCandidates()
    addCollectionDropdownOpen.value = true
  } catch (error) {
    store.showErrorStatus(error, 'Unable to load collection folders')
  }
}

function addCollection(candidate: CollectionFolderCandidate) {
  if (writeActionsDisabled.value) {
    return
  }
  store.queueCollectionCreation(candidate)
}

function closeAddCollectionDropdown() {
  addCollectionDropdownOpen.value = false
}

function openCollectionEdit(collection: MusicCollection, event?: MouseEvent) {
  selectCollection(collection)
  if (writeActionsDisabled.value) {
    return
  }
  collectionEditForm.name = collection.name
  collectionEditForm.type = collection.type
  collectionEditTarget.value = event?.currentTarget instanceof HTMLElement ? event.currentTarget : undefined
  collectionEditOpenId.value = collection.id
  void store.loadCollectionMetadata(collection.id, true)
  void nextTick(() => {
    const input = document.querySelector<HTMLInputElement>('.collection-edit-card input')
    input?.focus()
    input?.select()
  })
}

async function saveCollectionEdit(collection: MusicCollection) {
  if (writeActionsDisabled.value) {
    return
  }
  const name = collectionEditForm.name.trim()
  if (!name) {
    return
  }
  collectionEditOpenId.value = null
  if (name === collection.name && collectionEditForm.type === collection.type) {
    return
  }
  try {
    await store.updateCollection(collection.id, {
      name,
      type: collectionEditForm.type,
    })
  } catch (error) {
    store.showErrorStatus(error, 'Unable to save collection')
  }
}

async function saveOpenCollectionEdit() {
  if (!collectionToEdit.value) {
    return
  }
  await saveCollectionEdit(collectionToEdit.value)
}

function closeCollectionEdit() {
  collectionEditOpenId.value = null
  collectionEditTarget.value = undefined
}

async function startScan(collectionId: number) {
  selectCollectionById(collectionId)
  if (scanActionsDisabled.value) {
    return
  }
  try {
    await store.runScanJob(collectionId)
  } catch (error) {
    store.showErrorStatus(error, 'Unable to start collection scan')
  }
}

function selectCollection(collection: MusicCollection) {
  if (selectedCollectionId.value === collection.id) {
    return
  }
  void store.selectCollection(collection.id)
}

function selectCollectionById(collectionId: number) {
  const collection = collections.value.find((item) => item.id === collectionId)
  if (collection) {
    selectCollection(collection)
    return
  }
  if (selectedCollectionId.value !== collectionId) {
    void store.selectCollection(collectionId)
  }
}

const renderedFixedPaneWidths = computed(() => {
  const availableWidth = Math.max(1, collectionWorkspaceWidth.value - paneResizerTotalWidth())
  if (selectedCollectionIsTitle.value) {
    return fitFixedPaneWidths(
      [collectionPanePreferredWidth.value],
      [collectionPaneMinimumWidth()],
      titlePaneMinimumWidth(),
      availableWidth,
    )
  }
  return fitFixedPaneWidths(
    [collectionPanePreferredWidth.value, collectionArtistPanePreferredWidth.value],
    [collectionPaneMinimumWidth(), artistPaneMinimumWidth()],
    albumPaneMinimumWidth(),
    availableWidth,
  )
})

function paneStyle(index: number) {
  const fixedWidth = renderedFixedPaneWidths.value[index]
  return {
    display: 'flex',
    flex: fixedWidth === undefined ? '1 1 0' : `0 0 ${fixedWidth}px`,
    flexDirection: 'column',
    minWidth: '0',
    overflow: index === 0 ? 'visible' : 'hidden',
  } satisfies CSSProperties
}

function titlePaneStyle() {
  return {
    display: 'flex',
    flex: '1 1 0',
    flexDirection: 'column',
    minWidth: '0',
    overflow: 'hidden',
  } satisfies CSSProperties
}

function paneResizerTotalWidth() {
  return selectedCollectionIsTitle.value ? paneResizerWidth : paneResizerWidth * 2
}

function albumPaneMinimumWidth() {
  return Math.max(paneHeaderMinimumWidths.albums, minimumGridWidth('album'))
}

function titlePaneMinimumWidth() {
  return Math.max(paneHeaderMinimumWidths.titles, minimumGridWidth('title'))
}

function startPaneResize(index: number, event: PointerEvent) {
  event.preventDefault()
  if (!threePaneElement.value) {
    return
  }
  const target = resolveElement(index === 0 ? collectionsPaneElement.value : artistsPaneElement.value)
  if (!target) {
    return
  }

  const startX = event.clientX
  const fixedWidths = [...renderedFixedPaneWidths.value]
  const startWidth = fixedWidths[index]
  const availableWidth = Math.max(1, threePaneElement.value.clientWidth - paneResizerTotalWidth())
  const flexibleMinimum = selectedCollectionIsTitle.value ? titlePaneMinimumWidth() : albumPaneMinimumWidth()
  const occupiedByOtherFixedPanes = fixedWidths.reduce((sum, width, widthIndex) => widthIndex === index ? sum : sum + width, 0)
  const maximum = Math.max(1, availableWidth - occupiedByOtherFixedPanes - flexibleMinimum)
  const preferredMinimum = index === 0 ? collectionPaneMinimumWidth() : artistPaneMinimumWidth()
  const minimum = Math.min(preferredMinimum, maximum)
  let currentWidth = startWidth
  let pendingWidth: number | null = null
  let paneResizeFrame: number | null = null

  function applyPendingWidth() {
    if (pendingWidth === null) {
      return
    }
    currentWidth = pendingWidth
    target!.style.flex = `0 0 ${currentWidth}px`
    updatePaneWidthsDuringResize(selectedCollectionIsTitle.value)
    pendingWidth = null
  }

  function move(pointerEvent: PointerEvent) {
    pendingWidth = Math.min(Math.max(minimum, startWidth + pointerEvent.clientX - startX), maximum)
    if (paneResizeFrame !== null) {
      return
    }
    paneResizeFrame = window.requestAnimationFrame(() => {
      paneResizeFrame = null
      applyPendingWidth()
    })
  }

  function stop() {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', stop)
    window.removeEventListener('pointercancel', stop)
    if (paneResizeFrame !== null) {
      window.cancelAnimationFrame(paneResizeFrame)
      paneResizeFrame = null
    }
    applyPendingWidth()
    const roundedWidth = Math.round(currentWidth)
    if (index === 0) {
      collectionPanePreferredWidth.value = roundedWidth
      savePaneWidth(collectionPaneWidthPreferenceKey, roundedWidth)
    } else {
      collectionArtistPanePreferredWidth.value = roundedWidth
      savePaneWidth(collectionArtistPaneWidthPreferenceKey, roundedWidth)
    }
    paneResizeActive = false
    document.body.classList.remove('is-pane-resizing')
    void nextTick(setupPaneWidthObserver)
  }

  paneResizeActive = true
  paneWidthObserver?.disconnect()
  document.body.classList.add('is-pane-resizing')
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', stop)
  window.addEventListener('pointercancel', stop)
}

async function loadPaneWidths() {
  const [collectionsPreference, artistsPreference] = await Promise.all([
    store.loadPreference(collectionPaneWidthPreferenceKey),
    store.loadPreference(collectionArtistPaneWidthPreferenceKey),
  ])
  collectionPanePreferredWidth.value = parsePaneWidth(collectionsPreference?.value)
    ?? uiSettings.value.paneWidthDefaults.collections
  collectionArtistPanePreferredWidth.value = parsePaneWidth(artistsPreference?.value)
    ?? uiSettings.value.paneWidthDefaults.collectionArtists
}

function parsePaneWidth(value?: string | null) {
  const width = Number(value)
  return Number.isFinite(width) && width > 0 ? Math.round(width) : null
}

function savePaneWidth(key: string, width: number) {
  void store.savePreference(key, String(width)).catch((error) => {
    store.showErrorStatus(error, 'Unable to save pane width')
  })
}

function applyColumnWidthDefaults() {
  const defaults = uiSettings.value.workspaceColumnDefaults
  Object.assign(albumColumnWidths, defaults.album)
  Object.assign(titleColumnWidths, defaults.title)
}

async function loadColumnWidths() {
  await Promise.all([
    loadColumnWidthPreference('album'),
    loadColumnWidthPreference('title'),
  ])
}

async function loadPresenceFilters() {
  await Promise.all([
    loadPresencePreference('artist', artistPresence),
    loadArtistUncheckedPreference(),
    loadAlbumShowAllPreference(),
    loadPresencePreference('title', titlePresence),
  ])
  presencePreferencesLoaded = true
}

async function loadPresencePreference(scope: keyof typeof presencePreferenceKeys, target: { value: PresenceFilter[] }) {
  const preference = await store.loadPreference(presencePreferenceKeys[scope])
  if (!preference?.value) {
    return
  }
  try {
    const parsed = JSON.parse(preference.value)
    const normalized = normalizePresenceFilter(parsed)
    if (normalized) {
      target.value = normalized
    }
  } catch (error) {
    // Ignore invalid stored UI state and keep the default filter.
  }
}

function normalizePresenceFilter(value: unknown) {
  if (!Array.isArray(value)) {
    return null
  }
  const normalized: PresenceFilter[] = []
  value.forEach((item) => {
    if ((item === 'local' || item === 'nonLocal') && !normalized.includes(item)) {
      normalized.push(item)
    }
  })
  return normalized
}

async function loadArtistUncheckedPreference() {
  const preference = await store.loadPreference(artistUncheckedPreferenceKey)
  if (!preference?.value) {
    return
  }
  try {
    const parsed = JSON.parse(preference.value)
    artistUnchecked.value = normalizeArtistUncheckedFilter(parsed)
  } catch (error) {
    // Ignore invalid stored UI state and keep the default filter.
  }
}

function normalizeArtistUncheckedFilter(value: unknown) {
  if (!Array.isArray(value)) {
    return []
  }
  const normalized: ArtistUncheckedFilter[] = []
  if (value.includes('unchecked')) {
    normalized.push('unchecked')
  }
  return normalized
}

async function loadAlbumShowAllPreference() {
  const preference = await store.loadPreference(albumShowAllPreferenceKey)
  if (!preference?.value) {
    return
  }
  try {
    const parsed = JSON.parse(preference.value)
    albumShowAll.value = normalizeAlbumShowAllFilter(parsed)
  } catch (error) {
    // Ignore invalid stored UI state and keep the default filter.
  }
}

function normalizeAlbumShowAllFilter(value: unknown) {
  if (!Array.isArray(value)) {
    return [...defaultAlbumShowAllFilter]
  }
  const normalized: AlbumShowAllFilter[] = []
  if (value.includes('showAll')) {
    normalized.push('showAll')
  }
  return normalized
}

function savePresenceFilter(scope: keyof typeof presencePreferenceKeys, value: PresenceFilter[]) {
  if (!presencePreferencesLoaded) {
    return
  }
  const normalized = normalizePresenceFilter(value) ?? []
  void store.savePreference(presencePreferenceKeys[scope], JSON.stringify(normalized)).catch((error) => {
    store.showErrorStatus(error, 'Unable to save filter')
  })
}

function saveArtistUncheckedFilter(value: ArtistUncheckedFilter[]) {
  if (!presencePreferencesLoaded) {
    return
  }
  void store.savePreference(artistUncheckedPreferenceKey, JSON.stringify(normalizeArtistUncheckedFilter(value))).catch((error) => {
    store.showErrorStatus(error, 'Unable to save filter')
  })
}

function saveAlbumShowAllFilter(value: AlbumShowAllFilter[]) {
  if (!presencePreferencesLoaded) {
    return
  }
  void store.savePreference(albumShowAllPreferenceKey, JSON.stringify(normalizeAlbumShowAllFilter(value))).catch((error) => {
    store.showErrorStatus(error, 'Unable to save filter')
  })
}

async function loadColumnWidthPreference(table: keyof typeof columnWidthPreferenceKeys) {
  const widths = columnWidthState(table)
  await Promise.all(
    Object.entries(columnWidthPreferenceKeys[table]).map(async ([key, preferenceKey]) => {
      const preference = await store.loadPreference(preferenceKey)
      if (!preference?.value || !(key in widths)) {
        return
      }
      const value = parseColumnWidthPreference(preference.value)
      if (value !== null) {
        widths[key] = Math.max(columnMinimumWidth(table, key), value)
      }
    }),
  )
}

function parseColumnWidthPreference(value: string) {
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) {
    return null
  }
  return Math.round(parsed)
}

function columnWidthState(table: keyof typeof columnWidthPreferenceKeys) {
  if (table === 'album') return albumColumnWidths as Record<string, number>
  return titleColumnWidths as Record<string, number>
}

function columnGridStyle(table: 'artist' | keyof typeof columnWidthPreferenceKeys) {
  if (table === 'artist') {
    return {
      '--workspace-grid-columns': 'minmax(0, 1fr)',
      '--workspace-grid-min-width': '100%',
    }
  }
  return table === 'album' ? albumGridColumns.gridStyle() : titleGridColumns.gridStyle()
}

function tableColumnKeys(table: 'artist' | keyof typeof columnWidthPreferenceKeys) {
  if (table === 'album' && !showAlbumCollectionsColumn.value) {
    return tableColumnOrders.album.filter((key) => key !== 'home') as readonly string[]
  }
  return tableColumnOrders[table] as readonly string[]
}

function minimumGridWidth(table: keyof typeof columnWidthPreferenceKeys) {
  return table === 'album'
    ? albumGridColumns.minimumGridWidth()
    : titleGridColumns.minimumGridWidth()
}

function startColumnResize(table: keyof typeof columnWidthPreferenceKeys, key: string, event: PointerEvent) {
  if (table === 'album') {
    albumGridColumns.startResize(key as AlbumColumnKey, event)
  } else {
    titleGridColumns.startResize(key as TitleColumnKey, event)
  }
}

function saveColumnWidth(table: keyof typeof columnWidthPreferenceKeys, key: string, width: number) {
  const preferenceKey = columnWidthPreferenceKeys[table][key as keyof typeof columnWidthPreferenceKeys[typeof table]]
  if (!preferenceKey) {
    return
  }
  void store.savePreference(
    preferenceKey,
    String(Math.round(width)),
  ).catch((error) => {
    store.showErrorStatus(error, 'Unable to save column width')
  })
}

function columnMinimumWidth(table: keyof typeof columnWidthPreferenceKeys, key: string) {
  if (key === 'spacer') {
    return 0
  }
  if (key === 'action') {
    return sortedCollectionAlbums.value.some((album) => album.localRelativePath)
      ? albumActionColumnMinimumWidths.moveWithInfo
      : albumActionColumnMinimumWidths.move
  }
  if (table === 'album' && key === 'checked') {
    return checkboxColumnMinimumWidth
  }
  if (isSortableColumn(table, key)) {
    if (key === 'releaseYear') {
      return releaseYearColumnMinimumWidth
    }
    if (table === 'title' && key === 'title') {
      return titleCompoundHeaderMinimumWidth
    }
    return sortableColumnMinimumWidth
  }
  if (table === 'album' && key === 'home') {
    return 90
  }
  return Math.max(1, uiSettings.value.tableGridColumnMinWidth)
}

function isSortableColumn(table: keyof typeof columnWidthPreferenceKeys, key: string) {
  if (table === 'album') {
    return key === 'name' || key === 'releaseYear'
  }
  if (table === 'title') {
    return key === 'title' || key === 'artist' || key === 'releaseYear'
  }
  return false
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

function openProviderSetup(artist: Artist) {
  selectArtistRow(artist)
  if (writeActionsDisabled.value) {
    return
  }
  providerSetupArtist.value = artist
  providerSetupProviderId.value = 'musicbrainz'
  providerSetupUrl.value = ''
  providerCandidates.value = []
  providerSetupDialog.value = true
}

function closeProviderSetup() {
  providerSetupDialog.value = false
  providerSetupArtist.value = null
  providerSetupUrl.value = ''
  providerCandidates.value = []
}

async function loadProviderCandidates() {
  if (!providerSetupArtist.value) {
    return
  }
  providerSetupMatching.value = true
  providerCandidates.value = []
  try {
    providerCandidates.value = await store.searchProviderCandidates(
      providerSetupArtist.value.id,
      providerSetupProviderId.value,
    )
  } catch (error) {
    store.showErrorStatus(error, `Unable to search ${providerSetupDefinition.value.label}`)
  } finally {
    providerSetupMatching.value = false
  }
}

async function selectProviderSetupProvider(providerId: ProviderId) {
  if (writeActionsDisabled.value || providerSetupSaving.value) {
    return
  }
  providerSetupProviderId.value = providerId
  providerSetupUrl.value = ''
  await loadProviderCandidates()
}

async function useProviderCandidate(candidate: ArtistProviderCandidate) {
  if (!providerSetupArtist.value || writeActionsDisabled.value) {
    return
  }
  const artist = providerSetupArtist.value
  providerSetupSaving.value = true
  const provider = providerDefinition(candidate.providerId)
  try {
    await store.saveArtistProvider(artist.id, {
      providerId: candidate.providerId,
      providerArtistId: candidate.providerArtistId,
      providerArtistName: candidate.providerArtistName,
      providerCountry: candidate.country,
      providerDisambiguation: candidate.disambiguation,
      providerActive: candidate.active,
      providerUrl: candidate.providerUrl,
      enabled: true,
    })
    closeProviderSetup()
    await scanArtistProvider(artist)
  } catch (error) {
    store.showErrorStatus(error, `Unable to save ${provider.label} provider`)
  } finally {
    providerSetupSaving.value = false
  }
}

async function saveUrlProvider() {
  if (!providerSetupArtist.value || writeActionsDisabled.value || providerSetupUrlValidation.value) {
    return
  }
  const artist = providerSetupArtist.value
  const providerId = providerSetupProviderId.value
  const url = providerSetupUrl.value.trim()
  if (!url || providerId === 'musicbrainz') {
    return
  }
  providerSetupSaving.value = true
  try {
    await store.saveArtistProvider(artist.id, {
      providerId,
      providerUrl: url,
      enabled: true,
    })
    closeProviderSetup()
    await scanArtistProvider(artist)
  } catch (error) {
    store.showErrorStatus(error, 'Unable to save provider')
  } finally {
    providerSetupSaving.value = false
  }
}

function openExternal(url?: string | null) {
  if (url) {
    window.open(url, '_blank', 'noopener')
  }
}

async function clearArtistProvider(artist: Artist) {
  selectArtistRow(artist)
  if (writeActionsDisabled.value) {
    return
  }
  const provider = providerForArtist(artist)
  if (!provider) {
    return
  }
  try {
    await store.clearArtistProvider(artist.id, provider.providerId)
  } catch (error) {
    store.showErrorStatus(error, 'Unable to remove provider')
  }
}

async function handleArtistProviderAction(artist: Artist) {
  if (providerForArtist(artist)) {
    await scanArtistProvider(artist)
    return
  }
  openProviderSetup(artist)
}

async function scanArtistProvider(artist: Artist) {
  selectArtistRow(artist)
  if (scanActionsDisabled.value) {
    return
  }
  try {
    await store.runProviderArtistJob(artist.id)
  } catch (error) {
    if (!store.providerJob?.message?.startsWith('Provider check failed')) {
      store.showErrorStatus(error, 'Provider check failed')
    }
  }
}

function providerScanIsRunningForArtist(artist: Artist) {
  if (providerJobIsRunning.value) {
    if (providerJob.value?.requestedArtistId != null) {
      return providerJob.value.requestedArtistId === artist.id
    }
    return providerJob.value?.activeArtistId === artist.id
  }
  return false
}

function providerScanIsRunningForCollection() {
  return providerJobIsRunning.value
    && providerJob.value?.kind === 'PROVIDER_COLLECTION'
    && providerJob.value?.requestedCollectionId === selectedCollectionId.value
}

function artistScanIsRunning(artist: Artist) {
  if (!uiSettings.value.artistScanSpinnerEnabled) {
    return false
  }
  return providerScanIsRunningForArtist(artist)
}

async function refreshCollectionProviders() {
  if (scanActionsDisabled.value) {
    return
  }
  if (!selectedCollectionId.value) {
    return
  }
  try {
    await store.runProviderCollectionJob(selectedCollectionId.value)
  } catch (error) {
    if (!store.providerJob?.message?.startsWith('Provider check failed')) {
      store.showErrorStatus(error, 'Provider check failed')
    }
  }
}

async function updateAlbumChecked(album: Album, checked: boolean) {
  selectAlbumRow(album)
  if (writeActionsDisabled.value) {
    return
  }
  if (albumCheckedToggleDisabled(album)) {
    return
  }
  try {
    await store.updateAlbum({ ...album, checked })
  } catch (error) {
    store.showErrorStatus(error, 'Unable to update album')
  }
}

function albumCheckedValue(album: Album) {
  return album.onDisk || album.checked
}

function albumCheckedToggleDisabled(album: Album) {
  return album.onDisk
}

onMounted(async () => {
  document.addEventListener('pointerdown', handleDocumentPointerDown)
  document.addEventListener('keydown', handleDocumentKeyDown)
  applyColumnWidthDefaults()
  await loadColumnWidths()
  await loadPresenceFilters()
  await loadPaneWidths()
  await store.loadCollections()
  await store.loadScanJob()
  if (scanIsRunning.value) {
    store.startScanJobPolling()
  }
  viewReady.value = true
  await nextTick()
  setupPaneWidthObserver()
  scheduleArtistRowMeasurement()
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', handleDocumentPointerDown)
  document.removeEventListener('keydown', handleDocumentKeyDown)
  paneResizeActive = false
  document.body.classList.remove('is-pane-resizing')
  paneWidthObserver?.disconnect()
  paneWidthObserver = null
  if (artistRowMeasureFrame !== null) {
    window.cancelAnimationFrame(artistRowMeasureFrame)
    artistRowMeasureFrame = null
  }
})

watch(selectedCollectionIsTitle, () => {
  selectedAlbumRowId.value = null
  selectedTitleRowId.value = null
  resetArtistGridScroll()
  resetAlbumGridScroll()
  resetTitleGridScroll()
  void nextTick(setupPaneWidthObserver)
})

watch(selectedCollectionId, () => {
  albumMoveMenuOpenId.value = null
  hoveredCollectionId.value = null
  focusedCollectionId.value = null
  hoveredArtistRowId.value = null
  focusedArtistRowId.value = null
  selectedAlbumRowId.value = null
  selectedTitleRowId.value = null
  resetArtistGridScroll()
  resetAlbumGridScroll()
  resetTitleGridScroll()
})

watch(selectedArtistId, () => {
  selectedAlbumRowId.value = null
  resetAlbumGridScroll()
})

watch(selectedCollectionArtistsLoading, (loading) => {
  if (!loading && selectedCollectionIsArtist.value) {
    resetArtistGridScroll()
  }
})

watch(selectedCollectionTitlesLoading, (loading) => {
  if (!loading && selectedCollectionIsTitle.value) {
    resetTitleGridScroll()
  }
})

watch(selectedArtistAlbumsLoading, (loading) => {
  if (!loading && selectedCollectionIsArtist.value) {
    resetAlbumGridScroll()
  }
})

watch([titlePresence, () => titleSort.key, () => titleSort.direction, titleSortMode], () => {
  resetTitleGridScroll()
}, { deep: true })

watch(artistPresence, (value) => {
  resetArtistGridScroll()
  savePresenceFilter('artist', value)
}, { deep: true })

watch(artistUnchecked, (value) => {
  resetArtistGridScroll()
  saveArtistUncheckedFilter(value)
}, { deep: true })

watch(albumShowAll, (value) => {
  resetAlbumGridScroll()
  saveAlbumShowAllFilter(value)
}, { deep: true })

watch(providerSetupProviderId, () => {
  providerCandidates.value = []
  providerSetupUrl.value = ''
})

watch([() => artistSort.key, () => artistSort.direction], () => {
  resetArtistGridScroll()
})

watch([visibleArtistRows, () => paneWidths.artists], () => {
  scheduleArtistRowMeasurement()
}, { flush: 'post' })

watch([hoveredArtistRowId, focusedArtistRowId, selectedArtistId], () => {
  scheduleArtistRowMeasurement()
}, { flush: 'post' })

watch([() => albumSort.key, () => albumSort.direction, showAlbumCollectionsColumn], () => {
  resetAlbumGridScroll()
})

watch(titlePresence, (value) => {
  savePresenceFilter('title', value)
}, { deep: true })

watch(sortedCollectionAlbums, (albums) => {
  if (selectedAlbumRowId.value !== null && !albums.some((album) => album.id === selectedAlbumRowId.value)) {
    selectedAlbumRowId.value = null
  }
})

watch(sortedCollectionTitleItems, (items) => {
  if (selectedTitleRowId.value !== null && !items.some((item) => item.id === selectedTitleRowId.value)) {
    selectedTitleRowId.value = null
  }
})

</script>

<template>
  <v-container v-if="viewReady" fluid class="app-page collections-workspace">
    <div ref="threePaneElement" class="three-pane">
      <v-sheet ref="collectionsPaneElement" class="pane collections-pane" :style="paneStyle(0)">
        <div class="pane-header">
          <div class="pane-header__primary">
            <span class="pane-header__title">Collections</span>
          </div>
          <div ref="addCollectionAnchor" class="pane-header__actions">
            <v-btn
              prepend-icon="mdi-plus"
              size="small"
              :variant="addCollectionDropdownOpen ? 'tonal' : 'text'"
              color="primary"
              :class="[actionLabelClassFor(showCollectionAddLabel()), 'app-toolbar-button']"
              :disabled="writeActionsDisabled"
              @click="toggleAddCollectionDropdown"
            >
              <span v-if="showCollectionAddLabel()">Add</span>
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
              :disabled="writeActionsDisabled"
              @click="addCollection(candidate)"
            >
              <span>{{ candidate.folderName }}</span>
            </button>
            <div v-if="collectionCandidates.length === 0" class="pane-empty pane-empty--compact">
              No available folders.
            </div>
          </div>
        </div>

        <div ref="collectionListElement" class="collection-list">
          <template v-for="row in collectionRows" :key="row.key">
            <div
              v-if="row.pending"
              class="nav-row nav-row--pending"
              aria-disabled="true"
            >
              <span class="nav-row__title">
                <AppSpinner
                  color="primary"
                  class="collection-type-spinner"
                />
                <span class="nav-row__name">{{ row.name }}</span>
              </span>
            </div>
            <div
              v-else
              class="nav-row"
              :class="{
                'is-selected': row.collection.id === selectedCollectionId,
                'nav-row--pending': row.collection.id === deletingCollectionId,
              }"
              :aria-disabled="row.collection.id === deletingCollectionId"
              @click.capture="selectCollection(row.collection)"
              @focusin="focusedCollectionId = row.collection.id"
              @focusout="handleCollectionRowFocusOut(row.collection, $event)"
              @mouseenter="hoveredCollectionId = row.collection.id"
              @mouseleave="hoveredCollectionId = null"
            >
              <span class="nav-row__title">
                <AppSpinner
                  v-if="row.collection.id === deletingCollectionId"
                  class="collection-type-spinner"
                />
                <v-tooltip v-else :text="collectionTypeLabel(row.collection)" location="top">
                  <template #activator="{ props }">
                    <v-icon
                      v-bind="props"
                      :icon="collectionTypeIcon(row.collection)"
                      size="16"
                      class="collection-type-icon"
                      :class="row.collection.type === 'TITLE' ? 'collection-type-icon--title' : 'collection-type-icon--artist'"
                    ></v-icon>
                  </template>
                </v-tooltip>
                <span class="nav-row__name">
                  {{ row.collection.id === deletingCollectionId ? `Deleting ${row.collection.name}…` : row.collection.name }}
                </span>
              </span>
              <span v-if="row.collection.id !== deletingCollectionId" class="nav-row__trailing">
                <span class="nav-row__info" @click.stop>
                  <v-tooltip location="end" :open-on-hover="true" @update:model-value="(open) => loadCollectionInfo(row.collection, open)">
                    <template #activator="{ props }">
                      <v-icon
                        v-bind="props"
                        icon="mdi-information-outline"
                        size="16"
                        class="collection-info-icon"
                      ></v-icon>
                    </template>
                    <div class="collection-info-tooltip">
                      <div
                        v-if="collectionMetadataLoading[row.collection.id] && !collectionMetadata[row.collection.id]"
                        class="collection-info-tooltip__loading"
                      >
                        <AppSpinner />
                        <span>Loading info</span>
                      </div>
                      <template v-else-if="collectionMetadata[row.collection.id]">
                        <div v-for="line in collectionInfoLines(row.collection)" :key="line">{{ line }}</div>
                      </template>
                      <div v-else>Info not loaded</div>
                    </div>
                  </v-tooltip>
                </span>
                <InlineRowActions
                  class="nav-row__actions"
                  :available-width="collectionActionAvailableWidth(row.collection)"
                  @measured="recordCollectionActionMeasurement"
                >
                  <template #default="{ showLabels }">
                  <v-tooltip text="Edit collection" location="top">
                    <template #activator="{ props }">
                      <RowActionButton
                        v-bind="props"
                        icon="mdi-pencil"
                        label="Edit"
                        :show-label="showLabels"
                        :disabled="writeActionsDisabled"
                        @click.stop="openCollectionEdit(row.collection, $event)"
                      />
                    </template>
                  </v-tooltip>
                  <v-tooltip text="Scan collection" location="top">
                    <template #activator="{ props }">
                      <RowActionButton
                        v-bind="props"
                        icon="mdi-refresh"
                        label="Scan"
                        :show-label="showLabels"
                        :disabled="scanActionsDisabled"
                        @click.stop="startScan(row.collection.id)"
                      />
                    </template>
                  </v-tooltip>
                  <v-tooltip text="Delete collection" location="top">
                    <template #activator="{ props }">
                      <RowActionButton
                        v-bind="props"
                        icon="mdi-trash-can-outline"
                        label="Delete"
                        color="error"
                        :show-label="showLabels"
                        :disabled="writeActionsDisabled"
                        @click.stop="askDeleteCollection(row.collection)"
                      />
                    </template>
                  </v-tooltip>
                  </template>
                </InlineRowActions>
              </span>
            </div>
          </template>
        </div>
      </v-sheet>

      <v-overlay
        v-model="collectionEditDialogOpen"
        :target="collectionEditTarget"
        location="end"
        location-strategy="connected"
        scroll-strategy="reposition"
        :scrim="false"
        :offset="8"
        content-class="collection-edit-overlay__content"
        @click:outside="closeCollectionEdit"
      >
        <v-card v-if="collectionToEdit" class="dialog-card collection-edit-card">
          <v-card-title>Edit Collection</v-card-title>
          <v-card-text class="edit-form collection-edit-form">
            <v-row dense class="edit-form__grid">
              <v-col cols="12">
                <v-text-field
                  v-model="collectionEditForm.name"
                  label="Name"
                  :disabled="writeActionsDisabled"
                  hide-details="auto"
                ></v-text-field>
              </v-col>
            </v-row>

            <div class="collection-edit-row">
              <span class="modal-section-title collection-edit-row__label">Type</span>
              <v-btn-toggle
                v-model="collectionEditForm.type"
                mandatory
                density="compact"
                color="primary"
                class="app-toolbar-toggle collection-type-toggle"
                :disabled="writeActionsDisabled || !collectionTypeEditable"
              >
                <v-btn value="ARTIST" size="small">
                  <v-icon
                    icon="mdi-account-music"
                    size="16"
                    class="collection-type-icon collection-type-icon--artist"
                  ></v-icon>
                  <span>Artist</span>
                </v-btn>
                <v-btn value="TITLE" size="small">
                  <v-icon
                    icon="mdi-album"
                    size="16"
                    class="collection-type-icon collection-type-icon--title"
                  ></v-icon>
                  <span>Title</span>
                </v-btn>
              </v-btn-toggle>
            </div>
          </v-card-text>
          <v-card-actions>
            <v-spacer></v-spacer>
            <v-btn size="small" variant="text" class="app-toolbar-button" @click.stop="closeCollectionEdit">Cancel</v-btn>
            <v-btn
              size="small"
              color="primary"
              variant="flat"
              class="app-toolbar-button"
              :disabled="writeActionsDisabled || !collectionEditForm.name.trim()"
              @click.stop="saveOpenCollectionEdit"
            >
              Save
            </v-btn>
          </v-card-actions>
        </v-card>
      </v-overlay>

      <div class="pane-resizer" @pointerdown="startPaneResize(0, $event)"></div>

      <template v-if="selectedCollectionIsTitle">
        <v-sheet ref="titlesPaneElement" class="pane titles-pane" :style="titlePaneStyle()">
          <div class="pane-header">
            <div class="pane-header__primary">
              <span class="pane-header__title">Titles</span>
            </div>
          </div>
          <div class="pane-filter-bar">
            <span class="pane-filter-bar__label">Filter</span>
            <v-btn-toggle
              v-model="titlePresence"
              multiple
              density="compact"
              color="primary"
              class="app-toolbar-toggle presence-filter-toggle"
            >
              <v-btn value="local" size="small" prepend-icon="mdi-folder-outline">
                <v-tooltip activator="parent" text="Show titles with a local folder in this collection." location="top"></v-tooltip>
                Local
              </v-btn>
              <v-btn value="nonLocal" size="small" prepend-icon="mdi-cloud-outline">
                <v-tooltip activator="parent" text="Show titles not currently found on disk in this collection." location="top"></v-tooltip>
                Non-local
              </v-btn>
            </v-btn-toggle>
          </div>

          <div v-if="!selectedCollection" class="pane-empty">Select a collection.</div>
          <div v-else-if="selectedCollectionTitlesLoading" class="pane-loading">
            <AppSpinner variant="pane" />
          </div>
          <div
            v-else
            ref="titleGridElement"
            class="workspace-grid"
            :style="columnGridStyle('title')"
            @scroll="handleTitleGridScroll"
          >
            <div class="workspace-grid__row workspace-grid__header">
              <div
                class="workspace-grid__cell workspace-grid__header-cell sortable-header"
                data-column="title.title"
                @click="handleTitleHeaderClick('title', $event)"
              >
                <span class="sortable-header__label">Title</span>
                <v-tooltip :text="titleSortModeTooltip()" location="top">
                  <template #activator="{ props }">
                    <v-btn
                      v-bind="props"
                      :icon="titleSortModeIcon()"
                      size="x-small"
                      variant="text"
                      color="primary"
                      class="title-sort-mode-button"
                      @click.stop="toggleTitleSortMode"
                    ></v-btn>
                  </template>
                </v-tooltip>
                <v-icon
                  v-if="titleSort.key === 'title'"
                  :icon="sortIcon(titleSort.direction)"
                  size="14"
                  class="sort-direction-icon"
                ></v-icon>
                  <span
                    class="column-resize-handle"
                    @pointerdown="startColumnResize('title', 'title', $event)"
                    @click="suppressHeaderSortClick($event)"
                  ></span>
              </div>
              <div
                class="workspace-grid__cell workspace-grid__header-cell sortable-header"
                data-column="title.artist"
                @click="handleTitleHeaderClick('artist', $event)"
              >
                <span class="sortable-header__label">Artist</span>
                <v-icon
                  v-if="titleSort.key === 'artist'"
                  :icon="sortIcon(titleSort.direction)"
                  size="14"
                  class="sort-direction-icon"
                ></v-icon>
                  <span
                    class="column-resize-handle"
                    @pointerdown="startColumnResize('title', 'artist', $event)"
                    @click="suppressHeaderSortClick($event)"
                  ></span>
              </div>
              <div
                class="workspace-grid__cell workspace-grid__header-cell sortable-header"
                data-column="title.releaseYear"
                @click="handleTitleHeaderClick('releaseYear', $event)"
              >
                <span class="sortable-header__label">Year</span>
                <v-icon
                  v-if="titleSort.key === 'releaseYear'"
                  :icon="sortIcon(titleSort.direction)"
                  size="14"
                  class="sort-direction-icon"
                ></v-icon>
                <span
                  class="column-resize-handle"
                  @pointerdown="startColumnResize('title', 'releaseYear', $event)"
                  @click="suppressHeaderSortClick($event)"
                ></span>
              </div>
              <div class="workspace-grid__cell workspace-grid__header-cell" data-column="title.spacer"></div>
            </div>
            <div
              v-if="titleVirtualTopSpacerHeight > 0"
              class="workspace-grid__virtual-spacer"
              :style="{ height: `${titleVirtualTopSpacerHeight}px` }"
              aria-hidden="true"
            ></div>
            <div
              v-for="item in visibleTitleRows"
              :key="item.id"
              class="workspace-grid__row workspace-row"
              :class="titleRowClass(item)"
              @click.capture="selectTitleRow(item)"
            >
                <div data-column="title.title" class="workspace-grid__cell truncate-cell">
                  <span :class="albumTitleClasses(item)">{{ item.title }}</span>
                </div>
                <div data-column="title.artist" class="workspace-grid__cell truncate-cell">
                  <span>{{ item.artistName ?? '' }}</span>
                </div>
                <div data-column="title.releaseYear" class="workspace-grid__cell release-year-cell">
                  <v-chip
                    v-if="item.releaseYear"
                    class="album-metadata-chip numeric-chip--year"
                    :class="albumReleaseYearChipClasses(item)"
                    variant="tonal"
                  >
                    {{ item.releaseYear }}
                  </v-chip>
                </div>
                <div data-column="title.spacer" class="workspace-grid__cell"></div>
            </div>
            <div
              v-if="titleVirtualBottomSpacerHeight > 0"
              class="workspace-grid__virtual-spacer"
              :style="{ height: `${titleVirtualBottomSpacerHeight}px` }"
              aria-hidden="true"
            ></div>
          </div>
        </v-sheet>
      </template>

      <template v-else>
      <v-sheet ref="artistsPaneElement" class="pane artists-pane" :style="paneStyle(1)">
        <div class="pane-header">
          <div class="pane-header__primary">
            <span class="pane-header__title">Artists</span>
          </div>
          <div class="pane-header__actions">
            <v-tooltip text="Scan artist providers in this collection" location="top">
              <template #activator="{ props }">
                <v-btn
                  v-bind="props"
                  size="small"
                  variant="text"
                  color="primary"
                  prepend-icon="mdi-cloud-sync-outline"
                  :class="[actionLabelClass('artists'), 'app-toolbar-button']"
                  :loading="providerScanIsRunningForCollection()"
                  :disabled="!selectedCollectionIsArtist || collectionArtists.length === 0 || scanActionsDisabled"
                  @click="refreshCollectionProviders"
                >
                  <span v-if="showActionLabels('artists')">Provider</span>
                </v-btn>
              </template>
            </v-tooltip>
            <v-tooltip text="Sort artists" location="top">
              <template #activator="{ props }">
                <v-btn
                  v-bind="props"
                  :prepend-icon="artistSort.direction === 'asc' ? 'mdi-sort-alphabetical-ascending' : 'mdi-sort-alphabetical-descending'"
                  size="small"
                  variant="text"
                  color="primary"
                  :class="[actionLabelClass('artists'), 'app-toolbar-button']"
                  :disabled="!selectedCollectionId"
                  @click="toggleArtistSort('name')"
                >
                  <span v-if="showActionLabels('artists')">Sort</span>
                </v-btn>
              </template>
            </v-tooltip>
          </div>
        </div>
        <div class="pane-filter-bar">
          <span class="pane-filter-bar__label">Filter</span>
          <v-btn-toggle
            v-model="artistPresence"
            multiple
            density="compact"
            color="primary"
            class="app-toolbar-toggle presence-filter-toggle"
          >
            <v-btn value="local" size="small" prepend-icon="mdi-folder-outline">
              <v-tooltip activator="parent" text="Show artists with local albums in this collection." location="top"></v-tooltip>
              Local
            </v-btn>
            <v-btn value="nonLocal" size="small" prepend-icon="mdi-cloud-outline">
              <v-tooltip activator="parent" text="Show artists linked to this collection without local disk evidence here." location="top"></v-tooltip>
              Non-local
            </v-btn>
          </v-btn-toggle>
          <v-btn-toggle
            v-model="artistUnchecked"
            multiple
            density="compact"
            color="primary"
            class="app-toolbar-toggle presence-filter-toggle"
          >
            <v-btn value="unchecked" size="small" prepend-icon="mdi-alert-circle-outline">
              <v-tooltip activator="parent" text="Show only artists with unchecked albums to listen through." location="top"></v-tooltip>
              Unchecked
            </v-btn>
          </v-btn-toggle>
        </div>

        <div v-if="!selectedCollection" class="pane-empty">Select a collection.</div>
        <div v-else-if="selectedCollectionArtistsLoading" class="pane-loading">
          <AppSpinner variant="pane" />
        </div>
        <div
          v-else
          ref="artistGridElement"
          class="workspace-grid workspace-grid--no-header"
          :style="columnGridStyle('artist')"
          @scroll="handleArtistGridScroll"
        >
          <div
            v-if="artistVirtualTopSpacerHeight > 0"
            class="workspace-grid__virtual-spacer"
            :style="{ height: `${artistVirtualTopSpacerHeight}px` }"
            aria-hidden="true"
          ></div>
          <div
            v-for="artist in visibleArtistRows"
            :key="artist.id"
            :ref="(element) => setArtistRowElement(artist.id, element)"
            class="workspace-grid__row workspace-row"
            :class="artistRowClass(artist)"
            @click.capture="selectArtistRow(artist)"
            @focusin="focusedArtistRowId = artist.id"
            @focusout="handleArtistRowFocusOut(artist, $event)"
            @mouseenter="hoveredArtistRowId = artist.id"
            @mouseleave="hoveredArtistRowId = null"
          >
            <div data-column="artist.name" class="workspace-grid__cell">
              <div class="artist-cell">
                <AppSpinner
                  v-if="artistScanIsRunning(artist)"
                  class="artist-cell__spinner"
                />
                <v-tooltip v-if="artistFailureTooltip(artist)" :text="artistFailureTooltip(artist)" location="top">
                  <template #activator="{ props }">
                    <v-icon
                      v-bind="props"
                      icon="mdi-alert-circle"
                      size="16"
                      class="artist-cell__failure"
                    ></v-icon>
                  </template>
                </v-tooltip>
                <span class="cell-strong">{{ artist.name }}</span>
                <div class="artist-row-trailing" :class="artistRowTrailingClass(artist)">
                  <v-tooltip v-if="artistIssueLabel(artist)" :text="artistIssueLabel(artist)" location="top">
                    <template #activator="{ props }">
                      <v-chip
                        v-bind="props"
                        :aria-label="artistIssueLabel(artist)"
                        class="artist-issue-chip numeric-chip numeric-chip--count unchecked-count-chip"
                        data-adaptive-control
                        :data-show-label="showArtistIssueLabel(artist)"
                        color="warning"
                        size="x-small"
                        variant="tonal"
                      >
                        <span class="artist-issue-chip__count">{{ artist.uncheckedAlbumCount }}</span>
                        <span class="artist-issue-chip__label adaptive-control-label">unchecked</span>
                      </v-chip>
                    </template>
                  </v-tooltip>
                  <InlineRowActions
                    class="artist-row-actions"
                    :available-width="artistActionAvailableWidth(artist)"
                    @measured="recordArtistActionMeasurement(artist.id, $event)"
                  >
                    <template #default="{ showLabels }">
                    <v-tooltip
                      v-if="providerForArtist(artist)"
                      :text="`Scan ${providerChipText(artist)}`"
                      location="top"
                    >
                      <template #activator="{ props }">
                        <ProviderChip
                          v-bind="props"
                          class="collections-provider-chip"
                          :provider-id="providerForArtist(artist)?.providerId"
                          :label="providerChipText(artist)"
                          :show-label="showLabels"
                          :error="Boolean(providerForArtist(artist)?.lastErrorMessage)"
                          closable
                          close-icon="mdi-trash-can-outline"
                          :disabled="scanActionsDisabled"
                          @click.stop="scanArtistProvider(artist)"
                          @click:close.stop="clearArtistProvider(artist)"
                        ></ProviderChip>
                      </template>
                    </v-tooltip>
                    <v-tooltip v-else text="Add provider" location="top">
                      <template #activator="{ props }">
                        <RowActionButton
                          v-bind="props"
                          icon="mdi-cloud-plus-outline"
                          label="Add provider"
                          :show-label="showLabels"
                          :disabled="scanActionsDisabled"
                          @click.stop="handleArtistProviderAction(artist)"
                        />
                      </template>
                    </v-tooltip>
                    </template>
                  </InlineRowActions>
                </div>
              </div>
            </div>
          </div>
          <div
            v-if="artistVirtualBottomSpacerHeight > 0"
            class="workspace-grid__virtual-spacer"
            :style="{ height: `${artistVirtualBottomSpacerHeight}px` }"
            aria-hidden="true"
          ></div>
        </div>
      </v-sheet>

      <div class="pane-resizer" @pointerdown="startPaneResize(1, $event)"></div>

      <v-sheet ref="albumsPaneElement" class="pane albums-pane" :style="paneStyle(2)">
        <div class="pane-header">
          <div class="pane-header__primary">
            <span class="pane-header__title">Albums</span>
            <span v-if="selectedArtist" class="pane-header__meta">{{ selectedArtist.name }}</span>
          </div>
        </div>
        <div class="pane-filter-bar">
          <span class="pane-filter-bar__label">Filter</span>
          <v-btn-toggle
            v-model="albumShowAll"
            multiple
            density="compact"
            color="primary"
            class="app-toolbar-toggle presence-filter-toggle"
          >
            <v-btn value="showAll" size="small" prepend-icon="mdi-eye-outline">
              <v-tooltip activator="parent" :text="albumShowAllTooltip()" location="top"></v-tooltip>
              Show All
            </v-btn>
          </v-btn-toggle>
        </div>

        <div v-if="!selectedArtist" class="pane-empty">Select an artist.</div>
        <div v-else-if="selectedArtistAlbumsLoading" class="pane-loading">
          <AppSpinner variant="pane" />
        </div>
        <div v-else-if="sortedCollectionAlbums.length === 0" class="pane-empty pane-empty--action">
          <span>{{ albumPaneEmptyMessage() }}</span>
          <div class="pane-empty__actions">
            <v-btn
              v-if="!providerForArtist(selectedArtist)"
              color="primary"
              variant="tonal"
              prepend-icon="mdi-cloud-plus-outline"
              :disabled="scanActionsDisabled"
              @click="handleArtistProviderAction(selectedArtist)"
            >
              Add provider
            </v-btn>
            <v-tooltip
              v-else
              :text="`Scan ${providerChipText(selectedArtist)}`"
              location="top"
            >
              <template #activator="{ props }">
                <ProviderChip
                  v-bind="props"
                  class="collections-provider-chip pane-empty-provider-chip"
                  :provider-id="providerForArtist(selectedArtist)?.providerId"
                  :label="providerChipText(selectedArtist)"
                  :error="Boolean(providerForArtist(selectedArtist)?.lastErrorMessage)"
                  closable
                  close-icon="mdi-trash-can-outline"
                  :disabled="scanActionsDisabled"
                  @click="scanArtistProvider(selectedArtist)"
                  @click:close.stop="clearArtistProvider(selectedArtist)"
                ></ProviderChip>
              </template>
            </v-tooltip>
          </div>
        </div>
        <div
          v-else
          ref="albumGridElement"
          class="workspace-grid"
          :style="columnGridStyle('album')"
          @scroll="handleAlbumGridScroll"
        >
          <div class="workspace-grid__row workspace-grid__header">
            <div
              class="workspace-grid__cell workspace-grid__header-cell sortable-header"
              data-column="album.name"
              @click="handleAlbumHeaderClick('name', $event)"
            >
              <span class="sortable-header__label">Name</span>
              <v-icon
                v-if="albumSort.key === 'name'"
                :icon="sortIcon(albumSort.direction)"
                size="14"
                class="sort-direction-icon"
              ></v-icon>
                <span
                  class="column-resize-handle"
                  @pointerdown="startColumnResize('album', 'name', $event)"
                  @click="suppressHeaderSortClick($event)"
                ></span>
            </div>
            <div
              class="workspace-grid__cell workspace-grid__header-cell sortable-header"
              data-column="album.releaseYear"
              @click="handleAlbumHeaderClick('releaseYear', $event)"
            >
              <span class="sortable-header__label">Year</span>
              <v-icon
                v-if="albumSort.key === 'releaseYear'"
                :icon="sortIcon(albumSort.direction)"
                size="14"
                class="sort-direction-icon"
              ></v-icon>
                <span
                  class="column-resize-handle"
                  @pointerdown="startColumnResize('album', 'releaseYear', $event)"
                  @click="suppressHeaderSortClick($event)"
                ></span>
            </div>
            <div class="workspace-grid__cell workspace-grid__header-cell" data-column="album.checked">
              <span class="sortable-header__label">Checked</span>
                <span
                  class="column-resize-handle"
                  @pointerdown="startColumnResize('album', 'checked', $event)"
                  @click="suppressHeaderSortClick($event)"
                ></span>
            </div>
            <div
              v-if="showAlbumCollectionsColumn"
              class="workspace-grid__cell workspace-grid__header-cell"
              data-column="album.home"
            >
              <span class="sortable-header__label">{{ albumCollectionsColumnLabel() }}</span>
                <span
                  class="column-resize-handle"
                  @pointerdown="startColumnResize('album', 'home', $event)"
                  @click="suppressHeaderSortClick($event)"
                ></span>
            </div>
          </div>
          <div
            v-if="albumVirtualTopSpacerHeight > 0"
            class="workspace-grid__virtual-spacer"
            :style="{ height: `${albumVirtualTopSpacerHeight}px` }"
            aria-hidden="true"
          ></div>
          <div
            v-for="album in visibleAlbumRows"
            :key="album.id"
            class="workspace-grid__row workspace-row"
            :class="albumRowClass(album)"
            @click.capture="selectAlbumRow(album)"
          >
              <div data-column="album.name" class="workspace-grid__cell truncate-cell">
                <div class="album-cell">
                  <span :class="albumTitleClasses(album)">{{ album.title }}</span>
                </div>
              </div>
              <div data-column="album.releaseYear" class="workspace-grid__cell release-year-cell">
                <v-chip
                  v-if="album.releaseYear"
                  class="album-metadata-chip numeric-chip--year"
                  :class="albumReleaseYearChipClasses(album)"
                  variant="tonal"
                >
                  {{ album.releaseYear }}
                </v-chip>
              </div>
              <div data-column="album.checked" class="workspace-grid__cell checkbox-cell">
                <v-tooltip
                  v-if="albumCheckedToggleDisabled(album)"
                  text="Present on disk; can't uncheck"
                  location="top"
                >
                  <template #activator="{ props }">
                    <span v-bind="props" class="checkbox-cell__tooltip-anchor" @click.stop="selectAlbumRow(album)">
                      <v-checkbox
                        :model-value="albumCheckedValue(album)"
                        color="primary"
                        density="compact"
                        disabled
                        hide-details
                      ></v-checkbox>
                    </span>
                  </template>
                </v-tooltip>
                <v-checkbox
                  v-else
                  :model-value="albumCheckedValue(album)"
                  color="primary"
                  density="compact"
                  :disabled="writeActionsDisabled"
                  hide-details
                  @click.stop="selectAlbumRow(album)"
                  @update:model-value="(value) => updateAlbumChecked(album, Boolean(value))"
                ></v-checkbox>
              </div>
              <div
                v-if="showAlbumCollectionsColumn"
                data-column="album.home"
                class="workspace-grid__cell album-collections-cell"
              >
                <div class="album-collection-chips">
                  <v-chip
                    v-if="album.collection.id !== selectedCollectionId"
                    size="x-small"
                    variant="tonal"
                    color="primary"
                    class="collection-chip album-home-chip album-home-chip--navigation"
                    @click.stop="navigateToAlbumHome(album)"
                  >
                    {{ album.collection.name }}
                  </v-chip>
                  <v-chip
                    v-else
                    size="x-small"
                    variant="tonal"
                    class="collection-chip album-home-chip album-home-chip--current"
                  >
                    {{ album.collection.name }}
                  </v-chip>
                </div>
              </div>
              <ActionColumn column="album.action">
                <template #default="{ showLabels }">
                  <v-menu
                    :model-value="albumMoveMenuOpenId === album.id"
                    location="bottom end"
                    :offset="4"
                    @update:model-value="(open) => albumMoveMenuOpenId = open ? album.id : null"
                  >
                    <template #activator="{ props: menuProps }">
                      <v-tooltip :text="albumMoveTooltip(album)" location="top">
                        <template #activator="{ props: tooltipProps }">
                          <span v-bind="tooltipProps" class="row-action-tooltip-anchor">
                            <RowActionButton
                              v-bind="menuProps"
                              icon="mdi-folder-move-outline"
                              label="Move to"
                              :show-label="showLabels"
                              :disabled="albumMoveDisabled(album)"
                              @click.stop
                            />
                          </span>
                        </template>
                      </v-tooltip>
                    </template>
                    <v-card class="album-move-menu" @click.stop>
                      <div class="album-move-menu__chips">
                        <v-chip
                          v-for="collection in albumMoveDestinations(album)"
                          :key="collection.id"
                          size="small"
                          variant="tonal"
                          color="primary"
                          class="collection-chip"
                          @click.stop="moveAlbumTo(album, collection.id)"
                        >
                          {{ collection.name }}
                        </v-chip>
                      </div>
                    </v-card>
                  </v-menu>
                  <v-tooltip v-if="album.localRelativePath" :text="albumDiskTitle(album)" location="top">
                    <template #activator="{ props }">
                      <v-btn
                        v-bind="props"
                        icon="mdi-information-outline"
                        size="x-small"
                        variant="text"
                        class="album-info-button"
                        @click.stop="selectAlbumRow(album)"
                      ></v-btn>
                    </template>
                  </v-tooltip>
                </template>
              </ActionColumn>
          </div>
          <div
            v-if="albumVirtualBottomSpacerHeight > 0"
            class="workspace-grid__virtual-spacer"
            :style="{ height: `${albumVirtualBottomSpacerHeight}px` }"
            aria-hidden="true"
          ></div>
        </div>
      </v-sheet>
      </template>
    </div>

    <ProviderMatchDialog
      v-model="providerSetupDialog"
      v-model:provider-id="providerSetupProviderId"
      v-model:url="providerSetupUrl"
      :candidates="providerCandidates"
      :loading="providerSetupMatching"
      :saving="providerSetupSaving"
      :disabled="writeActionsDisabled"
      :show-url="providerSetupProviderId !== 'musicbrainz'"
      :url-validation="providerSetupUrlValidation"
      empty-text="No candidates loaded."
      @select-provider="selectProviderSetupProvider"
      @use-candidate="useProviderCandidate"
      @save-url="saveUrlProvider"
      @open-external="openExternal"
      @close="closeProviderSetup"
    />

    <v-dialog v-model="deleteCollectionDialog" width="460" max-width="calc(100vw - 64px)">
      <v-card class="dialog-card delete-collection-dialog">
        <v-card-title>Delete Collection</v-card-title>
        <v-card-text class="edit-form">
          <div>Delete {{ collectionToDelete?.name }}?</div>
          <div v-if="collectionDeletePreviewLoading" class="pane-loading pane-loading--compact">
            <AppSpinner variant="control" />
          </div>
          <div v-else-if="collectionDeletePreview" class="cell-muted">
            This deletes {{ collectionDeletePreview.albumCount }} album{{ collectionDeletePreview.albumCount === 1 ? '' : 's' }} and {{ collectionDeletePreview.artistCount }} artist{{ collectionDeletePreview.artistCount === 1 ? '' : 's' }} that would otherwise have no albums.
          </div>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn variant="text" @click="deleteCollectionDialog = false">Cancel</v-btn>
          <v-btn color="error" :disabled="writeActionsDisabled || collectionDeletePreviewLoading || !collectionDeletePreview" @click="deleteCollection">Delete</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </v-container>
</template>
