import { defineStore } from 'pinia'
import { apiGet, apiSend } from '@/api'
import type {
  Album,
  Artist,
  ArtistProviderLink,
  CollectionFolderCandidate,
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
  selectedCollectionId: string | null
  selectedArtistId: number | null
  musicRoot: MusicRootInfo | null
  scanRuns: ScanRun[]
  scanJob: ScanJobStatus | null
  scanEvents: Record<number, ScanEvent[]>
  providerLinks: Record<number, ArtistProviderLink[]>
  providerCheckRuns: ProviderCheckRun[]
  uiSettings: UiSettings
  statusHistory: StatusHistoryEntry[]
  providerStatus: { running: boolean; message: string | null }
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

export const useLibraryStore = defineStore('library', {
  state: (): State => ({
    artists: [],
    albums: [],
    collections: [],
    collectionCandidates: [],
    collectionArtists: [],
    collectionAlbums: [],
    selectedCollectionId: null,
    selectedArtistId: null,
    musicRoot: null,
    scanRuns: [],
    scanJob: null,
    scanEvents: {},
    providerLinks: {},
    providerCheckRuns: [],
    uiSettings: {
      statusCompleteVisibleMs: 4000,
      scanPollIntervalMs: 100,
      collectionScanSpinnerEnabled: true,
      collectionScanProgressEnabled: true,
      defaults: {
        statusCompleteVisibleMs: 4000,
        scanPollIntervalMs: 100,
        collectionScanSpinnerEnabled: true,
        collectionScanProgressEnabled: true,
      },
      overrides: {
        statusCompleteVisibleMs: false,
        scanPollIntervalMs: false,
        collectionScanSpinnerEnabled: false,
        collectionScanProgressEnabled: false,
      },
    },
    statusHistory: [],
    providerStatus: {
      running: false,
      message: null,
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
    async updateCollection(collectionId: string, payload: { name: string }) {
      const collection = await apiSend<MusicCollection>(`/api/collections/${encodeURIComponent(collectionId)}`, 'PUT', {
        name: payload.name,
      })
      this.collections = this.collections.map((item) => (item.id === collection.id ? collection : item))
      return collection
    },
    async deleteCollection(collectionId: string) {
      await apiSend(`/api/collections/${encodeURIComponent(collectionId)}`, 'DELETE')
      this.collections = this.collections.filter((collection) => collection.id !== collectionId)
      if (this.selectedCollectionId === collectionId) {
        this.selectedCollectionId = null
        this.selectedArtistId = null
        this.collectionArtists = []
        this.collectionAlbums = []
      }
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
    addStatusHistory(message: string, state: StatusHistoryEntry['state'] = 'info') {
      this.statusHistory = [
        {
          id: Date.now() * 1000 + Math.floor(Math.random() * 1000),
          createdAt: new Date().toLocaleString(),
          message,
          state,
        },
        ...this.statusHistory,
      ].slice(0, 100)
    },
    async loadArtists(search?: string) {
      this.artists = await apiGet<Artist[]>(withQuery('/api/artists', { search }))
    },
    async selectCollection(collectionId: string) {
      this.selectedCollectionId = collectionId
      this.selectedArtistId = null
      this.collectionAlbums = []
      await this.loadArtistsForSelectedCollection()
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
          collectionId: this.selectedCollectionId,
          artistId: this.selectedArtistId,
        }),
      )
    },
    async refreshCollectionContext() {
      if (!this.selectedCollectionId) {
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
      await this.loadArtistsForSelectedCollection()
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
      return artist
    },
    async addAlbum(artistId: number, title: string, releaseYear: number | null, checked: boolean) {
      const album = await apiSend<Album>('/api/albums', 'POST', { artistId, title, releaseYear, checked })
      this.albums = [album, ...this.albums.filter((item) => item.id !== album.id)]
      await this.refreshCollectionContext()
    },
    async updateAlbum(album: Album) {
      const updated = await apiSend<Album>(`/api/albums/${album.id}`, 'PUT', {
        artistId: album.artistId,
        title: album.title,
        releaseYear: album.releaseYear,
        releaseDate: album.releaseDate,
        checked: album.checked,
        notes: album.notes,
      })
      this.replaceAlbum(updated)
      await this.refreshCollectionContext()
    },
    async deleteAlbum(albumId: number) {
      await apiSend(`/api/albums/${albumId}`, 'DELETE')
      this.albums = this.albums.filter((album) => album.id !== albumId)
      this.collectionAlbums = this.collectionAlbums.filter((album) => album.id !== albumId)
      await this.refreshCollectionContext()
    },
    replaceAlbum(album: Album) {
      this.albums = this.albums.some((item) => item.id === album.id)
        ? this.albums.map((item) => (item.id === album.id ? album : item))
        : [album, ...this.albums]
      this.collectionAlbums = this.collectionAlbums.some((item) => item.id === album.id)
        ? this.collectionAlbums.map((item) => (item.id === album.id ? album : item))
        : this.collectionAlbums
    },
    async scan(collectionId?: string) {
      const query = collectionId ? `?collectionId=${encodeURIComponent(collectionId)}` : ''
      await apiSend(`/api/scan${query}`, 'POST')
      await this.loadSettings()
      await this.refreshCollectionArtistsOnly(true)
    },
    async startScanJob(collectionId?: string) {
      const query = collectionId ? `?collectionId=${encodeURIComponent(collectionId)}` : ''
      this.scanJob = await apiSend<ScanJobStatus>(`/api/scan/jobs${query}`, 'POST')
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
      this.providerStatus = { running: true, message: `Checking provider links for ${artistName}` }
      try {
        const summary = await apiSend<ProviderCheckSummary>(`/api/provider-checks/artist/${artistId}`, 'POST')
        this.providerCheckRuns = await apiGet<ProviderCheckRun[]>('/api/provider-checks/runs?limit=25')
        await this.loadArtists()
        await this.refreshCollectionContext()
        const message = `Provider check complete: ${summary.newAlbumCount} new albums`
        this.providerStatus = { running: false, message }
        return summary
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error)
        this.providerStatus = { running: false, message: `Provider check failed: ${message}` }
        throw error
      }
    },
    async checkAllProviders() {
      this.providerStatus = { running: true, message: 'Checking all provider links' }
      try {
        const summary = await apiSend<ProviderCheckSummary>('/api/provider-checks/all', 'POST')
        await this.loadAll()
        await this.refreshCollectionContext()
        const message = `Provider check complete: ${summary.newAlbumCount} new albums`
        this.providerStatus = { running: false, message }
        return summary
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error)
        this.providerStatus = { running: false, message: `Provider check failed: ${message}` }
        throw error
      }
    },
  },
})
