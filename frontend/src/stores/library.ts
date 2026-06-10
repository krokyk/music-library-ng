import { defineStore } from 'pinia'
import { apiGet, apiSend, apiText } from '@/api'
import { formatDateWithJavaPattern } from '@/dateFormat'
import type {
  Album,
  Artist,
  ArtistProviderLink,
  CollectionFolderCandidate,
  CollectionMetadata,
  MusicRootInfo,
  MusicCollection,
  ScanJobStatus,
  ProviderCheckRun,
  ProviderCheckSummary,
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
  providerUrl: string
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
  uiSettings: UiSettings
  statusHistory: StatusHistoryEntry[]
  manualStatus: { id: number; message: string; state: Exclude<StatusHistoryEntry['state'], 'running'> } | null
  providerStatus: { running: boolean; message: string | null; state: StatusHistoryEntry['state'] }
  loading: boolean
  error: string | null
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
    status: 120,
    action: 178,
  },
}

const defaultActionLabelThresholds = {
  collections: 600,
  artists: 900,
  albums: 600,
  titles: 1000,
}

const defaultActionLabelThresholdConstraints = {
  min: {
    collections: 400,
    artists: 700,
    albums: 400,
    titles: 800,
  },
  max: 2000,
  step: 50,
}

let scanJobPoller: number | null = null

