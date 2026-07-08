import { defineStore } from 'pinia'
import { apiGet, apiSend } from '@/api'
import { formatDateWithJavaPattern } from '@/dateFormat'
import { providerDefinition, type ProviderId } from '@/providers'
import type {
  Album,
  AlbumReleaseDateConflictPlan,
  AlbumReleaseDateConflictResult,
  AlbumTitleConflictPlan,
  AlbumTitleConflictResult,
  Artist,
  ArtistProviderBulkMatchResult,
  ArtistProviderCandidate,
  ArtistProviderLink,
  CollectionFolderCandidate,
  CollectionMetadata,
  MusicRootInfo,
  MusicCollection,
  ProviderCheckJobStatus,
  ProviderReleaseDateConflict,
  ProviderTitleConflict,
  ReportArtifact,
  ScanJobStatus,
  StatusHistoryEntry,
  UiSettings,
  UiSettingsValues,
  UserPreference,
} from '@/types'

interface ArtistPayload {
  id?: number
  name: string
  sortName?: string | null
  countryOverride?: string | null
  activeOverride?: boolean | null
}

interface ProviderLinkPayload {
  id?: number
  providerId: string
  providerArtistId?: string | null
  providerArtistName?: string | null
  providerCountry?: string | null
  providerDisambiguation?: string | null
  providerActive?: boolean | null
  providerUrl?: string | null
  enabled: boolean
}

interface State {
  artists: Artist[]
  albums: Album[]
  collections: MusicCollection[]
  collectionCandidates: CollectionFolderCandidate[]
  collectionArtists: Artist[]
  collectionAlbums: Album[]
  collectionTitleItems: Album[]
  collectionArtistsByCollection: Record<string, Artist[]>
  collectionArtistsLoading: Record<string, boolean>
  collectionAlbumsByArtist: Record<string, Album[]>
  collectionAlbumsLoading: Record<string, boolean>
  collectionTitleItemsByCollection: Record<string, Album[]>
  collectionTitleItemsLoading: Record<string, boolean>
  collectionMetadata: Record<string, CollectionMetadata>
  collectionMetadataLoading: Record<string, boolean>
  selectedCollectionId: string | null
  selectedArtistId: number | null
  musicRoot: MusicRootInfo | null
  scanJob: ScanJobStatus | null
  providerLinks: Record<number, ArtistProviderLink[]>
  providerJob: ProviderCheckJobStatus | null
  providerReleaseDateConflicts: ProviderReleaseDateConflict[]
  providerTitleConflicts: ProviderTitleConflict[]
  uiSettings: UiSettings
  statusHistory: StatusHistoryEntry[]
  manualStatus: { id: number; message: string; state: Exclude<StatusHistoryEntry['state'], 'running'> } | null
  providerStatus: {
    running: boolean
    message: string | null
    state: StatusHistoryEntry['state']
    reports?: ReportArtifact[]
  }
  loading: boolean
}

function withQuery(path: string, params: Record<string, string | number | boolean | null | undefined>) {
  const query = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== null && value !== undefined && value !== '') {
      query.set(key, String(value))
    }
  })
  const suffix = query.toString()
  return suffix ? `${path}?${suffix}` : path
}

const defaultWorkspaceColumnDefaults = {
  artist: {
    name: 280,
  },
  album: {
    name: 360,
    releaseDate: 140,
    checked: 120,
    collections: 180,
    action: 136,
  },
  title: {
    title: 460,
    artist: 220,
    releaseDate: 150,
    action: 178,
  },
}

const defaultArtistsScreenColumnDefaults = {
  name: 250,
  country: 190,
  status: 110,
  albums: 68,
  unchecked: 86,
  local: 64,
  provider: 180,
  action: 126,
}

let scanJobPoller: number | null = null
let scanJobPollingActive = false
let providerJobPoller: number | null = null

