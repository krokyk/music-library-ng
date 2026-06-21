<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useLibraryStore } from '@/stores/library'
import type {
  Artist,
  ArtistProviderCandidate,
} from '@/types'
import type { CSSProperties } from 'vue'

type ProviderId = 'musicbrainz' | 'spirit_of_metal' | 'metal_archives'
type SortDirection = 'asc' | 'desc'
type ArtistSortKey = 'name' | 'country' | 'type' | 'status' | 'albumCount' | 'uncheckedAlbumCount' | 'localAlbumCount' | 'provider'
type ArtistScreenColumnKey = 'name' | 'country' | 'type' | 'status' | 'albums' | 'unchecked' | 'local' | 'provider' | 'action'
type ArtistsPaneKey = 'artists' | 'details'

interface ProviderDefinition {
  id: ProviderId
  label: string
  actionIcon: string
  iconSrc: string
  chipClass: string
}

const providerDefinitions: ProviderDefinition[] = [
  {
    id: 'musicbrainz',
    label: 'MusicBrainz',
    actionIcon: 'mdi-music-circle',
    iconSrc: '/provider-icons/musicbrainz.svg',
    chipClass: 'artists-provider-chip--musicbrainz',
  },
  {
    id: 'spirit_of_metal',
    label: 'Spirit of Metal',
    actionIcon: 'mdi-fire',
    iconSrc: '/provider-icons/spirit-of-metal.png',
    chipClass: 'artists-provider-chip--spirit-of-metal',
  },
  {
    id: 'metal_archives',
    label: 'Metal Archives',
    actionIcon: 'mdi-archive',
    iconSrc: '/provider-icons/metal-archives.ico',
    chipClass: 'artists-provider-chip--metal-archives',
  },
]

const store = useLibraryStore()
const { artists, albums, collections, providerJob, providerLinks, providerStatus, scanJob, uiSettings, loading } = storeToRefs(store)

const search = ref('')
const selectedArtistId = ref<number | null>(null)
const artistToDelete = ref<Artist | null>(null)
const deleteArtistDialog = ref(false)
const deleteArtistWarningDialog = ref(false)
const deletingArtist = ref(false)
const deletingArtistId = ref<number | null>(null)
const matchDialog = ref(false)
const matchLoading = ref(false)
const providerCandidates = ref<ArtistProviderCandidate[]>([])
const matchingArtistId = ref<number | null>(null)
const providerUrlDialog = ref(false)
const providerUrlSaving = ref(false)
const providerUrlArtist = ref<Artist | null>(null)
const providerUrlProviderId = ref<ProviderId | null>(null)
const providerUrl = ref('')
const artistsScreenElement = ref<HTMLElement | null>(null)
const artistsTablePaneElement = ref<unknown>(null)
const artistDetailsPaneElement = ref<unknown>(null)
const artistsGridElement = ref<HTMLElement | null>(null)
const defaultArtistsPanePercents = [70, 30]
const paneResizerWidth = 10
const artistsGridHeaderHeight = 38
const artistsGridRowHeight = 42
const artistsGridBufferRows = 12
const artistsGridFallbackViewportHeight = 900
const artistsPanePercents = ref([...defaultArtistsPanePercents])
const artistsPaneLayoutSaveTimer = ref<number | null>(null)
const artistsPaneNames = ['artists', 'details'] as const
const artistsPaneLayoutPreferenceKey = 'artists-screen.layout.panes'
const artistsPaneWidths = reactive<Record<ArtistsPaneKey, number>>({
  artists: 0,
  details: 0,
})
const artistSort = reactive<{ key: ArtistSortKey; direction: SortDirection }>({
  key: 'name',
  direction: 'asc',
})
const suppressHeaderSortUntil = ref(0)
const artistsScreenColumnWidths = reactive<Record<ArtistScreenColumnKey, number>>({
  name: 250,
  country: 76,
  type: 80,
  status: 84,
  albums: 68,
  unchecked: 86,
  local: 64,
  provider: 180,
  action: 104,
})
const artistsScreenColumnOrder = [
  'name',
  'country',
  'type',
  'status',
  'albums',
  'unchecked',
  'local',
  'provider',
  'action',
] as const satisfies readonly ArtistScreenColumnKey[]
const artistsScreenHeaders: Array<{ key: ArtistSortKey; column: Exclude<ArtistScreenColumnKey, 'action'>; label: string }> = [
  { key: 'name', column: 'name', label: 'Artist' },
  { key: 'country', column: 'country', label: 'Country' },
  { key: 'type', column: 'type', label: 'Type' },
  { key: 'status', column: 'status', label: 'Status' },
  { key: 'albumCount', column: 'albums', label: 'Albums' },
  { key: 'uncheckedAlbumCount', column: 'unchecked', label: 'Unchecked' },
  { key: 'localAlbumCount', column: 'local', label: 'Local' },
  { key: 'provider', column: 'provider', label: 'Provider' },
]
const artistsScreenColumnWidthPreferenceKeys: Record<ArtistScreenColumnKey, string> = {
  name: 'artists-screen.artists-pane.name',
  country: 'artists-screen.artists-pane.country',
  type: 'artists-screen.artists-pane.type',
  status: 'artists-screen.artists-pane.status',
  albums: 'artists-screen.artists-pane.albums',
  unchecked: 'artists-screen.artists-pane.unchecked',
  local: 'artists-screen.artists-pane.local',
  provider: 'artists-screen.artists-pane.provider',
  action: 'artists-screen.artists-pane.action',
}
const artistsScreenActionColumnWidths = {
  icon: 104,
  labeled: 390,
}
const artistsGridScrollTop = ref(0)
const artistsGridViewportHeight = ref(0)
let artistsPaneWidthObserver: ResizeObserver | null = null
let artistsPaneResizeActive = false
const scanIsRunning = computed(() => scanJob.value?.status === 'RUNNING')
const providerIsRunning = computed(() => providerJob.value?.status === 'RUNNING' || providerStatus.value.running)
const writeActionsDisabled = computed(() => scanIsRunning.value || providerIsRunning.value)

