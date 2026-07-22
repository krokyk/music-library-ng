import { defineStore } from 'pinia'
import { apiGet, apiSend } from '@/api'
import { formatDateWithJavaPattern } from '@/dateFormat'
import { providerDefinition, type ProviderId } from '@/providers'
import type {
  Album,
  Artist,
  ArtistCountryConflict,
  ArtistProviderConflicts,
  ArtistProviderBulkMatchResult,
  ArtistProviderCandidate,
  ArtistProviderLink,
  ArtistStatusConflict,
  CollectionFolderCandidate,
  CollectionDeletePreview,
  CollectionDeleteResult,
  CollectionMetadata,
  MusicRootInfo,
  MusicCollection,
  ProviderCheckJobStatus,
  ProviderConflictResolutionRequest,
  ProviderConflictResolutionResult,
  ProviderReleaseYearConflict,
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
  pendingCollectionCandidates: CollectionFolderCandidate[]
  collectionCreationRunning: boolean
  deletingCollectionId: number | null
  collectionArtists: Artist[]
  collectionAlbums: Album[]
  collectionTitleItems: Album[]
  collectionArtistsByCollection: Record<number, Artist[]>
  collectionArtistsLoading: Record<number, boolean>
  collectionAlbumsByArtist: Record<string, Album[]>
  collectionAlbumsLoading: Record<string, boolean>
  collectionTitleItemsByCollection: Record<number, Album[]>
  collectionTitleItemsLoading: Record<number, boolean>
  collectionMetadata: Record<number, CollectionMetadata>
  collectionMetadataLoading: Record<number, boolean>
  selectedCollectionId: number | null
  selectedArtistId: number | null
  musicRoot: MusicRootInfo | null
  scanJob: ScanJobStatus | null
  providerJob: ProviderCheckJobStatus | null
  artistCountryConflicts: ArtistCountryConflict[]
  artistStatusConflicts: ArtistStatusConflict[]
  providerReleaseYearConflicts: ProviderReleaseYearConflict[]
  providerTitleConflicts: ProviderTitleConflict[]
  uiSettings: UiSettings
  preferenceCache: Record<string, UserPreference | null>
  statusHistory: StatusHistoryEntry[]
  manualStatus: { id: number; message: string; state: Exclude<StatusHistoryEntry['state'], 'running'> } | null
  providerStatus: {
    running: boolean
    message: string | null
    state: StatusHistoryEntry['state']
    reports?: ReportArtifact[]
  }
  loading: boolean
  libraryLoaded: boolean
  libraryLoading: boolean
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

let scanJobPoller: number | null = null
let scanJobPollingActive = false
let providerJobPoller: number | null = null
let pendingAlbumUpdate: Promise<void> | null = null

export const useLibraryStore = defineStore('library', {
  state: (): State => ({
    artists: [],
    albums: [],
    collections: [],
    collectionCandidates: [],
    pendingCollectionCandidates: [],
    collectionCreationRunning: false,
    deletingCollectionId: null,
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
    providerJob: null,
    artistCountryConflicts: [],
    artistStatusConflicts: [],
    providerReleaseYearConflicts: [],
    providerTitleConflicts: [],
    uiSettings: null!,
    preferenceCache: {},
    statusHistory: [],
    manualStatus: null,
    providerStatus: {
      running: false,
      message: null,
      state: 'info',
    },
    loading: false,
    libraryLoaded: false,
    libraryLoading: false,
  }),
  actions: {
    async loadAll() {
      if (pendingAlbumUpdate) {
        await pendingAlbumUpdate.catch(() => undefined)
      }
      if (this.libraryLoaded || this.libraryLoading) {
        return
      }
      this.libraryLoading = true
      this.loading = true
      try {
        const [
          artists,
          albums,
          collections,
          musicRoot,
          artistCountryConflicts,
          artistStatusConflicts,
          providerReleaseYearConflicts,
          providerTitleConflicts,
        ] = await Promise.all([
          apiGet<Artist[]>('/api/artists'),
          apiGet<Album[]>('/api/albums'),
          apiGet<MusicCollection[]>('/api/collections'),
          apiGet<MusicRootInfo>('/api/settings/music-root'),
          apiGet<ArtistCountryConflict[]>('/api/provider-conflicts/artist-countries'),
          apiGet<ArtistStatusConflict[]>('/api/provider-conflicts/artist-statuses'),
          apiGet<ProviderReleaseYearConflict[]>('/api/provider-conflicts/release-years'),
          apiGet<ProviderTitleConflict[]>('/api/provider-conflicts/titles'),
        ])
        this.artists = artists
        this.albums = albums
        this.collections = collections
        this.musicRoot = musicRoot
        this.artistCountryConflicts = artistCountryConflicts
        this.artistStatusConflicts = artistStatusConflicts
        this.providerReleaseYearConflicts = providerReleaseYearConflicts
        this.providerTitleConflicts = providerTitleConflicts
        this.libraryLoaded = true
      } catch (error) {
        this.showErrorStatus(error, 'Unable to load library data')
      } finally {
        this.libraryLoading = false
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
      const candidates = await apiGet<CollectionFolderCandidate[]>('/api/collections/candidates')
      const unavailablePaths = new Set([
        ...this.collections.map((collection) => collection.relativePath),
        ...this.pendingCollectionCandidates.map((candidate) => candidate.relativePath),
      ])
      this.collectionCandidates = candidates.filter((candidate) => !unavailablePaths.has(candidate.relativePath))
    },
    async createCollection(relativePath: string) {
      const collection = await apiSend<MusicCollection>('/api/collections', 'POST', { relativePath })
      this.pendingCollectionCandidates = this.pendingCollectionCandidates.filter((candidate) => candidate.relativePath !== relativePath)
      this.collections = [...this.collections.filter((item) => item.id !== collection.id), collection]
        .sort((left, right) => left.name.localeCompare(right.name))
      this.collectionCandidates = this.collectionCandidates.filter((candidate) => candidate.relativePath !== relativePath)
      this.showStatus(`Added collection "${collection.name}" (${collection.type === 'TITLE' ? 'Title' : 'Artist'}).`, 'done')
      return collection
    },
    queueCollectionCreation(candidate: CollectionFolderCandidate) {
      if (
        this.pendingCollectionCandidates.some((item) => item.relativePath === candidate.relativePath)
        || this.collections.some((collection) => collection.relativePath === candidate.relativePath)
      ) {
        return
      }
      this.collectionCandidates = this.collectionCandidates.filter((item) => item.relativePath !== candidate.relativePath)
      this.pendingCollectionCandidates = [...this.pendingCollectionCandidates, candidate]
      void this.processCollectionCreationQueue()
    },
    async processCollectionCreationQueue() {
      if (this.collectionCreationRunning) {
        return
      }
      this.collectionCreationRunning = true
      try {
        while (this.pendingCollectionCandidates.length > 0) {
          const candidate = this.pendingCollectionCandidates[0]
          try {
            await this.createCollection(candidate.relativePath)
          } catch (error) {
            this.collectionCandidates = [
              ...this.collectionCandidates.filter((item) => item.relativePath !== candidate.relativePath),
              candidate,
            ]
              .sort((left, right) => left.folderName.localeCompare(right.folderName))
            this.showErrorStatus(error, 'Unable to add collection')
          } finally {
            this.pendingCollectionCandidates = this.pendingCollectionCandidates
              .filter((item) => item.relativePath !== candidate.relativePath)
          }
        }
      } finally {
        this.collectionCreationRunning = false
      }
    },
    async updateCollection(collectionId: number, payload: { name: string, type: MusicCollection['type'] }) {
      const collection = await apiSend<MusicCollection>(`/api/collections/${encodeURIComponent(collectionId)}`, 'PUT', {
        name: payload.name,
        type: payload.type,
      })
      this.collections = this.collections.map((item) => (item.id === collection.id ? collection : item))
      this.invalidateCollectionContent(collection.id)
      return collection
    },
    async loadCollectionDeletePreview(collectionId: number) {
      return apiGet<CollectionDeletePreview>(`/api/collections/${encodeURIComponent(collectionId)}/delete-preview`)
    },
    async deleteCollection(collectionId: number) {
      const wasSelected = this.selectedCollectionId === collectionId
      const previousArtistId = this.selectedArtistId
      this.deletingCollectionId = collectionId
      if (wasSelected) {
        this.selectedCollectionId = null
        this.selectedArtistId = null
      }
      try {
        const result = await apiSend<CollectionDeleteResult>(`/api/collections/${encodeURIComponent(collectionId)}`, 'DELETE')
        this.invalidateCollectionMetadata()
        this.invalidateCollectionContent()
        await this.loadArtists()
        this.collections = this.collections.filter((collection) => collection.id !== collectionId)
        this.albums = this.albums.filter((album) => album.collection.id !== collectionId)
        if (wasSelected) {
          this.collectionArtists = []
          this.collectionAlbums = []
          this.collectionTitleItems = []
        }
        return result
      } catch (error) {
        if (wasSelected) {
          this.selectedCollectionId = collectionId
          this.selectedArtistId = previousArtistId
        }
        throw error
      } finally {
        this.deletingCollectionId = null
      }
    },
    invalidateCollectionContent(collectionId?: number) {
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
    async loadCollectionMetadata(collectionId: number, force = false) {
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
    invalidateCollectionMetadata(collectionId?: number) {
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
      this.uiSettings = await apiGet<UiSettings>('/api/settings/ui')
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
    async loadArtist(artistId: number, collectionId?: number | null) {
      return apiGet<Artist>(withQuery(`/api/artists/${artistId}`, { collectionId }))
    },
    async selectCollection(collectionId: number) {
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
    async loadTitlesForCollection(collectionId: number, force = false, options: { clearCurrent?: boolean } = {}) {
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
    async loadArtistsForSelectedCollection() {
      if (!this.selectedCollectionId) {
        this.collectionArtists = []
        return
      }
      await this.loadArtistsForCollection(this.selectedCollectionId)
    },
    async loadArtistsForCollection(collectionId: number, force = false, options: { clearCurrent?: boolean } = {}) {
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
    async refreshArtistAfterScanStep(artistId: number | null | undefined, collectionId?: number | null) {
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
    async refreshCollectionAfterScan(collectionId?: number | null) {
      const selectedCollection = collectionId && this.selectedCollectionId === collectionId
        ? this.collections.find((collection) => collection.id === collectionId)
        : null
      if (selectedCollection?.type === 'TITLE' && collectionId) {
        this.collectionTitleItemsLoading = { ...this.collectionTitleItemsLoading, [collectionId]: true }
      } else if (selectedCollection && collectionId) {
        this.collectionArtistsLoading = { ...this.collectionArtistsLoading, [collectionId]: true }
      }
      this.invalidateCollectionMetadata(collectionId ?? undefined)
      this.invalidateCollectionContent(collectionId ?? undefined)
      const [, , albums] = await Promise.all([
        this.loadCollections(),
        this.loadArtists(),
        apiGet<Album[]>('/api/albums'),
      ])
      this.albums = albums
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
        await this.loadProviderConflicts()
        return artist
      }
      await this.loadArtists()
      this.invalidateCollectionContent()
      await this.refreshCollectionContext()
      this.invalidateCollectionMetadata()
      return artist
    },
    async updateAlbum(album: Album) {
      const operation = (async () => {
        const previous = this.findCachedAlbum(album.id)
        const updated = await apiSend<Album>(`/api/albums/${album.id}`, 'PUT', {
          title: album.title,
          releaseYear: album.releaseYear,
          checked: album.checked,
          notes: album.notes,
        })
        this.replaceAlbum(updated)
        this.updateArtistAlbumCheckCounts(previous, updated)
        this.invalidateCollectionMetadata()
        await this.loadProviderConflicts()
      })()
      pendingAlbumUpdate = operation
      try {
        await operation
      } finally {
        if (pendingAlbumUpdate === operation) {
          pendingAlbumUpdate = null
        }
      }
    },
    async rehomeAlbum(album: Album, collectionId: number) {
      const previousCollectionId = album.collection.id
      const updated = await apiSend<Album>(
        `/api/albums/${album.id}/collection`,
        'PUT',
        { collectionId },
      )
      this.replaceAlbum(updated)
      this.invalidateCollectionMetadata(previousCollectionId)
      this.invalidateCollectionMetadata(collectionId)
      await this.refreshArtistsAfterScopedJob(updated.artistIds, this.selectedCollectionId)
      return updated
    },
    async refreshArtistAfterScopedJob(artistId: number, collectionId?: number | null) {
      await this.refreshArtistsAfterScopedJob([artistId], collectionId)
    },
    async refreshArtistsAfterScopedJob(artistIds: number[], collectionId?: number | null) {
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
    async refreshSingleArtistAfterScopedJob(artistId: number, collectionId?: number | null) {
      const [globalArtist, scopedArtist] = await Promise.all([
        this.loadArtist(artistId),
        collectionId ? this.loadArtist(artistId, collectionId) : Promise.resolve(null),
      ])
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
    replaceCollectionArtist(artist: Artist, collectionId?: number | null) {
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
    removeCollectionArtistFromCache(artistId: number, collectionId: number) {
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
    invalidateOtherCollectionArtistCaches(collectionId: number) {
      this.collectionArtistsByCollection = Object.fromEntries(
        Object.entries(this.collectionArtistsByCollection)
          .filter(([key]) => Number(key) === collectionId),
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
    invalidateAlbumCacheForArtist(artistId: number) {
      const cacheKey = String(artistId)
      const { [cacheKey]: _albums, ...rest } = this.collectionAlbumsByArtist
      this.collectionAlbumsByArtist = rest
    },
    async runScanJob(collectionId?: number) {
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
    async startScanJob(collectionId?: number) {
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
    async runProviderArtistJob(artistId: number, collectionId?: number | null) {
      const scopedCollectionId = collectionId === undefined ? this.selectedCollectionId : collectionId
      await this.startProviderArtistJob(artistId, scopedCollectionId)
      this.startProviderJobPolling()
      return this.providerJob
    },
    async runProviderCollectionJob(collectionId: number) {
      await this.startProviderCollectionJob(collectionId)
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
    async startProviderArtistJob(artistId: number, collectionId?: number | null) {
      const scopedCollectionId = collectionId === undefined ? this.selectedCollectionId : collectionId
      this.providerJob = await apiSend<ProviderCheckJobStatus>(
        withQuery(`/api/provider-checks/jobs/artist/${artistId}`, { collectionId: scopedCollectionId }),
        'POST',
      )
      return this.providerJob
    },
    async startProviderCollectionJob(collectionId: number) {
      this.providerJob = await apiSend<ProviderCheckJobStatus>(
        `/api/provider-checks/jobs/collection/${encodeURIComponent(collectionId)}`,
        'POST',
      )
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
    async loadProviderConflicts() {
      const [artistCountryConflicts, artistStatusConflicts, releaseYearConflicts, titleConflicts] = await Promise.all([
        apiGet<ArtistCountryConflict[]>('/api/provider-conflicts/artist-countries'),
        apiGet<ArtistStatusConflict[]>('/api/provider-conflicts/artist-statuses'),
        apiGet<ProviderReleaseYearConflict[]>('/api/provider-conflicts/release-years'),
        apiGet<ProviderTitleConflict[]>('/api/provider-conflicts/titles'),
      ])
      this.artistCountryConflicts = artistCountryConflicts
      this.artistStatusConflicts = artistStatusConflicts
      this.providerReleaseYearConflicts = releaseYearConflicts
      this.providerTitleConflicts = titleConflicts
      return { artistCountryConflicts, artistStatusConflicts, releaseYearConflicts, titleConflicts }
    },
    async openArtistProviderConflicts(artistId: number) {
      const conflicts = await apiSend<ArtistProviderConflicts>(
        `/api/provider-conflicts/artists/${artistId}/open`,
        'POST',
      )
      this.replaceArtistProviderConflicts(artistId, conflicts)
      await this.refreshArtistAfterScopedJob(artistId, this.currentCollectionScopeForArtist(artistId))
      return conflicts
    },
    replaceArtistProviderConflicts(artistId: number, conflicts: ArtistProviderConflicts) {
      this.artistCountryConflicts = [
        ...this.artistCountryConflicts.filter((conflict) => conflict.artistId !== artistId),
        ...conflicts.countries,
      ]
      this.artistStatusConflicts = [
        ...this.artistStatusConflicts.filter((conflict) => conflict.artistId !== artistId),
        ...conflicts.statuses,
      ]
      this.providerTitleConflicts = [
        ...this.providerTitleConflicts.filter((conflict) => conflict.artistId !== artistId),
        ...conflicts.titles,
      ]
      this.providerReleaseYearConflicts = [
        ...this.providerReleaseYearConflicts.filter((conflict) => conflict.artistId !== artistId),
        ...conflicts.years,
      ]
    },
    async resolveProviderConflict(request: ProviderConflictResolutionRequest) {
      const result = await apiSend<ProviderConflictResolutionResult>('/api/provider-conflicts/resolve', 'POST', request)
      if (result.artist) {
        this.replaceArtist(result.artist)
      }
      if (result.album) {
        this.replaceAlbum(result.album)
      }
      this.invalidateCollectionMetadata()
      await this.loadProviderConflicts()
      await this.refreshArtistAfterScopedJob(request.artistId, this.currentCollectionScopeForArtist(request.artistId))
      return result
    },
    async loadPreference(key: string) {
      if (Object.prototype.hasOwnProperty.call(this.preferenceCache, key)) {
        return this.preferenceCache[key]
      }
      try {
        const preference = await apiGet<UserPreference>(`/api/preferences/${encodeURIComponent(key)}`)
        this.preferenceCache = { ...this.preferenceCache, [key]: preference }
        return preference
      } catch (error) {
        this.preferenceCache = { ...this.preferenceCache, [key]: null }
        return null
      }
    },
    async savePreference(key: string, value: string) {
      const preference = await apiSend<UserPreference>(`/api/preferences/${encodeURIComponent(key)}`, 'PUT', { value })
      this.preferenceCache = { ...this.preferenceCache, [key]: preference }
      return preference
    },
    async searchProviderCandidates(artistId: number, providerId: ProviderId) {
      return apiGet<ArtistProviderCandidate[]>(`/api/artists/${artistId}/provider-candidates/${providerId}`)
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
      await this.refreshArtistAfterScopedJob(artistId, this.currentCollectionScopeForArtist(artistId))
      return provider
    },
    async clearArtistProvider(artistId: number, providerId: string) {
      await apiSend(`/api/artists/${artistId}/providers/${encodeURIComponent(providerId)}`, 'DELETE')
      await this.refreshArtistAfterScopedJob(artistId, this.currentCollectionScopeForArtist(artistId))
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

function hasCachedValue<T>(cache: Record<string | number, T>, key: string | number) {
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
