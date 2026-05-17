export type AlbumStatus = 'CHECKED' | 'MISSING' | 'WANTED' | 'IGNORED'

export interface Artist {
  id: number
  name: string
  sortName?: string | null
  notes?: string | null
  createdAt: string
  updatedAt: string
}

export interface Album {
  id: number
  artistId: number
  artistName: string
  title: string
  releaseYear?: number | null
  status: AlbumStatus
  relativePath?: string | null
  resolvedPath?: string | null
  collectionId?: string | null
  collectionName?: string | null
  createdAt: string
  updatedAt: string
}

export interface MusicCollection {
  id: string
  name: string
  relativePath: string
  resolvedPath?: string | null
  exists: boolean
  parser: string
  enabled: boolean
  lastScanAt?: string | null
  lastScanStatus?: string | null
  lastScanMessage?: string | null
}

export interface RootCandidate {
  configuredPath: string
  resolvedPath: string
  exists: boolean
  markersFound: boolean
}

export interface MusicRootInfo {
  selectedRoot?: string | null
  detected: boolean
  valid: boolean
  markers: string[]
  candidates: RootCandidate[]
}

export interface ScanRun {
  id: number
  collectionId?: string | null
  collectionName?: string | null
  startedAt: string
  finishedAt?: string | null
  status: string
  parsedCount: number
  createdCount: number
  updatedCount: number
  skippedCount: number
  message?: string | null
}

export interface ScanEvent {
  id: number
  scanRunId: number
  level: string
  message: string
  createdAt: string
}