const filteredArtists = computed(() => {
  const needle = search.value.trim().toLowerCase()
  if (!needle) return artists.value
  return artists.value.filter((artist) => artist.name.toLowerCase().includes(needle))
})

const sortedArtists = computed(() =>
  [...filteredArtists.value].sort(compareArtistRows),
)

const artistsVirtualViewportHeight = computed(() =>
  Math.max(artistsGridViewportHeight.value, artistsGridFallbackViewportHeight),
)

const artistsVirtualRowCount = computed(() =>
  Math.ceil(artistsVirtualViewportHeight.value / artistsGridRowHeight) + artistsGridBufferRows * 2,
)

const artistsVirtualStartIndex = computed(() => {
  const total = sortedArtists.value.length
  const maximumStart = Math.max(0, total - artistsVirtualRowCount.value)
  const visibleStart = Math.floor(Math.max(0, artistsGridScrollTop.value - artistsGridHeaderHeight) / artistsGridRowHeight)
  return Math.min(Math.max(0, visibleStart - artistsGridBufferRows), maximumStart)
})

const artistsVirtualEndIndex = computed(() =>
  Math.min(sortedArtists.value.length, artistsVirtualStartIndex.value + artistsVirtualRowCount.value),
)

const visibleArtistRows = computed(() =>
  sortedArtists.value.slice(artistsVirtualStartIndex.value, artistsVirtualEndIndex.value),
)

const artistsVirtualTopSpacerHeight = computed(() =>
  artistsVirtualStartIndex.value * artistsGridRowHeight,
)

const artistsVirtualBottomSpacerHeight = computed(() =>
  Math.max(0, sortedArtists.value.length - artistsVirtualEndIndex.value) * artistsGridRowHeight,
)

const artistStats = computed(() => ({
  total: artists.value.length,
  unchecked: artists.value.reduce((sum, artist) => sum + artist.uncheckedAlbumCount, 0),
  providers: artists.value.reduce((sum, artist) => sum + artist.providerLinkCount, 0),
}))

const selectedArtist = computed(() => artists.value.find((artist) => artist.id === selectedArtistId.value) ?? null)
const selectedProvider = computed(() => selectedArtist.value ? providerForArtist(selectedArtist.value) : null)
const selectedAlbums = computed(() => {
  if (!selectedArtist.value) {
    return []
  }
  const artistId = selectedArtist.value.id
  return albums.value
    .filter((album) => album.artistIds.includes(artistId))
    .slice()
    .sort((left, right) =>
      (left.releaseDate ?? '').localeCompare(right.releaseDate ?? '')
      || left.title.localeCompare(right.title),
    )
})
const providerUrlDefinition = computed(() =>
  providerUrlProviderId.value ? providerDefinition(providerUrlProviderId.value) : null,
)

function compareArtistRows(left: Artist, right: Artist) {
  const leftValue = artistSortValue(left, artistSort.key)
  const rightValue = artistSortValue(right, artistSort.key)
  const result = typeof leftValue === 'number' && typeof rightValue === 'number'
    ? compareNumber(leftValue, rightValue)
    : compareText(String(leftValue), String(rightValue))
  return applyDirection(result || compareText(left.name, right.name), artistSort.direction)
}

function artistSortValue(artist: Artist, key: ArtistSortKey) {
  if (key === 'country') return artistCountry(artist) ?? ''
  if (key === 'type') return artistType(artist) ?? ''
  if (key === 'status') return artistStatus(artist)
  if (key === 'albumCount') return artist.albumCount
  if (key === 'uncheckedAlbumCount') return artist.uncheckedAlbumCount
  if (key === 'localAlbumCount') return artist.localAlbumCount
  if (key === 'provider') return providerChipText(artist)
  return artist.name
}

function compareText(left: string | null | undefined, right: string | null | undefined) {
  return (left ?? '').localeCompare(right ?? '', undefined, { numeric: true, sensitivity: 'base' })
}

function compareNumber(left: number, right: number) {
  return left - right
}

function applyDirection(result: number, direction: SortDirection) {
  return direction === 'asc' ? result : -result
}

function oppositeDirection(direction: SortDirection) {
  return direction === 'asc' ? 'desc' : 'asc'
}

function sortIcon(direction: SortDirection) {
  return direction === 'asc' ? 'mdi-arrow-up' : 'mdi-arrow-down'
}

function handleArtistsGridScroll(event: Event) {
  const element = event.currentTarget
  if (!(element instanceof HTMLElement)) {
    return
  }
  updateArtistsGridViewport(element)
}

function resetArtistsGridScroll() {
  artistsGridScrollTop.value = 0
  void nextTick(() => {
    if (!artistsGridElement.value) {
      artistsGridViewportHeight.value = 0
      return
    }
    artistsGridElement.value.scrollTop = 0
    updateArtistsGridViewport(artistsGridElement.value)
  })
}

function updateArtistsGridViewport(element: HTMLElement) {
  artistsGridScrollTop.value = element.scrollTop
  artistsGridViewportHeight.value = element.clientHeight
}

function toggleArtistSort(key: ArtistSortKey) {
  if (artistSort.key === key) {
    artistSort.direction = oppositeDirection(artistSort.direction)
    return
  }
  artistSort.key = key
  artistSort.direction = 'asc'
}

