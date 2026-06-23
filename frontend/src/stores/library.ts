import { defineStore } from 'pinia'
import { apiGet, apiSend, apiText } from '@/api'
import { formatDateWithJavaPattern } from '@/dateFormat'
import type {
  Album,
  AlbumReviewDecision,
  Artist,
  ArtistProviderBulkMatchResult,
  ArtistProviderCandidate,
  ArtistProviderLink,
  CollectionFolderCandidate,
  CollectionMetadata,
  MusicRootInfo,
  MusicCollection,
  ScanJobStatus,
  ProviderCheckRun,
  ProviderCheckJobStatus,
  ProviderCheckSummary,
  ProviderRefreshResult,
  ScanEvent,
  ScanRun,
  StatusHistoryEntry,
  UiSettings,
  UiSettingsValues,
  UserPreference,
} from '@/types'

interface ArtistPayload {
  id?: number
  name: string
  sortName?: string | null
  notes?: string | null
  collectionIds: string[]
}

interface ProviderLinkPayload {
  id?: number
  providerId: string
  providerArtistId?: string | null
  providerArtistName?: string | null
  providerArtistType?: string | null
  providerArtistCountry?: string | null
  providerArtistDisambiguation?: string | null
  providerArtistActive?: boolean | null
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
  scanRuns: ScanRun[]
  scanJob: ScanJobStatus | null
  scanEvents: Record<number, ScanEvent[]>
  scanReports: Record<number, string>
  scanReportsLoading: Record<number, boolean>
  providerLinks: Record<number, ArtistProviderLink[]>
  providerCheckRuns: ProviderCheckRun[]
  providerJob: ProviderCheckJobStatus | null
  uiSettings: UiSettings
  statusHistory: StatusHistoryEntry[]
  manualStatus: { id: number; message: string; state: Exclude<StatusHistoryEntry['state'], 'running'> } | null
  providerStatus: { running: boolean; message: string | null; state: StatusHistoryEntry['state'] }
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
    action: 122,
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
  country: 76,
  type: 80,
  status: 84,
  albums: 68,
  unchecked: 86,
  local: 64,
  provider: 180,
  action: 104,
}