export const useLibraryStore = defineStore('library', {
  state: (): State => ({
    artists: [],
    albums: [],
    collections: [],
    collectionCandidates: [],
    collectionArtists: [],
    collectionAlbums: [],
    collectionTitleItems: [],
    collectionArtistsByCollection: {},
    collectionArtistsLoading: {},
    collectionAlbumsByArtist: {},
    collectionAlbumsLoading: {},
    collectionTitleItemsByCollection: {},
    collectionTitleItemsLoading: {},
    collectionMetadata: {},
    collectionMetadataLoading: {},
    selectedCollectionId: null,
    selectedArtistId: null,
    musicRoot: null,
    scanJob: null,
    providerLinks: {},
    providerJob: null,
    providerReleaseDateConflicts: [],
    providerTitleConflicts: [],
    uiSettings: {
      statusCompleteVisibleMs: 10000,
      scanPollIntervalMs: 200,
      artistScanSpinnerEnabled: true,
      providerBatchRescanDelayMinutes: 60,
      statusHistoryDateFormat: 'yyyy-MM-dd HH:mm:ss.SSS',
      releaseDateDisplayFormat: 'yyyy-MM-dd',
      statusBarLocation: 'top',
      workspaceColumnDefaults: defaultWorkspaceColumnDefaults,
      artistsScreenColumnDefaults: defaultArtistsScreenColumnDefaults,
      tableGridColumnMinWidth: 40,
      defaults: {
        statusCompleteVisibleMs: 10000,
        scanPollIntervalMs: 200,
        artistScanSpinnerEnabled: true,
        providerBatchRescanDelayMinutes: 60,
        statusHistoryDateFormat: 'yyyy-MM-dd HH:mm:ss.SSS',
        releaseDateDisplayFormat: 'yyyy-MM-dd',
        statusBarLocation: 'top',
        workspaceColumnDefaults: defaultWorkspaceColumnDefaults,
        artistsScreenColumnDefaults: defaultArtistsScreenColumnDefaults,
        tableGridColumnMinWidth: 40,
      },
      overrides: {
        statusCompleteVisibleMs: false,
        scanPollIntervalMs: false,
        artistScanSpinnerEnabled: false,
        providerBatchRescanDelayMinutes: false,
        statusHistoryDateFormat: false,
        releaseDateDisplayFormat: false,
        statusBarLocation: false,
      },
    },
    statusHistory: [],
    manualStatus: null,
    providerStatus: {
      running: false,
      message: null,
      state: 'info',
    },
    loading: false,
  }),
  actions: {
    async loadAll() {
      this.loading = true
      try {
        const [artists, albums, collections, musicRoot, providerReleaseDateConflicts, providerTitleConflicts] = await Promise.all([
          apiGet<Artist[]>('/api/artists'),
          apiGet<Album[]>('/api/albums'),
          apiGet<MusicCollection[]>('/api/collections'),
          apiGet<MusicRootInfo>('/api/settings/music-root'),
          apiGet<ProviderReleaseDateConflict[]>('/api/provider-conflicts/release-dates'),
          apiGet<ProviderTitleConflict[]>('/api/provider-conflicts/titles'),
        ])
        this.artists = artists
        this.albums = albums
        this.collections = collections
        this.musicRoot = musicRoot
        this.providerReleaseDateConflicts = providerReleaseDateConflicts
        this.providerTitleConflicts = providerTitleConflicts
      } catch (error) {
        this.showErrorStatus(error, 'Unable to load library data')
      } finally {
        this.loading = false
      }
    },
    async loadCollections() {
      this.loading = true
      try {
        this.collections = await apiGet<MusicCollection[]>('/api/collections')
      } catch (error) {
        this.showErrorStatus(error, 'Unable to load collections')
      } finally {
        this.loading = false
      }
    },
    async loadCollectionCandidates() {
      this.collectionCandidates = await apiGet<CollectionFolderCandidate[]>('/api/collections/candidates')
    },
    async createCollection(relativePath: string) {
      const collection = await apiSend<MusicCollection>('/api/collections', 'POST', { relativePath })
      this.collections = [...this.collections, collection].sort((left, right) => left.name.localeCompare(right.name))
      this.collectionCandidates = this.collectionCandidates.filter((candidate) => candidate.relativePath !== relativePath)
      return collection
    },
    async updateCollection(collectionId: string, payload: { name?: string; type?: MusicCollection['type'] }) {
      const previous = this.collections.find((item) => item.id === collectionId)
      const collection = await apiSend<MusicCollection>(`/api/collections/${encodeURIComponent(collectionId)}`, 'PUT', {
        name: payload.name,
        type: payload.type,
      })
      this.collections = this.collections.map((item) => (item.id === collection.id ? collection : item))
      if (this.selectedCollectionId === collection.id && previous?.type !== collection.type) {
        this.invalidateCollectionContent(collection.id)
        await this.selectCollection(collection.id)
      }
      return collection
    },
    async deleteCollection(collectionId: string) {
      const wasSelected = this.selectedCollectionId === collectionId
      await apiSend(`/api/collections/${encodeURIComponent(collectionId)}`, 'DELETE')
      this.collections = this.collections.filter((collection) => collection.id !== collectionId)
      this.invalidateCollectionMetadata()
      this.invalidateCollectionContent()
      await this.loadArtists()
      if (wasSelected) {
        this.selectedCollectionId = null
        this.selectedArtistId = null
        this.collectionArtists = []
        this.collectionAlbums = []
        this.collectionTitleItems = []
      } else {
        await this.refreshCollectionContext()
      }
    },
    invalidateCollectionContent(collectionId?: string) {
      if (!collectionId) {
        this.collectionArtistsByCollection = {}
        this.collectionTitleItemsByCollection = {}
        this.collectionAlbumsByArtist = {}
        return
      }
      const { [collectionId]: _artists, ...artistRest } = this.collectionArtistsByCollection
      const { [collectionId]: _titles, ...titleRest } = this.collectionTitleItemsByCollection
      this.collectionArtistsByCollection = artistRest
      this.collectionTitleItemsByCollection = titleRest
      this.collectionAlbumsByArtist = {}
    },
    async loadCollectionMetadata(collectionId: string, force = false) {
      if (!force && this.collectionMetadata[collectionId]) {
        return this.collectionMetadata[collectionId]
      }
      if (this.collectionMetadataLoading[collectionId]) {
        return this.collectionMetadata[collectionId] ?? null
      }
      this.collectionMetadataLoading = { ...this.collectionMetadataLoading, [collectionId]: true }
      try {
        const metadata = await apiGet<CollectionMetadata>(`/api/collections/${encodeURIComponent(collectionId)}/metadata`)
        this.collectionMetadata = { ...this.collectionMetadata, [collectionId]: metadata }
        return metadata
      } finally {
        const { [collectionId]: _removed, ...rest } = this.collectionMetadataLoading
        this.collectionMetadataLoading = rest
      }
    },
    invalidateCollectionMetadata(collectionId?: string) {
      if (!collectionId) {
        this.collectionMetadata = {}
        this.collectionMetadataLoading = {}
        return
      }
      const { [collectionId]: _metadata, ...metadataRest } = this.collectionMetadata
      const { [collectionId]: _loading, ...loadingRest } = this.collectionMetadataLoading
      this.collectionMetadata = metadataRest
      this.collectionMetadataLoading = loadingRest
    },
    async loadSettings() {
      this.loading = true
      try {
        const [collections, musicRoot] = await Promise.all([
          apiGet<MusicCollection[]>('/api/collections'),
          apiGet<MusicRootInfo>('/api/settings/music-root'),
        ])
        this.collections = collections
        this.musicRoot = musicRoot
      } catch (error) {
        this.showErrorStatus(error, 'Unable to load settings')
      } finally {
        this.loading = false
      }
    },
    async loadUiSettings() {
      try {
        this.uiSettings = await apiGet<UiSettings>('/api/settings/ui')
      } catch (error) {
        this.showErrorStatus(error, 'Unable to load UI settings')
      }
      return this.uiSettings
    },
    async saveUiSettings(payload: Partial<UiSettingsValues>) {
      this.uiSettings = await apiSend<UiSettings>('/api/settings/ui', 'PUT', payload)
      return this.uiSettings
    },
    async resetUiSettings() {
      this.uiSettings = await apiSend<UiSettings>('/api/settings/ui', 'DELETE')
      return this.uiSettings
    },
    addStatusHistory(
      message: string,
      state: StatusHistoryEntry['state'] = 'info',
      options: { reports?: ReportArtifact[] } = {},
    ) {
      const reports = options.reports?.filter((report) => report.text) ?? []
      this.statusHistory = [
        ...this.statusHistory,
        {
          id: Date.now() * 1000 + Math.floor(Math.random() * 1000),
          createdAt: formatDateWithJavaPattern(new Date(), this.uiSettings.statusHistoryDateFormat),
          message,
          state,
          reports: reports.length ? reports : undefined,
        },
      ].slice(-100)
    },
    showStatus(message: string, state: Exclude<StatusHistoryEntry['state'], 'running'> = 'info') {
      this.manualStatus = {
        id: Date.now() * 1000 + Math.floor(Math.random() * 1000),
        message,
        state,
      }
    },
    showErrorStatus(error: unknown, prefix = 'Operation failed') {
      const detail = error instanceof Error ? error.message : String(error)
      this.showStatus(prefix ? `${prefix}: ${detail}` : detail, 'failed')
    },
    async loadArtists(search?: string) {
      this.artists = await apiGet<Artist[]>(withQuery('/api/artists', { search }))
    },
    async loadArtist(artistId: number, collectionId?: string | null) {
      return apiGet<Artist>(withQuery(`/api/artists/${artistId}`, { collectionId }))
    },
    async selectCollection(collectionId: string) {
      this.selectedCollectionId = collectionId
      this.selectedArtistId = null
      this.collectionAlbums = []
      const collection = this.collections.find((item) => item.id === collectionId)
      if (collection?.type === 'TITLE') {
        this.collectionArtists = []
        if (!hasCachedValue(this.collectionTitleItemsByCollection, collectionId)) {
          this.collectionTitleItems = []
        }
        await this.loadTitlesForSelectedCollection()
      } else {
        this.collectionTitleItems = []
        if (!hasCachedValue(this.collectionArtistsByCollection, collectionId)) {
          this.collectionArtists = []
        }
        await this.loadArtistsForSelectedCollection()
      }
    },
    async loadTitlesForSelectedCollection() {
      if (!this.selectedCollectionId) {
        this.collectionTitleItems = []
        return
      }
      await this.loadTitlesForCollection(this.selectedCollectionId)
    },
    async loadTitlesForCollection(collectionId: string, force = false, options: { clearCurrent?: boolean } = {}) {
      if (!force && hasCachedValue(this.collectionTitleItemsByCollection, collectionId)) {
        this.collectionTitleItems = this.collectionTitleItemsByCollection[collectionId]
        return this.collectionTitleItems
      }
      if (!force && this.collectionTitleItemsLoading[collectionId]) {
        return this.collectionTitleItemsByCollection[collectionId] ?? []
      }
      if (this.selectedCollectionId === collectionId && options.clearCurrent !== false) {
        this.collectionTitleItems = []
      }
      this.collectionTitleItemsLoading = { ...this.collectionTitleItemsLoading, [collectionId]: true }
      try {
        const titles = await apiGet<Album[]>(`/api/collections/${encodeURIComponent(collectionId)}/titles`)
        this.collectionTitleItemsByCollection = { ...this.collectionTitleItemsByCollection, [collectionId]: titles }
        if (this.selectedCollectionId === collectionId) {
          this.collectionTitleItems = titles
        }
        return titles
      } finally {
        const { [collectionId]: _removed, ...rest } = this.collectionTitleItemsLoading
        this.collectionTitleItemsLoading = rest
      }
    },
    async createTitleItem(collectionId: string, payload: { title: string; artistName?: string | null; releaseDate?: string | null; sortName?: string | null }) {
      const created = await apiSend<Album>(
        `/api/collections/${encodeURIComponent(collectionId)}/titles`,
        'POST',
        payload,
      )
      this.collectionTitleItems = [created, ...this.collectionTitleItems.filter((current) => current.id !== created.id)]
      this.collectionTitleItemsByCollection = {
        ...this.collectionTitleItemsByCollection,
        [collectionId]: [created, ...(this.collectionTitleItemsByCollection[collectionId] ?? []).filter((current) => current.id !== created.id)],
      }
      this.invalidateCollectionMetadata(collectionId)
      await this.refreshArtistsAfterScopedJob(created.artistIds, null)
      return created
    },
    async updateTitleItem(item: Album, payload: { title: string; artistName?: string | null; releaseDate?: string | null; sortName?: string | null }) {
      if (!this.selectedCollectionId) {
        return item
      }
      const updated = await apiSend<Album>(
        `/api/collections/${encodeURIComponent(this.selectedCollectionId)}/titles/${item.id}`,
        'PUT',
        payload,
      )
      this.collectionTitleItems = this.collectionTitleItems.map((current) => (current.id === updated.id ? updated : current))
      this.collectionTitleItemsByCollection = {
        ...this.collectionTitleItemsByCollection,
        [this.selectedCollectionId]: (this.collectionTitleItemsByCollection[this.selectedCollectionId] ?? this.collectionTitleItems)
          .map((current) => (current.id === updated.id ? updated : current)),
      }
      this.replaceCachedAlbum(updated)
      this.invalidateCollectionMetadata(this.selectedCollectionId)
      await this.refreshArtistsAfterScopedJob([...item.artistIds, ...updated.artistIds], null)
      return updated
    },
    async removeTitleFromSelectedCollection(item: Album) {
      if (!this.selectedCollectionId) {
        return
      }
      const collectionId = this.selectedCollectionId
      const updated = await apiSend<Album>(`/api/collections/${encodeURIComponent(collectionId)}/titles/${item.id}`, 'DELETE')
      this.replaceAlbum(updated)
      this.invalidateCollectionMetadata(collectionId)
      await this.refreshArtistsAfterScopedJob(updated.artistIds, null)
      await this.loadTitlesForCollection(collectionId, true)
      return updated
    },
    async loadArtistsForSelectedCollection() {
      if (!this.selectedCollectionId) {
        this.collectionArtists = []
        return
      }
      await this.loadArtistsForCollection(this.selectedCollectionId)
    },
    async loadArtistsForCollection(collectionId: string, force = false, options: { clearCurrent?: boolean } = {}) {
      if (!force && hasCachedValue(this.collectionArtistsByCollection, collectionId)) {
        this.collectionArtists = this.collectionArtistsByCollection[collectionId]
        this.clearInvalidSelectedArtist()
        return this.collectionArtists
      }
      if (!force && this.collectionArtistsLoading[collectionId]) {
        return this.collectionArtistsByCollection[collectionId] ?? []
      }
      if (this.selectedCollectionId === collectionId && options.clearCurrent !== false) {
        this.collectionArtists = []
      }
      this.collectionArtistsLoading = { ...this.collectionArtistsLoading, [collectionId]: true }
      try {
        const artists = await apiGet<Artist[]>(withQuery('/api/artists', { collectionId }))
        this.collectionArtistsByCollection = { ...this.collectionArtistsByCollection, [collectionId]: artists }
        if (this.selectedCollectionId === collectionId) {
          this.collectionArtists = artists
          this.clearInvalidSelectedArtist()
        }
        return artists
      } finally {
        const { [collectionId]: _removed, ...rest } = this.collectionArtistsLoading
        this.collectionArtistsLoading = rest
      }
    },
    clearInvalidSelectedArtist() {
      if (this.selectedArtistId && !this.collectionArtists.some((artist) => artist.id === this.selectedArtistId)) {
        this.selectedArtistId = null
        this.collectionAlbums = []
      }
    },
    async selectArtist(artistId: number) {
      this.selectedArtistId = artistId
      if (!hasCachedValue(this.collectionAlbumsByArtist, String(artistId))) {
        this.collectionAlbums = []
      }
      await this.loadAlbumsForSelectedArtist(this.scanJob?.status === 'RUNNING' || this.providerJob?.status === 'RUNNING')
    },
    async loadAlbumsForSelectedArtist(force = false) {
      if (!this.selectedCollectionId || !this.selectedArtistId) {
        this.collectionAlbums = []
        return
      }
      await this.loadAlbumsForArtist(this.selectedArtistId, force)
    },
    async refreshArtistAfterScanStep(artistId: number | null | undefined, collectionId?: string | null) {
      if (!artistId) {
        return
      }
      await this.refreshArtistAfterScopedJob(artistId, collectionId ?? this.selectedCollectionId)
    },
    async loadAlbumsForArtist(artistId: number, force = false, options: { clearCurrent?: boolean } = {}) {
      const cacheKey = String(artistId)
      if (!force && hasCachedValue(this.collectionAlbumsByArtist, cacheKey)) {
        this.collectionAlbums = this.collectionAlbumsByArtist[cacheKey]
        return this.collectionAlbums
      }
      if (!force && this.collectionAlbumsLoading[cacheKey]) {
        return this.collectionAlbumsByArtist[cacheKey] ?? []
      }
      if (this.selectedArtistId === artistId && options.clearCurrent !== false) {
        this.collectionAlbums = []
      }
      this.collectionAlbumsLoading = { ...this.collectionAlbumsLoading, [cacheKey]: true }
      try {
        const albums = await apiGet<Album[]>(
          withQuery('/api/albums', {
            artistId,
          }),
        )
        this.replaceGlobalAlbumsForArtists([artistId], albums)
        this.collectionAlbumsByArtist = { ...this.collectionAlbumsByArtist, [cacheKey]: albums }
        if (this.selectedArtistId === artistId) {
          this.collectionAlbums = albums
        }
        return albums
      } finally {
        const { [cacheKey]: _removed, ...rest } = this.collectionAlbumsLoading
        this.collectionAlbumsLoading = rest
      }
    },
    async refreshCollectionContext() {
      if (!this.selectedCollectionId) {
        return
      }
      const collection = this.collections.find((item) => item.id === this.selectedCollectionId)
      if (collection?.type === 'TITLE') {
        await this.loadTitlesForCollection(this.selectedCollectionId, true)
        return
      }
      await this.loadArtistsForCollection(this.selectedCollectionId, true)
      if (this.selectedArtistId) {
        await this.loadAlbumsForArtist(this.selectedArtistId, true)
      }
    },
    async refreshCollectionAfterScan(collectionId?: string | null) {
      this.invalidateCollectionMetadata(collectionId ?? undefined)
      this.invalidateCollectionContent(collectionId ?? undefined)
      await this.loadCollections()
      await this.loadArtists()
      if (!collectionId || this.selectedCollectionId !== collectionId) {
        return
      }
      const collection = this.collections.find((item) => item.id === collectionId)
      if (collection?.type === 'TITLE') {
        await this.loadTitlesForCollection(collectionId, true, { clearCurrent: false })
        return
      }
      await this.loadArtistsForCollection(collectionId, true, { clearCurrent: false })
      if (this.selectedArtistId) {
        await this.loadAlbumsForArtist(this.selectedArtistId, true, { clearCurrent: false })
      }
    },
    async refreshCollectionArtistsOnly(clearArtistSelection = false) {
      if (!this.selectedCollectionId) {
        return
      }
      if (clearArtistSelection) {
        this.selectedArtistId = null
      }
      this.collectionAlbums = []
      const collection = this.collections.find((item) => item.id === this.selectedCollectionId)
      if (collection?.type === 'TITLE') {
        await this.loadTitlesForCollection(this.selectedCollectionId, true)
      } else {
        await this.loadArtistsForCollection(this.selectedCollectionId, true)
      }
    },
    async addArtist(name: string) {
      await this.saveArtist({ name, sortName: null, countryOverride: null, activeOverride: null })
      await this.loadArtists()
    },
    async saveArtist(payload: ArtistPayload) {
      const body = {
        name: payload.name,
        sortName: payload.sortName ?? null,
        countryOverride: payload.countryOverride ?? null,
        activeOverride: payload.activeOverride ?? null,
      }
      const artist = payload.id
        ? await apiSend<Artist>(`/api/artists/${payload.id}`, 'PUT', body)
        : await apiSend<Artist>('/api/artists', 'POST', body)
      if (payload.id) {
        this.replaceArtist(artist)
        this.replaceCollectionArtist(artist, this.currentCollectionScopeForArtist(artist.id))
        return artist
      }
      await this.loadArtists()
      this.invalidateCollectionContent()
      await this.refreshCollectionContext()
      this.invalidateCollectionMetadata()
      return artist
    },
    async addAlbum(artistId: number, title: string, releaseDate: string | null, checked: boolean) {
      const album = await apiSend<Album>('/api/albums', 'POST', { artistId, title, releaseDate, checked })
      this.albums = [album, ...this.albums.filter((item) => item.id !== album.id)]
      this.invalidateAlbumCacheForArtist(artistId)
      await this.refreshArtistAfterScopedJob(artistId, null)
      await this.refreshCollectionContext()
    },
    async updateAlbum(album: Album) {
      const previous = this.findCachedAlbum(album.id)
      const updated = await apiSend<Album>(`/api/albums/${album.id}`, 'PUT', {
        title: album.title,
        releaseDate: album.releaseDate,
        checked: album.checked,
        notes: album.notes,
      })
      this.replaceAlbum(updated)
      this.updateArtistAlbumCheckCounts(previous, updated)
      this.invalidateCollectionMetadata()
      await this.loadProviderConflicts()
    },
    async addAlbumsToCollection(collectionId: string, albumIds: number[]) {
      if (albumIds.length === 0) {
        return []
      }
      const updated = await apiSend<Album[]>(
        `/api/collections/${encodeURIComponent(collectionId)}/albums`,
        'POST',
        { albumIds },
      )
      updated.forEach((album) => this.replaceAlbum(album))
      this.invalidateCollectionMetadata(collectionId)
      await this.refreshArtistsAfterScopedJob(
        updated.flatMap((album) => album.artistIds),
        collectionId,
      )
      await this.refreshCollectionContext()
      return updated
    },
    async removeAlbumFromSelectedCollection(album: Album) {
      if (!this.selectedCollectionId) {
        return album
      }
      const collectionId = this.selectedCollectionId
      const updated = await apiSend<Album>(
        `/api/collections/${encodeURIComponent(collectionId)}/albums/${album.id}`,
        'DELETE',
      )
      this.replaceAlbum(updated)
      this.invalidateCollectionMetadata(collectionId)
      await this.refreshArtistsAfterScopedJob(updated.artistIds, collectionId)
      await this.refreshCollectionContext()
      return updated
    },
    async deleteArtist(artistId: number) {
      await apiSend(`/api/artists/${artistId}`, 'DELETE')
      const [artists, albums] = await Promise.all([
        apiGet<Artist[]>('/api/artists'),
        apiGet<Album[]>('/api/albums'),
      ])
      this.artists = artists
      this.albums = albums
      this.invalidateCollectionContent()
      this.collectionArtists = this.collectionArtists.filter((artist) => artist.id !== artistId)
      delete this.providerLinks[artistId]
      if (this.selectedArtistId === artistId) {
        this.selectedArtistId = null
        this.collectionAlbums = []
      }
      await this.refreshCollectionContext()
      this.invalidateCollectionMetadata()
    },
    async removeArtistFromSelectedCollection(artistId: number) {
      if (!this.selectedCollectionId) {
        return
      }
      const collectionId = this.selectedCollectionId
      await apiSend(
        `/api/artists/${artistId}/collections/${encodeURIComponent(collectionId)}`,
        'DELETE',
      )
      this.collectionArtists = this.collectionArtists.filter((artist) => artist.id !== artistId)
      this.collectionArtistsByCollection = {
        ...this.collectionArtistsByCollection,
        [collectionId]: (this.collectionArtistsByCollection[collectionId] ?? this.collectionArtists)
          .filter((artist) => artist.id !== artistId),
      }
      if (this.selectedArtistId === artistId) {
        this.selectedArtistId = null
        this.collectionAlbums = []
      }
      await this.refreshArtistAfterScopedJob(artistId, collectionId)
      this.invalidateCollectionMetadata(collectionId)
    },
    async refreshArtistAfterScopedJob(artistId: number, collectionId?: string | null) {
      await this.refreshArtistsAfterScopedJob([artistId], collectionId)
    },
    async refreshArtistsAfterScopedJob(artistIds: number[], collectionId?: string | null) {
      const ids = uniqueArtistIds(artistIds)
      if (ids.length === 0) {
        return
      }
      const scopedCollectionId = collectionId === undefined ? this.selectedCollectionId : collectionId
      if (ids.length === 1) {
        await this.refreshSingleArtistAfterScopedJob(ids[0], scopedCollectionId)
        return
      }
      const [globalArtists, scopedArtists] = await Promise.all([
        this.artists.length > 0 || !scopedCollectionId
          ? apiGet<Artist[]>('/api/artists')
          : Promise.resolve([]),
        scopedCollectionId
          ? apiGet<Artist[]>(withQuery('/api/artists', { collectionId: scopedCollectionId }))
          : Promise.resolve([]),
      ])
      const globalArtistsById = artistMap(globalArtists)
      const scopedArtistsById = artistMap(scopedArtists)

      ids.forEach((artistId) => {
        const globalArtist = globalArtistsById.get(artistId)
        if (globalArtist) {
          this.replaceArtist(globalArtist)
        }
        if (scopedCollectionId) {
          const scopedArtist = scopedArtistsById.get(artistId)
          if (scopedArtist) {
            this.replaceCollectionArtist(scopedArtist, scopedCollectionId)
          } else {
            this.removeCollectionArtistFromCache(artistId, scopedCollectionId)
          }
        } else if (globalArtist) {
          this.replaceCollectionArtist(globalArtist)
        }
      })

      if (scopedCollectionId) {
        this.invalidateOtherCollectionArtistCaches(scopedCollectionId)
      }

      await this.refreshGlobalAlbumsForArtists(ids)
      if (this.selectedArtistId && ids.includes(this.selectedArtistId)) {
        await this.loadAlbumsForArtist(this.selectedArtistId, true, { clearCurrent: false })
      } else {
        ids.forEach((artistId) => this.invalidateAlbumCacheForArtist(artistId))
      }
    },
    async refreshSingleArtistAfterScopedJob(artistId: number, collectionId?: string | null) {
      const [globalArtist, scopedArtist] = await Promise.all([
        this.loadArtist(artistId),
        collectionId ? this.loadArtist(artistId, collectionId) : Promise.resolve(null),
      ])
      await this.loadArtistProvider(artistId)
      this.replaceArtist(globalArtist)
      if (collectionId && scopedArtist) {
        this.replaceCollectionArtist(scopedArtist, collectionId)
        this.invalidateOtherCollectionArtistCaches(collectionId)
      } else {
        this.replaceCollectionArtist(globalArtist)
      }
      if (this.selectedArtistId === artistId) {
        await this.loadAlbumsForArtist(artistId, true, { clearCurrent: false })
      } else {
        await this.refreshGlobalAlbumsForArtists([artistId])
        this.invalidateAlbumCacheForArtist(artistId)
      }
    },
    async refreshGlobalAlbumsForArtists(artistIds: number[]) {
      const ids = uniqueArtistIds(artistIds)
      if (ids.length === 0) {
        return
      }
      if (ids.length === 1) {
        const albums = await apiGet<Album[]>(withQuery('/api/albums', { artistId: ids[0] }))
        this.replaceGlobalAlbumsForArtists(ids, albums)
        return
      }
      this.albums = await apiGet<Album[]>('/api/albums')
    },
    replaceGlobalAlbumsForArtists(artistIds: number[], refreshedAlbums: Album[]) {
      const ids = new Set(artistIds)
      this.albums = [
        ...this.albums.filter((album) => !album.artistIds.some((artistId) => ids.has(artistId))),
        ...refreshedAlbums,
      ]
    },
    replaceArtist(artist: Artist) {
      if (!this.artists.some((item) => item.id === artist.id)) {
        this.artists = [...this.artists, artist]
        return
      }
      this.artists = this.artists.map((item) => (item.id === artist.id ? artist : item))
    },
    replaceCollectionArtist(artist: Artist, collectionId?: string | null) {
      if (!collectionId) {
        const update = (item: Artist) => (
          item.id === artist.id ? { ...artist, localAlbumCount: item.localAlbumCount } : item
        )
        this.collectionArtists = this.collectionArtists.map(update)
        this.collectionArtistsByCollection = Object.fromEntries(
          Object.entries(this.collectionArtistsByCollection).map(([key, artists]) => [
            key,
            artists.map(update),
          ]),
        )
        return
      }
      const belongsToCollection = artist.collectionIds.includes(collectionId)
      if (this.selectedCollectionId === collectionId) {
        this.collectionArtists = replaceArtistInList(this.collectionArtists, artist, belongsToCollection)
        this.clearInvalidSelectedArtist()
      }
      if (hasCachedValue(this.collectionArtistsByCollection, collectionId)) {
        this.collectionArtistsByCollection = {
          ...this.collectionArtistsByCollection,
          [collectionId]: replaceArtistInList(
            this.collectionArtistsByCollection[collectionId],
            artist,
            belongsToCollection,
          ),
        }
      }
    },
    removeCollectionArtistFromCache(artistId: number, collectionId: string) {
      if (this.selectedCollectionId === collectionId) {
        this.collectionArtists = this.collectionArtists.filter((artist) => artist.id !== artistId)
        this.clearInvalidSelectedArtist()
      }
      if (hasCachedValue(this.collectionArtistsByCollection, collectionId)) {
        this.collectionArtistsByCollection = {
          ...this.collectionArtistsByCollection,
          [collectionId]: this.collectionArtistsByCollection[collectionId]
            .filter((artist) => artist.id !== artistId),
        }
      }
    },
    invalidateOtherCollectionArtistCaches(collectionId: string) {
      this.collectionArtistsByCollection = Object.fromEntries(
        Object.entries(this.collectionArtistsByCollection)
          .filter(([key]) => key === collectionId),
      )
    },
    replaceAlbum(album: Album) {
      this.albums = this.albums.some((item) => item.id === album.id)
        ? this.albums.map((item) => (item.id === album.id ? album : item))
        : [album, ...this.albums]
      this.collectionAlbums = this.collectionAlbums.some((item) => item.id === album.id)
        ? this.collectionAlbums.map((item) => (item.id === album.id ? album : item))
        : this.collectionAlbums
      this.collectionTitleItems = this.collectionTitleItems.some((item) => item.id === album.id)
        ? this.collectionTitleItems.map((item) => (item.id === album.id ? album : item))
        : this.collectionTitleItems
      this.replaceCachedAlbum(album)
    },
    replaceCachedAlbum(album: Album) {
      this.collectionAlbumsByArtist = Object.fromEntries(
        Object.entries(this.collectionAlbumsByArtist).map(([key, albums]) => [
          key,
          albums.map((item) => (item.id === album.id ? album : item)),
        ]),
      )
      this.collectionTitleItemsByCollection = Object.fromEntries(
        Object.entries(this.collectionTitleItemsByCollection).map(([key, titles]) => [
          key,
          titles.map((item) => (item.id === album.id ? album : item)),
        ]),
      )
    },
    findCachedAlbum(albumId: number) {
      return this.albums.find((album) => album.id === albumId)
        ?? this.collectionAlbums.find((album) => album.id === albumId)
        ?? this.collectionTitleItems.find((album) => album.id === albumId)
        ?? Object.values(this.collectionAlbumsByArtist)
          .flat()
          .find((album) => album.id === albumId)
        ?? Object.values(this.collectionTitleItemsByCollection)
          .flat()
          .find((album) => album.id === albumId)
        ?? null
    },
    updateArtistAlbumCheckCounts(previous: Album | null, updated: Album) {
      if (!previous || previous.checked === updated.checked) {
        return
      }
      const artistIds = new Set(updated.artistIds)
      const checkedDelta = updated.checked ? 1 : -1
      const updateArtist = (artist: Artist) => {
        if (!artistIds.has(artist.id)) {
          return artist
        }
        return {
          ...artist,
          checkedAlbumCount: Math.max(0, artist.checkedAlbumCount + checkedDelta),
          uncheckedAlbumCount: Math.max(0, artist.uncheckedAlbumCount - checkedDelta),
        }
      }
      this.artists = this.artists.map(updateArtist)
      this.collectionArtists = this.collectionArtists.map(updateArtist)
      this.collectionArtistsByCollection = Object.fromEntries(
        Object.entries(this.collectionArtistsByCollection).map(([key, artists]) => [
          key,
          artists.map(updateArtist),
        ]),
      )
    },
    removeCachedAlbum(albumId: number) {
      this.collectionAlbumsByArtist = Object.fromEntries(
        Object.entries(this.collectionAlbumsByArtist).map(([key, albums]) => [
          key,
          albums.filter((album) => album.id !== albumId),
        ]),
      )
      this.collectionTitleItemsByCollection = Object.fromEntries(
        Object.entries(this.collectionTitleItemsByCollection).map(([key, titles]) => [
          key,
          titles.filter((album) => album.id !== albumId),
        ]),
      )
    },
    invalidateAlbumCacheForArtist(artistId: number) {
      const cacheKey = String(artistId)
      const { [cacheKey]: _albums, ...rest } = this.collectionAlbumsByArtist
      this.collectionAlbumsByArtist = rest
    },
    async runScanJob(collectionId?: string) {
      await this.startScanJob(collectionId)
      this.startScanJobPolling()
      return this.scanJob
    },
    startScanJobPolling() {
      if (scanJobPollingActive) {
        return
      }
      scanJobPollingActive = true
      const intervalMs = Math.min(2000, Math.max(100, this.uiSettings.scanPollIntervalMs))
      const schedulePoll = () => {
        if (!scanJobPollingActive) {
          return
        }
        scanJobPoller = window.setTimeout(() => void poll(), intervalMs)
      }
      const poll = async (): Promise<void> => {
        scanJobPoller = null
        try {
          const status = await this.loadScanJob()
          if (status?.status === 'RUNNING') {
            schedulePoll()
            return
          }
          scanJobPollingActive = false
          const collectionId = status?.requestedCollectionId ?? status?.activeCollectionId ?? undefined
          await this.refreshCollectionAfterScan(collectionId)
        } catch (error) {
          this.stopScanJobPolling()
          this.showErrorStatus(error, 'Unable to poll scan status')
        }
      }
      schedulePoll()
    },
    stopScanJobPolling() {
      scanJobPollingActive = false
      if (scanJobPoller !== null) {
        window.clearTimeout(scanJobPoller)
        scanJobPoller = null
      }
    },
    async startScanJob(collectionId?: string) {
      const query = collectionId ? `?collectionId=${encodeURIComponent(collectionId)}` : ''
      this.scanJob = await apiSend<ScanJobStatus>(`/api/scan/jobs${query}`, 'POST')
      return this.scanJob
    },
    async loadScanJob() {
      try {
        this.scanJob = await apiGet<ScanJobStatus>('/api/scan/jobs/current')
      } catch (error) {
        this.showErrorStatus(error, 'Unable to load scan status')
      }
      return this.scanJob
    },
    async cancelScanJob() {
      this.scanJob = await apiSend<ScanJobStatus>('/api/scan/jobs/current/cancel', 'POST')
      return this.scanJob
    },
    async runProviderArtistJob(artistId: number, collectionId?: string | null) {
      const scopedCollectionId = collectionId === undefined ? this.selectedCollectionId : collectionId
      await this.startProviderArtistJob(artistId, scopedCollectionId)
      this.startProviderJobPolling()
      return this.providerJob
    },
    async runProviderCollectionJob(collectionId: string) {
      await this.startProviderCollectionJob(collectionId)
      this.startProviderJobPolling()
      return this.providerJob
    },
    async runProviderAllJob() {
      await this.startProviderAllJob()
      this.startProviderJobPolling()
      return this.providerJob
    },
    startProviderJobPolling() {
      if (providerJobPoller !== null) {
        return
      }
      const intervalMs = Math.min(2000, Math.max(100, this.uiSettings.scanPollIntervalMs))
      const poll = async () => {
        try {
          const previousActiveArtistId = this.providerJob?.activeArtistId ?? null
          const status = await this.loadProviderJob()
          if (status?.status === 'RUNNING' && previousActiveArtistId && previousActiveArtistId !== status.activeArtistId) {
            await this.refreshArtistAfterScanStep(previousActiveArtistId, status.requestedCollectionId)
          }
          if (!status || status.status !== 'RUNNING') {
            this.stopProviderJobPolling()
            const collectionId = status?.requestedCollectionId ?? undefined
            const artistIds = uniqueArtistIds([status?.requestedArtistId, ...(status?.artistIds ?? [])])
            await this.loadProviderConflicts()
            this.invalidateCollectionMetadata(collectionId)
            if (artistIds.length > 0) {
              await this.refreshArtistsAfterScopedJob(artistIds, collectionId)
            }
          }
        } catch (error) {
          this.stopProviderJobPolling()
          this.showErrorStatus(error, 'Unable to poll provider status')
        }
      }
      providerJobPoller = window.setInterval(() => void poll(), intervalMs)
    },
    stopProviderJobPolling() {
      if (providerJobPoller === null) {
        return
      }
      window.clearInterval(providerJobPoller)
      providerJobPoller = null
    },
    async startProviderArtistJob(artistId: number, collectionId?: string | null) {
      const scopedCollectionId = collectionId === undefined ? this.selectedCollectionId : collectionId
      this.providerJob = await apiSend<ProviderCheckJobStatus>(
        withQuery(`/api/provider-checks/jobs/artist/${artistId}`, { collectionId: scopedCollectionId }),
        'POST',
      )
      return this.providerJob
    },
    async startProviderCollectionJob(collectionId: string) {
      this.providerJob = await apiSend<ProviderCheckJobStatus>(
        `/api/provider-checks/jobs/collection/${encodeURIComponent(collectionId)}`,
        'POST',
      )
      return this.providerJob
    },
    async startProviderAllJob() {
      this.providerJob = await apiSend<ProviderCheckJobStatus>('/api/provider-checks/jobs/all', 'POST')
      return this.providerJob
    },
    async loadProviderJob() {
      try {
        this.providerJob = await apiGet<ProviderCheckJobStatus>('/api/provider-checks/jobs/current')
      } catch (error) {
        this.showErrorStatus(error, 'Unable to load provider status')
      }
      return this.providerJob
    },
    async loadProviderReleaseDateConflicts() {
      this.providerReleaseDateConflicts = await apiGet<ProviderReleaseDateConflict[]>('/api/provider-conflicts/release-dates')
      return this.providerReleaseDateConflicts
    },
    async loadProviderTitleConflicts() {
      this.providerTitleConflicts = await apiGet<ProviderTitleConflict[]>('/api/provider-conflicts/titles')
      return this.providerTitleConflicts
    },
    async loadProviderConflicts() {
      const [releaseDateConflicts, titleConflicts] = await Promise.all([
        apiGet<ProviderReleaseDateConflict[]>('/api/provider-conflicts/release-dates'),
        apiGet<ProviderTitleConflict[]>('/api/provider-conflicts/titles'),
      ])
      this.providerReleaseDateConflicts = releaseDateConflicts
      this.providerTitleConflicts = titleConflicts
      return { releaseDateConflicts, titleConflicts }
    },
    async planUseProviderReleaseDate(conflict: ProviderReleaseDateConflict) {
      return apiGet<AlbumReleaseDateConflictPlan>(providerConflictPath(conflict, 'provider-year-plan'))
    },
    async keepLocalReleaseDate(conflict: ProviderReleaseDateConflict) {
      const result = await apiSend<AlbumReleaseDateConflictResult>(providerConflictPath(conflict, 'keep-local'), 'POST')
      await this.refreshAfterProviderReleaseDateResolution(conflict, result)
      return result
    },
    async useProviderReleaseDate(conflict: ProviderReleaseDateConflict) {
      const result = await apiSend<AlbumReleaseDateConflictResult>(providerConflictPath(conflict, 'use-provider-year'), 'POST')
      await this.refreshAfterProviderReleaseDateResolution(conflict, result)
      return result
    },
    async planUseProviderTitle(conflict: ProviderTitleConflict) {
      return apiGet<AlbumTitleConflictPlan>(providerTitleConflictPath(conflict, 'provider-title-plan'))
    },
    async keepLocalTitle(conflict: ProviderTitleConflict) {
      const result = await apiSend<AlbumTitleConflictResult>(providerTitleConflictPath(conflict, 'keep-local'), 'POST')
      await this.refreshAfterProviderTitleResolution(conflict, result)
      return result
    },
    async useProviderTitle(conflict: ProviderTitleConflict) {
      const result = await apiSend<AlbumTitleConflictResult>(providerTitleConflictPath(conflict, 'use-provider-title'), 'POST')
      await this.refreshAfterProviderTitleResolution(conflict, result)
      return result
    },
    async resetKeepLocalReleaseDate(albumId: number, providerLinkId: number, artistId?: number | null) {
      const result = await apiSend<AlbumReleaseDateConflictResult>(
        providerConflictIdPath(albumId, providerLinkId, 'reset-keep-local'),
        'POST',
      )
      this.replaceAlbum(result.album)
      this.invalidateCollectionMetadata()
      await this.loadProviderConflicts()
      const refreshArtistId = artistId ?? result.album.artistIds[0] ?? null
      if (refreshArtistId) {
        await this.refreshArtistAfterScopedJob(refreshArtistId, this.currentCollectionScopeForArtist(refreshArtistId))
      }
      return result
    },
    async refreshAfterProviderReleaseDateResolution(
      conflict: ProviderReleaseDateConflict,
      result: AlbumReleaseDateConflictResult,
    ) {
      this.replaceAlbum(result.album)
      this.invalidateCollectionMetadata()
      await this.loadProviderConflicts()
      await this.refreshArtistAfterScopedJob(conflict.artistId, this.currentCollectionScopeForArtist(conflict.artistId))
    },
    async refreshAfterProviderTitleResolution(
      conflict: ProviderTitleConflict,
      result: AlbumTitleConflictResult,
    ) {
      this.replaceAlbum(result.album)
      this.invalidateCollectionMetadata()
      await this.loadProviderConflicts()
      await this.refreshArtistAfterScopedJob(conflict.artistId, this.currentCollectionScopeForArtist(conflict.artistId))
    },
    async cancelProviderJob() {
      this.providerJob = await apiSend<ProviderCheckJobStatus>('/api/provider-checks/jobs/current/cancel', 'POST')
      return this.providerJob
    },
    async loadPreference(key: string) {
      try {
        return await apiGet<UserPreference>(`/api/preferences/${encodeURIComponent(key)}`)
      } catch (error) {
        return null
      }
    },
    async savePreference(key: string, value: string) {
      return apiSend<UserPreference>(`/api/preferences/${encodeURIComponent(key)}`, 'PUT', { value })
    },
    async loadArtistProvider(artistId: number) {
      try {
        const providers = await apiGet<ArtistProviderLink[]>(`/api/artists/${artistId}/providers`)
        this.providerLinks[artistId] = providers
        return providers[0] ?? null
      } catch (error) {
        if (error instanceof Error && error.message.startsWith('404 ')) {
          this.providerLinks[artistId] = []
          return null
        }
        throw error
      }
    },
    async searchMusicBrainzCandidates(artistId: number) {
      return this.searchProviderCandidates(artistId, 'musicbrainz')
    },
    async searchProviderCandidates(artistId: number, providerId: ProviderId) {
      return apiGet<ArtistProviderCandidate[]>(`/api/artists/${artistId}/provider-candidates/${providerId}`)
    },
    async bulkMatchMusicBrainz(artistIds: number[]) {
      return this.bulkMatchProvider('musicbrainz', artistIds)
    },
    async bulkMatchProvider(providerId: ProviderId, artistIds: number[]) {
      const count = artistIds.length
      const provider = providerDefinition(providerId)
      this.providerStatus = {
        running: true,
        message: `Matching ${provider.label} for ${count} artist${count === 1 ? '' : 's'}`,
        state: 'running',
      }
      try {
        const result = await apiSend<ArtistProviderBulkMatchResult>(
          `/api/provider-matches/${providerId}/artists`,
          'POST',
          { artistIds },
        )
        for (const item of result.items) {
          if (item.providerLink) {
            this.upsertCachedProviderLink(item.artistId, item.providerLink)
          }
        }
        const refreshedArtistIds = uniqueArtistIds(result.items.map((item) => item.artistId))
        const collectionScope = this.currentCollectionScopeForArtists(refreshedArtistIds)
        await this.refreshArtistsAfterScopedJob(refreshedArtistIds, collectionScope)
        this.invalidateCollectionMetadata(collectionScope ?? undefined)
        const detail = result.messages.join(' ').trim()
        const message = detail || `${provider.label} bulk match complete: ${result.matchedCount} matched`
        this.providerStatus = {
          running: false,
          message,
          state: result.errorCount > 0 ? 'failed'
            : result.manualCount > 0 || result.noMatchCount > 0 ? 'warning'
              : 'done',
        }
        return result
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error)
        this.providerStatus = { running: false, message: `${provider.label} bulk match failed: ${message}`, state: 'failed' }
        throw error
      }
    },
    async saveArtistProvider(artistId: number, payload: ProviderLinkPayload) {
      const provider = await apiSend<ArtistProviderLink>(`/api/artists/${artistId}/provider`, 'PUT', {
        providerId: payload.providerId,
        providerArtistId: payload.providerArtistId,
        providerArtistName: payload.providerArtistName,
        providerCountry: payload.providerCountry,
        providerDisambiguation: payload.providerDisambiguation,
        providerActive: payload.providerActive,
        providerUrl: payload.providerUrl,
        enabled: payload.enabled,
      })
      this.upsertCachedProviderLink(artistId, provider)
      await this.refreshArtistAfterScopedJob(artistId, this.currentCollectionScopeForArtist(artistId))
      return provider
    },
    async clearArtistProvider(artistId: number, providerId?: string | null) {
      if (providerId) {
        await apiSend(`/api/artists/${artistId}/providers/${encodeURIComponent(providerId)}`, 'DELETE')
        this.providerLinks[artistId] = (this.providerLinks[artistId] ?? []).filter((link) => link.providerId !== providerId)
      } else {
        await apiSend(`/api/artists/${artistId}/provider`, 'DELETE')
        this.providerLinks[artistId] = []
      }
      await this.refreshArtistAfterScopedJob(artistId, this.currentCollectionScopeForArtist(artistId))
    },
    upsertCachedProviderLink(artistId: number, provider: ArtistProviderLink) {
      const current = this.providerLinks[artistId] ?? []
      this.providerLinks[artistId] = [
        ...current.filter((link) => link.providerId !== provider.providerId),
        provider,
      ].sort((left, right) => left.providerId.localeCompare(right.providerId))
    },
    currentCollectionScopeForArtist(artistId: number) {
      if (!this.selectedCollectionId) {
        return null
      }
      return this.collectionArtists.some((artist) => artist.id === artistId)
        ? this.selectedCollectionId
        : null
    },
    currentCollectionScopeForArtists(artistIds: number[]) {
      if (!this.selectedCollectionId || artistIds.length === 0) {
        return null
      }
      const ids = new Set(artistIds)
      return this.collectionArtists.some((artist) => ids.has(artist.id))
        ? this.selectedCollectionId
        : null
    },
  },
})

