export interface Artist {
  id: number
  name: string
  sortName?: string | null
  countryOverride?: string | null
  activeOverride?: boolean | null
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
  providerCountry?: string | null
  providerDisambiguation?: string | null
  providerActive?: boolean | null
  providerLastErrorMessage?: string | null
  providerLinks: ArtistProviderLink[]
  collectionAlbumCount: number
  localScanErrorMessage?: string | null
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
}

export interface AlbumProviderLink {
  id: number
  albumId: number
  providerId: string
  providerReleaseGroupId: string
  providerTitle: string
  providerReleaseDate?: string | null
  providerUrl?: string | null
  releaseDateResolution?: string | null
  titleResolution?: string | null
  releaseDateConflict: boolean
  titleConflict: boolean
  createdAt: string
  updatedAt: string
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
  providerLinks: AlbumProviderLink[]
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

export interface ReportArtifact {
  title: string
  path?: string | null
  text: string
}

export interface ScanJobStatus {
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
  processedArtistIds: number[]
  itemTotal: number
  itemProcessed: number
  artistCount: number
  parsedCount: number
  createdCount: number
  skippedCount: number
  cancelRequested: boolean
  message?: string | null
  reports: ReportArtifact[]
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
  reports?: ReportArtifact[]
}

export interface UserPreference {
  key: string
  value: string
  updatedAt: string
}

export interface ArtistProviderLink {
  id: number
  artistId: number
  artistName: string
  providerId: string
  providerArtistId?: string | null
  providerArtistName?: string | null
  providerCountry?: string | null
  providerDisambiguation?: string | null
  providerActive?: boolean | null
  providerUrl?: string | null
  enabled: boolean
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

export interface ArtistProviderCandidateAlbum {
  title: string
  providerReleaseDate?: string | null
  providerUrl?: string | null
  localAlbumId?: number | null
  localTitle?: string | null
  localReleaseDate?: string | null
  localOnDisk: boolean
  releaseDateConflict: boolean
  matchType: 'exact' | 'normalized' | 'fuzzy' | 'none'
  titleScore: number
  evidenceStrength: number
  localEvidenceKind: 'local' | 'checked' | 'unchecked' | 'provider-only'
  genericTitle: boolean
}

export interface ArtistProviderCandidate {
  providerId: string
  providerArtistId: string
  providerArtistName: string
  providerUrl: string
  country?: string | null
  disambiguation?: string | null
  active?: boolean | null
  providerScore: number
  matchScore: number
  finalScore: number
  nameScore: number
  albumEvidenceScore: number
  yearBonus: number
  evidenceSummary: string
  matchedLocalAlbums: string[]
  albumEvidence: ArtistProviderCandidateAlbum[]
  releaseGroups: RemoteReleaseGroup[]
  albums: ArtistProviderCandidateAlbum[]
}

export type ArtistProviderBulkMatchStatus =
  | 'MATCHED'
  | 'NEEDS_MANUAL'
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
  manualCount: number
  noMatchCount: number
  skippedCount: number
  errorCount: number
  items: ArtistProviderBulkMatchItem[]
  messages: string[]
}

export interface ProviderCheckJobStatus {
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
  releaseDateConflictCount: number
  titleConflictCount: number
  errorCount: number
  cancelRequested: boolean
  message?: string | null
  artistIds: number[]
  reports: ReportArtifact[]
}

export interface ProviderReleaseDateConflictSource {
  providerLinkId: number
  providerId: string
  providerTitle: string
  providerReleaseDate?: string | null
  providerUrl?: string | null
}

export interface ProviderReleaseDateConflict {
  albumId: number
  providerLinkId: number
  artistId: number
  artistName: string
  albumTitle: string
  localReleaseDate?: string | null
  providerTitle: string
  providerReleaseDate?: string | null
  providerId: string
  providerUrl?: string | null
  localRelativePath?: string | null
  sources: ProviderReleaseDateConflictSource[]
}

export interface ProviderTitleConflictSource {
  providerLinkId: number
  providerId: string
  providerTitle: string
  providerReleaseDate?: string | null
  providerUrl?: string | null
}

export interface ProviderTitleConflict {
  albumId: number
  providerLinkId: number
  artistId: number
  artistName: string
  albumTitle: string
  localReleaseDate?: string | null
  providerTitle: string
  providerReleaseDate?: string | null
  providerId: string
  providerUrl?: string | null
  localRelativePath?: string | null
  sources: ProviderTitleConflictSource[]
}

export interface AudioTagFilePlan {
  relativePath: string
  status: string
  message: string
}

export interface AlbumReleaseDateConflictFolderPlan {
  localPathId: number
  collectionId: string
  collectionName: string
  sourcePath: string
  targetPath: string
  sourceRelativePath: string
  targetRelativePath: string
  audioFileCount: number
  unsupportedFileCount: number
  files: AudioTagFilePlan[]
}

export interface AlbumReleaseDateConflictPlan {
  albumId: number
  providerLinkId: number
  albumTitle: string
  localReleaseDate?: string | null
  providerTitle: string
  providerReleaseDate?: string | null
  sourcePath: string
  targetPath: string
  sourceRelativePath: string
  targetRelativePath: string
  folderCount: number
  audioFileCount: number
  unsupportedFileCount: number
  folders: AlbumReleaseDateConflictFolderPlan[]
  files: AudioTagFilePlan[]
  warnings: string[]
}

export interface AlbumReleaseDateConflictResult {
  album: Album
  sourcePath: string
  targetPath: string
  folderCount: number
  duplicateAlbumsMerged: number
  tagFilesUpdated: number
  files: AudioTagFilePlan[]
  warnings: string[]
}

export interface AlbumTitleConflictPlan {
  albumId: number
  providerLinkId: number
  albumTitle: string
  localReleaseDate?: string | null
  providerTitle: string
  providerReleaseDate?: string | null
  sourcePath: string
  targetPath: string
  sourceRelativePath: string
  targetRelativePath: string
  folderCount: number
  audioFileCount: number
  unsupportedFileCount: number
  folders: AlbumReleaseDateConflictFolderPlan[]
  files: AudioTagFilePlan[]
  warnings: string[]
}

export interface AlbumTitleConflictResult {
  album: Album
  sourcePath: string
  targetPath: string
  folderCount: number
  duplicateAlbumsMerged: number
  tagFilesUpdated: number
  files: AudioTagFilePlan[]
  warnings: string[]
}