function handleArtistHeaderClick(key: ArtistSortKey, event: MouseEvent) {
  if (shouldSuppressHeaderSort(event)) {
    return
  }
  toggleArtistSort(key)
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

function releaseDateYearLabel(releaseDate: string | null | undefined) {
  if (releaseDate && /^\d{4}/.test(releaseDate)) {
    return releaseDate.slice(0, 4)
  }
  return ''
}

function artistsScreenColumnGridStyle() {
  const columns = artistsScreenColumnOrder
    .map((key, index) => (
      index === artistsScreenColumnOrder.length - 1
        ? `minmax(${artistsScreenColumnMinimumWidth(key)}px, 1fr)`
        : `${artistsScreenRenderedColumnWidth(key)}px`
    ))
    .join(' ')
  return {
    '--workspace-grid-columns': columns,
    '--workspace-grid-min-width': `${artistsScreenMinimumGridWidth()}px`,
  }
}

function artistsScreenRenderedColumnWidth(key: ArtistScreenColumnKey) {
  return Math.max(artistsScreenColumnMinimumWidth(key), artistsScreenColumnWidths[key])
}

function artistsScreenFixedColumnsWidth() {
  return artistsScreenColumnOrder
    .slice(0, -1)
    .reduce((sum, key) => sum + artistsScreenRenderedColumnWidth(key), 0)
}

function artistsScreenMinimumGridWidth() {
  return artistsScreenFixedColumnsWidth() + artistsScreenColumnMinimumWidth('action')
}

function artistsScreenTableAvailableWidth() {
  const grid = document.querySelector('.artists-table-pane .workspace-grid')
  if (grid instanceof HTMLElement) {
    return grid.clientWidth
  }
  return artistsPaneWidths.artists > 0 ? Math.max(0, artistsPaneWidths.artists - 2) : 0
}

function artistsScreenRightmostColumnAvailableWidth() {
  const available = artistsScreenTableAvailableWidth()
  if (available <= 0) {
    return artistsScreenColumnMinimumWidth('action')
  }
  return Math.max(artistsScreenColumnMinimumWidth('action'), available - artistsScreenFixedColumnsWidth())
}

function artistsScreenColumnMinimumWidth(key: ArtistScreenColumnKey) {
  if (key === 'action') {
    return artistsScreenActionColumnWidths.icon
  }
  return Math.max(1, uiSettings.value.tableGridColumnMinWidth)
}

function showArtistsScreenActionLabels() {
  return artistsPaneWidths.artists >= uiSettings.value.actionLabelThresholds.artists
    && artistsScreenRightmostColumnAvailableWidth() >= artistsScreenActionColumnWidths.labeled
}

function artistScreenRowActionClass() {
  return [actionLabelClassFor(showArtistsScreenActionLabels()), 'workspace-row-action']
}

function actionLabelClassFor(showLabels: boolean) {
  return {
    'action-button--labeled': showLabels,
    'action-button--icon-only': !showLabels,
  }
}

function startArtistScreenColumnResize(key: ArtistScreenColumnKey, event: PointerEvent) {
  event.preventDefault()
  event.stopPropagation()
  suppressHeaderSortClick(event)
  const leftIndex = artistsScreenColumnOrder.indexOf(key)
  if (leftIndex < 0 || leftIndex >= artistsScreenColumnOrder.length - 1) {
    return
  }
  const rendered = Object.fromEntries(
    artistsScreenColumnOrder.map((columnKey) => [columnKey, artistsScreenRenderedColumnWidth(columnKey)]),
  ) as Record<ArtistScreenColumnKey, number>
  const leftStart = rendered[key]
  const startX = event.clientX
  document.body.classList.add('is-column-resizing')

  const beforeWidth = artistsScreenColumnOrder
    .slice(0, leftIndex)
    .reduce((sum, columnKey) => sum + rendered[columnKey], 0)
  const rightDataBlockWidth = artistsScreenColumnOrder
    .slice(leftIndex + 1, -1)
    .reduce((sum, columnKey) => sum + rendered[columnKey], 0)
  const leftMinimum = artistsScreenColumnMinimumWidth(key)
  const rightmostMinimum = artistsScreenColumnMinimumWidth('action')
  const available = artistsScreenTableAvailableWidth()
  const leftMaximum = available > 0
    ? Math.max(leftMinimum, available - beforeWidth - rightDataBlockWidth - rightmostMinimum)
    : Number.POSITIVE_INFINITY

  function move(pointerEvent: PointerEvent) {
    const left = Math.min(
      Math.max(leftMinimum, Math.round(leftStart + pointerEvent.clientX - startX)),
      leftMaximum,
    )
    artistsScreenColumnWidths[key] = left
  }

  function stop() {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', stop)
    window.removeEventListener('pointercancel', stop)
    document.body.classList.remove('is-column-resizing')
    suppressHeaderSortClick()
    saveArtistsScreenColumnWidth(key)
  }

  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', stop)
  window.addEventListener('pointercancel', stop)
}

function saveArtistsScreenColumnWidth(key: ArtistScreenColumnKey) {
  void store.savePreference(
    artistsScreenColumnWidthPreferenceKeys[key],
    String(Math.round(artistsScreenColumnWidths[key] ?? artistsScreenColumnMinimumWidth(key))),
  ).catch((error) => {
    store.showErrorStatus(error, 'Unable to save column width')
  })
}

function applyArtistsScreenColumnDefaults() {
  const defaults = uiSettings.value.artistsScreenColumnDefaults
  artistsScreenColumnOrder.forEach((key) => {
    artistsScreenColumnWidths[key] = Math.max(artistsScreenColumnMinimumWidth(key), defaults[key])
  })
}

async function loadArtistsScreenColumnWidths() {
  await Promise.all(
    artistsScreenColumnOrder.map(async (key) => {
      const preference = await store.loadPreference(artistsScreenColumnWidthPreferenceKeys[key])
      if (!preference?.value) {
        return
      }
      const value = parseColumnWidthPreference(preference.value)
      if (value !== null) {
        artistsScreenColumnWidths[key] = Math.max(artistsScreenColumnMinimumWidth(key), value)
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

function artistsPaneStyle(index: number) {
  return {
    display: 'flex',
    flex: artistsPaneFlexValue(index, artistsPanePercents.value),
    flexDirection: 'column',
    minWidth: '0',
    overflow: 'hidden',
  } satisfies CSSProperties
}

function artistsPaneFlexValue(index: number, percents: number[], totalResizerWidth = paneResizerWidth) {
  const percent = percents[index]
  const resizerShare = (totalResizerWidth * percent) / 100
  return `0 0 calc(${percent}% - ${resizerShare}px)`
}

function startArtistsPaneResize(event: PointerEvent) {
  event.preventDefault()
  if (!artistsScreenElement.value) {
    return
  }
  const startX = event.clientX
  const startPercents = [...artistsPanePercents.value]
  const paneAreaWidth = Math.max(1, artistsScreenElement.value.clientWidth - paneResizerWidth)
  const [leftMinimumPx, rightMinimumPx] = artistsPaneMinimums(paneAreaWidth)
  const leftMinimum = (leftMinimumPx / paneAreaWidth) * 100
  const rightMinimum = (rightMinimumPx / paneAreaWidth) * 100
  const leftMaximum = Math.max(leftMinimum, 100 - rightMinimum)

  function move(pointerEvent: PointerEvent) {
    const deltaPercent = ((pointerEvent.clientX - startX) / paneAreaWidth) * 100
    const left = Math.min(Math.max(leftMinimum, startPercents[0] + deltaPercent), leftMaximum)
    artistsPanePercents.value = normalizeArtistsPanePercents([left, 100 - left])
  }

  function stop() {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', stop)
    window.removeEventListener('pointercancel', stop)
    artistsPaneResizeActive = false
    document.body.classList.remove('is-pane-resizing')
    saveArtistsPaneLayout()
    void nextTick(setupArtistsPaneWidthObserver)
  }

  artistsPaneResizeActive = true
  artistsPaneWidthObserver?.disconnect()
  document.body.classList.add('is-pane-resizing')
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', stop)
  window.addEventListener('pointercancel', stop)
}

function artistsPaneMinimums(areaWidth: number) {
  const raw = [Math.max(420, artistsScreenMinimumGridWidth()), 280]
  const total = raw[0] + raw[1]
  if (total <= areaWidth) {
    return raw
  }
  return raw.map((value) => Math.max(1, (value / total) * areaWidth))
}

function normalizeArtistsPanePercents(values: number[]) {
  const cleaned = values.map((value) => (Number.isFinite(value) && value > 0 ? value : 0))
  const total = cleaned.reduce((sum, value) => sum + value, 0)
  if (total <= 0) {
    return [...defaultArtistsPanePercents]
  }
  const first = Math.round((cleaned[0] / total) * 10000) / 100
  return [first, Math.round((100 - first) * 100) / 100]
}

async function loadArtistsPaneLayout() {
  const preference = await store.loadPreference(artistsPaneLayoutPreferenceKey)
  if (!preference?.value) {
    return
  }
  try {
    const parsed = JSON.parse(preference.value)
    if (isArtistsPaneLayoutObject(parsed)) {
      artistsPanePercents.value = normalizeArtistsPanePercents([parsed.artists, parsed.details])
    } else if (Array.isArray(parsed) && parsed.length === 2 && parsed.every((value) => typeof value === 'number')) {
      artistsPanePercents.value = normalizeArtistsPanePercents(parsed)
    }
  } catch (error) {
    // Ignore invalid stored UI state and keep the default layout.
  }
}

function saveArtistsPaneLayout() {
  if (artistsPaneLayoutSaveTimer.value !== null) {
    window.clearTimeout(artistsPaneLayoutSaveTimer.value)
    artistsPaneLayoutSaveTimer.value = null
  }
  const rounded = normalizeArtistsPanePercents(artistsPanePercents.value)
  artistsPanePercents.value = rounded
  void store.savePreference(artistsPaneLayoutPreferenceKey, JSON.stringify({
    [artistsPaneNames[0]]: rounded[0],
    [artistsPaneNames[1]]: rounded[1],
  })).catch((error) => {
    store.showErrorStatus(error, 'Unable to save pane layout')
  })
}

function isArtistsPaneLayoutObject(value: unknown): value is Record<(typeof artistsPaneNames)[number], number> {
  if (!value || typeof value !== 'object') {
    return false
  }
  const layout = value as Record<string, unknown>
  return artistsPaneNames.every((name) => typeof layout[name] === 'number')
}

function setupArtistsPaneWidthObserver() {
  artistsPaneWidthObserver?.disconnect()
  artistsPaneWidthObserver = new ResizeObserver((entries) => {
    if (artistsPaneResizeActive) {
      return
    }
    entries.forEach((entry) => {
      const pane = (entry.target as HTMLElement).dataset.paneKey as ArtistsPaneKey | undefined
      if (pane) {
        artistsPaneWidths[pane] = Math.round(entry.contentRect.width)
      }
    })
  })
  observeArtistsPaneWidth('artists', artistsTablePaneElement.value)
  observeArtistsPaneWidth('details', artistDetailsPaneElement.value)
}

function observeArtistsPaneWidth(pane: ArtistsPaneKey, paneRef: unknown) {
  const element = resolveElement(paneRef)
  if (!element || !artistsPaneWidthObserver) {
    return
  }
  element.dataset.paneKey = pane
  artistsPaneWidths[pane] = Math.round(element.getBoundingClientRect().width)
  artistsPaneWidthObserver.observe(element)
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

async function selectArtist(artist: Artist) {
  if (deletingArtistId.value === artist.id) {
    return
  }
  selectedArtistId.value = artist.id
  try {
    await store.loadArtistProvider(artist.id)
  } catch (error) {
    store.showErrorStatus(error, 'Unable to load artist provider')
  }
}

async function openMusicBrainzMatch(artist: Artist) {
  if (writeActionsDisabled.value) {
    return
  }
  selectedArtistId.value = artist.id
  matchingArtistId.value = artist.id
  matchDialog.value = true
  matchLoading.value = true
  providerCandidates.value = []
  try {
    providerCandidates.value = await store.searchMusicBrainzCandidates(artist.id)
  } catch (error) {
    store.showErrorStatus(error, 'Unable to search MusicBrainz')
  } finally {
    matchLoading.value = false
    matchingArtistId.value = null
  }
}

async function useCandidate(candidate: ArtistProviderCandidate) {
  if (writeActionsDisabled.value) {
    return
  }
  if (!selectedArtistId.value) return
  try {
    await store.saveArtistProvider(selectedArtistId.value, {
      providerId: 'musicbrainz',
      providerArtistId: candidate.providerArtistId,
      providerArtistName: candidate.providerArtistName,
      providerArtistType: candidate.type,
      providerArtistCountry: candidate.country,
      providerArtistDisambiguation: candidate.disambiguation,
      providerArtistActive: candidate.active,
      providerUrl: candidate.providerUrl,
      enabled: true,
    })
    matchDialog.value = false
    store.showStatus(`MusicBrainz provider saved for ${candidate.providerArtistName}.`, 'done')
  } catch (error) {
    store.showErrorStatus(error, 'Unable to save MusicBrainz provider')
  }
}

async function startProviderSetup(artist: Artist, providerId: ProviderId) {
  if (writeActionsDisabled.value) {
    return
  }
  await selectArtist(artist)
  if (providerId === 'musicbrainz') {
    await openMusicBrainzMatch(artist)
    return
  }
  providerUrlArtist.value = artist
  providerUrlProviderId.value = providerId
  providerUrl.value = ''
  providerUrlDialog.value = true
}

async function saveUrlProvider() {
  if (writeActionsDisabled.value) {
    return
  }
  if (!providerUrlArtist.value || !providerUrlProviderId.value) {
    return
  }
  const url = providerUrl.value.trim()
  if (!url) {
    return
  }
  providerUrlSaving.value = true
  try {
    const provider = providerDefinition(providerUrlProviderId.value)
    await store.saveArtistProvider(providerUrlArtist.value.id, {
      providerId: providerUrlProviderId.value,
      providerUrl: url,
      enabled: true,
    })
    store.showStatus(`${provider.label} provider saved for ${providerUrlArtist.value.name}.`, 'done')
    providerUrlDialog.value = false
  } catch (error) {
    store.showErrorStatus(error, 'Unable to save provider')
  } finally {
    providerUrlSaving.value = false
  }
}

async function clearArtistProvider(artist: Artist) {
  if (writeActionsDisabled.value) {
    return
  }
  try {
    await store.clearArtistProvider(artist.id)
    store.showStatus(`Provider cleared for ${artist.name}.`, 'done')
  } catch (error) {
    store.showErrorStatus(error, 'Unable to clear provider')
  }
}

function providerForArtist(artist: Artist) {
  const cached = providerLinks.value[artist.id]?.[0]
  if (cached) {
    return cached
  }
  if (!artist.providerId) {
    return null
  }
  return {
    providerId: artist.providerId,
    providerArtistId: artist.providerArtistId,
    providerArtistName: artist.providerArtistName,
    providerArtistType: artist.providerArtistType,
    providerArtistCountry: artist.providerArtistCountry,
    providerArtistDisambiguation: artist.providerArtistDisambiguation,
    providerArtistActive: artist.providerArtistActive,
    providerUrl: artist.providerUrl,
    lastErrorMessage: null,
  }
}

function providerDefinition(providerId?: string | null) {
  return providerDefinitions.find((provider) => provider.id === providerId)
    ?? {
      id: 'musicbrainz' as ProviderId,
      label: providerId ?? 'Provider',
      actionIcon: 'mdi-link-variant',
      iconSrc: '',
      chipClass: 'artists-provider-chip--generic',
    }
}

function providerActionsForArtist(artist: Artist) {
  const selectedProviderId = providerForArtist(artist)?.providerId
  return providerDefinitions.filter((provider) => provider.id !== selectedProviderId)
}

function providerChipText(artist: Artist) {
  const provider = providerForArtist(artist)
  return provider ? providerDefinition(provider.providerId).label : 'None'
}

function providerChipClasses(artist: Artist) {
  const provider = providerForArtist(artist)
  if (!provider) {
    return []
  }
  return [
    'artists-provider-chip',
    providerDefinition(provider.providerId).chipClass,
    { 'artists-provider-chip--error': Boolean(provider.lastErrorMessage) },
  ]
}

function providerChipIconSrc(artist: Artist) {
  const provider = providerForArtist(artist)
  return provider ? providerDefinition(provider.providerId).iconSrc : ''
}

function providerIdentityLabel() {
  if (!selectedProvider.value) {
    return null
  }
  return selectedProvider.value.providerArtistName
    || selectedProvider.value.providerArtistId
    || selectedProvider.value.providerUrl
    || null
}

function artistCountry(artist: Artist) {
  return providerForArtist(artist)?.providerArtistCountry ?? artist.providerArtistCountry ?? null
}

function artistCountryLabel(artist: Artist) {
  const country = artistCountry(artist)
  if (!country) {
    return 'Unknown'
  }
  const normalized = country.trim().toUpperCase()
  const flag = countryFlag(normalized)
  return flag ? `${flag} ${normalized}` : normalized
}

function countryFlag(country: string) {
  if (!/^[A-Z]{2}$/.test(country)) {
    return ''
  }
  return String.fromCodePoint(
    ...[...country].map((character) => 0x1f1e6 + character.charCodeAt(0) - 65),
  )
}

function artistType(artist: Artist) {
  return providerForArtist(artist)?.providerArtistType ?? artist.providerArtistType ?? null
}

function artistStatus(artist: Artist) {
  const active = providerForArtist(artist)?.providerArtistActive ?? artist.providerArtistActive
  if (active === true) {
    return 'Active'
  }
  if (active === false) {
    return 'Inactive'
  }
  return 'Unknown'
}

function candidateInfo(candidate: ArtistProviderCandidate) {
  const status = candidate.active === true ? 'Active' : candidate.active === false ? 'Inactive' : null
  return [candidate.type, candidate.country, status, candidate.disambiguation].filter(Boolean).join(' · ')
}

function openExternal(url?: string | null) {
  if (url) {
    window.open(url, '_blank', 'noopener')
  }
}

function askDeleteArtist(artist: Artist) {
  if (writeActionsDisabled.value) {
    return
  }
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
  if (writeActionsDisabled.value) {
    return
  }
  deleteArtistDialog.value = false
  if (artistDeleteNeedsWarning(artistToDelete.value)) {
    deleteArtistWarningDialog.value = true
    return
  }
  void deleteArtist()
}

async function deleteArtist() {
  if (writeActionsDisabled.value) {
    return
  }
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
    store.showErrorStatus(error, 'Unable to delete artist')
  } finally {
    deletingArtist.value = false
    deletingArtistId.value = null
  }
}

function artistScreenRowClass(artist: Artist) {
  return {
    'is-selected': selectedArtistId.value === artist.id,
    'workspace-row--deleting': deletingArtistId.value === artist.id,
  }
}

onMounted(async () => {
  await store.loadUiSettings()
  applyArtistsScreenColumnDefaults()
  await Promise.all([
    loadArtistsScreenColumnWidths(),
    loadArtistsPaneLayout(),
    store.loadAll(),
  ])
  await nextTick()
  setupArtistsPaneWidthObserver()
})

onBeforeUnmount(() => {
  artistsPaneResizeActive = false
  document.body.classList.remove('is-pane-resizing')
  document.body.classList.remove('is-column-resizing')
  artistsPaneWidthObserver?.disconnect()
  artistsPaneWidthObserver = null
  if (artistsPaneLayoutSaveTimer.value !== null) {
    saveArtistsPaneLayout()
  }
})

watch(search, () => {
  resetArtistsGridScroll()
})

watch([() => artistSort.key, () => artistSort.direction], () => {
  resetArtistsGridScroll()
})
</script>

<template>
  <v-container fluid class="app-page artists-page">
    <div ref="artistsScreenElement" class="artists-two-pane">
      <v-sheet ref="artistsTablePaneElement" class="pane artists-table-pane" :style="artistsPaneStyle(0)">
        <div class="pane-header">
          <div class="pane-header__primary">
            <span class="pane-header__title">Artists</span>
            <span class="pane-header__meta">
              {{ artistStats.total }} artists / {{ artistStats.unchecked }} unchecked / {{ artistStats.providers }} providers
            </span>
          </div>
        </div>

        <div class="pane-filter-bar artists-search-bar">
          <v-text-field
            v-model="search"
            class="artists-search-field"
            prepend-inner-icon="mdi-magnify"
            density="compact"
            label="Search artists"
            hide-details
          ></v-text-field>
        </div>

        <div v-if="loading && sortedArtists.length === 0" class="pane-loading">
          <v-progress-circular indeterminate size="60" width="5"></v-progress-circular>
        </div>
        <div
          v-else
          ref="artistsGridElement"
          class="workspace-grid artists-screen-grid"
          :style="artistsScreenColumnGridStyle()"
          @scroll="handleArtistsGridScroll"
        >
          <div class="workspace-grid__row workspace-grid__header">
            <div
              v-for="header in artistsScreenHeaders"
              :key="header.column"
              class="workspace-grid__cell workspace-grid__header-cell sortable-header"
              :data-column="`artists.${header.column}`"
              @click="handleArtistHeaderClick(header.key, $event)"
            >
              <span class="sortable-header__label">{{ header.label }}</span>
              <v-icon
                v-if="artistSort.key === header.key"
                :icon="sortIcon(artistSort.direction)"
                size="14"
                class="sort-direction-icon"
              ></v-icon>
              <span
                class="column-resize-handle"
                @pointerdown="startArtistScreenColumnResize(header.column, $event)"
                @click="suppressHeaderSortClick($event)"
              ></span>
            </div>
          </div>

          <div
            v-if="artistsVirtualTopSpacerHeight > 0"
            class="workspace-grid__virtual-spacer"
            :style="{ height: `${artistsVirtualTopSpacerHeight}px` }"
            aria-hidden="true"
          ></div>
          <div
            v-for="artist in visibleArtistRows"
            :key="artist.id"
            class="workspace-grid__row workspace-row"
            :class="artistScreenRowClass(artist)"
            @click="selectArtist(artist)"
          >
            <div data-column="artists.name" class="workspace-grid__cell truncate-cell">
              <div class="artist-cell">
                <v-progress-circular
                  v-if="deletingArtistId === artist.id"
                  indeterminate
                  size="14"
                  width="2"
                  class="artist-cell__spinner"
                ></v-progress-circular>
                <span class="cell-strong" :title="artist.name">{{ artist.name }}</span>
              </div>
            </div>
            <div data-column="artists.country" class="workspace-grid__cell truncate-cell">
              <span :class="{ 'cell-muted': !artistCountry(artist) }">{{ artistCountryLabel(artist) }}</span>
            </div>
            <div data-column="artists.type" class="workspace-grid__cell truncate-cell">
              <span :class="{ 'cell-muted': !artistType(artist) }">{{ artistType(artist) || 'Unknown' }}</span>
            </div>
            <div data-column="artists.status" class="workspace-grid__cell">
              <v-chip
                v-if="artistStatus(artist) !== 'Unknown'"
                :color="artistStatus(artist) === 'Active' ? 'success' : 'default'"
                size="x-small"
                variant="tonal"
              >
                {{ artistStatus(artist) }}
              </v-chip>
              <span v-else class="cell-muted">Unknown</span>
            </div>
            <div data-column="artists.albums" class="workspace-grid__cell artists-count-cell">
              <span>{{ artist.albumCount }}</span>
            </div>
            <div data-column="artists.unchecked" class="workspace-grid__cell artists-count-cell">
              <v-chip :color="artist.uncheckedAlbumCount > 0 ? 'warning' : 'default'" size="x-small" variant="tonal">
                {{ artist.uncheckedAlbumCount }}
              </v-chip>
            </div>
            <div data-column="artists.local" class="workspace-grid__cell artists-count-cell">
              <span>{{ artist.localAlbumCount }}</span>
            </div>
            <div data-column="artists.provider" class="workspace-grid__cell truncate-cell">
              <v-chip
                v-if="providerForArtist(artist)"
                :class="providerChipClasses(artist)"
                size="small"
                variant="flat"
                closable
                close-icon="mdi-trash-can-outline"
                :disabled="writeActionsDisabled"
                @click.stop
                @click:close.stop="clearArtistProvider(artist)"
              >
                <img
                  v-if="providerChipIconSrc(artist)"
                  class="artists-provider-chip__icon"
                  :src="providerChipIconSrc(artist)"
                  alt=""
                  aria-hidden="true"
                >
                <span class="artists-provider-chip__text">{{ providerChipText(artist) }}</span>
              </v-chip>
              <span v-else class="cell-muted">None</span>
            </div>
            <div data-column="artists.action" class="workspace-grid__cell row-action-cell">
              <div class="row-actions">
                <v-tooltip
                  v-for="provider in providerActionsForArtist(artist)"
                  :key="provider.id"
                  :text="provider.label"
                  location="top"
                >
                  <template #activator="{ props }">
                    <v-btn
                      v-bind="props"
                      :prepend-icon="provider.actionIcon"
                      size="x-small"
                      variant="text"
                      color="primary"
                      :class="artistScreenRowActionClass()"
                      :loading="provider.id === 'musicbrainz' && matchingArtistId === artist.id"
                      :disabled="writeActionsDisabled || deletingArtistId === artist.id"
                      @click.stop="startProviderSetup(artist, provider.id)"
                    >
                      <span v-if="showArtistsScreenActionLabels()">{{ provider.label }}</span>
                    </v-btn>
                  </template>
                </v-tooltip>
                <v-tooltip text="Delete artist" location="top">
                  <template #activator="{ props }">
                    <v-btn
                      v-bind="props"
                      prepend-icon="mdi-trash-can-outline"
                      size="x-small"
                      variant="text"
                      color="error"
                      :class="artistScreenRowActionClass()"
                      :loading="deletingArtistId === artist.id"
                      :disabled="writeActionsDisabled || (deletingArtistId !== null && deletingArtistId !== artist.id)"
                      @click.stop="askDeleteArtist(artist)"
                    >
                      <span v-if="showArtistsScreenActionLabels()">Delete</span>
                    </v-btn>
                  </template>
                </v-tooltip>
              </div>
            </div>
          </div>
          <div
            v-if="artistsVirtualBottomSpacerHeight > 0"
            class="workspace-grid__virtual-spacer"
            :style="{ height: `${artistsVirtualBottomSpacerHeight}px` }"
            aria-hidden="true"
          ></div>
        </div>
      </v-sheet>

      <div class="pane-resizer" @pointerdown="startArtistsPaneResize"></div>

      <v-sheet ref="artistDetailsPaneElement" class="pane artist-details-pane" :style="artistsPaneStyle(1)">
        <div class="pane-header">
          <div class="pane-header__primary">
            <span class="pane-header__title">Artist Info</span>
            <span v-if="selectedArtist" class="pane-header__meta">{{ selectedArtist.name }}</span>
          </div>
        </div>

        <div v-if="!selectedArtist" class="pane-empty">Select an artist.</div>
        <div v-else class="artist-details-pane__body">
          <section class="artist-details-section">
            <div class="artist-details-heading">{{ selectedArtist.name }}</div>
            <div class="artist-info-grid">
              <div class="cell-muted">Sort name</div>
              <div>{{ selectedArtist.sortName || 'None' }}</div>
              <div class="cell-muted">Country</div>
              <div :class="{ 'cell-muted': !artistCountry(selectedArtist) }">{{ artistCountryLabel(selectedArtist) }}</div>
              <div class="cell-muted">Type</div>
              <div :class="{ 'cell-muted': !artistType(selectedArtist) }">{{ artistType(selectedArtist) || 'Unknown' }}</div>
              <div class="cell-muted">Status</div>
              <div :class="{ 'cell-muted': artistStatus(selectedArtist) === 'Unknown' }">{{ artistStatus(selectedArtist) }}</div>
              <div class="cell-muted">Collections</div>
              <div class="dialog-chip-row">
                <v-chip
                  v-for="collectionId in selectedArtist.collectionIds"
                  :key="collectionId"
                  size="x-small"
                  variant="tonal"
                >
                  {{ collections.find((collection) => collection.id === collectionId)?.name ?? collectionId }}
                </v-chip>
                <span v-if="selectedArtist.collectionIds.length === 0" class="cell-muted">None</span>
              </div>
              <div class="cell-muted">Albums</div>
              <div>{{ selectedArtist.albumCount }} total, {{ selectedArtist.uncheckedAlbumCount }} unchecked</div>
              <div class="cell-muted">Provider</div>
              <div>
                <v-chip
                  v-if="selectedProvider"
                  size="small"
                  variant="flat"
                  :class="providerChipClasses(selectedArtist)"
                >
                  <img
                    v-if="providerChipIconSrc(selectedArtist)"
                    class="artists-provider-chip__icon"
                    :src="providerChipIconSrc(selectedArtist)"
                    alt=""
                    aria-hidden="true"
                  >
                  <span class="artists-provider-chip__text">{{ providerChipText(selectedArtist) }}</span>
                </v-chip>
                <span v-else class="cell-muted">None</span>
              </div>
            </div>
          </section>

          <section v-if="selectedProvider" class="artist-details-section artist-provider-section">
            <a
              v-if="selectedProvider.providerUrl && providerIdentityLabel()"
              class="artist-link artist-link--name"
              :href="selectedProvider.providerUrl"
              target="_blank"
              rel="noopener noreferrer"
            >
              {{ providerIdentityLabel() }}
            </a>
            <div v-else-if="providerIdentityLabel()" class="cell-strong">{{ providerIdentityLabel() }}</div>
            <a
              v-if="selectedProvider.providerUrl"
              class="mono-path artist-link artist-link--url"
              :href="selectedProvider.providerUrl"
              target="_blank"
              rel="noopener noreferrer"
            >
              {{ selectedProvider.providerUrl }}
            </a>
            <div v-if="selectedProvider.lastErrorMessage" class="text-error">{{ selectedProvider.lastErrorMessage }}</div>
            <v-btn
              size="small"
              variant="text"
              prepend-icon="mdi-open-in-new"
              :disabled="!selectedProvider.providerUrl"
              @click="openExternal(selectedProvider.providerUrl)"
            >
              Open
            </v-btn>
          </section>

          <section class="artist-details-section">
            <div class="panel-title">Known Albums</div>
            <div v-if="selectedAlbums.length === 0" class="cell-muted">No known albums.</div>
            <div v-else class="artist-known-albums">
              <div v-for="album in selectedAlbums" :key="album.id" class="artist-known-album">
                <span class="artist-known-album__year" :class="{ 'cell-muted': !releaseDateYearLabel(album.releaseDate) }">
                  {{ releaseDateYearLabel(album.releaseDate) || 'No date' }}
                </span>
                <span class="artist-known-album__title" :title="album.title">{{ album.title }}</span>
              </div>
            </div>
          </section>
        </div>
      </v-sheet>
    </div>

    <v-dialog v-model="matchDialog" max-width="900">
      <v-card class="dialog-card">
        <v-card-title>Match MusicBrainz</v-card-title>
        <v-card-text class="edit-form">
          <v-progress-linear v-if="matchLoading" indeterminate color="primary"></v-progress-linear>
          <div v-if="!matchLoading && providerCandidates.length === 0" class="cell-muted">No candidates found.</div>
          <v-list v-else density="compact" class="provider-list">
            <v-list-item v-for="candidate in providerCandidates" :key="candidate.providerArtistId">
              <v-list-item-title>
                <span class="cell-strong">{{ candidate.providerArtistName }}</span>
                <v-chip size="x-small" color="primary" variant="tonal" class="ml-2">{{ candidate.matchScore }}</v-chip>
              </v-list-item-title>
              <v-list-item-subtitle>
                {{ candidateInfo(candidate) }}
              </v-list-item-subtitle>
              <div class="mono-path">{{ candidate.providerArtistId }}</div>
              <div v-if="candidate.matchedLocalAlbums.length" class="dialog-chip-row mt-2">
                <v-chip
                  v-for="album in candidate.matchedLocalAlbums.slice(0, 6)"
                  :key="album"
                  size="small"
                  variant="tonal"
                >
                  {{ album }}
                </v-chip>
              </div>
              <div v-if="candidate.releaseGroups.length" class="cell-muted mt-2">
                {{ candidate.releaseGroups.slice(0, 5).map((group) => group.title).join(' · ') }}
              </div>
              <template #append>
                <v-btn size="small" variant="text" prepend-icon="mdi-open-in-new" @click="openExternal(candidate.providerUrl)">
                  Open
                </v-btn>
                <v-btn size="small" color="primary" :disabled="writeActionsDisabled" @click="useCandidate(candidate)">Use</v-btn>
              </template>
            </v-list-item>
          </v-list>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn variant="text" @click="matchDialog = false">Close</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog v-model="providerUrlDialog" max-width="560">
      <v-card class="dialog-card">
        <v-card-title>{{ providerUrlDefinition?.label }} Provider</v-card-title>
        <v-card-text class="edit-form">
          <div class="cell-muted">{{ providerUrlArtist?.name }}</div>
          <v-text-field
            v-model="providerUrl"
            label="URL"
            prepend-inner-icon="mdi-link-variant"
            autofocus
            :disabled="writeActionsDisabled"
            hide-details
            @keyup.enter="saveUrlProvider"
          ></v-text-field>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn variant="text" @click="providerUrlDialog = false">Cancel</v-btn>
          <v-btn color="primary" :loading="providerUrlSaving" :disabled="writeActionsDisabled || !providerUrl.trim()" @click="saveUrlProvider">
            Save
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog v-model="deleteArtistDialog" max-width="460">
      <v-card class="dialog-card">
        <v-card-title>Delete Artist</v-card-title>
        <v-card-text>
          Delete <span class="dialog-entity-name">{{ artistToDelete?.name }}</span> from the library database?
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn variant="text" @click="deleteArtistDialog = false">Cancel</v-btn>
          <v-btn color="error" :loading="deletingArtist" :disabled="writeActionsDisabled" @click="confirmDeleteArtist">Delete</v-btn>
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
          <v-btn color="error" :loading="deletingArtist" :disabled="writeActionsDisabled" @click="deleteArtist">Delete anyway</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </v-container>
</template>