let scanJobPoller: number | null = null
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
    scanRuns: [],
    scanJob: null,
    scanEvents: {},
    scanReports: {},
    scanReportsLoading: {},
    providerLinks: {},
      providerCheckRuns: [],
      providerJob: null,
      uiSettings: {
        statusCompleteVisibleMs: 10000,
        scanPollIntervalMs: 200,
        collectionScanSpinnerEnabled: true,
        artistScanSpinnerEnabled: true,
        collectionScanProgressEnabled: true,
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
          collectionScanSpinnerEnabled: true,
          artistScanSpinnerEnabled: true,
          collectionScanProgressEnabled: true,
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
        collectionScanSpinnerEnabled: false,
        artistScanSpinnerEnabled: false,
        collectionScanProgressEnabled: false,
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
        const [artists, albums, collections, musicRoot, scanRuns, providerCheckRuns] = await Promise.all([
          apiGet<Artist[]>('/api/artists'),
          apiGet<Album[]>('/api/albums'),
          apiGet<MusicCollection[]>('/api/collections'),
          apiGet<MusicRootInfo>('/api/settings/music-root'),
          apiGet<ScanRun[]>('/api/scan/runs?limit=25'),
          apiGet<ProviderCheckRun[]>('/api/provider-checks/runs?limit=25'),
        ])
        this.artists = artists
        this.albums = albums
        this.collections = collections
        this.musicRoot = musicRoot
        this.scanRuns = scanRuns
        this.providerCheckRuns = providerCheckRuns
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
      const collection = await apiSend<MusicCollection>(`/api/collections/${encodeURIComponent(collectionId)}`, 'PUT', {
        name: payload.name,
        type: payload.type,
      })
      this.collections = this.collections.map((item) => (item.id === collection.id ? collection : item))
      this.invalidateCollectionContent(collection.id)
      if (this.selectedCollectionId === collection.id) {
        await this.selectCollection(collection.id)
      }
      return collection
    },
    async deleteCollection(collectionId: string) {
      await apiSend(`/api/collections/${encodeURIComponent(collectionId)}`, 'DELETE')
      this.collections = this.collections.filter((collection) => collection.id !== collectionId)
      this.invalidateCollectionMetadata(collectionId)
      this.invalidateCollectionContent(collectionId)
      if (this.selectedCollectionId === collectionId) {
        this.selectedCollectionId = null
        this.selectedArtistId = null
        this.collectionArtists = []
        this.collectionAlbums = []
        this.collectionTitleItems = []
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
        const [collections, musicRoot, scanRuns] = await Promise.all([
          apiGet<MusicCollection[]>('/api/collections'),
          apiGet<MusicRootInfo>('/api/settings/music-root'),
          apiGet<ScanRun[]>('/api/scan/runs?limit=25'),
        ])
        this.collections = collections
        this.musicRoot = musicRoot
        this.scanRuns = scanRuns
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
      options: { scanRunIds?: number[] } = {},
    ) {
      this.statusHistory = [
        ...this.statusHistory,
        {
          id: Date.now() * 1000 + Math.floor(Math.random() * 1000),
          createdAt: formatDateWithJavaPattern(new Date(), this.uiSettings.statusHistoryDateFormat),
          message,
          state,
          scanRunIds: options.scanRunIds,
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
    async loadTitlesForCollection(collectionId: string, force = false) {
      if (!force && hasCachedValue(this.collectionTitleItemsByCollection, collectionId)) {
        this.collectionTitleItems = this.collectionTitleItemsByCollection[collectionId]
        return this.collectionTitleItems
      }
      if (!force && this.collectionTitleItemsLoading[collectionId]) {
        return this.collectionTitleItemsByCollection[collectionId] ?? []
      }
      if (this.selectedCollectionId === collectionId) {
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
      return updated
    },
    async deleteTitleLocalPath(item: Album) {
      if (!this.selectedCollectionId) {
        return
      }
      await apiSend(`/api/collections/${encodeURIComponent(this.selectedCollectionId)}/titles/${item.id}`, 'DELETE')
      await this.loadTitlesForCollection(this.selectedCollectionId, true)
      this.invalidateCollectionMetadata(this.selectedCollectionId)
    },
    async loadArtistsForSelectedCollection() {
      if (!this.selectedCollectionId) {
        this.collectionArtists = []
        return
      }
      await this.loadArtistsForCollection(this.selectedCollectionId)
    },
    async loadArtistsForCollection(collectionId: string, force = false) {
      if (!force && hasCachedValue(this.collectionArtistsByCollection, collectionId)) {
        this.collectionArtists = this.collectionArtistsByCollection[collectionId]
        this.clearInvalidSelectedArtist()
        return this.collectionArtists
      }
      if (!force && this.collectionArtistsLoading[collectionId]) {
        return this.collectionArtistsByCollection[collectionId] ?? []
      }
      if (this.selectedCollectionId === collectionId) {
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
    async refreshSelectedArtistAfterScanStep(artistId: number | null | undefined) {
      if (!artistId || this.selectedArtistId !== artistId) {
        return
      }
      await this.loadAlbumsForArtist(artistId, true)
    },
    async loadAlbumsForArtist(artistId: number, force = false) {
      const cacheKey = String(artistId)
      if (!force && hasCachedValue(this.collectionAlbumsByArtist, cacheKey)) {
        this.collectionAlbums = this.collectionAlbumsByArtist[cacheKey]
        return this.collectionAlbums
      }
      if (!force && this.collectionAlbumsLoading[cacheKey]) {
        return this.collectionAlbumsByArtist[cacheKey] ?? []
      }
      if (this.selectedArtistId === artistId) {
        this.collectionAlbums = []
      }
      this.collectionAlbumsLoading = { ...this.collectionAlbumsLoading, [cacheKey]: true }
      try {
        const albums = await apiGet<Album[]>(
          withQuery('/api/albums', {
            artistId,
          }),
        )
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
    async addArtist(name: string, collectionIds: string[] = []) {
      await this.saveArtist({ name, sortName: null, notes: null, collectionIds })
      await this.loadArtists()
    },
    async saveArtist(payload: ArtistPayload) {
      const body = {
        name: payload.name,
        sortName: payload.sortName ?? null,
        notes: payload.notes ?? null,
        collectionIds: payload.collectionIds,
      }
      const artist = payload.id
        ? await apiSend<Artist>(`/api/artists/${payload.id}`, 'PUT', body)
        : await apiSend<Artist>('/api/artists', 'POST', body)
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
    },
    async untrackMissingAlbumLocalPaths(albumId: number, collectionId: string) {
      const updated = await apiSend<Album>(
        `/api/collections/${collectionId}/albums/${albumId}/missing-local-paths`,
        'DELETE',
      )
      this.replaceAlbum(updated)
      this.invalidateCollectionMetadata(collectionId)
    },
    async deleteAlbum(albumId: number) {
      await apiSend(`/api/albums/${albumId}`, 'DELETE')
      this.albums = this.albums.filter((album) => album.id !== albumId)
      this.collectionAlbums = this.collectionAlbums.filter((album) => album.id !== albumId)
      this.collectionTitleItems = this.collectionTitleItems.filter((album) => album.id !== albumId)
      this.removeCachedAlbum(albumId)
      await this.refreshCollectionContext()
      this.invalidateCollectionMetadata()
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
      await this.loadArtists()
      this.invalidateCollectionMetadata(collectionId)
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
      if (scanJobPoller !== null) {
        return
      }
      const intervalMs = Math.min(2000, Math.max(100, this.uiSettings.scanPollIntervalMs))
      const poll = async () => {
        try {
          const previousActiveArtistId = this.scanJob?.activeArtistId ?? null
          const status = await this.loadScanJob()
          if (status?.status === 'RUNNING' && previousActiveArtistId && previousActiveArtistId !== status.activeArtistId) {
            await this.refreshSelectedArtistAfterScanStep(previousActiveArtistId)
          }
          if (!status || status.status !== 'RUNNING') {
            this.stopScanJobPolling()
            const collectionId = status?.requestedCollectionId ?? status?.activeCollectionId ?? undefined
            this.invalidateCollectionMetadata(collectionId)
            this.invalidateCollectionContent(collectionId)
            await this.loadSettings()
            if (status?.kind === 'LOCAL_ALBUMS') {
              await this.refreshCollectionContext()
            } else {
              await this.refreshCollectionArtistsOnly(true)
            }
          }
        } catch (error) {
          this.stopScanJobPolling()
          this.showErrorStatus(error, 'Unable to poll scan status')
        }
      }
      scanJobPoller = window.setInterval(() => void poll(), intervalMs)
    },
    stopScanJobPolling() {
      if (scanJobPoller === null) {
        return
      }
      window.clearInterval(scanJobPoller)
      scanJobPoller = null
    },
    async startScanJob(collectionId?: string) {
      const query = collectionId ? `?collectionId=${encodeURIComponent(collectionId)}` : ''
      this.scanJob = await apiSend<ScanJobStatus>(`/api/scan/jobs${query}`, 'POST')
      return this.scanJob
    },
    async runLocalAlbumScanJob(collectionId: string, artistId?: number) {
      const query = withQuery('/api/scan/jobs/local-albums', { collectionId, artistId })
      this.scanJob = await apiSend<ScanJobStatus>(query, 'POST')
      this.startScanJobPolling()
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
    async runProviderArtistJob(artistId: number) {
      await this.startProviderArtistJob(artistId)
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
            await this.refreshSelectedArtistAfterScanStep(previousActiveArtistId)
          }
          if (!status || status.status !== 'RUNNING') {
            this.stopProviderJobPolling()
            const collectionId = status?.requestedCollectionId ?? undefined
            this.providerCheckRuns = await apiGet<ProviderCheckRun[]>('/api/provider-checks/runs?limit=25')
            this.invalidateCollectionContent(collectionId)
            this.invalidateCollectionMetadata(collectionId)
            await this.loadArtists()
            await this.refreshCollectionContext()
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
    async startProviderArtistJob(artistId: number) {
      this.providerJob = await apiSend<ProviderCheckJobStatus>(
        withQuery(`/api/provider-checks/jobs/artist/${artistId}`, { collectionId: this.selectedCollectionId }),
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
    async cancelProviderJob() {
      this.providerJob = await apiSend<ProviderCheckJobStatus>('/api/provider-checks/jobs/current/cancel', 'POST')
      return this.providerJob
    },
    async loadScanEvents(runId: number) {
      this.scanEvents[runId] = await apiGet<ScanEvent[]>(`/api/scan/runs/${runId}/events`)
    },
    async loadScanReport(runId: number) {
      if (this.scanReports[runId]) {
        return this.scanReports[runId]
      }
      this.scanReportsLoading[runId] = true
      try {
        const report = await apiText(`/api/scan/runs/${runId}/report`)
        this.scanReports[runId] = report
        return report
      } finally {
        this.scanReportsLoading[runId] = false
      }
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
    async loadProviderLinks(artistId: number) {
      this.providerLinks[artistId] = await apiGet<ArtistProviderLink[]>(`/api/artists/${artistId}/provider-links`)
    },
    async loadArtistProvider(artistId: number) {
      try {
        const provider = await apiGet<ArtistProviderLink>(`/api/artists/${artistId}/provider`)
        this.providerLinks[artistId] = [provider]
        return provider
      } catch (error) {
        if (error instanceof Error && error.message.startsWith('404 ')) {
          this.providerLinks[artistId] = []
          return null
        }
        throw error
      }
    },
    async searchMusicBrainzCandidates(artistId: number) {
      return apiGet<ArtistProviderCandidate[]>(`/api/artists/${artistId}/provider-candidates/musicbrainz`)
    },
    async bulkMatchMusicBrainz(artistIds: number[]) {
      const count = artistIds.length
      this.providerStatus = {
        running: true,
        message: `Matching MusicBrainz for ${count} artist${count === 1 ? '' : 's'}`,
        state: 'running',
      }
      try {
        const result = await apiSend<ArtistProviderBulkMatchResult>(
          '/api/provider-matches/musicbrainz/artists',
          'POST',
          { artistIds },
        )
        for (const item of result.items) {
          if (item.providerLink) {
            this.providerLinks[item.artistId] = [item.providerLink]
          }
        }
        this.invalidateCollectionContent()
        await this.loadArtists()
        await this.refreshCollectionContext()
        this.invalidateCollectionMetadata()
        const detail = result.messages.join(' ').trim()
        const message = detail || `MusicBrainz bulk match complete: ${result.matchedCount} matched`
        this.providerStatus = {
          running: false,
          message,
          state: result.errorCount > 0 ? 'failed'
            : result.reviewCount > 0 || result.noMatchCount > 0 ? 'warning'
              : 'done',
        }
        return result
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error)
        this.providerStatus = { running: false, message: `MusicBrainz bulk match failed: ${message}`, state: 'failed' }
        throw error
      }
    },
    async saveArtistProvider(artistId: number, payload: ProviderLinkPayload) {
      const provider = await apiSend<ArtistProviderLink>(`/api/artists/${artistId}/provider`, 'PUT', {
        providerId: payload.providerId,
        providerArtistId: payload.providerArtistId,
        providerArtistName: payload.providerArtistName,
        providerArtistType: payload.providerArtistType,
        providerArtistCountry: payload.providerArtistCountry,
        providerArtistDisambiguation: payload.providerArtistDisambiguation,
        providerArtistActive: payload.providerArtistActive,
        providerUrl: payload.providerUrl,
        enabled: payload.enabled,
      })
      this.providerLinks[artistId] = [provider]
      this.invalidateCollectionContent()
      await this.loadArtists()
      await this.refreshCollectionContext()
      return provider
    },
    async clearArtistProvider(artistId: number) {
      await apiSend(`/api/artists/${artistId}/provider`, 'DELETE')
      this.providerLinks[artistId] = []
      this.invalidateCollectionContent()
      await this.loadArtists()
      await this.refreshCollectionContext()
    },
    async saveProviderLink(artistId: number, payload: ProviderLinkPayload) {
      const body = {
        providerId: payload.providerId,
        providerArtistId: payload.providerArtistId,
        providerArtistName: payload.providerArtistName,
        providerArtistType: payload.providerArtistType,
        providerArtistCountry: payload.providerArtistCountry,
        providerArtistDisambiguation: payload.providerArtistDisambiguation,
        providerArtistActive: payload.providerArtistActive,
        providerUrl: payload.providerUrl,
        enabled: payload.enabled,
      }
      if (payload.id) {
        await apiSend<ArtistProviderLink>(`/api/artists/${artistId}/provider-links/${payload.id}`, 'PUT', body)
      } else {
        await apiSend<ArtistProviderLink>(`/api/artists/${artistId}/provider-links`, 'POST', body)
      }
      await this.loadProviderLinks(artistId)
      this.invalidateCollectionContent()
      await this.loadArtists()
      await this.refreshCollectionContext()
    },
    async addProviderLink(artistId: number, providerId: string, providerUrl: string) {
      await this.saveProviderLink(artistId, { providerId, providerUrl, enabled: true })
    },
    async deleteProviderLink(artistId: number, linkId: number) {
      await apiSend(`/api/artists/${artistId}/provider-links/${linkId}`, 'DELETE')
      await this.loadProviderLinks(artistId)
      this.invalidateCollectionContent()
      await this.loadArtists()
      await this.refreshCollectionContext()
    },
    async refreshArtistProvider(artistId: number) {
      const artistName = this.collectionArtists.find((artist) => artist.id === artistId)?.name
        ?? this.artists.find((artist) => artist.id === artistId)?.name
        ?? `artist ${artistId}`
      this.providerStatus = { running: true, message: `Refreshing MusicBrainz for ${artistName}`, state: 'running' }
      try {
        const result = await apiSend<ProviderRefreshResult>(`/api/artists/${artistId}/provider/refresh`, 'POST')
        this.providerCheckRuns = await apiGet<ProviderCheckRun[]>('/api/provider-checks/runs?limit=25')
        this.invalidateCollectionContent(this.selectedCollectionId ?? undefined)
        await this.loadArtists()
        await this.refreshCollectionContext()
        this.invalidateCollectionMetadata(this.selectedCollectionId ?? undefined)
        const detail = result.messages.join(' ').trim()
        const message = detail || `MusicBrainz refresh complete: ${result.createdAlbumCount} new albums`
        this.providerStatus = {
          running: false,
          message,
          state: result.reviewRequiredCount > 0 ? 'warning' : 'done',
        }
        return result
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error)
        this.providerStatus = { running: false, message: `MusicBrainz refresh failed: ${message}`, state: 'failed' }
        throw error
      }
    },
    async applyProviderAlbumDecisions(artistId: number, decisions: AlbumReviewDecision[]) {
      this.providerStatus = { running: true, message: 'Applying MusicBrainz review decisions', state: 'running' }
      try {
        const result = await apiSend<ProviderRefreshResult>(
          `/api/artists/${artistId}/provider/album-decisions`,
          'POST',
          { decisions },
        )
        this.providerCheckRuns = await apiGet<ProviderCheckRun[]>('/api/provider-checks/runs?limit=25')
        this.invalidateCollectionContent(this.selectedCollectionId ?? undefined)
        await this.loadArtists()
        await this.refreshCollectionContext()
        this.invalidateCollectionMetadata(this.selectedCollectionId ?? undefined)
        const detail = result.messages.join(' ').trim()
        this.providerStatus = { running: false, message: detail, state: 'done' }
        return result
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error)
        this.providerStatus = { running: false, message: `MusicBrainz decisions failed: ${message}`, state: 'failed' }
        throw error
      }
    },
    async checkArtistProvider(artistId: number) {
      const artistName = this.collectionArtists.find((artist) => artist.id === artistId)?.name
        ?? this.artists.find((artist) => artist.id === artistId)?.name
        ?? `artist ${artistId}`
      this.providerStatus = { running: true, message: `Checking provider links for ${artistName}`, state: 'running' }
      try {
        const summary = await apiSend<ProviderCheckSummary>(
          withQuery(`/api/provider-checks/artist/${artistId}`, { collectionId: this.selectedCollectionId }),
          'POST',
        )
        this.providerCheckRuns = await apiGet<ProviderCheckRun[]>('/api/provider-checks/runs?limit=25')
        this.invalidateCollectionContent(this.selectedCollectionId ?? undefined)
        await this.loadArtists()
        await this.refreshCollectionContext()
        this.invalidateCollectionMetadata(this.selectedCollectionId ?? undefined)
        const detail = summary.messages.join(' ').trim()
        const message = detail || `Provider check complete: ${summary.newAlbumCount} new albums`
        this.providerStatus = { running: false, message, state: providerSummaryState(summary) }
        return summary
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error)
        this.providerStatus = { running: false, message: `Provider check failed: ${message}`, state: 'failed' }
        throw error
      }
    },
    async checkCollectionProviders(collectionId: string) {
      const collection = this.collections.find((item) => item.id === collectionId)
      const collectionName = collection?.name ?? collectionId
      this.providerStatus = { running: true, message: `Checking provider links for ${collectionName}`, state: 'running' }
      try {
        const summary = await apiSend<ProviderCheckSummary>(
          `/api/provider-checks/collection/${encodeURIComponent(collectionId)}`,
          'POST',
        )
        this.providerCheckRuns = await apiGet<ProviderCheckRun[]>('/api/provider-checks/runs?limit=25')
        this.invalidateCollectionContent(collectionId)
        await this.loadArtists()
        await this.refreshCollectionContext()
        this.invalidateCollectionMetadata(collectionId)
        const detail = summary.messages.join(' ').trim()
        const message = detail || `Provider check complete: ${summary.newAlbumCount} new albums`
        this.providerStatus = { running: false, message, state: providerSummaryState(summary) }
        return summary
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error)
        this.providerStatus = { running: false, message: `Provider check failed: ${message}`, state: 'failed' }
        throw error
      }
    },
    async checkAllProviders() {
      this.providerStatus = { running: true, message: 'Checking all provider links', state: 'running' }
      try {
        const summary = await apiSend<ProviderCheckSummary>('/api/provider-checks/all', 'POST')
        this.invalidateCollectionContent()
        await this.loadAll()
        await this.refreshCollectionContext()
        this.invalidateCollectionMetadata()
        const detail = summary.messages.join(' ').trim()
        const message = detail || `Provider check complete: ${summary.newAlbumCount} new albums`
        this.providerStatus = { running: false, message, state: providerSummaryState(summary) }
        return summary
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error)
        this.providerStatus = { running: false, message: `Provider check failed: ${message}`, state: 'failed' }
        throw error
      }
    },
  },
})

function hasCachedValue<T>(cache: Record<string, T>, key: string) {
  return Object.prototype.hasOwnProperty.call(cache, key)
}

function providerSummaryState(summary: ProviderCheckSummary): StatusHistoryEntry['state'] {
  if (summary.errorCount > 0) {
    return 'failed'
  }
  if (summary.messages.some((message) => message.includes('No enabled provider links found'))) {
    return 'warning'
  }
  return 'done'
}
