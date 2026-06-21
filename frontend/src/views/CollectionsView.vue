<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useLibraryStore } from '@/stores/library'
import { formatDateWithJavaPattern } from '@/dateFormat'
import type { Album, Artist, CollectionFolderCandidate, MusicCollection } from '@/types'
import type { CSSProperties } from 'vue'

interface ArtistForm {
  id: number | null
  name: string
  sortName: string
  notes: string
  collectionIds: string[]
}

type SortDirection = 'asc' | 'desc'
type ArtistSortKey = 'name'
type AlbumSortKey = 'name' | 'releaseDate'
type TitleSortKey = 'title' | 'artist' | 'releaseDate'
type TitleSortMode = 'title' | 'sortName'
type PresenceFilter = 'local' | 'nonLocal'
type PaneLayoutKind = 'artist' | 'title'

const store = useLibraryStore()
const {
  collections,
  collectionCandidates,
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
  providerStatus,
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
const defaultPanePercents = [27, 30, 43]
const paneResizerWidth = 10
const minimumRestoredPanePercent = 0.1
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
const panePercents = ref([...defaultPanePercents])
const paneLayoutCache = reactive<Record<PaneLayoutKind, number[]>>({
  artist: [...defaultPanePercents],
  title: [...defaultPanePercents],
})
const paneLayoutSaveTimer = ref<number | null>(null)
const paneNames = ['collections', 'artists', 'albums'] as const
const titleItemDialog = ref(false)
const titleItemSaving = ref(false)
const titleItemToEdit = ref<Album | null>(null)
const albumEditDialog = ref(false)
const albumEditSaving = ref(false)
const albumToEdit = ref<Album | null>(null)
let paneWidthObserver: ResizeObserver | null = null

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

const titleItemForm = reactive({
  title: '',
  artistName: '',
  releaseDate: '',
  sortName: '',
})

const albumEditForm = reactive({
  title: '',
})

const artistColumnWidths = reactive({
  name: 280,
})

const albumColumnWidths = reactive({
  name: 360,
  releaseDate: 140,
  checked: 120,
  collections: 180,
  action: 122,
})

const titleColumnWidths = reactive({
  title: 460,
  artist: 220,
  releaseDate: 150,
  action: 178,
})

const artistSort = reactive<{ key: ArtistSortKey; direction: SortDirection }>({
  key: 'name',
  direction: 'asc',
})

const albumSort = reactive<{ key: AlbumSortKey; direction: SortDirection }>({
  key: 'releaseDate',
  direction: 'asc',
})

const titleSort = reactive<{ key: TitleSortKey; direction: SortDirection }>({
  key: 'title',
  direction: 'asc',
})

const titleSortMode = ref<TitleSortMode>('sortName')
const artistPresence = ref<PresenceFilter[]>(['local', 'nonLocal'])
const titlePresence = ref<PresenceFilter[]>(['local'])
const artistGridScrollTop = ref(0)
const artistGridViewportHeight = ref(0)
const albumGridScrollTop = ref(0)
const albumGridViewportHeight = ref(0)
const titleGridScrollTop = ref(0)
const titleGridViewportHeight = ref(0)
let presencePreferencesLoaded = false
let paneResizeActive = false
const suppressHeaderSortUntil = ref(0)

const columnWidthPreferenceKeys = {
  artist: {
    name: 'collections-screen.artists-pane.name',
  },
  album: {
    name: 'collections-screen.albums-pane.name',
    releaseDate: 'collections-screen.albums-pane.release-date',
    checked: 'collections-screen.albums-pane.checked',
    collections: 'collections-screen.albums-pane.also-in',
    action: 'collections-screen.albums-pane.action',
  },
  title: {
    title: 'collections-screen.titles-pane.title',
    artist: 'collections-screen.titles-pane.artist',
    releaseDate: 'collections-screen.titles-pane.release-date',
    action: 'collections-screen.titles-pane.action',
  },
} as const

const presencePreferenceKeys = {
  artist: 'collections-screen.artists-pane.presence-filter',
  title: 'collections-screen.titles-pane.presence-filter',
} as const

const paneHeaderMinimumWidths = {
  collections: 250,
  artists: 380,
  albums: 220,
  titles: 430,
} as const

const actionColumnWidths = {
  artist: { icon: 148, labeled: 340 },
  album: { icon: 116, labeled: 286 },
  title: { icon: 84, labeled: 178 },
} as const

const artistIssueColumnWidths = {
  compact: 34,
  labeled: 48,
} as const

const tableColumnOrders = {
  artist: ['name'],
  album: ['name', 'releaseDate', 'checked', 'collections', 'action'],
  title: ['title', 'artist', 'releaseDate', 'action'],
} as const

const artistForm = reactive<ArtistForm>({
  id: null,
  name: '',
  sortName: '',
  notes: '',
  collectionIds: [],
})

const selectedCollection = computed(() =>
  collections.value.find((collection) => collection.id === selectedCollectionId.value) ?? null,
)

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

const collectionOptions = computed(() =>
  collections.value.map((collection) => ({ title: collection.name, value: collection.id })),
)

const scanIsRunning = computed(() => scanJob.value?.status === 'RUNNING')
const collectionScanIsRunning = computed(() => scanIsRunning.value && scanJob.value?.kind !== 'LOCAL_ALBUMS')
const localAlbumScanIsRunning = computed(() => scanIsRunning.value && scanJob.value?.kind === 'LOCAL_ALBUMS')
const providerIsRunning = computed(() => providerStatus.value.running)
const paneLayoutPreferenceKeys = {
  artist: 'collections-screen.artist-layout.panes',
  title: 'collections-screen.title-layout.panes',
} as const

const sortedCollectionArtists = computed(() =>
  collectionArtists.value
    .filter((artist) => matchesPresenceFilter(artistIsLocalToSelectedCollection(artist), artistPresence.value))
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

const sortedCollectionAlbums = computed(() =>
  [...collectionAlbums.value].sort((left, right) => {
    const result = albumSort.key === 'releaseDate'
      ? compareReleaseDates(
        releaseDateSortValue(left.releaseDate),
        releaseDateSortValue(right.releaseDate),
        albumSort.direction,
      )
      : compareText(left.title, right.title)
    return albumSort.key === 'releaseDate'
      ? result || compareText(left.title, right.title)
      : applyDirection(result || compareReleaseDates(
        releaseDateSortValue(left.releaseDate),
        releaseDateSortValue(right.releaseDate),
        'asc',
      ), albumSort.direction)
  }),
)

const showAlbumCollectionsColumn = computed(() =>
  sortedCollectionAlbums.value.some((album) => albumExtraCollections(album).length > 0),
)

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
    } else if (titleSort.key === 'releaseDate') {
      result = compareReleaseDates(
        releaseDateSortValue(left.releaseDate),
        releaseDateSortValue(right.releaseDate),
        titleSort.direction,
      )
    }
    return titleSort.key === 'releaseDate'
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

function compareText(left: string | null | undefined, right: string | null | undefined) {
  return (left ?? '').localeCompare(right ?? '', undefined, { numeric: true, sensitivity: 'base' })
}

function compareReleaseDates(left: string | null, right: string | null, direction: SortDirection) {
  if (left === null && right === null) return 0
  if (left === null) return 1
  if (right === null) return -1
  return applyDirection(compareText(left, right), direction)
}

function applyDirection(result: number, direction: SortDirection) {
  return direction === 'asc' ? result : -result
}

function releaseDateSortValue(releaseDate: string | null | undefined) {
  if (releaseDate && releaseDate.trim()) {
    return releaseDate.trim()
  }
  return null
}

function releaseDateYearLabel(releaseDate: string | null | undefined) {
  if (releaseDate && /^\d{4}/.test(releaseDate)) {
    return releaseDate.slice(0, 4)
  }
  return ''
}

function releaseDateTooltip(releaseDate: string | null | undefined) {
  if (!releaseDate || releaseDate.length <= 4) {
    return ''
  }
  return formatReleaseDate(releaseDate)
}

function formatReleaseDate(releaseDate: string) {
  if (/^\d{4}-\d{2}-\d{2}$/.test(releaseDate)) {
    const [year, month, day] = releaseDate.split('-').map(Number)
    return formatDateWithJavaPattern(
      new Date(year, month - 1, day),
      uiSettings.value.releaseDateDisplayFormat,
      'yyyy-MM-dd',
    )
  }
  return releaseDate
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
    ? 'Title sorting uses Sort as'
    : 'Title sorting uses display title'
}

function albumDiskTitle(album: Album) {
  const activePaths = album.localPaths.filter((path) => !path.missingSince)
  if (activePaths.length > 0) {
    return activePaths.map((path) => path.relativePath).join('\n')
  }
  const missingPaths = album.localPaths.filter((path) => path.missingSince)
  if (missingPaths.length > 0) {
    return missingPaths
      .map((path) => `Missing: ${path.relativePath}`)
      .join('\n')
  }
  return 'No local folder'
}

function albumIsInSelectedCollection(album: Album) {
  return Boolean(
    selectedCollectionId.value
    && album.collections.some((collection) => collection.id === selectedCollectionId.value),
  )
}

function albumExtraCollections(album: Album) {
  return album.collections.filter((collection) => collection.id !== selectedCollectionId.value)
}

function albumIsLocalToSelectedCollection(album: Album) {
  if (!selectedCollectionId.value) {
    return false
  }
  return album.localPaths.some((path) =>
    path.collectionId === selectedCollectionId.value
    && !path.missingSince
    && path.onDisk,
  )
}

function artistIsLocalToSelectedCollection(artist: Artist) {
  return Boolean(
    selectedCollectionId.value
    && artist.localAlbumCount > 0,
  )
}

function artistCanBeRemovedFromSelectedCollection(artist: Artist) {
  return Boolean(
    selectedCollectionIsArtist.value
    && selectedCollectionId.value
    && artist.collectionIds.includes(selectedCollectionId.value)
    && !artistIsLocalToSelectedCollection(artist),
  )
}

function artistRowClass(artist: Artist) {
  return {
    'is-selected': artist.id === selectedArtistId.value,
    'workspace-row--nonlocal-artist': !artistIsLocalToSelectedCollection(artist),
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
  const otherCollection = !inSelectedCollection && album.collections.length > 0
  const libraryOnly = albumIsLibraryOnly(album, local, otherCollection)
  return {
    'album-presence-text--local': local,
    'album-presence-text--current-collection': inSelectedCollection,
    'album-presence-text--other-collection': otherCollection,
    'album-presence-text--nonlocal-checked': libraryOnly && album.checked,
    'album-presence-text--nonlocal-unchecked': libraryOnly && !album.checked,
  }
}

function albumIsLibraryOnly(album: Album, local = albumIsLocalToSelectedCollection(album), otherCollection?: boolean) {
  const belongsToOtherCollection = otherCollection ?? (!albumIsInSelectedCollection(album) && album.collections.length > 0)
  return !local && !belongsToOtherCollection
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
  return paneWidths[pane] >= uiSettings.value.actionLabelThresholds[pane]
}

function showArtistIssueLabel() {
  return showActionLabels('artists')
}

function artistIssueColumnWidth() {
  if (!collectionArtists.value.some((artist) => artist.uncheckedAlbumCount > 0)) {
    return 0
  }
  return showArtistIssueLabel() ? artistIssueColumnWidths.labeled : artistIssueColumnWidths.compact
}

function showCollectionAddLabel() {
  return paneWidths.collections >= 260
}

function actionLabelClass(pane: keyof typeof paneWidths) {
  return actionLabelClassFor(showActionLabels(pane))
}

function gridActionLabelClass(table: keyof typeof columnWidthPreferenceKeys) {
  return actionLabelClassFor(showGridActionLabels(table))
}

function rowActionClass(pane: keyof typeof paneWidths) {
  return [actionLabelClass(pane), 'workspace-row-action']
}

function gridRowActionClass(table: keyof typeof columnWidthPreferenceKeys) {
  return [gridActionLabelClass(table), 'workspace-row-action']
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
    if (paneResizeActive) {
      return
    }
    entries.forEach((entry) => {
      const pane = (entry.target as HTMLElement).dataset.paneKey as keyof typeof paneWidths | undefined
      if (pane) {
        paneWidths[pane] = Math.round(entry.contentRect.width)
      }
    })
  })
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

function updatePaneWidthsOnLabelThresholdCrossing(isTitleLayout: boolean) {
  const entries: Array<[keyof typeof paneWidths, HTMLElement | null]> = [
    ['collections', resolveElement(collectionsPaneElement.value)],
  ]
  if (isTitleLayout) {
    entries.push(['titles', resolveElement(titlesPaneElement.value)])
  } else {
    entries.push(['artists', resolveElement(artistsPaneElement.value)])
    entries.push(['albums', resolveElement(albumsPaneElement.value)])
  }

  let crossed = false
  entries.forEach(([pane, element]) => {
    if (!element) {
      return
    }
    const width = Math.round(element.getBoundingClientRect().width)
    if (paneLabelThresholdCrossed(pane, paneWidths[pane], width)) {
      paneWidths[pane] = width
      crossed = true
    }
  })
  return crossed
}

function paneLabelThresholdCrossed(pane: keyof typeof paneWidths, previousWidth: number, nextWidth: number) {
  return paneLabelThresholds(pane).some((threshold) => thresholdCrossed(previousWidth, nextWidth, threshold))
}

function paneLabelThresholds(pane: keyof typeof paneWidths) {
  const thresholds = [uiSettings.value.actionLabelThresholds[pane]]
  if (pane === 'collections') {
    thresholds.push(260)
  }
  if (pane === 'albums') {
    thresholds.push(fixedColumnsWidth('album') + actionColumnWidths.album.labeled)
  }
  if (pane === 'titles') {
    thresholds.push(fixedColumnsWidth('title') + actionColumnWidths.title.labeled)
  }
  return thresholds
}

function thresholdCrossed(previousWidth: number, nextWidth: number, threshold: number) {
  return (previousWidth < threshold && nextWidth >= threshold)
    || (previousWidth >= threshold && nextWidth < threshold)
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

function scanProgress(collection: MusicCollection) {
  if (!uiSettings.value.collectionScanProgressEnabled || !collectionScanIsRunning.value || scanJob.value?.activeCollectionId !== collection.id) {
    return 0
  }
  if (scanJob.value.itemTotal <= 0) {
    return 0
  }
  return Math.min(100, (scanJob.value.itemProcessed / scanJob.value.itemTotal) * 100)
}

function askDeleteCollection(collection: MusicCollection) {
  collectionToDelete.value = collection
  deleteCollectionDialog.value = true
}

async function deleteCollection() {
  if (!collectionToDelete.value) {
    return
  }
  try {
    await store.deleteCollection(collectionToDelete.value.id)
    deleteCollectionDialog.value = false
    collectionToDelete.value = null
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

async function addCollection(candidate: CollectionFolderCandidate) {
  try {
    await store.createCollection(candidate.relativePath)
  } catch (error) {
    store.showErrorStatus(error, 'Unable to add collection')
  }
}

function closeAddCollectionDropdown() {
  addCollectionDropdownOpen.value = false
}

function openCollectionEdit(collection: MusicCollection, event?: MouseEvent) {
  collectionEditForm.name = collection.name
  collectionEditForm.type = collection.type
  collectionEditTarget.value = event?.currentTarget instanceof HTMLElement ? event.currentTarget : undefined
  collectionEditOpenId.value = collection.id
  void nextTick(() => {
    const input = document.querySelector<HTMLInputElement>('.collection-edit-card input')
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

async function startScan(collectionId: string) {
  try {
    await store.runScanJob(collectionId)
  } catch (error) {
    store.showErrorStatus(error, 'Unable to start collection scan')
  }
}

function collectionIsScanning(collection: MusicCollection) {
  return collectionScanIsRunning.value && scanJob.value?.activeCollectionId === collection.id
}

function selectCollection(collection: MusicCollection) {
  if (collectionIsScanning(collection)) {
    return
  }
  void store.selectCollection(collection.id)
}

function paneStyle(index: number) {
  return {
    display: 'flex',
    flex: paneFlexValue(index, panePercents.value),
    flexDirection: 'column',
    minWidth: '0',
    overflow: index === 0 ? 'visible' : 'hidden',
  } satisfies CSSProperties
}

function titlePaneStyle() {
  return {
    display: 'flex',
    flex: titlePaneFlexValue(panePercents.value),
    flexDirection: 'column',
    minWidth: '0',
    overflow: 'hidden',
  } satisfies CSSProperties
}

function paneResizerTotalWidth() {
  return selectedCollectionIsTitle.value ? paneResizerWidth : paneResizerWidth * 2
}

function paneFlexValue(index: number, percents: number[], totalResizerWidth = paneResizerTotalWidth()) {
  const percent = percents[index]
  const resizerShare = (totalResizerWidth * percent) / 100
  return `0 0 calc(${percent}% - ${resizerShare}px)`
}

function titlePaneFlexValue(percents: number[], totalResizerWidth = paneResizerTotalWidth()) {
  const titlePercent = percents[1] + percents[2]
  const resizerShare = (totalResizerWidth * titlePercent) / 100
  return `0 0 calc(${titlePercent}% - ${resizerShare}px)`
}

function applyPaneFlexStyles(percents: number[], isTitleLayout = selectedCollectionIsTitle.value) {
  const totalResizerWidth = isTitleLayout ? paneResizerWidth : paneResizerWidth * 2
  const collectionsElement = resolveElement(collectionsPaneElement.value)
  if (collectionsElement) {
    collectionsElement.style.flex = paneFlexValue(0, percents, totalResizerWidth)
  }

  if (isTitleLayout) {
    const titlesElement = resolveElement(titlesPaneElement.value)
    if (titlesElement) {
      titlesElement.style.flex = titlePaneFlexValue(percents, totalResizerWidth)
    }
    return
  }

  const artistsElement = resolveElement(artistsPaneElement.value)
  const albumsElement = resolveElement(albumsPaneElement.value)
  if (artistsElement) {
    artistsElement.style.flex = paneFlexValue(1, percents, totalResizerWidth)
  }
  if (albumsElement) {
    albumsElement.style.flex = paneFlexValue(2, percents, totalResizerWidth)
  }
}

function startPaneResize(index: number, event: PointerEvent) {
  event.preventDefault()
  if (!threePaneElement.value) {
    return
  }
  const startX = event.clientX
  const startPercents = [...panePercents.value]
  let currentPercents = [...startPercents]
  const isTitleLayout = selectedCollectionIsTitle.value
  const paneAreaWidth = Math.max(1, threePaneElement.value.clientWidth - paneResizerTotalWidth())
  const minimums = paneMinimums()
  let pendingPanePercents: number[] | null = null
  let paneResizeFrame: number | null = null

  function applyPanePercents(values: number[]) {
    currentPercents = values
    pendingPanePercents = values
    if (paneResizeFrame !== null) {
      return
    }
    paneResizeFrame = window.requestAnimationFrame(() => {
      paneResizeFrame = null
      if (!pendingPanePercents) {
        return
      }
      applyPaneFlexStyles(pendingPanePercents, isTitleLayout)
      if (updatePaneWidthsOnLabelThresholdCrossing(isTitleLayout)) {
        panePercents.value = pendingPanePercents
      }
      pendingPanePercents = null
    })
  }

  function flushPanePercents() {
    if (paneResizeFrame !== null) {
      window.cancelAnimationFrame(paneResizeFrame)
      paneResizeFrame = null
    }
    if (pendingPanePercents) {
      applyPaneFlexStyles(pendingPanePercents, isTitleLayout)
      if (updatePaneWidthsOnLabelThresholdCrossing(isTitleLayout)) {
        panePercents.value = pendingPanePercents
      }
      pendingPanePercents = null
    }
  }

  function move(pointerEvent: PointerEvent) {
    const deltaPercent = ((pointerEvent.clientX - startX) / paneAreaWidth) * 100

    if (index === 0) {
      const leftMinimum = (minimums[0] / paneAreaWidth) * 100
      if (!selectedCollectionIsTitle.value) {
        const middleMinimum = (minimums[1] / paneAreaWidth) * 100
        const rightMinimum = (minimums[2] / paneAreaWidth) * 100
        const middle = Math.max(middleMinimum, startPercents[1])
        const leftMaximum = Math.max(leftMinimum, 100 - middle - rightMinimum)
        const left = Math.min(Math.max(leftMinimum, startPercents[0] + deltaPercent), leftMaximum)
        const right = 100 - left - middle

        applyPanePercents(normalizePanePercents([
          left,
          middle,
          right,
        ]))
        return
      }

      const titleMinimum = (titlePaneMinimumWidth() / paneAreaWidth) * 100
      const leftMaximum = Math.max(leftMinimum, 100 - titleMinimum)
      const left = Math.min(Math.max(leftMinimum, startPercents[0] + deltaPercent), leftMaximum)
      applyPanePercents(paneLayoutForTitleResize(left, startPercents))
      return
    }

    const combined = startPercents[index] + startPercents[index + 1]
    const leftMinimum = (minimums[index] / paneAreaWidth) * 100
    const rightMinimum = (minimums[index + 1] / paneAreaWidth) * 100
    const leftMaximum = Math.max(leftMinimum, combined - rightMinimum)
    const left = Math.min(Math.max(leftMinimum, startPercents[index] + deltaPercent), leftMaximum)
    const right = combined - left

    applyPanePercents(normalizePanePercents(
      startPercents.map((percent, percentIndex) => {
        if (percentIndex === index) return left
        if (percentIndex === index + 1) return right
        return percent
      }),
    ))
  }

  function stop() {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', stop)
    window.removeEventListener('pointercancel', stop)
    paneResizeActive = false
    document.body.classList.remove('is-pane-resizing')
    flushPanePercents()
    panePercents.value = currentPercents
    savePaneLayout()
    void nextTick(setupPaneWidthObserver)
  }

  paneResizeActive = true
  paneWidthObserver?.disconnect()
  document.body.classList.add('is-pane-resizing')
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', stop)
  window.addEventListener('pointercancel', stop)
}

function paneMinimums() {
  return [
    paneHeaderMinimumWidths.collections,
    paneHeaderMinimumWidths.artists,
    selectedCollectionIsTitle.value ? titlePaneMinimumWidth() : Math.max(paneHeaderMinimumWidths.albums, minimumGridWidth('album')),
  ]
}

function titlePaneMinimumWidth() {
  return Math.max(paneHeaderMinimumWidths.titles, minimumGridWidth('title'))
}

async function loadPaneLayouts() {
  await Promise.all([
    loadPaneLayout('artist'),
    loadPaneLayout('title'),
  ])
  activatePaneLayout(activePaneLayoutKind())
}

async function loadPaneLayout(kind: PaneLayoutKind) {
  const preference = await store.loadPreference(paneLayoutPreferenceKeys[kind])
  if (!preference?.value) {
    return
  }
  try {
    const parsed = JSON.parse(preference.value)
    if (isPaneLayoutObject(parsed)) {
      paneLayoutCache[kind] = repairPaneLayout([
        parsed.collections,
        parsed.artists,
        parsed.albums,
      ])
    } else if (Array.isArray(parsed) && parsed.length === 3 && parsed.every((value) => typeof value === 'number')) {
      paneLayoutCache[kind] = repairPaneLayout(parsed)
    }
  } catch (error) {
    // Ignore invalid stored UI state and keep the default layout.
  }
}

function activePaneLayoutKind(): PaneLayoutKind {
  return selectedCollectionIsTitle.value ? 'title' : 'artist'
}

function activatePaneLayout(kind: PaneLayoutKind) {
  panePercents.value = [...paneLayoutCache[kind]]
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
  const kind = activePaneLayoutKind()
  const rounded = repairPaneLayout(normalizePanePercents(panePercents.value))
    .map((value) => Math.round(value * 100) / 100)
  paneLayoutCache[kind] = rounded
  panePercents.value = rounded
  void store.savePreference(paneLayoutPreferenceKeys[kind], JSON.stringify(paneLayoutObject(rounded))).catch((error) => {
    store.showErrorStatus(error, 'Unable to save pane layout')
  })
}

function normalizePanePercents(values: number[]) {
  const cleaned = values.map((value) => (Number.isFinite(value) && value > 0 ? value : 0))
  const total = cleaned.reduce((sum, value) => sum + value, 0)
  if (total <= 0) {
    return [...defaultPanePercents]
  }
  const normalized = cleaned.map((value) => (value / total) * 100)
  const rounded = normalized.map((value) => Math.round(value * 10000) / 10000)
  rounded[2] = Math.round((100 - rounded[0] - rounded[1]) * 10000) / 10000
  return rounded
}

function paneLayoutForTitleResize(collectionsPercent: number, sourcePercents: number[]) {
  const right = Math.max(0, 100 - collectionsPercent)
  const artistShare = hiddenPaneArtistShare(sourcePercents)
  return normalizePanePercents([
    collectionsPercent,
    right * artistShare,
    right * (1 - artistShare),
  ])
}

function hiddenPaneArtistShare(sourcePercents: number[]) {
  const right = sourcePercents[1] + sourcePercents[2]
  const defaultRight = defaultPanePercents[1] + defaultPanePercents[2]
  const defaultShare = defaultPanePercents[1] / defaultRight
  if (
    right <= 0
    || sourcePercents[1] <= minimumRestoredPanePercent
    || sourcePercents[2] <= minimumRestoredPanePercent
  ) {
    return defaultShare
  }
  return sourcePercents[1] / right
}

function repairPaneLayout(values: number[]) {
  const normalized = normalizePanePercents(values)
  if (normalized[1] > minimumRestoredPanePercent) {
    return normalized
  }
  return paneLayoutForTitleResize(normalized[0], defaultPanePercents)
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

async function loadPresenceFilters() {
  await Promise.all([
    loadPresencePreference('artist', artistPresence),
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

function savePresenceFilter(scope: keyof typeof presencePreferenceKeys, value: PresenceFilter[]) {
  if (!presencePreferencesLoaded) {
    return
  }
  const normalized = normalizePresenceFilter(value) ?? []
  void store.savePreference(presencePreferenceKeys[scope], JSON.stringify(normalized)).catch((error) => {
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
  if (table === 'artist') return artistColumnWidths as Record<string, number>
  if (table === 'album') return albumColumnWidths as Record<string, number>
  return titleColumnWidths as Record<string, number>
}

function columnGridStyle(table: keyof typeof columnWidthPreferenceKeys) {
  if (table === 'artist') {
    const actionWidth = showActionLabels('artists') ? actionColumnWidths.artist.labeled : actionColumnWidths.artist.icon
    return {
      '--workspace-grid-columns': `minmax(0, 1fr) ${actionWidth + artistIssueColumnWidth()}px`,
      '--workspace-grid-min-width': '100%',
    }
  }
  const rendered = renderedColumnWidths(table)
  const keys = tableColumnKeys(table)
  const columns = keys
    .map((key, index) => (index === keys.length - 1 ? `minmax(${columnMinimumWidth(table, key)}px, 1fr)` : `${rendered[key]}px`))
    .join(' ')
  return {
    '--workspace-grid-columns': columns,
    '--workspace-grid-min-width': `${minimumGridWidth(table)}px`,
  }
}

function tableColumnKeys(table: keyof typeof columnWidthPreferenceKeys) {
  if (table === 'album' && !showAlbumCollectionsColumn.value) {
    return tableColumnOrders.album.filter((key) => key !== 'collections') as readonly string[]
  }
  return tableColumnOrders[table] as readonly string[]
}

function showGridActionLabels(table: keyof typeof columnWidthPreferenceKeys) {
  if (table === 'artist') {
    return showActionLabels('artists')
  }
  return showActionLabels(tablePaneKey(table)) && rightmostColumnAvailableWidth(table) >= actionColumnWidths[table].labeled
}

function renderedColumnWidths(table: keyof typeof columnWidthPreferenceKeys) {
  const widths = columnWidthState(table)
  const keys = tableColumnKeys(table)
  return Object.fromEntries(
    keys.map((key) => [key, renderedColumnWidth(table, key, widths)]),
  ) as Record<string, number>
}

function renderedColumnWidth(
  table: keyof typeof columnWidthPreferenceKeys,
  key: string,
  widths: Record<string, number>,
) {
  return Math.max(columnMinimumWidth(table, key), widths[key] ?? columnMinimumWidth(table, key))
}

function tablePaneKey(table: keyof typeof columnWidthPreferenceKeys): keyof typeof paneWidths {
  if (table === 'album') return 'albums'
  if (table === 'title') return 'titles'
  return 'artists'
}

function tableAvailableWidth(table: keyof typeof columnWidthPreferenceKeys) {
  const grid = document.querySelector(`.${tablePaneKey(table)}-pane .workspace-grid`)
  if (grid instanceof HTMLElement) {
    return grid.clientWidth
  }
  const pane = tablePaneKey(table)
  const paneWidth = paneWidths[pane]
  return paneWidth > 0 ? Math.max(0, paneWidth - 2) : 0
}

function fixedColumnsWidth(table: keyof typeof columnWidthPreferenceKeys) {
  const rendered = renderedColumnWidths(table)
  return tableColumnKeys(table)
    .slice(0, -1)
    .reduce((sum, key) => sum + rendered[key], 0)
}

function rightmostColumnAvailableWidth(table: keyof typeof columnWidthPreferenceKeys) {
  const lastKey = rightmostColumnKey(table)
  const available = tableAvailableWidth(table)
  if (available <= 0) {
    return renderedColumnWidth(table, lastKey, columnWidthState(table))
  }
  return Math.max(columnMinimumWidth(table, lastKey), available - fixedColumnsWidth(table))
}

function minimumGridWidth(table: keyof typeof columnWidthPreferenceKeys) {
  const lastKey = rightmostColumnKey(table)
  return fixedColumnsWidth(table) + columnMinimumWidth(table, lastKey)
}

function rightmostColumnKey(table: keyof typeof columnWidthPreferenceKeys) {
  const keys = tableColumnKeys(table)
  return keys[keys.length - 1]
}

function startColumnResize(table: keyof typeof columnWidthPreferenceKeys, key: string, event: PointerEvent) {
  event.preventDefault()
  event.stopPropagation()
  suppressHeaderSortClick(event)
  const keys = tableColumnKeys(table)
  const leftIndex = keys.indexOf(key)
  if (leftIndex < 0 || leftIndex >= keys.length - 1) {
    return
  }
  const widths = columnWidthState(table)
  const rendered = renderedColumnWidths(table)
  const leftStart = rendered[key]
  const startX = event.clientX
  document.body.classList.add('is-column-resizing')

  const beforeWidth = keys
    .slice(0, leftIndex)
    .reduce((sum, columnKey) => sum + rendered[columnKey], 0)
  const rightDataBlockWidth = keys
    .slice(leftIndex + 1, -1)
    .reduce((sum, columnKey) => sum + rendered[columnKey], 0)
  const leftMinimum = columnMinimumWidth(table, key)
  const rightmostKey = keys[keys.length - 1]
  const rightmostMinimum = columnMinimumWidth(table, rightmostKey)
  const available = tableAvailableWidth(table)
  const leftMaximum = available > 0
    ? Math.max(leftMinimum, available - beforeWidth - rightDataBlockWidth - rightmostMinimum)
    : Number.POSITIVE_INFINITY

  function move(pointerEvent: PointerEvent) {
    const left = Math.min(
      Math.max(leftMinimum, Math.round(leftStart + pointerEvent.clientX - startX)),
      leftMaximum,
    )
    widths[key] = left
  }

  function stop() {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', stop)
    window.removeEventListener('pointercancel', stop)
    document.body.classList.remove('is-column-resizing')
    suppressHeaderSortClick()
    saveColumnWidth(table, key)
  }

  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', stop)
  window.addEventListener('pointercancel', stop)
}

function saveColumnWidth(table: keyof typeof columnWidthPreferenceKeys, key: string) {
  const widths = columnWidthState(table)
  const preferenceKey = columnWidthPreferenceKeys[table][key as keyof typeof columnWidthPreferenceKeys[typeof table]]
  if (!preferenceKey) {
    return
  }
  void store.savePreference(
    preferenceKey,
    String(Math.round(widths[key] ?? columnMinimumWidth(table, key))),
  ).catch((error) => {
    store.showErrorStatus(error, 'Unable to save column width')
  })
}

function columnMinimumWidth(table: keyof typeof columnWidthPreferenceKeys, key: string) {
  if (key === 'action') {
    return actionColumnWidths[table].icon
  }
  if (table === 'album' && key === 'collections') {
    return 90
  }
  return Math.max(1, uiSettings.value.tableGridColumnMinWidth)
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
  artistDialog.value = true
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
      if (!artistIsLocalToSelectedCollection(artist)) {
        includeNonLocal(artistPresence)
      }
      await store.selectArtist(artist.id)
    }
  } catch (error) {
    store.showErrorStatus(error, artistForm.id === null ? 'Unable to add artist' : 'Unable to save artist')
  } finally {
    savingArtist.value = false
  }
}

async function removeArtistFromCollection(artist: Artist) {
  try {
    await store.removeArtistFromSelectedCollection(artist.id)
  } catch (error) {
    store.showErrorStatus(error, 'Unable to remove artist from collection')
  }
}

async function refreshArtist(artist: Artist) {
  refreshingArtistId.value = artist.id
  try {
    await store.checkArtistProvider(artist.id)
  } catch (error) {
    if (!store.providerStatus.message?.startsWith('Provider check failed')) {
      store.showErrorStatus(error, 'Provider check failed')
    }
  } finally {
    refreshingArtistId.value = null
  }
}

function localAlbumScanIsRunningForArtist(artist: Artist) {
  return localAlbumScanIsRunning.value && scanJob.value?.requestedArtistId === artist.id
}

function localAlbumScanIsRunningForCollection() {
  return localAlbumScanIsRunning.value
    && scanJob.value?.requestedCollectionId === selectedCollectionId.value
    && scanJob.value?.requestedArtistId == null
}

function providerScanIsRunningForArtist(artist: Artist) {
  if (!providerIsRunning.value) {
    return false
  }
  if (refreshingArtistId.value !== null) {
    return refreshingArtistId.value === artist.id
  }
  return selectedCollectionIsArtist.value
    && selectedCollectionId.value !== null
    && artist.providerLinkCount > 0
}

function artistScanIsRunning(artist: Artist) {
  if (!uiSettings.value.artistScanSpinnerEnabled) {
    return false
  }
  return localAlbumScanIsRunningForArtist(artist)
    || localAlbumScanIsRunningForCollection()
    || providerScanIsRunningForArtist(artist)
}

async function scanLocalAlbumsForArtist(artist: Artist) {
  if (!selectedCollectionId.value) {
    return
  }
  try {
    await store.runLocalAlbumScanJob(selectedCollectionId.value, artist.id)
  } catch (error) {
    store.showErrorStatus(error, 'Unable to start local album scan')
  }
}

async function scanLocalAlbumsForCollection() {
  if (!selectedCollectionId.value) {
    return
  }
  try {
    await store.runLocalAlbumScanJob(selectedCollectionId.value)
  } catch (error) {
    store.showErrorStatus(error, 'Unable to start local album scan')
  }
}

async function refreshCollectionProviders() {
  if (!selectedCollectionId.value) {
    return
  }
  try {
    await store.checkCollectionProviders(selectedCollectionId.value)
  } catch (error) {
    if (!store.providerStatus.message?.startsWith('Provider check failed')) {
      store.showErrorStatus(error, 'Provider check failed')
    }
  }
}

async function updateAlbumChecked(album: Album, checked: boolean) {
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

function albumHasMissingLocalPath(album: Album) {
  return album.localPaths.some((path) => path.missingSince)
}

function openAlbumEditDialog(album: Album) {
  albumToEdit.value = album
  albumEditForm.title = album.title
  albumEditDialog.value = true
  void nextTick(() => {
    const input = document.querySelector<HTMLInputElement>('.album-edit-dialog input')
    input?.select()
  })
}

async function saveAlbumTitle() {
  if (!albumToEdit.value || !albumEditForm.title.trim()) {
    return
  }
  albumEditSaving.value = true
  try {
    await store.updateAlbum({
      ...albumToEdit.value,
      title: albumEditForm.title.trim(),
    })
    albumEditDialog.value = false
    albumToEdit.value = null
  } catch (error) {
    store.showErrorStatus(error, 'Unable to save album title')
  } finally {
    albumEditSaving.value = false
  }
}

async function untrackMissingAlbumLocalPaths(album: Album) {
  if (!selectedCollectionId.value) {
    return
  }
  try {
    await store.untrackMissingAlbumLocalPaths(album.id, selectedCollectionId.value)
  } catch (error) {
    store.showErrorStatus(error, 'Unable to untrack missing album path')
  }
}

function askDeleteAlbum(album: Album) {
  albumToDelete.value = album
  deleteDialog.value = true
}

async function deleteAlbum() {
  if (!albumToDelete.value) {
    return
  }
  try {
    await store.deleteAlbum(albumToDelete.value.id)
    deleteDialog.value = false
    albumToDelete.value = null
  } catch (error) {
    store.showErrorStatus(error, 'Unable to delete album')
  }
}

function openTitleItemDialog(item?: Album) {
  titleItemToEdit.value = item ?? null
  titleItemForm.title = item?.title ?? ''
  titleItemForm.artistName = item?.artistName ?? ''
  titleItemForm.releaseDate = item?.releaseDate ?? ''
  titleItemForm.sortName = item?.sortName ?? ''
  titleItemDialog.value = true
}

async function saveTitleItem() {
  if (!titleItemForm.title.trim() || !selectedCollectionId.value) {
    return
  }
  titleItemSaving.value = true
  try {
    const payload = {
      title: titleItemForm.title.trim(),
      artistName: titleItemForm.artistName.trim() || null,
      releaseDate: titleItemForm.releaseDate.trim() || null,
      sortName: titleItemForm.sortName.trim() || null,
    }
    if (titleItemToEdit.value) {
      await store.updateTitleItem(titleItemToEdit.value, payload)
    } else {
      await store.createTitleItem(selectedCollectionId.value, payload)
      includeNonLocal(titlePresence)
    }
    titleItemDialog.value = false
    titleItemToEdit.value = null
  } catch (error) {
    store.showErrorStatus(error, titleItemToEdit.value ? 'Unable to save title' : 'Unable to add title')
  } finally {
    titleItemSaving.value = false
  }
}

async function deleteTitleLocalPath(item: Album) {
  try {
    await store.deleteTitleLocalPath(item)
  } catch (error) {
    store.showErrorStatus(error, 'Unable to delete title local path')
  }
}

onMounted(async () => {
  document.addEventListener('pointerdown', handleDocumentPointerDown)
  document.addEventListener('keydown', handleDocumentKeyDown)
  await store.loadUiSettings()
  applyColumnWidthDefaults()
  await loadColumnWidths()
  await loadPresenceFilters()
  await loadPaneLayouts()
  await store.loadCollections()
  activatePaneLayout(activePaneLayoutKind())
  await store.loadScanJob()
  if (scanIsRunning.value) {
    store.startScanJobPolling()
  }
  await nextTick()
  setupPaneWidthObserver()
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', handleDocumentPointerDown)
  document.removeEventListener('keydown', handleDocumentKeyDown)
  paneResizeActive = false
  document.body.classList.remove('is-pane-resizing')
  paneWidthObserver?.disconnect()
  paneWidthObserver = null
  if (paneLayoutSaveTimer.value !== null) {
    savePaneLayout()
  }
})

watch(selectedCollectionIsTitle, () => {
  activatePaneLayout(activePaneLayoutKind())
  resetArtistGridScroll()
  resetAlbumGridScroll()
  resetTitleGridScroll()
  void nextTick(setupPaneWidthObserver)
})

watch(selectedCollectionId, () => {
  resetArtistGridScroll()
  resetAlbumGridScroll()
  resetTitleGridScroll()
})

watch(selectedArtistId, () => {
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

watch([() => artistSort.key, () => artistSort.direction], () => {
  resetArtistGridScroll()
})

watch([() => albumSort.key, () => albumSort.direction, showAlbumCollectionsColumn], () => {
  resetAlbumGridScroll()
})

watch(titlePresence, (value) => {
  savePresenceFilter('title', value)
}, { deep: true })
</script>

<template>
  <v-container fluid class="app-page collections-workspace">
    <v-alert v-if="error" type="error" variant="tonal" class="mb-3">{{ error }}</v-alert>

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
            <span class="nav-row__info" @click.stop>
              <v-tooltip location="end" :open-on-hover="true" @update:model-value="(open) => loadCollectionInfo(collection, open)">
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
                    v-if="collectionMetadataLoading[collection.id] && !collectionMetadata[collection.id]"
                    class="collection-info-tooltip__loading"
                  >
                    <v-progress-circular indeterminate size="14" width="2"></v-progress-circular>
                    <span>Loading info</span>
                  </div>
                  <template v-else-if="collectionMetadata[collection.id]">
                    <div v-for="line in collectionInfoLines(collection)" :key="line">{{ line }}</div>
                  </template>
                  <div v-else>Info not loaded</div>
                </div>
              </v-tooltip>
            </span>
            <span class="nav-row__actions">
              <v-tooltip text="Edit collection" location="top">
                <template #activator="{ props }">
                  <v-btn
                    v-bind="props"
                    prepend-icon="mdi-pencil"
                    size="x-small"
                    variant="text"
                    color="primary"
                    :class="rowActionClass('collections')"
                    :disabled="collectionIsScanning(collection)"
                    @click.stop="openCollectionEdit(collection, $event)"
                  >
                    <span v-if="showActionLabels('collections')">Edit</span>
                  </v-btn>
                </template>
              </v-tooltip>
              <v-tooltip text="Scan collection" location="top">
                <template #activator="{ props }">
                  <v-btn
                    v-bind="props"
                    prepend-icon="mdi-refresh"
                    size="x-small"
                    variant="text"
                    color="primary"
                    :class="rowActionClass('collections')"
                    :disabled="scanIsRunning"
                    @click.stop="startScan(collection.id)"
                  >
                    <span v-if="showActionLabels('collections')">Scan</span>
                  </v-btn>
                </template>
              </v-tooltip>
              <v-tooltip text="Delete collection" location="top">
                <template #activator="{ props }">
                  <v-btn
                    v-bind="props"
                    prepend-icon="mdi-trash-can-outline"
                    size="x-small"
                    variant="text"
                    color="error"
                    :class="rowActionClass('collections')"
                    :disabled="collectionIsScanning(collection)"
                    @click.stop="askDeleteCollection(collection)"
                  >
                    <span v-if="showActionLabels('collections')">Delete</span>
                  </v-btn>
                </template>
              </v-tooltip>
            </span>
          </div>
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
                  hide-details="auto"
                  @keydown.enter.stop="saveOpenCollectionEdit"
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
              :disabled="!collectionEditForm.name.trim()"
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
            <div class="pane-header__actions">
              <v-tooltip text="Add title" location="top">
                <template #activator="{ props }">
                  <v-btn
                    v-bind="props"
                    prepend-icon="mdi-plus"
                    size="small"
                    variant="text"
                    color="primary"
                    :class="[actionLabelClass('titles'), 'app-toolbar-button']"
                    :disabled="!selectedCollectionId"
                    @click="openTitleItemDialog()"
                  >
                    <span v-if="showActionLabels('titles')">Add</span>
                  </v-btn>
                </template>
              </v-tooltip>
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
              <v-btn value="local" size="small" title="Local" prepend-icon="mdi-folder-outline">
                Local
              </v-btn>
              <v-btn value="nonLocal" size="small" title="Non-local" prepend-icon="mdi-cloud-outline">
                Non-local
              </v-btn>
            </v-btn-toggle>
          </div>

          <div v-if="!selectedCollection" class="pane-empty">Select a collection.</div>
          <div v-else-if="selectedCollectionTitlesLoading" class="pane-loading">
            <v-progress-circular indeterminate size="60" width="5"></v-progress-circular>
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
                data-column="title.releaseDate"
                @click="handleTitleHeaderClick('releaseDate', $event)"
              >
                <span class="sortable-header__label">Release date</span>
                <v-icon
                  v-if="titleSort.key === 'releaseDate'"
                  :icon="sortIcon(titleSort.direction)"
                  size="14"
                  class="sort-direction-icon"
                ></v-icon>
                  <span
                    class="column-resize-handle"
                    @pointerdown="startColumnResize('title', 'releaseDate', $event)"
                    @click="suppressHeaderSortClick($event)"
                  ></span>
              </div>
            </div>
            <div
              v-if="titleVirtualTopSpacerHeight > 0"
              class="workspace-grid__virtual-spacer"
              :style="{ height: `${titleVirtualTopSpacerHeight}px` }"
              aria-hidden="true"
            ></div>
            <div v-for="item in visibleTitleRows" :key="item.id" class="workspace-grid__row workspace-row">
                <div data-column="title.title" class="workspace-grid__cell truncate-cell">
                  <span :class="albumPresenceClass(item)">{{ item.title }}</span>
                </div>
                <div data-column="title.artist" class="workspace-grid__cell truncate-cell">
                  <span>{{ item.artistName ?? '' }}</span>
                </div>
                <div data-column="title.releaseDate" class="workspace-grid__cell cell-muted">
                  <v-tooltip v-if="releaseDateTooltip(item.releaseDate)" :text="releaseDateTooltip(item.releaseDate)" location="top">
                    <template #activator="{ props }">
                      <span v-bind="props">{{ releaseDateYearLabel(item.releaseDate) }}</span>
                    </template>
                  </v-tooltip>
                  <span v-else>{{ releaseDateYearLabel(item.releaseDate) }}</span>
                </div>
                <div class="workspace-grid__cell row-action-cell">
                  <div class="row-actions">
                    <v-tooltip text="Edit title metadata" location="top">
                      <template #activator="{ props }">
                      <v-btn
                        v-bind="props"
                          prepend-icon="mdi-pencil"
                          size="x-small"
                          variant="text"
                          color="primary"
                          :class="gridRowActionClass('title')"
                          @click.stop="openTitleItemDialog(item)"
                        >
                          <span v-if="showGridActionLabels('title')">Edit</span>
                        </v-btn>
                      </template>
                    </v-tooltip>
                    <v-tooltip text="Remove local title path" location="top">
                      <template #activator="{ props }">
                        <v-btn
                          v-bind="props"
                          prepend-icon="mdi-trash-can-outline"
                          size="x-small"
                          variant="text"
                          color="error"
                          :class="gridRowActionClass('title')"
                          :disabled="!item.hasLocalPath"
                          @click.stop="deleteTitleLocalPath(item)"
                        >
                          <span v-if="showGridActionLabels('title')">Delete</span>
                        </v-btn>
                      </template>
                    </v-tooltip>
                  </div>
                </div>
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
            <v-tooltip text="Scan local albums" location="top">
              <template #activator="{ props }">
                <v-btn
                  v-bind="props"
                  size="small"
                  variant="text"
                  color="primary"
                  prepend-icon="mdi-folder-sync-outline"
                  :class="[actionLabelClass('artists'), 'app-toolbar-button']"
                  :loading="localAlbumScanIsRunningForCollection()"
                  :disabled="!selectedCollectionIsArtist || collectionArtists.length === 0 || scanIsRunning || providerIsRunning"
                  @click="scanLocalAlbumsForCollection"
                >
                  <span v-if="showActionLabels('artists')">Local</span>
                </v-btn>
              </template>
            </v-tooltip>
            <v-tooltip text="Scan providers" location="top">
              <template #activator="{ props }">
                <v-btn
                  v-bind="props"
                  size="small"
                  variant="text"
                  color="primary"
                  prepend-icon="mdi-cloud-sync-outline"
                  :class="[actionLabelClass('artists'), 'app-toolbar-button']"
                  :loading="providerIsRunning && refreshingArtistId === null"
                  :disabled="!selectedCollectionIsArtist || collectionArtists.length === 0 || scanIsRunning || providerIsRunning"
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
            <v-tooltip text="Add artist" location="top">
              <template #activator="{ props }">
                <v-btn
                  v-bind="props"
                  prepend-icon="mdi-account-plus"
                  size="small"
                  variant="text"
                  color="primary"
                  :class="[actionLabelClass('artists'), 'app-toolbar-button']"
                  :disabled="!selectedCollectionId"
                  @click="openArtistDialog()"
                >
                  <span v-if="showActionLabels('artists')">Add</span>
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
            <v-btn value="local" size="small" title="Local" prepend-icon="mdi-folder-outline">
              Local
            </v-btn>
            <v-btn value="nonLocal" size="small" title="Non-local" prepend-icon="mdi-cloud-outline">
              Non-local
            </v-btn>
          </v-btn-toggle>
        </div>

        <div v-if="!selectedCollection" class="pane-empty">Select a collection.</div>
        <div v-else-if="selectedCollectionArtistsLoading" class="pane-loading">
          <v-progress-circular indeterminate size="60" width="5"></v-progress-circular>
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
            class="workspace-grid__row workspace-row"
            :class="artistRowClass(artist)"
            @click="store.selectArtist(artist.id)"
          >
              <div data-column="artist.name" class="workspace-grid__cell">
                <div class="artist-cell">
                  <v-progress-circular
                    v-if="artistScanIsRunning(artist)"
                    indeterminate
                    size="14"
                    width="2"
                    class="artist-cell__spinner"
                  ></v-progress-circular>
                  <span class="cell-strong">{{ artist.name }}</span>
                </div>
              </div>
              <div class="workspace-grid__cell row-action-cell artist-row-action-cell">
                <v-tooltip v-if="artistIssueLabel(artist)" :text="artistIssueLabel(artist)" location="top">
                  <template #activator="{ props }">
                    <v-chip
                      v-bind="props"
                      :aria-label="artistIssueLabel(artist)"
                      class="artist-issue-chip"
                      :class="{ 'artist-issue-chip--compact': !showArtistIssueLabel() }"
                      color="warning"
                      size="x-small"
                      variant="tonal"
                    >
                      <span class="artist-issue-chip__count">{{ artist.uncheckedAlbumCount }}</span>
                      <span v-if="showArtistIssueLabel()" class="artist-issue-chip__label">unchecked</span>
                    </v-chip>
                  </template>
                </v-tooltip>
                <div class="row-actions">
                  <v-tooltip text="Scan local albums" location="top">
                    <template #activator="{ props }">
                      <v-btn
                        v-bind="props"
                        prepend-icon="mdi-folder-sync-outline"
                        size="x-small"
                        variant="text"
                        color="primary"
                        :class="rowActionClass('artists')"
                        :loading="localAlbumScanIsRunningForArtist(artist)"
                        :disabled="scanIsRunning || providerIsRunning"
                        @click.stop="scanLocalAlbumsForArtist(artist)"
                      >
                        <span v-if="showActionLabels('artists')">Local</span>
                      </v-btn>
                    </template>
                  </v-tooltip>
                  <v-tooltip text="Scan providers" location="top">
                    <template #activator="{ props }">
                      <v-btn
                        v-bind="props"
                        prepend-icon="mdi-cloud-sync-outline"
                        size="x-small"
                        variant="text"
                        color="primary"
                        :class="rowActionClass('artists')"
                        :loading="refreshingArtistId === artist.id"
                        :disabled="scanIsRunning || providerIsRunning"
                        @click.stop="refreshArtist(artist)"
                      >
                        <span v-if="showActionLabels('artists')">Provider</span>
                      </v-btn>
                    </template>
                  </v-tooltip>
                  <v-tooltip text="Edit artist" location="top">
                    <template #activator="{ props }">
                      <v-btn
                        v-bind="props"
                        prepend-icon="mdi-pencil"
                        size="x-small"
                        variant="text"
                        color="primary"
                        :class="rowActionClass('artists')"
                        :disabled="scanIsRunning || providerIsRunning"
                        @click.stop="openArtistDialog(artist)"
                      >
                        <span v-if="showActionLabels('artists')">Edit</span>
                      </v-btn>
                    </template>
                  </v-tooltip>
                  <v-tooltip
                    v-if="artistCanBeRemovedFromSelectedCollection(artist)"
                    text="Remove artist from this collection"
                    location="top"
                  >
                    <template #activator="{ props }">
                      <v-btn
                        v-bind="props"
                        prepend-icon="mdi-account-remove-outline"
                        size="x-small"
                        variant="text"
                        color="error"
                        :class="rowActionClass('artists')"
                        :disabled="scanIsRunning || providerIsRunning"
                        @click.stop="removeArtistFromCollection(artist)"
                      >
                        <span v-if="showActionLabels('artists')">Remove</span>
                      </v-btn>
                    </template>
                  </v-tooltip>
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

        <div v-if="!selectedArtist" class="pane-empty">Select an artist.</div>
        <div v-else-if="selectedArtistAlbumsLoading" class="pane-loading">
          <v-progress-circular indeterminate size="60" width="5"></v-progress-circular>
        </div>
        <div v-else-if="sortedCollectionAlbums.length === 0" class="pane-empty pane-empty--action">
          <span>No albums loaded for this artist.</span>
          <div class="pane-empty__actions">
            <v-btn
              color="primary"
              variant="tonal"
              prepend-icon="mdi-folder-sync-outline"
              :loading="localAlbumScanIsRunningForArtist(selectedArtist)"
              :disabled="scanIsRunning || providerIsRunning"
              @click="scanLocalAlbumsForArtist(selectedArtist)"
            >
              Scan local albums
            </v-btn>
            <v-btn
              color="primary"
              variant="tonal"
              prepend-icon="mdi-cloud-sync-outline"
              :loading="refreshingArtistId === selectedArtist.id"
              :disabled="scanIsRunning || providerIsRunning"
              @click="refreshArtist(selectedArtist)"
            >
              Scan providers
            </v-btn>
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
              data-column="album.releaseDate"
              @click="handleAlbumHeaderClick('releaseDate', $event)"
            >
              <span class="sortable-header__label">Release date</span>
              <v-icon
                v-if="albumSort.key === 'releaseDate'"
                :icon="sortIcon(albumSort.direction)"
                size="14"
                class="sort-direction-icon"
              ></v-icon>
                <span
                  class="column-resize-handle"
                  @pointerdown="startColumnResize('album', 'releaseDate', $event)"
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
              data-column="album.collections"
            >
              <span class="sortable-header__label">Also in</span>
                <span
                  class="column-resize-handle"
                  @pointerdown="startColumnResize('album', 'collections', $event)"
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
          <div v-for="album in visibleAlbumRows" :key="album.id" class="workspace-grid__row workspace-row">
              <div data-column="album.name" class="workspace-grid__cell truncate-cell">
                <div class="album-cell">
                  <span :class="albumPresenceClass(album)">{{ album.title }}</span>
                </div>
              </div>
              <div data-column="album.releaseDate" class="workspace-grid__cell cell-muted">
                <v-tooltip v-if="releaseDateTooltip(album.releaseDate)" :text="releaseDateTooltip(album.releaseDate)" location="top">
                  <template #activator="{ props }">
                    <span v-bind="props">{{ releaseDateYearLabel(album.releaseDate) }}</span>
                  </template>
                </v-tooltip>
                <span v-else>{{ releaseDateYearLabel(album.releaseDate) }}</span>
              </div>
              <div data-column="album.checked" class="workspace-grid__cell checkbox-cell">
                <v-tooltip
                  v-if="albumCheckedToggleDisabled(album)"
                  text="Present on disk; can't uncheck"
                  location="top"
                >
                  <template #activator="{ props }">
                    <span v-bind="props" class="checkbox-cell__tooltip-anchor" @click.stop>
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
                  hide-details
                  @click.stop
                  @update:model-value="(value) => updateAlbumChecked(album, Boolean(value))"
                ></v-checkbox>
              </div>
              <div
                v-if="showAlbumCollectionsColumn"
                data-column="album.collections"
                class="workspace-grid__cell album-collections-cell"
              >
                <div v-if="albumExtraCollections(album).length" class="album-collection-chips">
                  <v-chip
                    v-for="collection in albumExtraCollections(album)"
                    :key="collection.id"
                    size="x-small"
                    variant="tonal"
                    color="primary"
                  >
                    {{ collection.name }}
                  </v-chip>
                </div>
              </div>
              <div class="workspace-grid__cell row-action-cell">
                <div class="row-actions">
                  <v-tooltip v-if="album.localPaths.length" :text="albumDiskTitle(album)" location="top">
                    <template #activator="{ props }">
                      <v-btn
                        v-bind="props"
                        icon="mdi-information-outline"
                        size="x-small"
                        variant="text"
                        class="album-info-button"
                        @click.stop
                      ></v-btn>
                    </template>
                  </v-tooltip>
                  <v-tooltip text="Edit album title" location="top">
                    <template #activator="{ props }">
                      <v-btn
                        v-bind="props"
                        prepend-icon="mdi-pencil-outline"
                        size="x-small"
                        variant="text"
                        color="primary"
                        :class="gridRowActionClass('album')"
                        @click.stop="openAlbumEditDialog(album)"
                      >
                        <span v-if="showGridActionLabels('album')">Edit</span>
                      </v-btn>
                    </template>
                  </v-tooltip>
                  <v-tooltip v-if="albumHasMissingLocalPath(album)" text="Forget missing local folder" location="top">
                    <template #activator="{ props }">
                      <v-btn
                        v-bind="props"
                        prepend-icon="mdi-folder-remove-outline"
                        size="x-small"
                        variant="text"
                        color="warning"
                        :class="gridRowActionClass('album')"
                        @click.stop="untrackMissingAlbumLocalPaths(album)"
                      >
                        <span v-if="showGridActionLabels('album')">Untrack</span>
                      </v-btn>
                    </template>
                  </v-tooltip>
                  <v-tooltip text="Delete album" location="top">
                    <template #activator="{ props }">
                      <v-btn
                        v-bind="props"
                        prepend-icon="mdi-trash-can-outline"
                        size="x-small"
                        variant="text"
                        color="error"
                        :class="gridRowActionClass('album')"
                        @click.stop="askDeleteAlbum(album)"
                      >
                        <span v-if="showGridActionLabels('album')">Delete</span>
                      </v-btn>
                    </template>
                  </v-tooltip>
                </div>
              </div>
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

    <v-dialog v-model="artistDialog" max-width="860">
      <v-card class="dialog-card">
        <v-card-title>{{ artistForm.id ? 'Artist Details' : 'Add Artist' }}</v-card-title>
        <v-card-text class="edit-form">
          <v-row dense class="edit-form__grid">
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
                :menu-props="{ maxHeight: 184 }"
                class="artist-collection-select"
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
        <v-card-title>{{ titleItemToEdit ? 'Title Metadata' : 'Add Title' }}</v-card-title>
        <v-card-text class="edit-form">
          <div v-if="titleItemToEdit?.localPaths.length" class="cell-muted edit-form__meta">
            {{ titleItemToEdit.localPaths.find((path) => !path.missingSince)?.relativePath ?? titleItemToEdit.localPaths[0]?.relativePath }}
          </div>
          <v-row dense class="edit-form__grid">
            <v-col cols="12">
              <v-text-field v-model="titleItemForm.title" label="Title" hide-details="auto"></v-text-field>
            </v-col>
            <v-col cols="12" md="8">
              <v-text-field v-model="titleItemForm.artistName" label="Artist" hide-details="auto"></v-text-field>
            </v-col>
            <v-col cols="12" md="4">
              <v-text-field v-model="titleItemForm.releaseDate" label="Release date" hide-details="auto"></v-text-field>
            </v-col>
            <v-col cols="12">
              <v-text-field v-model="titleItemForm.sortName" label="Sort as" hide-details="auto"></v-text-field>
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

    <v-dialog v-model="albumEditDialog" max-width="460">
      <v-card class="dialog-card album-edit-dialog">
        <v-card-title>Edit Album</v-card-title>
        <v-card-text class="edit-form">
          <div v-if="albumToEdit?.localPaths.length" class="cell-muted edit-form__meta">
            {{ albumToEdit.localPaths.find((path) => !path.missingSince)?.relativePath ?? albumToEdit.localPaths[0]?.relativePath }}
          </div>
          <v-text-field
            v-model="albumEditForm.title"
            label="Title"
            variant="outlined"
            density="compact"
            autofocus
            @keydown.enter.prevent="saveAlbumTitle"
          ></v-text-field>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn variant="text" @click="albumEditDialog = false">Cancel</v-btn>
          <v-btn color="primary" :loading="albumEditSaving" :disabled="!albumEditForm.title.trim()" @click="saveAlbumTitle">
            Save
          </v-btn>
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