export const useLibraryStore = defineStore('library', {
  state: (): State => ({
    artists: [],
    albums: [],
    collections: [],
    collectionCandidates: [],
    collectionArtists: [],
    collectionAlbums: [],
    collectionTitleItems: [],
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
    uiSettings: {
      statusCompleteVisibleMs: 10000,
      scanPollIntervalMs: 200,
      collectionScanSpinnerEnabled: true,
      artistScanSpinnerEnabled: true,
      collectionScanProgressEnabled: true,
      statusHistoryDateFormat: 'yyyy-MM-dd HH:mm:ss.SSS',
      releaseDateDisplayFormat: 'yyyy-MM-dd',
      statusBarLocation: 'top',
      actionLabelThresholds: defaultActionLabelThresholds,
      workspaceColumnDefaults: defaultWorkspaceColumnDefaults,
      tableGridColumnMinWidth: 40,
      actionLabelThresholdConstraints: defaultActionLabelThresholdConstraints,
      defaults: {
        statusCompleteVisibleMs: 10000,
        scanPollIntervalMs: 200,
        collectionScanSpinnerEnabled: true,
        artistScanSpinnerEnabled: true,
        collectionScanProgressEnabled: true,
        statusHistoryDateFormat: 'yyyy-MM-dd HH:mm:ss.SSS',
        releaseDateDisplayFormat: 'yyyy-MM-dd',
        statusBarLocation: 'top',
        actionLabelThresholds: defaultActionLabelThresholds,
        workspaceColumnDefaults: defaultWorkspaceColumnDefaults,
        tableGridColumnMinWidth: 40,
      },
      overrides: {
        statusCompleteVisibleMs: false,
        scanPollIntervalMs: false,
        collectionScanSpinnerEnabled: false,
        artistScanSpinnerEnabled: false,
        collectionScanProgressEnabled: false,
        statusHistoryDateFormat: false,
        releaseDateDisplayFormat: false,
        statusBarLocation: false,
        collectionActionLabelThreshold: false,
        artistActionLabelThreshold: false,
        albumActionLabelThreshold: false,
        titleActionLabelThreshold: false,
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
    error: null,
  }),
  actions: {
    async loadAll() {
      this.loading = true
      this.error = null
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
        this.error = error instanceof Error ? error.message : String(error)
      } finally {
        this.loading = false
      }
    },
    async loadCollections() {
      this.loading = true
      this.error = null
      try {
        this.collections = await apiGet<MusicCollection[]>('/api/collections')
      } catch (error) {
        this.error = error instanceof Error ? error.message : String(error)
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
      if (this.selectedCollectionId === collection.id) {
        await this.selectCollection(collection.id)
      }
      return collection
    },
    async deleteCollection(collectionId: string) {
      await apiSend(`/api/collections/${encodeURIComponent(collectionId)}`, 'DELETE')
      this.collections = this.collections.filter((collection) => collection.id !== collectionId)
      this.invalidateCollectionMetadata(collectionId)
      if (this.selectedCollectionId === collectionId) {
        this.selectedCollectionId = null
        this.selectedArtistId = null
        this.collectionArtists = []
        this.collectionAlbums = []
        this.collectionTitleItems = []
      }
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
      this.error = null
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
        this.error = error instanceof Error ? error.message : String(error)
      } finally {
        this.loading = false
      }
    },
    async loadUiSettings() {
      try {
        this.uiSettings = await apiGet<UiSettings>('/api/settings/ui')
      } catch (error) {
        this.error = error instanceof Error ? error.message : String(error)
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
        await this.loadTitlesForSelectedCollection()
      } else {
        this.collectionTitleItems = []
        await this.loadArtistsForSelectedCollection()
      }
    },
    async loadTitlesForSelectedCollection() {
      if (!this.selectedCollectionId) {
        this.collectionTitleItems = []
        return
      }
      this.collectionTitleItems = await apiGet<Album[]>(
        `/api/collections/${encodeURIComponent(this.selectedCollectionId)}/titles`,
      )
    },
    async createTitleItem(collectionId: string, payload: { title: string; artistName?: string | null; releaseDate?: string | null; sortName?: string | null }) {
      const created = await apiSend<Album>(
        `/api/collections/${encodeURIComponent(collectionId)}/titles`,
        'POST',
        payload,
      )
      this.collectionTitleItems = [created, ...this.collectionTitleItems.filter((current) => current.id !== created.id)]
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
      this.invalidateCollectionMetadata(this.selectedCollectionId)
      return updated
    },
    async deleteTitleLocalPath(item: Album) {
      if (!this.selectedCollectionId) {
        return
      }
      await apiSend(`/api/collections/${encodeURIComponent(this.selectedCollectionId)}/titles/${item.id}`, 'DELETE')
      await this.loadTitlesForSelectedCollection()
      this.invalidateCollectionMetadata(this.selectedCollectionId)
    },
    async loadArtistsForSelectedCollection() {
      if (!this.selectedCollectionId) {
        this.collectionArtists = []
        return
      }
      this.collectionArtists = await apiGet<Artist[]>(
        withQuery('/api/artists', { collectionId: this.selectedCollectionId }),
      )
      if (this.selectedArtistId && !this.collectionArtists.some((artist) => artist.id === this.selectedArtistId)) {
        this.selectedArtistId = null
        this.collectionAlbums = []
      }
    },
    async selectArtist(artistId: number) {
      this.selectedArtistId = artistId
      await this.loadAlbumsForSelectedArtist()
    },
    async loadAlbumsForSelectedArtist() {
      if (!this.selectedCollectionId || !this.selectedArtistId) {
        this.collectionAlbums = []
        return
      }
      this.collectionAlbums = await apiGet<Album[]>(
        withQuery('/api/albums', {
          artistId: this.selectedArtistId,
        }),
      )
    },
    async refreshCollectionContext() {
      if (!this.selectedCollectionId) {
        return
      }
      const collection = this.collections.find((item) => item.id === this.selectedCollectionId)
      if (collection?.type === 'TITLE') {
        await this.loadTitlesForSelectedCollection()
        return
      }
      await this.loadArtistsForSelectedCollection()
      if (this.selectedArtistId) {
        await this.loadAlbumsForSelectedArtist()
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
        await this.loadTitlesForSelectedCollection()
      } else {
        await this.loadArtistsForSelectedCollection()
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
      await this.refreshCollectionContext()
      this.invalidateCollectionMetadata()
      return artist
    },
    async addAlbum(artistId: number, title: string, releaseDate: string | null, checked: boolean) {
      const album = await apiSend<Album>('/api/albums', 'POST', { artistId, title, releaseDate, checked })
      this.albums = [album, ...this.albums.filter((item) => item.id !== album.id)]
      await this.refreshCollectionContext()
    },
    async updateAlbum(album: Album) {
      const updated = await apiSend<Album>(`/api/albums/${album.id}`, 'PUT', {
        title: album.title,
        releaseDate: album.releaseDate,
        checked: album.checked,
        notes: album.notes,
      })
      this.replaceAlbum(updated)
      await this.refreshCollectionContext()
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
          const status = await this.loadScanJob()
          if (!status || status.status !== 'RUNNING') {
            this.stopScanJobPolling()
            this.invalidateCollectionMetadata(status?.requestedCollectionId ?? status?.activeCollectionId ?? undefined)
            await this.loadSettings()
            if (status?.kind === 'LOCAL_ALBUMS') {
              await this.refreshCollectionContext()
            } else {
              await this.refreshCollectionArtistsOnly(true)
            }
          }
        } catch (error) {
          this.stopScanJobPolling()
          this.error = error instanceof Error ? error.message : String(error)
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
      this.scanJob = await apiGet<ScanJobStatus>('/api/scan/jobs/current')
      return this.scanJob
    },
    async cancelScanJob() {
      this.scanJob = await apiSend<ScanJobStatus>('/api/scan/jobs/current/cancel', 'POST')
      return this.scanJob
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
    async saveProviderLink(artistId: number, payload: ProviderLinkPayload) {
      const body = {
        providerId: payload.providerId,
        providerUrl: payload.providerUrl,
        enabled: payload.enabled,
      }
      if (payload.id) {
        await apiSend<ArtistProviderLink>(`/api/artists/${artistId}/provider-links/${payload.id}`, 'PUT', body)
      } else {
        await apiSend<ArtistProviderLink>(`/api/artists/${artistId}/provider-links`, 'POST', body)
      }
      await this.loadProviderLinks(artistId)
      await this.loadArtists()
      await this.refreshCollectionContext()
    },
    async addProviderLink(artistId: number, providerId: string, providerUrl: string) {
      await this.saveProviderLink(artistId, { providerId, providerUrl, enabled: true })
    },
    async deleteProviderLink(artistId: number, linkId: number) {
      await apiSend(`/api/artists/${artistId}/provider-links/${linkId}`, 'DELETE')
      await this.loadProviderLinks(artistId)
      await this.loadArtists()
      await this.refreshCollectionContext()
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

function providerSummaryState(summary: ProviderCheckSummary): StatusHistoryEntry['state'] {
  if (summary.errorCount > 0) {
    return 'failed'
  }
  if (summary.messages.some((message) => message.includes('No enabled provider links found'))) {
    return 'warning'
  }
  return 'done'
}
