import { defineStore } from 'pinia'
import { apiGet, apiSend } from '@/api'
import type {
  Album,
  Artist,
  ArtistProviderLink,
  MusicRootInfo,
  MusicCollection,
  ProviderCheckRun,
  ProviderCheckSummary,
  ScanEvent,
  ScanRun,
} from '@/types'

interface State {
  artists: Artist[]
  albums: Album[]
  collections: MusicCollection[]
  musicRoot: MusicRootInfo | null
  scanRuns: ScanRun[]
  scanEvents: Record<number, ScanEvent[]>
  providerLinks: Record<number, ArtistProviderLink[]>
  providerCheckRuns: ProviderCheckRun[]
  loading: boolean
  error: string | null
}

export const useLibraryStore = defineStore('library', {
  state: (): State => ({
    artists: [],
    albums: [],
    collections: [],
    musicRoot: null,
    scanRuns: [],
    scanEvents: {},
    providerLinks: {},
    providerCheckRuns: [],
    loading: false,
    error: null,
  }),
  actions: {
    async loadAll() {
      this.loading = true
      this.error = null
      try {
        const [artists, albums, collections, musicRoot] = await Promise.all([
          apiGet<Artist[]>('/api/artists'),
          apiGet<Album[]>('/api/albums'),
          apiGet<MusicCollection[]>('/api/collections'),
          apiGet<MusicRootInfo>('/api/settings/music-root'),
        ])
        this.artists = artists
        this.albums = albums
        this.collections = collections
        this.musicRoot = musicRoot
        this.scanRuns = await apiGet<ScanRun[]>('/api/scan/runs?limit=25')
        this.providerCheckRuns = await apiGet<ProviderCheckRun[]>('/api/provider-checks/runs?limit=25')
      } catch (error) {
        this.error = error instanceof Error ? error.message : String(error)
      } finally {
        this.loading = false
      }
    },
    async addArtist(name: string) {
      await apiSend<Artist>('/api/artists', 'POST', { name })
      await this.loadAll()
    },
    async addAlbum(artistId: number, title: string, releaseYear: number | null, checked: boolean) {
      await apiSend<Album>('/api/albums', 'POST', { artistId, title, releaseYear, checked })
      await this.loadAll()
    },
    async updateAlbum(album: Album) {
      await apiSend<Album>(`/api/albums/${album.id}`, 'PUT', {
        artistId: album.artistId,
        title: album.title,
        releaseYear: album.releaseYear,
        releaseDate: album.releaseDate,
        checked: album.checked,
        notes: album.notes,
      })
      await this.loadAll()
    },
    async scan(collectionId?: string) {
      const query = collectionId ? `?collectionId=${encodeURIComponent(collectionId)}` : ''
      await apiSend(`/api/scan${query}`, 'POST')
      await this.loadAll()
    },
    async loadScanEvents(runId: number) {
      this.scanEvents[runId] = await apiGet<ScanEvent[]>(`/api/scan/runs/${runId}/events`)
    },
    async selectMusicRoot(root: string) {
      this.musicRoot = await apiSend<MusicRootInfo>('/api/settings/music-root', 'PUT', { root })
      await this.loadAll()
    },
    async loadProviderLinks(artistId: number) {
      this.providerLinks[artistId] = await apiGet<ArtistProviderLink[]>(`/api/artists/${artistId}/provider-links`)
    },
    async addProviderLink(artistId: number, providerId: string, providerUrl: string) {
      await apiSend<ArtistProviderLink>(`/api/artists/${artistId}/provider-links`, 'POST', {
        providerId,
        providerUrl,
        enabled: true,
      })
      await this.loadProviderLinks(artistId)
      await this.loadAll()
    },
    async checkArtistProvider(artistId: number) {
      const summary = await apiSend<ProviderCheckSummary>(`/api/provider-checks/artist/${artistId}`, 'POST')
      await this.loadAll()
      return summary
    },
    async checkAllProviders() {
      const summary = await apiSend<ProviderCheckSummary>('/api/provider-checks/all', 'POST')
      await this.loadAll()
      return summary
    },
  },
})
