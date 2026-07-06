<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useLibraryStore } from '@/stores/library'
import ProviderChip from '@/components/ProviderChip.vue'
import ProviderMatchDialog from '@/components/ProviderMatchDialog.vue'
import { countryFlagSrc, countryName, countryOptions, normalizeCountryCode } from '@/countries'
import { providerDefinition, providerDefinitions, providerExternalArtistUrl, type ProviderId } from '@/providers'
import type {
  Album,
  Artist,
  ArtistProviderBulkMatchItem,
  ArtistProviderBulkMatchResult,
  ArtistProviderCandidate,
  ArtistProviderLink,
  MusicCollection,
  ProviderReleaseDateConflict,
  ProviderTitleConflict,
} from '@/types'
import type { CSSProperties } from 'vue'

type SortDirection = 'asc' | 'desc'
type ArtistSortKey = 'name' | 'country' | 'status' | 'albumCount' | 'uncheckedAlbumCount' | 'localAlbumCount' | 'provider'
type ArtistScreenColumnKey = 'name' | 'country' | 'status' | 'albums' | 'unchecked' | 'local' | 'provider' | 'action'
type ArtistsPaneKey = 'artists' | 'details'
type ProviderConflictKind = 'title' | 'release-date'

interface ProviderConflictVariant {
  key: string
  conflict: ProviderReleaseDateConflict | ProviderTitleConflict
  providerValue: string
  providers: ProviderId[]
}

interface ProviderConflictSection {
  key: string
  kind: ProviderConflictKind
  albumId: number
  localAlbum: string
  localValue: string
  variants: ProviderConflictVariant[]
}

const store = useLibraryStore()
const {
  artists,
  albums,
  collections,
  providerJob,
  providerLinks,
  providerReleaseDateConflicts,
  providerTitleConflicts,
  providerStatus,
  scanJob,
  uiSettings,
  loading,
} = storeToRefs(store)

const artistSearchText = ref('')
const appliedArtistSearch = ref('')
const selectedArtistId = ref<number | null>(null)
const artistToDelete = ref<Artist | null>(null)
const deleteArtistDialog = ref(false)
const deleteArtistWarningDialog = ref(false)
const deletingArtist = ref(false)
const deletingArtistId = ref<number | null>(null)
const savingArtistDetails = ref(false)
const countryEditorArtistId = ref<number | null>(null)
const countryEditorSearch = ref('')
const matchDialog = ref(false)
const matchProviderId = ref<ProviderId>('musicbrainz')
const providerCandidatesById = reactive<Record<ProviderId, ArtistProviderCandidate[]>>({
  musicbrainz: [],
  spirit_of_metal: [],
  metal_archives: [],
})
const providerLoadingIds = ref<ProviderId[]>([])
const providerLoadedIds = ref<ProviderId[]>([])
const selectedProviderCandidateIds = reactive<Partial<Record<ProviderId, string | null>>>({})
const originalProviderCandidateIds = reactive<Partial<Record<ProviderId, string | null>>>({})
const matchingArtistId = ref<number | null>(null)
const assigningProviders = ref(false)
const bulkMatchLoadingProviderId = ref<ProviderId | null>(null)
const bulkMatchDialog = ref(false)
const bulkMatchResult = ref<ArtistProviderBulkMatchResult | null>(null)
const providerConflictDialog = ref(false)
const providerConflictArtistId = ref<number | null>(null)
const providerConflictActionKey = ref('')
const openProviderConflictSectionKey = ref<string | null>(null)
const resettingKeepLocalReleaseDateKey = ref('')
const collectionFilterMenu = ref(false)
const artistCollectionFilterIds = ref<string[]>([])
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
const artistSearchDebounceMs = 200
const artistsPanePercents = ref([...defaultArtistsPanePercents])
const artistsPaneLayoutSaveTimer = ref<number | null>(null)
const artistsPaneNames = ['artists', 'details'] as const
const artistsPaneLayoutPreferenceKey = 'artists-screen.layout.panes'
const artistsCollectionFilterPreferenceKey = 'artists-screen.collection-filter.ids'
const artistsPaneWidths = reactive<Record<ArtistsPaneKey, number>>({
  artists: 0,
  details: 0,
})
const artistDetailsForm = reactive({
  id: null as number | null,
  name: '',
  sortName: '',
})
const artistSort = reactive<{ key: ArtistSortKey; direction: SortDirection }>({
  key: 'name',
  direction: 'asc',
})
const suppressHeaderSortUntil = ref(0)
const artistsScreenSortableColumnMinimumWidth = 62
const artistsScreenIconActionButtonWidth = 30
const artistsScreenRowActionGap = 2
const artistsScreenActionColumnWidths = {
  icon: 3 * artistsScreenIconActionButtonWidth + 2 * artistsScreenRowActionGap,
  labeled: 360,
}
const artistsSearchControlsMinimumWidth = 260
const artistsBulkExpandedMinimumWidth = 900
const artistsScreenColumnWidths = reactive<Record<ArtistScreenColumnKey, number>>({
  name: 250,
  country: 190,
  status: 110,
  albums: 68,
  unchecked: 86,
  local: 64,
  provider: 180,
  action: artistsScreenActionColumnWidths.icon,
})
const artistsScreenColumnOrder = [
  'name',
  'country',
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
  { key: 'status', column: 'status', label: 'Status' },
  { key: 'albumCount', column: 'albums', label: 'Albums' },
  { key: 'uncheckedAlbumCount', column: 'unchecked', label: 'Unchecked' },
  { key: 'localAlbumCount', column: 'local', label: 'Local' },
  { key: 'provider', column: 'provider', label: 'Provider' },
]
const artistsScreenColumnWidthPreferenceKeys: Record<ArtistScreenColumnKey, string> = {
  name: 'artists-screen.artists-pane.name',
  country: 'artists-screen.artists-pane.country',
  status: 'artists-screen.artists-pane.status',
  albums: 'artists-screen.artists-pane.albums',
  unchecked: 'artists-screen.artists-pane.unchecked',
  local: 'artists-screen.artists-pane.local',
  provider: 'artists-screen.artists-pane.provider',
  action: 'artists-screen.artists-pane.action',
}
const artistsGridScrollTop = ref(0)
const artistsGridViewportHeight = ref(0)
let artistsPaneWidthObserver: ResizeObserver | null = null
let artistsPaneResizeActive = false
let artistSearchDebounceTimer: number | null = null
const scanIsRunning = computed(() => scanJob.value?.status === 'RUNNING')
const providerIsRunning = computed(() => providerJob.value?.status === 'RUNNING' || providerStatus.value.running)
const writeActionsDisabled = computed(() => scanIsRunning.value || providerIsRunning.value)