function hasCachedValue<T>(cache: Record<string, T>, key: string) {
  return Object.prototype.hasOwnProperty.call(cache, key)
}

function replaceArtistInList(artists: Artist[], artist: Artist, include: boolean) {
  const exists = artists.some((item) => item.id === artist.id)
  if (!include) {
    return exists ? artists.filter((item) => item.id !== artist.id) : artists
  }
  return exists
    ? artists.map((item) => (item.id === artist.id ? artist : item))
    : [...artists, artist]
}

function uniqueArtistIds(artistIds: Array<number | null | undefined>) {
  return [...new Set(
    artistIds.filter((artistId): artistId is number => typeof artistId === 'number' && artistId > 0),
  )]
}

function artistMap(artists: Artist[]) {
  return new Map(artists.map((artist) => [artist.id, artist]))
}

function providerConflictPath(conflict: ProviderReleaseDateConflict, action: string) {
  return providerConflictIdPath(conflict.albumId, conflict.providerLinkId, action)
}

function providerConflictIdPath(albumId: number, providerLinkId: number, action: string) {
  return `/api/albums/${albumId}/provider-links/${providerLinkId}/release-date-conflict/${action}`
}

function providerTitleConflictPath(conflict: ProviderTitleConflict, action: string) {
  return `/api/albums/${conflict.albumId}/provider-links/${conflict.providerLinkId}/title-conflict/${action}`
}
