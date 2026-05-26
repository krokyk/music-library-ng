export interface Artist {
  id: number
  name: string
  sortName?: string | null
  notes?: string | null
  collectionIds: string[]
  albumCount: number
  checkedAlbumCount: number
  uncheckedAlbumCount: number
  localAlbumCount: number
  providerLinkCount: number
  createdAt: string
  updatedAt: string
}

export interface AlbumLocalPath {
  id: number
  albumId: number
  collectionId: string
  collectionName: string
  relativePath: string
  resolvedPath?: string | null
  onDisk: boolean
  firstSeenAt: string
  lastSeenAt: string
  missingSince?: string | null
}

export interface Album {
  id: number
  artistId: number
  artistName: string
  title: string
  releaseYear?: number | null
  releaseDate?: string | null
  checked: boolean
  hasLocalPath: boolean
  onDisk: boolean
  localPaths: AlbumLocalPath[]
  notes?: string | null
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
  missingCount: number
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

export interface ArtistProviderLink {
  id: number
  artistId: number
  artistName: string
  providerId: string
  providerUrl: string
  enabled: boolean
  lastCheckedAt?: string | null
  lastSuccessAt?: string | null
  lastErrorAt?: string | null
  lastErrorMessage?: string | null
  createdAt: string
  updatedAt: string
}

export interface ProviderCheckSummary {
  runId: number
  processedArtistCount: number
  skippedArtistCount: number
  foundAlbumCount: number
  newAlbumCount: number
  existingAlbumCount: number
  errorCount: number
  messages: string[]
}

export interface ProviderCheckRun {
  id: number
  artistId?: number | null
  providerLinkId?: number | null
  startedAt: string
  finishedAt?: string | null
  status: string
  processedArtistCount: number
  foundAlbumCount: number
  newAlbumCount: number
  existingAlbumCount: number
  errorCount: number
  message?: string | null
}
