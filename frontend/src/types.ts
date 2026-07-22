export interface Artist {
  id: number
  name: string
  sortName?: string | null
  countryOverride?: string | null
  activeOverride?: boolean | null
  collectionIds: number[]
  albumCount: number
  uncheckedAlbumCount: number
  localAlbumCount: number
  providerCountry?: string | null
  providerActive?: boolean | null
  providerLinks: ArtistProviderLink[]
}

export interface AlbumProviderLink {
  id: number
  albumId: number
  providerId: string
  providerReleaseGroupId: string
  providerTitle: string
  providerReleaseYear?: number | null
  providerUrl?: string | null
  releaseYearResolution?: string | null
  titleResolution?: string | null
  releaseYearConflict: boolean
  titleConflict: boolean
  createdAt: string
  updatedAt: string
}

export interface AlbumCollection {
  id: number
  name: string
}

export interface Album {
  id: number
  artistIds: number[]
  collection: AlbumCollection
  artistName?: string | null
  title: string
  releaseYear?: number | null
  sortName?: string | null
  sortNameSource: 'AUTO' | 'MANUAL'
  checked: boolean
  localRelativePath?: string | null
  resolvedPath?: string | null
  onDisk: boolean
  providerLinks: AlbumProviderLink[]
  notes?: string | null
  createdAt: string
  updatedAt: string
}

export interface MusicCollection {
  id: number
  name: string
  relativePath: string
  resolvedPath?: string | null
  exists: boolean
  type: 'ARTIST' | 'TITLE'
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
  collectionId: number
  artistCount: number
  contributorArtistCount: number
  localAlbumCount: number
  knownAlbumCount: number
  uncheckedAlbumCount: number
  checkedAlbumCount: number
}

export interface CollectionDeletePreview {
  collectionId: number
  albumCount: number
  artistCount: number
}

export interface CollectionDeleteResult {
  collectionId: number
  albumsDeleted: number
  artistsDeleted: number
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
  kind: 'COLLECTION'
  requestedCollectionId?: number | null
  requestedCollectionName?: string | null
  requestedArtistId?: number | null
  requestedArtistName?: string | null
  activeCollectionId?: number | null
  activeCollectionName?: string | null
  activeArtistId?: number | null
  activeArtistName?: string | null
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
  artistScanSpinnerEnabled: boolean
  providerBatchRescanDelayMinutes: number
  statusHistoryDateFormat: string
  statusBarLocation: 'top' | 'bottom'
  workspaceColumnDefaults: WorkspaceColumnWidths
  artistsScreenColumnDefaults: ArtistsScreenColumnWidths
  tableGridColumnMinWidth: number
}

export interface UiSettings extends UiSettingsValues {
  defaults: UiSettingsDefaults
}

export interface UiSettingsDefaults {
  statusCompleteVisibleMs: number
  scanPollIntervalMs: number
  providerBatchRescanDelayMinutes: number
}

export interface WorkspaceColumnWidths {
  artist: {
    name: number
  }
  album: {
    name: number
    releaseYear: number
    checked: number
    home: number
    action: number
  }
  title: {
    title: number
    artist: number
    releaseYear: number
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

export interface ArtistProviderCandidateAlbum {
  title: string
  providerReleaseYear?: number | null
  providerUrl?: string | null
  localAlbumId?: number | null
  localTitle?: string | null
  localReleaseYear?: number | null
  localOnDisk: boolean
  releaseYearConflict: boolean
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
  finalScore: number
  nameScore: number
  albumEvidenceScore: number
  yearBonus: number
  evidenceSummary: string
  albumEvidence: ArtistProviderCandidateAlbum[]
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
  kind: 'PROVIDER_ARTIST' | 'PROVIDER_COLLECTION'
  requestedCollectionId?: number | null
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
  releaseYearConflictCount: number
  titleConflictCount: number
  errorCount: number
  message?: string | null
  artistIds: number[]
  reports: ReportArtifact[]
}

export interface ArtistCountryConflictSource {
  providerLinkId: number
  providerId: string
  providerCountry?: string | null
  providerUrl?: string | null
}

export interface ArtistCountryConflict {
  artistId: number
  artistName: string
  sources: ArtistCountryConflictSource[]
}

export interface ArtistStatusConflictSource {
  providerLinkId: number
  providerId: string
  providerActive?: boolean | null
  providerUrl?: string | null
}

export interface ArtistStatusConflict {
  artistId: number
  artistName: string
  sources: ArtistStatusConflictSource[]
}

export interface ProviderReleaseYearConflictSource {
  providerLinkId: number
  providerId: string
  providerTitle: string
  providerReleaseYear?: number | null
  providerUrl?: string | null
}

export interface ProviderReleaseYearConflict {
  albumId: number
  providerLinkId: number
  artistId: number
  artistName: string
  albumTitle: string
  localReleaseYear?: number | null
  providerTitle: string
  providerReleaseYear?: number | null
  providerId: string
  providerUrl?: string | null
  localRelativePath?: string | null
  sources: ProviderReleaseYearConflictSource[]
}

export interface ProviderTitleConflictSource {
  providerLinkId: number
  providerId: string
  providerTitle: string
  providerReleaseYear?: number | null
  providerUrl?: string | null
}

export interface ProviderTitleConflict {
  albumId: number
  providerLinkId: number
  artistId: number
  artistName: string
  albumTitle: string
  localReleaseYear?: number | null
  providerTitle: string
  providerReleaseYear?: number | null
  providerId: string
  providerUrl?: string | null
  localRelativePath?: string | null
  sources: ProviderTitleConflictSource[]
}

export type ProviderConflictKind = 'TITLE' | 'YEAR' | 'COUNTRY' | 'STATUS'
export type ProviderConflictAction = 'USE_PROVIDER' | 'KEEP_LOCAL' | 'RESET_KEEP_LOCAL'

export interface ProviderConflictResolutionRequest {
  kind: ProviderConflictKind
  action?: ProviderConflictAction
  artistId: number
  albumId?: number
  providerLinkId?: number
  country?: string
  active?: boolean
}

export interface ProviderConflictResolutionResult {
  kind: ProviderConflictKind
  action?: ProviderConflictAction | null
  artistId: number
  albumId?: number | null
  artist?: Artist | null
  album?: Album | null
  messages: ProviderConflictResolutionMessage[]
}

export interface ProviderConflictResolutionMessage {
  severity: 'INFO' | 'WARNING' | 'ERROR'
  code: string
  summary: string
  details: string[]
}

export interface ArtistProviderConflicts {
  countries: ArtistCountryConflict[]
  statuses: ArtistStatusConflict[]
  titles: ProviderTitleConflict[]
  years: ProviderReleaseYearConflict[]
}
