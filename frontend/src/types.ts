export interface Artist {
  id: number
  name: string
  sortName?: string | null
  notes?: string | null
  collectionIds: string[]
  localCollectionIds: string[]
  albumCount: number
  checkedAlbumCount: number
  uncheckedAlbumCount: number
  localAlbumCount: number
  providerLinkCount: number
  providerId?: string | null
  providerArtistId?: string | null
  providerArtistName?: string | null
  providerUrl?: string | null
  providerArtistType?: string | null
  providerArtistCountry?: string | null
  providerArtistDisambiguation?: string | null
  providerArtistActive?: boolean | null
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

export interface AlbumCollection {
  id: string
  name: string
}

export interface Album {
  id: number
  artistIds: number[]
  collections: AlbumCollection[]
  artistName: string
  title: string
  releaseDate?: string | null
  sortName?: string | null
  sortNameSource: 'AUTO' | 'MANUAL'
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
  type: 'ARTIST' | 'TITLE'
  parser: string
  lastScanAt?: string | null
  lastScanStatus?: string | null
  lastScanMessage?: string | null
}

export interface CollectionFolderCandidate {
  folderName: string
  relativePath: string
  collectionName: string
}

export interface CollectionMetadata {
  collectionId: string
  artistCount: number
  contributorArtistCount: number
  localAlbumCount: number
  knownAlbumCount: number
  uncheckedAlbumCount: number
  checkedAlbumCount: number
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
  reportPath?: string | null
}

export interface ScanJobStatus {
  id: string
  status: string
  kind: 'COLLECTION' | 'LOCAL_ALBUMS'
  requestedCollectionId?: string | null
  requestedCollectionName?: string | null
  requestedArtistId?: number | null
  requestedArtistName?: string | null
  activeCollectionId?: string | null
  activeCollectionName?: string | null
  activeArtistId?: number | null
  activeArtistName?: string | null
  itemTotal: number
  itemProcessed: number
  parsedCount: number
  createdCount: number
  skippedCount: number
  cancelRequested: boolean
  message?: string | null
  runIds: number[]
}

export interface UiSettingsValues {
  statusCompleteVisibleMs: number
  scanPollIntervalMs: number
  collectionScanSpinnerEnabled: boolean
  artistScanSpinnerEnabled: boolean
  collectionScanProgressEnabled: boolean
  providerBatchRescanDelayMinutes: number
  statusHistoryDateFormat: string
  releaseDateDisplayFormat: string
  statusBarLocation: 'top' | 'bottom'
  workspaceColumnDefaults: WorkspaceColumnWidths
  artistsScreenColumnDefaults: ArtistsScreenColumnWidths
  tableGridColumnMinWidth: number
}

export interface UiSettings extends UiSettingsValues {
  defaults: UiSettingsValues
  overrides: UiSettingOverrides
}

export interface UiSettingOverrides {
  statusCompleteVisibleMs: boolean
  scanPollIntervalMs: boolean
  collectionScanSpinnerEnabled: boolean
  artistScanSpinnerEnabled: boolean
  collectionScanProgressEnabled: boolean
  providerBatchRescanDelayMinutes: boolean
  statusHistoryDateFormat: boolean
  releaseDateDisplayFormat: boolean
  statusBarLocation: boolean
}

export interface WorkspaceColumnWidths {
  artist: {
    name: number
  }
  album: {
    name: number
    releaseDate: number
    checked: number
    collections: number
    action: number
  }
  title: {
    title: number
    artist: number
    releaseDate: number
    action: number
  }
}

export interface ArtistsScreenColumnWidths {
  name: number
  country: number
  type: number
  status: number
  albums: number
  unchecked: number
  local: number
  provider: number
  action: number
}

export interface StatusHistoryEntry {
  id: number
  createdAt: string
  message: string
  state: 'running' | 'done' | 'warning' | 'failed' | 'info'
  scanRunIds?: number[]
  providerRunIds?: number[]
}

export interface UserPreference {
  key: string
  value: string
  updatedAt: string
}

export interface ScanEvent {
  id: number
  scanRunId: number
  level: string
  message: string
  createdAt: string
}

export interface ProviderCheckEvent {
  id: number
  runId: number
  artistId?: number | null
  providerLinkId?: number | null
  level: string
  message: string
  createdAt: string
}

export interface ArtistProviderLink {
  id: number
  artistId: number
  artistName: string
  providerId: string
  providerArtistId?: string | null
  providerArtistName?: string | null
  providerArtistType?: string | null
  providerArtistCountry?: string | null
  providerArtistDisambiguation?: string | null
  providerArtistActive?: boolean | null
  providerUrl?: string | null
  enabled: boolean
  lastCheckedAt?: string | null
  lastSuccessAt?: string | null
  lastErrorAt?: string | null
  lastErrorMessage?: string | null
  createdAt: string
  updatedAt: string
}

export interface RemoteReleaseGroup {
  providerId: string
  providerReleaseGroupId: string
  title: string
  releaseDate?: string | null
  primaryType?: string | null
  secondaryTypes: string[]
  providerUrl?: string | null
}

export interface ArtistProviderCandidate {
  providerId: string
  providerArtistId: string
  providerArtistName: string
  providerUrl: string
  type?: string | null
  country?: string | null
  disambiguation?: string | null
  active?: boolean | null
  providerScore: number
  matchScore: number
  matchedLocalAlbums: string[]
  releaseGroups: RemoteReleaseGroup[]
}

export type ArtistProviderBulkMatchStatus =
  | 'MATCHED'
  | 'NEEDS_REVIEW'
  | 'NO_MATCH'
  | 'SKIPPED_EXISTING'
  | 'ERROR'

export interface ArtistProviderBulkMatchItem {
  artistId: number
  artistName?: string | null
  status: ArtistProviderBulkMatchStatus
  message?: string | null
  providerLink?: ArtistProviderLink | null
  acceptedCandidate?: ArtistProviderCandidate | null
  candidates: ArtistProviderCandidate[]
}

export interface ArtistProviderBulkMatchResult {
  requestedArtistCount: number
  processedArtistCount: number
  matchedCount: number
  reviewCount: number
  noMatchCount: number
  skippedCount: number
  errorCount: number
  items: ArtistProviderBulkMatchItem[]
  messages: string[]
}

export interface ProviderRefreshResult {
  runId: number
  artistId: number
  artistName: string
  providerId: string
  foundReleaseGroupCount: number
  existingAlbumCount: number
  createdAlbumCount: number
  skippedCount: number
  messages: string[]
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

export interface ProviderCheckJobStatus {
  id: string
  status: string
  kind: 'PROVIDER_ARTIST' | 'PROVIDER_COLLECTION' | 'PROVIDER_ALL'
  requestedCollectionId?: string | null
  requestedCollectionName?: string | null
  requestedArtistId?: number | null
  requestedArtistName?: string | null
  activeArtistId?: number | null
  activeArtistName?: string | null
  itemTotal: number
  itemProcessed: number
  skippedArtistCount: number
  foundAlbumCount: number
  newAlbumCount: number
  existingAlbumCount: number
  errorCount: number
  cancelRequested: boolean
  message?: string | null
  artistIds: number[]
  runIds: number[]
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
