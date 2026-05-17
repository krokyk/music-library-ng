import { defineStore } from 'pinia'
import { apiGet, apiSend } from '@/api'
import type { Album, AlbumStatus, Artist, MusicRootInfo, MusicCollection, ScanEvent, ScanRun } from '@/types'

interface State {
  artists: Artist[]
  albums: Album[]
  collections: MusicCollection[]
  musicRoot: MusicRootInfo | null
  scanRuns: ScanRun[]
  scanEvents: Record<number, ScanEvent[]>
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
    async addAlbum(artistId: number, title: string, releaseYear: number | null, status: AlbumStatus, collectionId?: string | null) {
      await apiSend<Album>('/api/albums', 'POST', { artistId, title, releaseYear, status, collectionId })
      await this.loadAll()
    },
    async updateAlbum(album: Album) {
      await apiSend<Album>(`/api/albums/${album.id}`, 'PUT', {
        artistId: album.artistId,
        title: album.title,
        releaseYear: album.releaseYear,
        status: album.status,
        relativePath: album.relativePath,
        collectionId: album.collectionId,
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
  },
})