const filteredArtists = computed(() => {
  const needle = appliedArtistSearch.value.toLowerCase()
  const selectedCollections = new Set(artistCollectionFilterIds.value)
  return artists.value.filter((artist) => {
    if (needle && !artist.name.toLowerCase().includes(needle)) {
      return false
    }
    if (selectedCollections.size === 0) {
      return true
    }
    return artist.collectionIds.some((collectionId) => selectedCollections.has(collectionId))
  })
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
const bulkProviderChipLabelsVisible = computed(() =>
  artistsPaneWidths.artists === 0 || artistsPaneWidths.artists >= artistsBulkExpandedMinimumWidth,
)
const bulkMatchItems = computed(() => bulkMatchResult.value?.items ?? [])
const selectedFilterCollections = computed(() =>
  artistCollectionFilterIds.value
    .map((id) => collections.value.find((collection) => collection.id === id))
    .filter((collection): collection is MusicCollection => Boolean(collection)),
)
const visibleSelectedFilterCollections = computed(() => selectedFilterCollections.value.slice(0, 4))
const hiddenSelectedFilterCollectionCount = computed(() =>
  Math.max(0, selectedFilterCollections.value.length - visibleSelectedFilterCollections.value.length),
)
const allCollectionsFilterSelected = computed(() => artistCollectionFilterIds.value.length === 0)

const selectedArtist = computed(() => artists.value.find((artist) => artist.id === selectedArtistId.value) ?? null)
const selectedProvider = computed(() => selectedArtist.value ? providerForArtist(selectedArtist.value) : null)
const providerSelectionDirty = computed(() =>
  providerDefinitions.some((provider) =>
    providerSelectionId(selectedProviderCandidateIds, provider.id) !== providerSelectionId(originalProviderCandidateIds, provider.id),
  ),
)
const artistDetailsDirty = computed(() => {
  const artist = selectedArtist.value
  if (!artist) {
    return false
  }
  return artistDetailsForm.name.trim() !== artist.name
    || (artistDetailsForm.sortName.trim() || '') !== (artist.sortName ?? '')
})
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
const providerConflictArtistIds = computed(() =>
  new Set([
    ...providerReleaseDateConflicts.value.map((conflict) => conflict.artistId),
    ...providerTitleConflicts.value.map((conflict) => conflict.artistId),
  ]),
)
const providerConflictArtist = computed(() =>
  artists.value.find((artist) => artist.id === providerConflictArtistId.value)
    ?? selectedArtist.value
    ?? null,
)
const providerConflictLinkedProviders = computed(() => {
  const artist = providerConflictArtist.value
  return artist ? providersForArtist(artist) : []
})
const providerConflictLinkedProviderIds = computed(() =>
  new Set(providerConflictLinkedProviders.value.map((provider) => provider.providerId)),
)
const providerConflictSections = computed(() => {
  const artistId = providerConflictArtist.value?.id ?? null
  if (!artistId) {
    return []
  }
  const sections = new Map<string, ProviderConflictSection>()
  providerTitleConflicts.value
    .filter((conflict) => conflict.artistId === artistId)
    .forEach((conflict) => addProviderConflictVariant(sections, 'title', conflict))
  providerReleaseDateConflicts.value
    .filter((conflict) => conflict.artistId === artistId)
    .forEach((conflict) => addProviderConflictVariant(sections, 'release-date', conflict))
  return [...sections.values()]
    .map((section) => ({
      ...section,
      variants: section.variants
        .map((variant) => ({
          ...variant,
          providers: variant.providers.filter((providerId) => providerConflictLinkedProviderIds.value.has(providerId)),
        }))
        .filter((variant) => variant.providers.length > 0),
    }))
    .filter((section) => section.variants.length > 0)
})
function compareArtistRows(left: Artist, right: Artist) {
  const leftValue = artistSortValue(left, artistSort.key)
  const rightValue = artistSortValue(right, artistSort.key)
  const result = typeof leftValue === 'number' && typeof rightValue === 'number'
    ? compareNumber(leftValue, rightValue)
    : compareText(String(leftValue), String(rightValue))
  return applyDirection(result || compareText(left.name, right.name), artistSort.direction)
}

function artistSortValue(artist: Artist, key: ArtistSortKey) {
  if (key === 'country') return artistCountryName(artist)
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

function normalizeArtistSearch(value: string | null | undefined) {
  return value?.trim() ?? ''
}

function applyArtistSearch(value: string | null | undefined) {
  const normalized = normalizeArtistSearch(value)
  if (appliedArtistSearch.value === normalized) {
    return
  }
  appliedArtistSearch.value = normalized
}

function scheduleArtistSearchApply(value: string | null | undefined) {
  if (artistSearchDebounceTimer !== null) {
    window.clearTimeout(artistSearchDebounceTimer)
    artistSearchDebounceTimer = null
  }
  const normalized = normalizeArtistSearch(value)
  if (appliedArtistSearch.value === normalized) {
    return
  }
  artistSearchDebounceTimer = window.setTimeout(() => {
    artistSearchDebounceTimer = null
    applyArtistSearch(value)
  }, artistSearchDebounceMs)
}

function clearArtistSearch() {
  if (artistSearchDebounceTimer !== null) {
    window.clearTimeout(artistSearchDebounceTimer)
    artistSearchDebounceTimer = null
  }
  artistSearchText.value = ''
  applyArtistSearch('')
}

function setAllCollectionFilter() {
  updateArtistCollectionFilter([])
}

function toggleCollectionFilter(collectionId: string) {
  const selected = new Set(artistCollectionFilterIds.value)
  if (selected.has(collectionId)) {
    selected.delete(collectionId)
  } else {
    selected.add(collectionId)
  }
  updateArtistCollectionFilter([...selected])
}

function collectionFilterSelected(collectionId: string) {
  return artistCollectionFilterIds.value.includes(collectionId)
}

function removeCollectionFilter(collectionId: string) {
  updateArtistCollectionFilter(artistCollectionFilterIds.value.filter((id) => id !== collectionId))
}

function updateArtistCollectionFilter(collectionIds: string[]) {
  artistCollectionFilterIds.value = [...new Set(collectionIds)].filter(Boolean)
  void saveArtistsCollectionFilter()
}

function pruneArtistCollectionFilter() {
  if (artistCollectionFilterIds.value.length === 0) {
    return
  }
  const knownCollections = new Set(collections.value.map((collection) => collection.id))
  const current = artistCollectionFilterIds.value.filter((collectionId) => knownCollections.has(collectionId))
  if (current.length !== artistCollectionFilterIds.value.length) {
    updateArtistCollectionFilter(current)
  }
}

async function loadArtistsCollectionFilter() {
  const preference = await store.loadPreference(artistsCollectionFilterPreferenceKey)
  if (!preference?.value) {
    return
  }
  try {
    const parsed = JSON.parse(preference.value)
    if (Array.isArray(parsed)) {
      artistCollectionFilterIds.value = parsed.filter((value): value is string => typeof value === 'string')
    }
  } catch (error) {
    artistCollectionFilterIds.value = []
  }
}

async function saveArtistsCollectionFilter() {
  await store.savePreference(artistsCollectionFilterPreferenceKey, JSON.stringify(artistCollectionFilterIds.value))
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

function providerConflictDisplayYear(releaseDate: string | null | undefined) {
  return releaseDateYearLabel(releaseDate) || 'No year'
}

function addProviderConflictVariant(
  sections: Map<string, ProviderConflictSection>,
  kind: ProviderConflictKind,
  conflict: ProviderReleaseDateConflict | ProviderTitleConflict,
) {
  const providerValue = kind === 'title'
    ? conflict.providerTitle
    : providerConflictDisplayYear(conflict.providerReleaseDate)
  const localValue = kind === 'title'
    ? providerConflictLocalTitle(conflict)
    : providerConflictDisplayYear(conflict.localReleaseDate)
  const sectionKey = `${kind}:${conflict.albumId}`
  const section = sections.get(sectionKey) ?? {
    key: sectionKey,
    kind,
    albumId: conflict.albumId,
    localAlbum: conflict.localRelativePath || conflict.albumTitle,
    localValue,
    variants: [],
  }
  const variantKey = `${sectionKey}:${normalizeConflictValue(providerValue)}`
  const current = section.variants.find((variant) => variant.key === variantKey)
  const providers = providerConflictProviders(conflict)
  if (current) {
    current.providers = mergeProviderIds(current.providers, providers)
    sections.set(sectionKey, section)
    return
  }
  section.variants.push({
    key: variantKey,
    conflict,
    providerValue,
    providers,
  })
  sections.set(sectionKey, section)
}

function providerConflictLocalTitle(conflict: ProviderReleaseDateConflict | ProviderTitleConflict) {
  if (conflict.albumTitle) {
    return conflict.albumTitle
  }
  return providerConflictTitleFromPath(conflict.localRelativePath) || 'No title'
}

function providerConflictTitleFromPath(path: string | null | undefined) {
  const value = path?.trim()
  if (!value) {
    return ''
  }
  const parts = value.split(/[\\/]/).filter(Boolean)
  const leaf = parts[parts.length - 1] ?? value
  const match = leaf.match(/^\s*.+?\s+-\s+\d{4}(?:-\d{2}(?:-\d{2})?)?\s+-\s+(.+?)\s*$/)
  return match?.[1]?.trim() ?? leaf
}

function normalizeConflictValue(value: string | null | undefined) {
  return (value ?? '').trim().toLowerCase().replace(/\s+/g, ' ')
}

function providerConflictProviders(conflict: ProviderReleaseDateConflict | ProviderTitleConflict) {
  const sources = conflict.sources?.length
    ? conflict.sources
    : [{ providerId: conflict.providerId }]
  return mergeProviderIds([], sources.map((source) => source.providerId as ProviderId))
}

function mergeProviderIds(left: ProviderId[], right: ProviderId[]) {
  const order = new Map(providerDefinitions.map((provider, index) => [provider.id, index]))
  return [...new Set([...left, ...right])]
    .sort((a, b) => (order.get(a) ?? 99) - (order.get(b) ?? 99))
}

function visibleConflictProviders(variant: ProviderConflictVariant) {
  return variant.providers.slice(0, 3)
}

function hiddenConflictProviderCount(variant: ProviderConflictVariant) {
  return Math.max(0, variant.providers.length - visibleConflictProviders(variant).length)
}

function providerConflictProviderUrl(providerId: ProviderId) {
  return providerConflictLinkedProviders.value
    .find((provider) => provider.providerId === providerId)
    ?.providerUrl
}

function providerConflictChipTooltip(providerId: ProviderId) {
  return `Open artist page in ${providerDefinition(providerId).label}`
}

function providerConflictTypeLabel(kind: ProviderConflictKind) {
  return kind === 'title' ? 'Title' : 'Release Date'
}

function providerConflictTotalLabel() {
  const count = providerConflictSections.value.length
  return `${count} conflict${count === 1 ? '' : 's'}`
}

function providerConflictSectionVariantLabel(section: ProviderConflictSection) {
  const count = section.variants.length
  return `${count} ${providerConflictTypeLabel(section.kind)} variant${count === 1 ? '' : 's'}`
}

function providerConflictSectionOpen(section: ProviderConflictSection) {
  return openProviderConflictSectionKey.value === section.key
}

function openProviderConflictSection(section: ProviderConflictSection) {
  openProviderConflictSectionKey.value = section.key
}

function syncOpenProviderConflictAlbum() {
  const sections = providerConflictSections.value
  if (sections.length === 0) {
    openProviderConflictSectionKey.value = null
    return
  }
  if (!sections.some((section) => section.key === openProviderConflictSectionKey.value)) {
    openProviderConflictSectionKey.value = sections[0].key
  }
}

async function openArtistProviderConflicts(artist: Artist) {
  selectedArtistId.value = artist.id
  providerConflictArtistId.value = artist.id
  openProviderConflictSectionKey.value = null
  try {
    await Promise.all([
      store.loadProviderConflicts(),
      store.loadArtistProvider(artist.id),
    ])
    syncOpenProviderConflictAlbum()
    providerConflictDialog.value = true
  } catch (error) {
    store.showErrorStatus(error, 'Unable to load provider conflicts')
  }
}

async function chooseProviderConflict(section: ProviderConflictSection, variant: ProviderConflictVariant | null) {
  if (providerConflictActionKey.value) {
    return
  }
  const side = variant ? 'provider' : 'local'
  providerConflictActionKey.value = variant ? `${side}:${variant.key}` : `${side}:${section.key}`
  try {
    if (section.kind === 'title') {
      const conflict = (variant?.conflict ?? section.variants[0]?.conflict) as ProviderTitleConflict | undefined
      if (!conflict) {
        return
      }
      if (variant) {
        await store.useProviderTitle(conflict)
      } else {
        await store.keepLocalTitle(conflict)
      }
    } else {
      if (variant) {
        await store.useProviderReleaseDate(variant.conflict as ProviderReleaseDateConflict)
      } else {
        for (const currentVariant of section.variants) {
          await store.keepLocalReleaseDate(currentVariant.conflict as ProviderReleaseDateConflict)
        }
      }
    }
    const artistName = providerConflictArtist.value?.name ?? section.variants[0]?.conflict.artistName ?? 'artist'
    store.showStatus(`Resolved provider conflict for ${artistName}.`, 'done')
    if (providerConflictSections.value.length === 0) {
      providerConflictDialog.value = false
    }
  } catch (error) {
    store.showErrorStatus(error, 'Unable to resolve provider conflict')
  } finally {
    providerConflictActionKey.value = ''
  }
}

function releaseDateYearsDiffer(localReleaseDate: string | null | undefined, providerReleaseDate: string | null | undefined) {
  const localYear = releaseDateYearLabel(localReleaseDate)
  const providerYear = releaseDateYearLabel(providerReleaseDate)
  return localYear !== '' && providerYear !== '' && localYear !== providerYear
}

function artistHasProviderConflict(artist: Artist) {
  return providerConflictArtistIds.value.has(artist.id)
}

function albumReleaseDateConflictLinks(album: Album) {
  return album.providerLinks.filter((link) => link.releaseDateConflict)
}

function albumHasReleaseDateConflict(album: Album) {
  return albumReleaseDateConflictLinks(album).length > 0
}

function albumKeptLocalReleaseDateLink(album: Album) {
  return album.providerLinks.find((link) => (
    link.releaseDateResolution === 'KEEP_LOCAL'
    && releaseDateYearsDiffer(album.releaseDate, link.providerReleaseDate)
  )) ?? null
}

function albumKeptLocalReleaseDateLinks(album: Album) {
  return album.providerLinks.filter((link) => (
    link.releaseDateResolution === 'KEEP_LOCAL'
    && releaseDateYearsDiffer(album.releaseDate, link.providerReleaseDate)
  ))
}

function albumReleaseDateChipClasses(album: Album) {
  const hasConflict = albumHasReleaseDateConflict(album)
  return {
    'release-date-chip--warning': hasConflict,
    'release-date-chip--action': hasConflict,
    'release-date-chip--kept-local': !hasConflict && albumKeptLocalReleaseDateLinks(album).length > 0,
  }
}

function albumReleaseDateConflictTooltip(album: Album) {
  const links = albumReleaseDateConflictLinks(album)
  if (links.length === 0) {
    return ''
  }
  const localYear = releaseDateYearLabel(album.releaseDate) || 'unknown'
  return `Local year ${localYear} conflicts with provider ${providerYearSourceSummary(links)}.`
}

function albumHasKeptLocalReleaseDate(album: Album) {
  return albumKeptLocalReleaseDateLinks(album).length > 0
}

function keptLocalReleaseDateTooltip(album: Album) {
  const links = albumKeptLocalReleaseDateLinks(album)
  if (links.length === 0) {
    return ''
  }
  const localYear = releaseDateYearLabel(album.releaseDate) || 'unknown'
  return `Local year ${localYear} was kept instead of provider ${providerYearSourceSummary(links)}. Click to reset this decision and show the conflict again.`
}

function providerYearSourceSummary(links: Album['providerLinks']) {
  const groups = new Map<string, string[]>()
  links.forEach((link) => {
    const providerYear = releaseDateYearLabel(link.providerReleaseDate) || 'unknown year'
    const label = providerDefinition(link.providerId).label
    const labels = groups.get(providerYear) ?? []
    if (!labels.includes(label)) {
      labels.push(label)
    }
    groups.set(providerYear, labels)
  })
  const summaries = [...groups.entries()].map(([year, labels]) => (
    `${year} from ${providerListSummary(labels)}`
  ))
  return summaries.length === 1 ? `year ${summaries[0]}` : `years ${summaries.join('; ')}`
}

function providerListSummary(labels: string[]) {
  const visible = labels.slice(0, 2).join(', ')
  const hidden = labels.length - 2
  return hidden > 0 ? `${visible} (+${hidden} more)` : visible
}

function openAlbumReleaseDateConflict(album: Album, event?: MouseEvent | KeyboardEvent) {
  if (!albumHasReleaseDateConflict(album)) {
    return
  }
  event?.preventDefault()
  event?.stopPropagation()
  if (selectedArtist.value) {
    void openArtistProviderConflicts(selectedArtist.value)
  }
}

function keptLocalReleaseDateKey(album: Album) {
  const link = albumKeptLocalReleaseDateLink(album)
  return link ? `${album.id}:${link.id}` : `${album.id}:none`
}

function isResettingKeptLocalReleaseDate(album: Album) {
  return resettingKeepLocalReleaseDateKey.value === keptLocalReleaseDateKey(album)
}

async function resetKeptLocalReleaseDate(album: Album, event?: MouseEvent) {
  event?.preventDefault()
  event?.stopPropagation()
  if (event?.currentTarget instanceof HTMLElement) {
    event.currentTarget.blur()
  }
  const link = albumKeptLocalReleaseDateLink(album)
  if (!link) {
    return
  }
  const artistId = selectedArtist.value?.id ?? album.artistIds[0] ?? null
  resettingKeepLocalReleaseDateKey.value = keptLocalReleaseDateKey(album)
  try {
    await store.resetKeepLocalReleaseDate(album.id, link.id, artistId)
    store.showStatus(`Reset kept local year for ${album.title}.`, 'warning')
  } catch (error) {
    store.showErrorStatus(error, 'Unable to reset kept local release year')
  } finally {
    resettingKeepLocalReleaseDateKey.value = ''
  }
}

function artistKnownAlbumPresenceClass(album: Album) {
  const local = album.onDisk || album.hasLocalPath
  return {
    'album-presence-text--local': local,
    'album-presence-text--nonlocal-checked': !local && album.checked,
    'album-presence-text--nonlocal-unchecked': !local && !album.checked,
  }
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
  const paneWidth = artistsPaneWidths.artists
  const grid = document.querySelector('.artists-table-pane .workspace-grid')
  if (grid instanceof HTMLElement) {
    return grid.clientWidth
  }
  return paneWidth > 0 ? Math.max(0, paneWidth - 2) : 0
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
  return artistsScreenSortableColumnMinimumWidth
}

function showArtistsScreenActionLabels() {
  return artistsScreenRightmostColumnAvailableWidth() >= artistsScreenActionColumnWidths.labeled
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
    const nextPercents = normalizeArtistsPanePercents([left, 100 - left])
    artistsPanePercents.value = nextPercents
    artistsPaneWidths.artists = Math.round((paneAreaWidth * nextPercents[0]) / 100)
    artistsPaneWidths.details = Math.round((paneAreaWidth * nextPercents[1]) / 100)
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
  const raw = [Math.max(420, artistsScreenMinimumGridWidth(), artistsSearchControlsMinimumWidth), 280]
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

function markArtistSelected(artist: Artist) {
  if (deletingArtistId.value === artist.id) {
    return
  }
  selectedArtistId.value = artist.id
}

function syncArtistDetailsForm(artist: Artist | null) {
  artistDetailsForm.id = artist?.id ?? null
  artistDetailsForm.name = artist?.name ?? ''
  artistDetailsForm.sortName = artist?.sortName ?? ''
}

async function saveSelectedArtistDetails() {
  if (writeActionsDisabled.value || savingArtistDetails.value || !selectedArtist.value) {
    return
  }
  const name = artistDetailsForm.name.trim()
  if (!name) {
    return
  }
  savingArtistDetails.value = true
  try {
    await store.saveArtist({
      id: selectedArtist.value.id,
      name,
      sortName: artistDetailsForm.sortName.trim() || null,
      countryOverride: selectedArtist.value.countryOverride ?? null,
      activeOverride: selectedArtist.value.activeOverride ?? null,
    })
  } catch (error) {
    store.showErrorStatus(error, 'Unable to save artist')
  } finally {
    savingArtistDetails.value = false
  }
}

async function openProviderMatch(artist: Artist) {
  if (writeActionsDisabled.value) {
    return
  }
  selectedArtistId.value = artist.id
  const missing = providerActionsForArtist(artist)
  matchProviderId.value = missing[0]?.id ?? 'musicbrainz'
  matchingArtistId.value = artist.id
  resetProviderMatchState()
  primeProviderMatchState(artist)
  matchDialog.value = true
  try {
    await Promise.all(missing.map((provider) => loadProviderCandidatesForMatch(provider.id, false, false)))
  } finally {
    matchingArtistId.value = null
  }
}

function resetProviderMatchState() {
  providerDefinitions.forEach((provider) => {
    providerCandidatesById[provider.id] = []
    selectedProviderCandidateIds[provider.id] = null
    originalProviderCandidateIds[provider.id] = null
  })
  providerLoadingIds.value = []
  providerLoadedIds.value = []
}

function primeProviderMatchState(artist: Artist) {
  providerDefinitions.forEach((provider) => {
    const link = providerForArtist(artist, provider.id)
    if (!link?.providerArtistId) {
      return
    }
    const candidate = providerLinkCandidate(link)
    providerCandidatesById[provider.id] = [candidate]
    selectedProviderCandidateIds[provider.id] = candidate.providerArtistId
    originalProviderCandidateIds[provider.id] = candidate.providerArtistId
    addProviderLoadedId(provider.id)
  })
}

async function loadProviderCandidatesForMatch(providerId = matchProviderId.value, force = false, activate = true) {
  if (writeActionsDisabled.value) {
    return
  }
  const artistId = selectedArtistId.value
  if (!artistId) {
    return
  }
  const artist = artists.value.find((item) => item.id === artistId)
  if (activate) {
    matchProviderId.value = providerId
  }
  if (!artist) {
    return
  }
  if (!force && providerLoadedIds.value.includes(providerId)) {
    return
  }
  addProviderLoadingId(providerId)
  try {
    providerCandidatesById[providerId] = withAssociatedProviderCandidate(
      artist,
      providerId,
      await store.searchProviderCandidates(artistId, providerId),
    )
    clearMissingProviderSelection(providerId)
    addProviderLoadedId(providerId)
  } catch (error) {
    store.showErrorStatus(error, `Unable to search ${providerDefinition(providerId).label}`)
  } finally {
    removeProviderLoadingId(providerId)
  }
}

function addProviderLoadingId(providerId: ProviderId) {
  if (!providerLoadingIds.value.includes(providerId)) {
    providerLoadingIds.value = [...providerLoadingIds.value, providerId]
  }
}

function removeProviderLoadingId(providerId: ProviderId) {
  providerLoadingIds.value = providerLoadingIds.value.filter((id) => id !== providerId)
}

function addProviderLoadedId(providerId: ProviderId) {
  if (!providerLoadedIds.value.includes(providerId)) {
    providerLoadedIds.value = [...providerLoadedIds.value, providerId]
  }
}

function providerSelectionId(source: Partial<Record<ProviderId, string | null>>, providerId: ProviderId) {
  return source[providerId] ?? null
}

function providerLinkCandidate(link: ArtistProviderLink): ArtistProviderCandidate {
  return {
    providerId: link.providerId,
    providerArtistId: link.providerArtistId ?? link.providerUrl ?? String(link.id),
    providerArtistName: link.providerArtistName ?? link.artistName,
    providerUrl: link.providerUrl ?? '',
    country: link.providerCountry ?? null,
    disambiguation: link.providerDisambiguation ?? null,
    active: link.providerActive ?? null,
    providerScore: 100,
    matchScore: 100,
    finalScore: 100,
    nameScore: 100,
    albumEvidenceScore: 0,
    yearBonus: 0,
    evidenceSummary: 'Already associated',
    matchedLocalAlbums: [],
    albumEvidence: [],
    releaseGroups: [],
    albums: [],
  }
}

function withAssociatedProviderCandidate(
  artist: Artist,
  providerId: ProviderId,
  candidates: ArtistProviderCandidate[],
) {
  const link = providerForArtist(artist, providerId)
  if (!link?.providerArtistId) {
    return candidates
  }
  if (candidates.some((candidate) => candidate.providerArtistId === link.providerArtistId)) {
    return candidates
  }
  return [providerLinkCandidate(link), ...candidates]
}

function clearMissingProviderSelection(providerId: ProviderId) {
  const selectedId = providerSelectionId(selectedProviderCandidateIds, providerId)
  if (!selectedId) {
    return
  }
  if (!providerCandidatesById[providerId].some((candidate) => candidate.providerArtistId === selectedId)) {
    selectedProviderCandidateIds[providerId] = null
  }
}

function providerSelectionSaveMessage(savedCount: number, removedCount: number) {
  const artistName = selectedArtist.value?.name ?? 'artist'
  const parts = [
    savedCount > 0 ? `${savedCount} provider${savedCount === 1 ? '' : 's'} saved` : null,
    removedCount > 0 ? `${removedCount} provider${removedCount === 1 ? '' : 's'} removed` : null,
  ].filter(Boolean)
  return `Provider selections for ${artistName}: ${parts.join(', ')}.`
}

function toggleProviderCandidate(candidate: ArtistProviderCandidate) {
  const providerId = candidate.providerId as ProviderId
  selectedProviderCandidateIds[providerId] = selectedProviderCandidateIds[providerId] === candidate.providerArtistId
    ? null
    : candidate.providerArtistId
}

function selectedProviderCandidate(providerId: ProviderId) {
  const selectedId = providerSelectionId(selectedProviderCandidateIds, providerId)
  if (!selectedId) {
    return null
  }
  return providerCandidatesById[providerId]
    .find((candidate) => candidate.providerArtistId === selectedId) ?? null
}

async function saveProviderSelections() {
  if (writeActionsDisabled.value || assigningProviders.value) {
    return
  }
  const artistId = selectedArtistId.value
  if (!artistId || !providerSelectionDirty.value) {
    return
  }
  assigningProviders.value = true
  let savedCount = 0
  let removedCount = 0
  let shouldScan = false
  try {
    for (const provider of providerDefinitions) {
      const originalId = providerSelectionId(originalProviderCandidateIds, provider.id)
      const selectedId = providerSelectionId(selectedProviderCandidateIds, provider.id)
      if (originalId === selectedId) {
        continue
      }
      if (!selectedId) {
        if (originalId) {
          await store.clearArtistProvider(artistId, provider.id)
          removedCount += 1
        }
        continue
      }
      const candidate = selectedProviderCandidate(provider.id)
      if (!candidate) {
        continue
      }
      await store.saveArtistProvider(artistId, {
        providerId: candidate.providerId,
        providerArtistId: candidate.providerArtistId,
        providerArtistName: candidate.providerArtistName,
        providerCountry: candidate.country,
        providerDisambiguation: candidate.disambiguation,
        providerActive: candidate.active,
        providerUrl: candidate.providerUrl,
        enabled: true,
      })
      savedCount += 1
      shouldScan = true
    }
    matchDialog.value = false
    store.showStatus(providerSelectionSaveMessage(savedCount, removedCount), 'done')
    if (shouldScan) {
      await scanArtistProviderById(artistId)
    }
  } catch (error) {
    store.showErrorStatus(error, 'Unable to save provider selections')
  } finally {
    assigningProviders.value = false
  }
}

async function refreshAllProviderCandidates() {
  const artist = selectedArtist.value
  if (!artist) {
    return
  }
  matchingArtistId.value = artist.id
  const activeProviderId = matchProviderId.value
  try {
    await Promise.all(providerDefinitions.map((provider) => loadProviderCandidatesForMatch(provider.id, true, false)))
  } finally {
    matchProviderId.value = activeProviderId
    matchingArtistId.value = null
  }
}

async function startProviderSetup(artist: Artist) {
  if (writeActionsDisabled.value) {
    return
  }
  await selectArtist(artist)
  await openProviderMatch(artist)
}

async function runBulkProviderMatch(providerId: ProviderId) {
  const missing = visibleArtistsMissingProvider(providerId)
  if (writeActionsDisabled.value || bulkMatchLoadingProviderId.value || missing.length === 0) {
    return
  }
  const artistIds = missing.map((artist) => artist.id)
  bulkMatchLoadingProviderId.value = providerId
  bulkMatchResult.value = null
  try {
    bulkMatchResult.value = await store.bulkMatchProvider(providerId, artistIds)
    bulkMatchDialog.value = true
  } catch (error) {
    if (!store.providerStatus.message?.includes('bulk match failed')) {
      store.showErrorStatus(error, `${providerDefinition(providerId).label} bulk match failed`)
    }
  } finally {
    bulkMatchLoadingProviderId.value = null
  }
}

async function useBulkCandidate(item: ArtistProviderBulkMatchItem) {
  if (writeActionsDisabled.value) {
    return
  }
  const candidate = bulkCandidate(item)
  if (!candidate) {
    return
  }
  const provider = providerDefinition(candidate.providerId)
  try {
    const providerLink = await store.saveArtistProvider(item.artistId, {
      providerId: candidate.providerId,
      providerArtistId: candidate.providerArtistId,
      providerArtistName: candidate.providerArtistName,
      providerCountry: candidate.country,
      providerDisambiguation: candidate.disambiguation,
      providerActive: candidate.active,
      providerUrl: candidate.providerUrl,
      enabled: true,
    })
    if (!bulkMatchResult.value) {
      return
    }
    const previousStatus = item.status
    bulkMatchResult.value = {
      ...bulkMatchResult.value,
      matchedCount: bulkMatchResult.value.matchedCount + (previousStatus === 'MATCHED' ? 0 : 1),
      manualCount: bulkMatchResult.value.manualCount - (previousStatus === 'NEEDS_MANUAL' ? 1 : 0),
      noMatchCount: bulkMatchResult.value.noMatchCount - (previousStatus === 'NO_MATCH' ? 1 : 0),
      skippedCount: bulkMatchResult.value.skippedCount - (previousStatus === 'SKIPPED_EXISTING' ? 1 : 0),
      errorCount: bulkMatchResult.value.errorCount - (previousStatus === 'ERROR' ? 1 : 0),
      items: bulkMatchResult.value.items.map((current) => current.artistId === item.artistId
        ? {
            ...current,
            status: 'MATCHED',
            message: `${provider.label} provider saved: ${candidate.providerArtistName}`,
            providerLink,
            acceptedCandidate: candidate,
          }
        : current),
    }
    store.showStatus(`${provider.label} provider saved for ${candidate.providerArtistName}.`, 'done')
  } catch (error) {
    store.showErrorStatus(error, `Unable to save ${provider.label} provider`)
  }
}

function bulkCandidate(item: ArtistProviderBulkMatchItem) {
  return item.acceptedCandidate ?? item.candidates[0] ?? null
}

function bulkEvidenceText(item: ArtistProviderBulkMatchItem) {
  const candidate = bulkCandidate(item)
  if (!candidate) {
    return ''
  }
  const provider = providerDefinition(candidate.providerId)
  return `${candidate.evidenceSummary} / ${candidate.providerScore} ${provider.label}`
}

function bulkStatusText(status: ArtistProviderBulkMatchItem['status']) {
  switch (status) {
    case 'MATCHED':
      return 'Matched'
    case 'NEEDS_MANUAL':
      return 'Needs manual'
    case 'NO_MATCH':
      return 'No match'
    case 'SKIPPED_EXISTING':
      return 'Skipped'
    case 'ERROR':
      return 'Error'
    default:
      return status
  }
}

function bulkStatusColor(status: ArtistProviderBulkMatchItem['status']) {
  switch (status) {
    case 'MATCHED':
      return 'success'
    case 'NEEDS_MANUAL':
      return 'warning'
    case 'SKIPPED_EXISTING':
      return 'info'
    case 'ERROR':
      return 'error'
    default:
      return 'default'
  }
}

async function clearArtistProvider(artist: Artist, providerId?: string | null) {
  selectedArtistId.value = artist.id
  if (writeActionsDisabled.value) {
    return
  }
  try {
    await store.clearArtistProvider(artist.id, providerId)
    store.showStatus(`Provider cleared for ${artist.name}.`, 'done')
  } catch (error) {
    store.showErrorStatus(error, 'Unable to clear provider')
  }
}

async function scanArtistProvider(artist: Artist) {
  selectedArtistId.value = artist.id
  await scanArtistProviderById(artist.id)
}

async function scanArtistProviderById(artistId: number) {
  if (writeActionsDisabled.value) {
    return
  }
  try {
    await store.runProviderArtistJob(artistId, null)
  } catch (error) {
    if (!store.providerJob?.message?.startsWith('Provider check failed')) {
      store.showErrorStatus(error, 'Provider check failed')
    }
  }
}

async function saveArtistCountryOverride(artist: Artist, value: string | null) {
  if (writeActionsDisabled.value) {
    return
  }
  const code = normalizeCountryCode(value)
  try {
    await store.saveArtist({
      id: artist.id,
      name: artist.name,
      sortName: artist.sortName ?? null,
      countryOverride: code,
      activeOverride: artist.activeOverride ?? null,
    })
  } catch (error) {
    store.showErrorStatus(error, 'Unable to save artist country')
  }
}

function setCountryEditorOpen(artist: Artist, open: boolean) {
  if (open) {
    openCountryEditor(artist)
    return
  }
  closeCountryEditor()
}

function openCountryEditor(artist: Artist) {
  if (writeActionsDisabled.value) {
    return
  }
  selectedArtistId.value = artist.id
  countryEditorArtistId.value = artist.id
  countryEditorSearch.value = ''
}

function closeCountryEditor() {
  countryEditorArtistId.value = null
  countryEditorSearch.value = ''
}

function filteredCountryOptions() {
  const needle = countryEditorSearch.value.trim().toLowerCase()
  if (!needle) {
    return countryOptions
  }
  return countryOptions.filter((country) =>
    country.name.toLowerCase().includes(needle)
    || country.code.toLowerCase().includes(needle),
  )
}

async function selectArtistCountryOverride(artist: Artist, value: string | null) {
  await saveArtistCountryOverride(artist, value)
  closeCountryEditor()
}

async function clearArtistCountryOverride(artist: Artist) {
  selectedArtistId.value = artist.id
  await saveArtistCountryOverride(artist, null)
}

async function saveArtistActiveOverride(artist: Artist, active: boolean | null) {
  if (writeActionsDisabled.value) {
    return
  }
  try {
    await store.saveArtist({
      id: artist.id,
      name: artist.name,
      sortName: artist.sortName ?? null,
      countryOverride: artist.countryOverride ?? null,
      activeOverride: active,
    })
  } catch (error) {
    store.showErrorStatus(error, 'Unable to save artist status')
  }
}

function providersForArtist(artist: Artist) {
  const cached = providerLinks.value[artist.id]
  if (cached) {
    return cached
  }
  if (artist.providerLinks?.length) {
    return artist.providerLinks
  }
  if (!artist.providerId) {
    return []
  }
  return [legacyProviderLink(artist)]
}

function providerForArtist(artist: Artist, providerId?: string | null) {
  const providers = providersForArtist(artist)
  if (providerId) {
    return providers.find((provider) => provider.providerId === providerId) ?? null
  }
  return providers[0] ?? null
}

function legacyProviderLink(artist: Artist): ArtistProviderLink {
  return {
    id: 0,
    artistId: artist.id,
    artistName: artist.name,
    providerId: artist.providerId ?? 'musicbrainz',
    providerArtistId: artist.providerArtistId,
    providerArtistName: artist.providerArtistName,
    providerCountry: artist.providerCountry,
    providerDisambiguation: artist.providerDisambiguation,
    providerActive: artist.providerActive,
    providerUrl: artist.providerUrl,
    enabled: true,
    lastErrorMessage: artist.providerLastErrorMessage ?? null,
    createdAt: artist.createdAt,
    updatedAt: artist.updatedAt,
  }
}

function visibleArtistsMissingProvider(providerId: ProviderId) {
  return sortedArtists.value.filter((artist) => !providerForArtist(artist, providerId))
}

function missingProviderCount(providerId: ProviderId) {
  return visibleArtistsMissingProvider(providerId).length
}

function providerActionsForArtist(artist: Artist) {
  const linked = new Set(providersForArtist(artist).map((provider) => provider.providerId))
  return providerDefinitions.filter((provider) => !linked.has(provider.id))
}

function providerChipText(artist: Artist) {
  const providers = providersForArtist(artist)
  return providers.length
    ? providers.map((provider) => providerDefinition(provider.providerId).label).join(', ')
    : 'None'
}

function providerLinkChipText(provider: ArtistProviderLink) {
  return providerDefinition(provider.providerId).label
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

function artistProviderExternalUrl(provider: ArtistProviderLink | null | undefined) {
  return providerExternalArtistUrl(provider?.providerId, provider?.providerUrl)
}

function artistCountry(artist: Artist) {
  return artist.countryOverride ?? providerCountry(artist)
}

function providerCountry(artist: Artist) {
  return providerForArtist(artist)?.providerCountry ?? artist.providerCountry ?? null
}

function artistCountryName(artist: Artist) {
  return countryName(artistCountry(artist))
}

function artistCountryFlagSrc(artist: Artist) {
  return countryFlagSrc(artistCountry(artist))
}

function providerCountryName(artist: Artist) {
  return countryName(providerCountry(artist))
}

function providerCountryFlagSrcForArtist(artist: Artist) {
  return countryFlagSrc(providerCountry(artist))
}

function artistCountryHasOverride(artist: Artist) {
  return Boolean(normalizeCountryCode(artist.countryOverride))
}

function artistStatus(artist: Artist) {
  return activeStatusLabel(artistActive(artist))
}

function activeStatusLabel(active: boolean | null | undefined) {
  if (active === true) {
    return 'Active'
  }
  if (active === false) {
    return 'Split-up'
  }
  return 'Unknown'
}

function artistActive(artist: Artist) {
  return artist.activeOverride ?? providerActive(artist)
}

function providerActive(artist: Artist) {
  return providerForArtist(artist)?.providerActive ?? artist.providerActive ?? null
}

function artistActiveHasOverride(artist: Artist) {
  return artist.activeOverride !== null && artist.activeOverride !== undefined
}

function statusChipColor(active: boolean | null | undefined) {
  if (active === true) {
    return 'success'
  }
  if (active === false) {
    return 'error'
  }
  return 'default'
}

function openExternal(url?: string | null) {
  if (url) {
    window.open(url, '_blank', 'noopener')
  }
}

function askDeleteArtist(artist: Artist) {
  selectedArtistId.value = artist.id
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
    lines.push(`Local albums on disk: ${artist.localAlbumCount}`)
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
    loadArtistsCollectionFilter(),
    store.loadAll(),
  ])
  pruneArtistCollectionFilter()
  await nextTick()
  setupArtistsPaneWidthObserver()
})

onBeforeUnmount(() => {
  artistsPaneResizeActive = false
  document.body.classList.remove('is-pane-resizing')
  document.body.classList.remove('is-column-resizing')
  artistsPaneWidthObserver?.disconnect()
  artistsPaneWidthObserver = null
  if (artistSearchDebounceTimer !== null) {
    window.clearTimeout(artistSearchDebounceTimer)
    artistSearchDebounceTimer = null
  }
  if (artistsPaneLayoutSaveTimer.value !== null) {
    saveArtistsPaneLayout()
  }
})

watch(artistSearchText, (value) => {
  scheduleArtistSearchApply(value)
})

watch(appliedArtistSearch, () => {
  resetArtistsGridScroll()
})

watch(artistCollectionFilterIds, () => {
  resetArtistsGridScroll()
})

watch([() => artistSort.key, () => artistSort.direction], () => {
  resetArtistsGridScroll()
})

watch(sortedArtists, (currentArtists) => {
  if (selectedArtistId.value !== null && !currentArtists.some((artist) => artist.id === selectedArtistId.value)) {
    selectedArtistId.value = null
  }
})

watch(selectedArtist, (artist) => {
  syncArtistDetailsForm(artist)
}, { immediate: true })

watch(providerConflictSections, () => {
  syncOpenProviderConflictAlbum()
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
          <div class="pane-header__actions artists-header-actions">
            <div class="artists-bulk-match-controls artists-bulk-match-controls--header">
              <span class="artists-bulk-match-label">Bulk match missing providers</span>
              <v-tooltip
                v-for="provider in providerDefinitions"
                :key="provider.id"
                :text="`Bulk match ${provider.label}`"
                location="top"
              >
                <template #activator="{ props }">
                  <ProviderChip
                    v-bind="props"
                    :provider-id="provider.id"
                    :show-label="bulkProviderChipLabelsVisible"
                    :count="missingProviderCount(provider.id)"
                    action
                    :compact="!bulkProviderChipLabelsVisible"
                    :disabled="writeActionsDisabled || missingProviderCount(provider.id) === 0 || Boolean(bulkMatchLoadingProviderId)"
                    @click="runBulkProviderMatch(provider.id)"
                  >
                    <template #prepend>
                      <v-progress-circular
                        v-if="bulkMatchLoadingProviderId === provider.id"
                        indeterminate
                        size="14"
                        width="2"
                        class="provider-action-chip__spinner"
                      ></v-progress-circular>
                        <img
                          v-else-if="provider.iconSrc"
                          class="provider-chip__icon"
                          :src="provider.iconSrc"
                          alt=""
                        aria-hidden="true"
                      >
                    </template>
                  </ProviderChip>
                </template>
              </v-tooltip>
            </div>
          </div>
        </div>

        <div class="pane-filter-bar artists-search-bar">
          <v-text-field
            v-model="artistSearchText"
            class="artists-search-field"
            prepend-inner-icon="mdi-magnify"
            density="compact"
            label="Search artists"
            aria-label="Search artists"
            hide-details
            @keydown.esc.prevent.stop="clearArtistSearch"
          >
            <template #append-inner>
              <v-btn
                v-if="artistSearchText"
                icon="mdi-close"
                size="x-small"
                density="compact"
                variant="text"
                class="artists-search-clear"
                aria-label="Clear artist search"
                @mousedown.prevent
                @click.stop="clearArtistSearch"
              ></v-btn>
            </template>
          </v-text-field>
        </div>

        <div class="artists-collection-filter-row">
          <span class="artists-collection-filter-row__label">Collections</span>
          <v-chip
            size="small"
            :color="allCollectionsFilterSelected ? 'primary' : undefined"
            :variant="allCollectionsFilterSelected ? 'flat' : 'tonal'"
            class="artists-collection-filter-chip"
            @click="setAllCollectionFilter"
          >
            All
          </v-chip>
          <v-chip
            v-for="collection in visibleSelectedFilterCollections"
            :key="collection.id"
            size="small"
            color="primary"
            variant="tonal"
            closable
            close-icon="mdi-close"
            class="artists-collection-filter-chip"
            @click:close.stop="removeCollectionFilter(collection.id)"
          >
            {{ collection.name }}
          </v-chip>
          <v-chip
            v-if="hiddenSelectedFilterCollectionCount > 0"
            size="small"
            variant="tonal"
            class="artists-collection-filter-chip"
          >
            +{{ hiddenSelectedFilterCollectionCount }}
          </v-chip>
          <v-menu
            v-model="collectionFilterMenu"
            :close-on-content-click="false"
            location="bottom end"
          >
            <template #activator="{ props }">
              <v-btn
                v-bind="props"
                icon="mdi-plus"
                size="x-small"
                density="compact"
                variant="text"
                color="primary"
                class="artists-collection-filter-add"
                aria-label="Choose collections"
              ></v-btn>
            </template>
            <v-card class="artists-collection-filter-menu">
              <v-list density="compact">
                <v-list-item
                  v-for="collection in collections"
                  :key="collection.id"
                  @click="toggleCollectionFilter(collection.id)"
                >
                  <template #prepend>
                    <v-checkbox
                      :model-value="collectionFilterSelected(collection.id)"
                      density="compact"
                      hide-details
                      @click.stop="toggleCollectionFilter(collection.id)"
                    ></v-checkbox>
                  </template>
                  <v-list-item-title>{{ collection.name }}</v-list-item-title>
                </v-list-item>
              </v-list>
            </v-card>
          </v-menu>
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
            @click.capture="markArtistSelected(artist)"
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
                <v-tooltip
                  v-if="artistHasProviderConflict(artist)"
                  text="Unresolved provider conflict"
                  location="top"
                >
                  <template #activator="{ props }">
                    <v-icon
                      v-bind="props"
                      icon="mdi-alert"
                      size="16"
                      color="warning"
                      class="provider-conflict-triangle artist-release-conflict-icon"
                    ></v-icon>
                  </template>
                </v-tooltip>
                <v-tooltip :text="artist.name" location="top">
                  <template #activator="{ props }">
                    <span v-bind="props" class="cell-strong">{{ artist.name }}</span>
                  </template>
                </v-tooltip>
              </div>
            </div>
            <div data-column="artists.country" class="workspace-grid__cell">
              <v-menu
                :model-value="countryEditorArtistId === artist.id"
                location="bottom start"
                origin="top start"
                :disabled="writeActionsDisabled"
                :close-on-content-click="false"
                @update:model-value="(open) => setCountryEditorOpen(artist, open)"
              >
                <template #activator="{ props }">
                  <div
                    v-bind="props"
                    class="artist-country-cell"
                    :class="{
                      'artist-country-cell--disabled': writeActionsDisabled,
                      'artist-country-cell--override': artistCountryHasOverride(artist),
                    }"
                    @click.stop="openCountryEditor(artist)"
                    @mousedown.stop
                  >
                    <span v-if="artistCountry(artist)" class="country-select-selection artist-country-cell__value">
                      <img
                        v-if="artistCountryFlagSrc(artist)"
                        class="country-flag"
                        :src="artistCountryFlagSrc(artist)"
                        alt=""
                        aria-hidden="true"
                      >
                      <span>{{ artistCountryName(artist) }}</span>
                    </span>
                    <span v-else class="cell-muted artist-country-cell__value">Unknown</span>
                    <v-btn
                      v-if="artistCountryHasOverride(artist)"
                      icon="mdi-close"
                      size="x-small"
                      density="compact"
                      variant="text"
                      class="artist-country-cell__clear"
                      :disabled="writeActionsDisabled"
                      @click.stop="clearArtistCountryOverride(artist)"
                      @mousedown.stop
                    ></v-btn>
                  </div>
                </template>
                <div class="artist-country-menu" @click.stop @mousedown.stop>
                  <v-text-field
                    v-model="countryEditorSearch"
                    autofocus
                    density="compact"
                    hide-details
                    label="Search country"
                    prepend-inner-icon="mdi-magnify"
                    variant="outlined"
                  ></v-text-field>
                  <v-list class="artist-country-menu__list" density="compact">
                    <v-list-item
                      v-for="country in filteredCountryOptions()"
                      :key="country.code"
                      :active="normalizeCountryCode(artistCountry(artist)) === country.code"
                      :subtitle="country.code"
                      :title="country.name"
                      @click="selectArtistCountryOverride(artist, country.code)"
                    >
                      <template #prepend>
                        <img class="country-flag country-flag--menu" :src="country.flagSrc" alt="" aria-hidden="true">
                      </template>
                    </v-list-item>
                    <v-list-item v-if="filteredCountryOptions().length === 0" title="No countries found"></v-list-item>
                  </v-list>
                </div>
              </v-menu>
            </div>
            <div data-column="artists.status" class="workspace-grid__cell">
              <v-menu
                location="bottom start"
                origin="top start"
                :disabled="writeActionsDisabled"
                :close-on-content-click="true"
              >
                <template #activator="{ props }">
                  <div class="artist-status-control" @click.stop @mousedown.stop>
                    <v-chip
                      v-bind="props"
                      size="x-small"
                      :color="statusChipColor(artistActive(artist))"
                      variant="tonal"
                      class="artist-status-chip"
                      :class="{ 'artist-status-chip--override': artistActiveHasOverride(artist) }"
                      :disabled="writeActionsDisabled"
                    >
                      {{ artistStatus(artist) }}
                    </v-chip>
                  </div>
                </template>
                <div class="artist-status-menu">
                  <v-chip
                    size="small"
                    color="success"
                    :variant="artistActive(artist) === true ? 'flat' : 'tonal'"
                    :disabled="writeActionsDisabled"
                    @click="saveArtistActiveOverride(artist, true)"
                  >
                    Active
                  </v-chip>
                  <v-chip
                    size="small"
                    color="error"
                    :variant="artistActive(artist) === false ? 'flat' : 'tonal'"
                    :disabled="writeActionsDisabled"
                    @click="saveArtistActiveOverride(artist, false)"
                  >
                    Split-up
                  </v-chip>
                  <v-chip
                    size="small"
                    variant="tonal"
                    :disabled="writeActionsDisabled || !artistActiveHasOverride(artist)"
                    @click="saveArtistActiveOverride(artist, null)"
                  >
                    <v-icon icon="mdi-close" size="14" start></v-icon>
                    Clear
                  </v-chip>
                </div>
              </v-menu>
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
              <div v-if="providersForArtist(artist).length" class="artists-provider-list">
                <v-tooltip
                  v-for="provider in providersForArtist(artist)"
                  :key="provider.providerId"
                  :text="providerLinkChipText(provider)"
                  location="top"
                >
                  <template #activator="{ props }">
                    <ProviderChip
                      v-bind="props"
                      :provider-id="provider.providerId"
                      :label="providerLinkChipText(provider)"
                      :show-label="providersForArtist(artist).length === 1"
                      :compact-row="providersForArtist(artist).length > 1"
                      :error="Boolean(provider.lastErrorMessage)"
                      closable
                      close-icon="mdi-trash-can-outline"
                      :disabled="writeActionsDisabled"
                      @click.stop="scanArtistProvider(artist)"
                      @click:close.stop="clearArtistProvider(artist, provider.providerId)"
                    ></ProviderChip>
                  </template>
                </v-tooltip>
              </div>
              <span v-else class="cell-muted">None</span>
            </div>
            <div data-column="artists.action" class="workspace-grid__cell row-action-cell">
              <div class="row-actions">
                <v-tooltip
                  v-if="artistHasProviderConflict(artist)"
                  :text="`Resolve conflicts for ${artist.name}`"
                  location="top"
                >
                  <template #activator="{ props }">
                    <v-btn
                      v-bind="props"
                      prepend-icon="mdi-alert-outline"
                      size="x-small"
                      variant="text"
                      color="warning"
                      :class="artistScreenRowActionClass()"
                      :disabled="writeActionsDisabled || deletingArtistId === artist.id"
                      @click.stop="openArtistProviderConflicts(artist)"
                    >
                      <span v-if="showArtistsScreenActionLabels()">Conflicts</span>
                    </v-btn>
                  </template>
                </v-tooltip>
                <v-tooltip :text="`Add providers for ${artist.name}`" location="top">
                  <template #activator="{ props }">
                    <v-btn
                      v-bind="props"
                      prepend-icon="mdi-link-plus"
                      size="x-small"
                      variant="text"
                      color="primary"
                      :class="artistScreenRowActionClass()"
                      :loading="matchingArtistId === artist.id && providerLoadingIds.length > 0"
                      :disabled="writeActionsDisabled || deletingArtistId === artist.id"
                      @click.stop="startProviderSetup(artist)"
                    >
                      <span v-if="showArtistsScreenActionLabels()">Add providers</span>
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
            <span v-if="selectedArtist" class="pane-header__meta artist-details-conflict-name">
              <span>{{ selectedArtist.name }}</span>
              <v-tooltip
                v-if="artistHasProviderConflict(selectedArtist)"
                text="Unresolved provider conflict"
                location="top"
              >
                <template #activator="{ props }">
                  <v-icon
                    v-bind="props"
                    icon="mdi-alert"
                    size="15"
                    color="warning"
                    class="provider-conflict-triangle artist-details-conflict-name__icon"
                  ></v-icon>
                </template>
              </v-tooltip>
            </span>
          </div>
        </div>

        <div v-if="!selectedArtist" class="pane-empty">Select an artist.</div>
        <div v-else class="artist-details-pane__body">
          <section class="artist-details-section">
            <div class="artist-details-heading">Identity</div>
            <div class="artist-identity-form">
              <v-text-field
                v-model="artistDetailsForm.name"
                label="Name"
                density="compact"
                variant="outlined"
                hide-details="auto"
                :disabled="writeActionsDisabled"
              ></v-text-field>
              <v-text-field
                v-model="artistDetailsForm.sortName"
                label="Sort name"
                density="compact"
                variant="outlined"
                hide-details="auto"
                :disabled="writeActionsDisabled"
              ></v-text-field>
              <v-btn
                size="small"
                color="primary"
                variant="tonal"
                :loading="savingArtistDetails"
                :disabled="writeActionsDisabled || !artistDetailsDirty || !artistDetailsForm.name.trim()"
                @click="saveSelectedArtistDetails"
              >
                Save
              </v-btn>
            </div>
          </section>

          <section class="artist-details-section">
            <div class="artist-details-heading">{{ selectedArtist.name }}</div>
            <div class="artist-info-grid">
              <div class="cell-muted">Country</div>
              <div class="artist-source-value" :class="{ 'cell-muted': !artistCountry(selectedArtist) }">
                <img
                  v-if="artistCountryFlagSrc(selectedArtist)"
                  class="country-flag"
                  :src="artistCountryFlagSrc(selectedArtist)"
                  alt=""
                  aria-hidden="true"
                >
                <span>{{ artistCountryName(selectedArtist) }}</span>
                <v-tooltip v-if="artistCountryHasOverride(selectedArtist)" text="Clear country override" location="top">
                  <template #activator="{ props }">
                    <v-btn
                      v-bind="props"
                      icon="mdi-close"
                      size="x-small"
                      density="compact"
                      variant="text"
                      class="artist-override-clear"
                      :disabled="writeActionsDisabled"
                      @click="saveArtistCountryOverride(selectedArtist, null)"
                    ></v-btn>
                  </template>
                </v-tooltip>
                <span v-if="artistCountryHasOverride(selectedArtist) && providerCountry(selectedArtist)" class="artist-provider-source">
                  (provider:
                  <img
                    v-if="providerCountryFlagSrcForArtist(selectedArtist)"
                    class="country-flag"
                    :src="providerCountryFlagSrcForArtist(selectedArtist)"
                    alt=""
                    aria-hidden="true"
                  >
                  {{ providerCountryName(selectedArtist) }})
                </span>
              </div>
              <div class="cell-muted">Status</div>
              <div class="artist-source-value" :class="{ 'cell-muted': artistStatus(selectedArtist) === 'Unknown' }">
                <v-chip size="x-small" :color="statusChipColor(artistActive(selectedArtist))" variant="tonal">
                  {{ artistStatus(selectedArtist) }}
                </v-chip>
                <v-tooltip v-if="artistActiveHasOverride(selectedArtist)" text="Clear status override" location="top">
                  <template #activator="{ props }">
                    <v-btn
                      v-bind="props"
                      icon="mdi-close"
                      size="x-small"
                      density="compact"
                      variant="text"
                      class="artist-override-clear"
                      :disabled="writeActionsDisabled"
                      @click="saveArtistActiveOverride(selectedArtist, null)"
                    ></v-btn>
                  </template>
                </v-tooltip>
                <span v-if="artistActiveHasOverride(selectedArtist) && providerActive(selectedArtist) !== null && providerActive(selectedArtist) !== undefined" class="artist-provider-source">
                  (provider:
                  <v-chip size="x-small" :color="statusChipColor(providerActive(selectedArtist))" variant="tonal">
                    {{ activeStatusLabel(providerActive(selectedArtist)) }}
                  </v-chip>)
                </span>
              </div>
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
              <div class="cell-muted">Providers</div>
              <div>
                <div v-if="providersForArtist(selectedArtist).length" class="dialog-chip-row">
                  <ProviderChip
                    v-for="provider in providersForArtist(selectedArtist)"
                    :key="provider.providerId"
                    :provider-id="provider.providerId"
                    :label="providerLinkChipText(provider)"
                    :error="Boolean(provider.lastErrorMessage)"
                  ></ProviderChip>
                </div>
                <span v-else class="cell-muted">None</span>
              </div>
            </div>
          </section>

          <section v-if="selectedProvider" class="artist-details-section artist-provider-section">
            <a
              v-if="selectedProvider.providerUrl && providerIdentityLabel()"
              class="artist-link artist-link--name"
              :href="artistProviderExternalUrl(selectedProvider)"
              target="_blank"
              rel="noopener noreferrer"
            >
              {{ providerIdentityLabel() }}
            </a>
            <div v-else-if="providerIdentityLabel()" class="cell-strong">{{ providerIdentityLabel() }}</div>
            <a
              v-if="selectedProvider.providerUrl"
              class="mono-path artist-link artist-link--url"
              :href="artistProviderExternalUrl(selectedProvider)"
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
              @click="openExternal(artistProviderExternalUrl(selectedProvider))"
            >
              Open
            </v-btn>
          </section>

          <section class="artist-details-section">
            <div class="panel-title">Known Albums</div>
            <div v-if="selectedAlbums.length === 0" class="cell-muted">No known albums.</div>
            <div v-else class="artist-known-albums">
              <div v-for="album in selectedAlbums" :key="album.id" class="artist-known-album">
                <span class="artist-known-album__year">
                  <span v-if="releaseDateYearLabel(album.releaseDate)" class="release-date-chip-badge">
                    <v-chip
                      class="release-date-chip artist-known-album__year-chip"
                      :class="albumReleaseDateChipClasses(album)"
                      variant="tonal"
                      @click="openAlbumReleaseDateConflict(album, $event)"
                    >
                      {{ releaseDateYearLabel(album.releaseDate) }}
                    </v-chip>
                    <v-tooltip
                      v-if="albumHasReleaseDateConflict(album)"
                      :text="albumReleaseDateConflictTooltip(album)"
                      location="top"
                      :open-on-click="false"
                    >
                      <template #activator="{ props }">
                        <v-icon
                          v-bind="props"
                          icon="mdi-alert"
                          size="13"
                          color="warning"
                          class="provider-conflict-triangle release-date-chip-badge__icon"
                          @click.stop="openAlbumReleaseDateConflict(album, $event)"
                        ></v-icon>
                      </template>
                    </v-tooltip>
                    <v-tooltip
                      v-if="albumHasKeptLocalReleaseDate(album) && !isResettingKeptLocalReleaseDate(album)"
                      :text="keptLocalReleaseDateTooltip(album)"
                      location="top"
                      :open-on-click="false"
                    >
                      <template #activator="{ props }">
                        <v-btn
                          v-bind="props"
                          icon="mdi-undo-variant"
                          size="x-small"
                          variant="text"
                          color="warning"
                          class="release-date-chip-badge__reset"
                          :disabled="resettingKeepLocalReleaseDateKey !== ''"
                          @mousedown.stop.prevent
                          @click.stop.prevent="resetKeptLocalReleaseDate(album, $event)"
                        ></v-btn>
                      </template>
                    </v-tooltip>
                  </span>
                  <span v-else class="cell-muted">No date</span>
                </span>
                <v-tooltip :text="album.title" location="top">
                  <template #activator="{ props }">
                    <span
                      v-bind="props"
                      class="artist-known-album__title"
                      :class="artistKnownAlbumPresenceClass(album)"
                    >
                      {{ album.title }}
                    </span>
                  </template>
                </v-tooltip>
              </div>
            </div>
          </section>
        </div>
      </v-sheet>
    </div>

    <ProviderMatchDialog
      v-model="matchDialog"
      v-model:provider-id="matchProviderId"
      multi-provider
      :candidates="[]"
      :candidates-by-provider="providerCandidatesById"
      :loading-provider-ids="providerLoadingIds"
      :loaded-provider-ids="providerLoadedIds"
      :selected-provider-candidate-ids="selectedProviderCandidateIds"
      :artist-name="selectedArtist?.name ?? null"
      :loading="providerLoadingIds.length > 0"
      :saving="assigningProviders"
      :disabled="writeActionsDisabled"
      :save-disabled="!providerSelectionDirty"
      :show-url="false"
      url=""
      url-validation=""
      empty-text="No candidates found."
      assign-label="Save"
      @select-provider="loadProviderCandidatesForMatch"
      @toggle-candidate="toggleProviderCandidate"
      @refresh-all="refreshAllProviderCandidates"
      @assign-selected="saveProviderSelections"
      @open-external="openExternal"
      @close="resetProviderMatchState"
    />

    <v-dialog
      v-model="providerConflictDialog"
      max-width="none"
      :content-props="{ class: 'provider-conflict-overlay' }"
    >
      <v-card class="dialog-card provider-conflict-dialog">
        <v-card-title class="provider-conflict-dialog__title">
          <span>{{ providerConflictArtist?.name ?? 'Artist' }}</span>
          <span class="provider-conflict-count-chip provider-conflict-count-chip--title">{{ providerConflictTotalLabel() }}</span>
        </v-card-title>
        <v-card-text>
          <div
            v-if="providerConflictLinkedProviders.length"
            class="provider-conflict-linked-providers dialog-chip-row"
          >
            <ProviderChip
              v-for="provider in providerConflictLinkedProviders"
              :key="provider.providerId"
              :provider-id="provider.providerId"
              :external-url="provider.providerUrl"
              open-external
            ></ProviderChip>
          </div>
          <div v-if="providerConflictSections.length === 0" class="pane-empty pane-empty--compact">
            No unresolved provider conflicts.
          </div>
          <div v-else class="provider-conflict-dialog__body">
            <section
              v-for="section in providerConflictSections"
              :key="section.key"
              class="provider-conflict-album-group"
              :class="{ 'provider-conflict-album-group--open': providerConflictSectionOpen(section) }"
            >
              <button
                type="button"
                class="provider-conflict-album-group__header"
                @click="openProviderConflictSection(section)"
              >
                <span class="provider-conflict-album-group__name mono-path">{{ section.localAlbum }}</span>
                <span class="provider-conflict-count-chip">{{ providerConflictSectionVariantLabel(section) }}</span>
              </button>

              <div
                v-if="providerConflictSectionOpen(section)"
                class="provider-conflict-album-group__body"
              >
                <div class="provider-conflict-choice-grid">
                  <button
                    type="button"
                    class="provider-conflict-choice provider-conflict-choice--local"
                    :disabled="Boolean(providerConflictActionKey)"
                    @click="chooseProviderConflict(section, null)"
                  >
                    <span class="provider-conflict-choice__label">Local</span>
                    <span class="provider-conflict-choice__value">{{ section.localValue }}</span>
                  </button>
                  <button
                    v-for="variant in section.variants"
                    :key="variant.key"
                    type="button"
                    class="provider-conflict-choice provider-conflict-choice--provider"
                    :disabled="Boolean(providerConflictActionKey)"
                    @click="chooseProviderConflict(section, variant)"
                  >
                    <span class="provider-conflict-choice__source-row">
                      <v-tooltip
                        v-for="providerId in visibleConflictProviders(variant)"
                        :key="providerId"
                        :text="providerConflictChipTooltip(providerId)"
                        location="top"
                      >
                        <template #activator="{ props }">
                          <ProviderChip
                            v-bind="props"
                            :provider-id="providerId"
                            :external-url="providerConflictProviderUrl(providerId)"
                            :show-label="false"
                            class="provider-conflict-source-chip"
                            open-external
                            @click.stop
                          ></ProviderChip>
                        </template>
                      </v-tooltip>
                      <v-chip
                        v-if="hiddenConflictProviderCount(variant) > 0"
                        size="small"
                        variant="tonal"
                      >
                        +{{ hiddenConflictProviderCount(variant) }}
                      </v-chip>
                    </span>
                    <span class="provider-conflict-choice__value">{{ variant.providerValue }}</span>
                  </button>
                </div>
              </div>
            </section>
          </div>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn variant="text" @click="providerConflictDialog = false">Close</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog v-model="bulkMatchDialog" max-width="1100">
      <v-card class="dialog-card">
        <v-card-title>Bulk Provider Match</v-card-title>
        <v-card-text class="edit-form">
          <div v-if="bulkMatchResult" class="dialog-chip-row">
            <v-chip size="small" color="success" variant="tonal">{{ bulkMatchResult.matchedCount }} matched</v-chip>
            <v-chip size="small" color="warning" variant="tonal">{{ bulkMatchResult.manualCount }} manual</v-chip>
            <v-chip size="small" variant="tonal">{{ bulkMatchResult.noMatchCount }} no match</v-chip>
            <v-chip size="small" color="info" variant="tonal">{{ bulkMatchResult.skippedCount }} skipped</v-chip>
            <v-chip size="small" color="error" variant="tonal">{{ bulkMatchResult.errorCount }} errors</v-chip>
          </div>

          <div class="bulk-match-dialog__table">
            <v-table class="music-table" density="compact">
              <thead>
                <tr>
                  <th>Artist</th>
                  <th>Status</th>
                  <th>Candidate</th>
                  <th>Evidence</th>
                  <th>Message</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in bulkMatchItems" :key="item.artistId">
                  <td class="cell-strong">{{ item.artistName || `artist ${item.artistId}` }}</td>
                  <td>
                    <v-chip size="x-small" :color="bulkStatusColor(item.status)" variant="tonal">
                      {{ bulkStatusText(item.status) }}
                    </v-chip>
                  </td>
                  <td>
                    <template v-if="bulkCandidate(item)">
                      <div class="cell-strong">{{ bulkCandidate(item)?.providerArtistName }}</div>
                      <div class="mono-path">{{ bulkCandidate(item)?.providerArtistId }}</div>
                    </template>
                    <span v-else class="cell-muted">None</span>
                  </td>
                  <td>{{ bulkEvidenceText(item) }}</td>
                  <td>{{ item.message }}</td>
                  <td class="text-right">
                    <v-btn
                      size="small"
                      variant="text"
                      icon="mdi-open-in-new"
                      :disabled="!bulkCandidate(item)?.providerUrl"
                      @click="openExternal(providerExternalArtistUrl(bulkCandidate(item)?.providerId, bulkCandidate(item)?.providerUrl))"
                    ></v-btn>
                    <v-btn
                      v-if="item.status === 'NEEDS_MANUAL'"
                      size="small"
                      color="primary"
                      variant="text"
                      :disabled="!bulkCandidate(item) || writeActionsDisabled"
                      @click="useBulkCandidate(item)"
                    >
                      Use
                    </v-btn>
                  </td>
                </tr>
              </tbody>
            </v-table>
          </div>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn variant="text" @click="bulkMatchDialog = false">Close</v-btn>
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
