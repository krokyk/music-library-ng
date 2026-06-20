<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useLibraryStore } from '@/stores/library'
import type {
  ActionLabelThresholds,
  ArtistProviderBulkMatchItem,
  ArtistProviderBulkMatchResult,
  UiSettingsValues,
} from '@/types'

type EditableUiSettingKey =
  | 'statusCompleteVisibleMs'
  | 'scanPollIntervalMs'
  | 'collectionScanSpinnerEnabled'
  | 'artistScanSpinnerEnabled'
  | 'collectionScanProgressEnabled'
  | 'statusBarLocation'
  | 'collectionActionLabelThreshold'
  | 'artistActionLabelThreshold'
  | 'albumActionLabelThreshold'
  | 'titleActionLabelThreshold'

type ActionLabelPane = keyof ActionLabelThresholds

interface UiForm {
  statusCompleteVisibleMs: number
  scanPollIntervalMs: number
  collectionScanSpinnerEnabled: boolean
  artistScanSpinnerEnabled: boolean
  collectionScanProgressEnabled: boolean
  statusHistoryDateFormat: string
  statusBarLocation: 'top' | 'bottom'
  actionLabelThresholds: ActionLabelThresholds
}

const store = useLibraryStore()
const { artists, collections, scanJob, scanRuns, scanEvents, musicRoot, uiSettings, error } = storeToRefs(store)

const scanPollMin = 100
const scanPollMax = 2000
const statusVisibleMin = 0
const statusVisibleMax = 30000

const uiForm = reactive<UiForm>({
  statusCompleteVisibleMs: 10000,
  scanPollIntervalMs: 200,
  collectionScanSpinnerEnabled: true,
  artistScanSpinnerEnabled: true,
  collectionScanProgressEnabled: true,
  statusHistoryDateFormat: 'yyyy-MM-dd HH:mm:ss.SSS',
  statusBarLocation: 'top',
  actionLabelThresholds: {
    collections: 600,
    artists: 900,
    albums: 600,
    titles: 1000,
  },
})
const savingUiSettings = ref(false)
const uiSaveTimer = ref<number | null>(null)
const pendingUiSettingKeys = new Set<EditableUiSettingKey>()
const scanIsRunning = computed(() => scanJob.value?.status === 'RUNNING')
const bulkMatchLoading = ref(false)
const bulkMatchDialog = ref(false)
const bulkMatchResult = ref<ArtistProviderBulkMatchResult | null>(null)
const bulkMatchItems = computed(() => bulkMatchResult.value?.items ?? [])

function syncUiForm() {
  uiForm.statusCompleteVisibleMs = uiSettings.value.statusCompleteVisibleMs
  uiForm.scanPollIntervalMs = uiSettings.value.scanPollIntervalMs
  uiForm.collectionScanSpinnerEnabled = uiSettings.value.collectionScanSpinnerEnabled
  uiForm.artistScanSpinnerEnabled = uiSettings.value.artistScanSpinnerEnabled
  uiForm.collectionScanProgressEnabled = uiSettings.value.collectionScanProgressEnabled
  uiForm.statusHistoryDateFormat = uiSettings.value.statusHistoryDateFormat
  uiForm.statusBarLocation = uiSettings.value.statusBarLocation
  uiForm.actionLabelThresholds = { ...uiSettings.value.actionLabelThresholds }
}

function settingValue(key: EditableUiSettingKey) {
  const value = key in uiForm ? uiForm[key as keyof UiForm] : null
  if (typeof value === 'boolean') {
    return value ? 'on' : 'off'
  }
  if (key === 'scanPollIntervalMs' || key === 'statusCompleteVisibleMs') {
    return `${value} ms`
  }
  return value
}

function collectionTypeIcon(type: string) {
  return type === 'TITLE' ? 'mdi-album' : 'mdi-account-music'
}

function collectionTypeIconClass(type: string) {
  return type === 'TITLE' ? 'collection-type-icon--title' : 'collection-type-icon--artist'
}

function collectionIsScanning(collectionId: string) {
  return scanIsRunning.value && scanJob.value?.activeCollectionId === collectionId
}

async function scanCollection(collectionId: string) {
  try {
    await store.runScanJob(collectionId)
  } catch (scanError) {
    store.showErrorStatus(scanError, 'Unable to start collection scan')
  }
}

async function runBulkMusicBrainzMatch() {
  if (artists.value.length === 0) {
    await store.loadArtists()
  }
  const artistIds = artists.value.map((artist) => artist.id)
  if (artistIds.length === 0) {
    return
  }
  bulkMatchLoading.value = true
  bulkMatchResult.value = null
  try {
    const result = await store.bulkMatchMusicBrainz(artistIds)
    bulkMatchResult.value = result
    bulkMatchDialog.value = true
  } catch (matchError) {
    if (!store.providerStatus.message?.startsWith('MusicBrainz bulk match failed')) {
      store.showErrorStatus(matchError, 'MusicBrainz bulk match failed')
    }
  } finally {
    bulkMatchLoading.value = false
  }
}

async function useBulkCandidate(item: ArtistProviderBulkMatchItem) {
  const candidate = bulkCandidate(item)
  if (!candidate) {
    return
  }
  try {
    const providerLink = await store.saveArtistProvider(item.artistId, {
      providerId: 'musicbrainz',
      providerArtistId: candidate.providerArtistId,
      providerArtistName: candidate.providerArtistName,
      providerUrl: candidate.providerUrl,
      enabled: true,
    })
    if (!bulkMatchResult.value) {
      return
    }
    const previousStatus = item.status
    bulkMatchResult.value = {
      ...bulkMatchResult.value,
      matchedCount: bulkMatchResult.value.matchedCount + (previousStatus === 'MATCHED' ? 0 : 1),
      reviewCount: bulkMatchResult.value.reviewCount - (previousStatus === 'NEEDS_REVIEW' ? 1 : 0),
      noMatchCount: bulkMatchResult.value.noMatchCount - (previousStatus === 'NO_MATCH' ? 1 : 0),
      skippedCount: bulkMatchResult.value.skippedCount - (previousStatus === 'SKIPPED_EXISTING' ? 1 : 0),
      errorCount: bulkMatchResult.value.errorCount - (previousStatus === 'ERROR' ? 1 : 0),
      items: bulkMatchResult.value.items.map((current) => current.artistId === item.artistId
        ? {
            ...current,
            status: 'MATCHED',
            message: `MusicBrainz provider saved: ${candidate.providerArtistName}`,
            providerLink,
            acceptedCandidate: candidate,
          }
        : current),
    }
    store.showStatus(`MusicBrainz provider saved for ${candidate.providerArtistName}.`, 'done')
  } catch (saveError) {
    store.showErrorStatus(saveError, 'Unable to save MusicBrainz provider')
  }
}

function bulkCandidate(item: ArtistProviderBulkMatchItem) {
  return item.acceptedCandidate ?? item.candidates[0] ?? null
}

function bulkEvidenceText(item: ArtistProviderBulkMatchItem) {
  const candidate = bulkCandidate(item)
  if (!candidate) {
    return ''
  }
  const albumMatches = candidate.matchedLocalAlbums.length
  const albumText = albumMatches === 1 ? '1 local album' : `${albumMatches} local albums`
  return `${candidate.matchScore} match / ${candidate.providerScore} MB${albumMatches > 0 ? ` / ${albumText}` : ''}`
}

function bulkStatusText(status: ArtistProviderBulkMatchItem['status']) {
  switch (status) {
    case 'MATCHED':
      return 'Matched'
    case 'NEEDS_REVIEW':
      return 'Needs review'
    case 'NO_MATCH':
      return 'No match'
    case 'SKIPPED_EXISTING':
      return 'Skipped'
    case 'ERROR':
      return 'Error'
    default:
      return status
  }
}

function bulkStatusColor(status: ArtistProviderBulkMatchItem['status']) {
  switch (status) {
    case 'MATCHED':
      return 'success'
    case 'NEEDS_REVIEW':
      return 'warning'
    case 'SKIPPED_EXISTING':
      return 'info'
    case 'ERROR':
      return 'error'
    default:
      return 'default'
  }
}

function openExternal(url?: string | null) {
  if (url) {
    window.open(url, '_blank', 'noopener')
  }
}

function normalizeUiForm() {
  uiForm.statusCompleteVisibleMs = normalizeNumber(
    uiForm.statusCompleteVisibleMs,
    uiSettings.value.defaults.statusCompleteVisibleMs,
    statusVisibleMin,
    statusVisibleMax,
  )
  uiForm.scanPollIntervalMs = normalizeNumber(
    uiForm.scanPollIntervalMs,
    uiSettings.value.defaults.scanPollIntervalMs,
    scanPollMin,
    scanPollMax,
  )
  uiForm.statusBarLocation = uiForm.statusBarLocation === 'bottom' ? 'bottom' : 'top'
  const constraints = uiSettings.value.actionLabelThresholdConstraints
  ;(['collections', 'artists', 'albums', 'titles'] as ActionLabelPane[]).forEach((pane) => {
    uiForm.actionLabelThresholds[pane] = normalizeNumber(
      uiForm.actionLabelThresholds[pane],
      uiSettings.value.defaults.actionLabelThresholds[pane],
      constraints.min[pane],
      constraints.max,
    )
  })
}

function normalizeNumber(value: unknown, fallback: number, min: number, max: number) {
  const numberValue = Number(value)
  if (!Number.isFinite(numberValue)) {
    return fallback
  }
  return Math.min(max, Math.max(min, Math.round(numberValue)))
}

function scheduleUiSettingsSave(key: EditableUiSettingKey) {
  pendingUiSettingKeys.add(key)
  if (uiSaveTimer.value !== null) {
    window.clearTimeout(uiSaveTimer.value)
  }
  uiSaveTimer.value = window.setTimeout(saveUiSettingsNow, 250)
}

async function saveUiSettingsNow(key?: EditableUiSettingKey) {
  if (uiSaveTimer.value !== null) {
    window.clearTimeout(uiSaveTimer.value)
    uiSaveTimer.value = null
  }
  const keysToSave = new Set(pendingUiSettingKeys)
  pendingUiSettingKeys.clear()
  if (key) {
    keysToSave.add(key)
  }
  if (keysToSave.size === 0) {
    return
  }
  normalizeUiForm()
  savingUiSettings.value = true
  try {
    await store.saveUiSettings(uiSettingsPayload([...keysToSave]))
    syncUiForm()
  } catch (saveError) {
    store.showErrorStatus(saveError, 'Unable to save UI settings')
  } finally {
    savingUiSettings.value = false
  }
}

function uiSettingsPayload(keys: EditableUiSettingKey[]) {
  const payload: Partial<UiSettingsValues> = {}
  keys.forEach((key) => {
    if (key === 'statusCompleteVisibleMs') payload.statusCompleteVisibleMs = uiForm.statusCompleteVisibleMs
    if (key === 'scanPollIntervalMs') payload.scanPollIntervalMs = uiForm.scanPollIntervalMs
    if (key === 'collectionScanSpinnerEnabled') payload.collectionScanSpinnerEnabled = uiForm.collectionScanSpinnerEnabled
    if (key === 'artistScanSpinnerEnabled') payload.artistScanSpinnerEnabled = uiForm.artistScanSpinnerEnabled
    if (key === 'collectionScanProgressEnabled') payload.collectionScanProgressEnabled = uiForm.collectionScanProgressEnabled
    if (key === 'statusBarLocation') payload.statusBarLocation = uiForm.statusBarLocation
    if (isActionLabelThresholdKey(key)) {
      payload.actionLabelThresholds = { ...uiForm.actionLabelThresholds }
    }
  })
  return payload
}

function isActionLabelThresholdKey(key: EditableUiSettingKey) {
  return key === 'collectionActionLabelThreshold'
    || key === 'artistActionLabelThreshold'
    || key === 'albumActionLabelThreshold'
    || key === 'titleActionLabelThreshold'
}

function scanPollChanged() {
  scheduleUiSettingsSave('scanPollIntervalMs')
}

function statusVisibleChanged() {
  scheduleUiSettingsSave('statusCompleteVisibleMs')
}

function statusBarLocationChanged() {
  scheduleUiSettingsSave('statusBarLocation')
}

function collectionScanSpinnerChanged() {
  scheduleUiSettingsSave('collectionScanSpinnerEnabled')
}

function artistScanSpinnerChanged() {
  scheduleUiSettingsSave('artistScanSpinnerEnabled')
}

function collectionScanProgressChanged() {
  scheduleUiSettingsSave('collectionScanProgressEnabled')
}

function actionLabelThresholdChanged(pane: ActionLabelPane) {
  scheduleUiSettingsSave(actionLabelSettingKey(pane))
}

function actionLabelSettingKey(pane: ActionLabelPane): EditableUiSettingKey {
  if (pane === 'collections') return 'collectionActionLabelThreshold'
  if (pane === 'artists') return 'artistActionLabelThreshold'
  if (pane === 'albums') return 'albumActionLabelThreshold'
  return 'titleActionLabelThreshold'
}

function actionLabelValue(pane: ActionLabelPane) {
  return `${uiForm.actionLabelThresholds[pane]} px`
}

function savePendingUiSettingsOnUnmount() {
  if (uiSaveTimer.value !== null) {
    window.clearTimeout(uiSaveTimer.value)
    void saveUiSettingsNow()
  }
}

async function resetUiSettings() {
  if (uiSaveTimer.value !== null) {
    window.clearTimeout(uiSaveTimer.value)
    uiSaveTimer.value = null
  }
  pendingUiSettingKeys.clear()
  savingUiSettings.value = true
  try {
    await store.resetUiSettings()
    syncUiForm()
  } catch (resetError) {
    store.showErrorStatus(resetError, 'Unable to reset UI settings')
  } finally {
    savingUiSettings.value = false
  }
}

onMounted(async () => {
  await Promise.all([store.loadSettings(), store.loadUiSettings(), store.loadScanJob(), store.loadArtists()])
  if (scanIsRunning.value) {
    store.startScanJobPolling()
  }
  syncUiForm()
})

onBeforeUnmount(() => {
  savePendingUiSettingsOnUnmount()
})
</script>

<template>
  <v-container fluid class="app-page settings-page">
    <v-sheet class="pane settings-pane">
      <div class="pane-header">
        <span>Settings</span>
      </div>

      <div class="settings-pane__body">
        <v-alert v-if="error" type="error" variant="tonal" class="mb-4">{{ error }}</v-alert>

        <section class="settings-section">
          <div class="settings-section__header">
            <div>
              <h2 class="settings-section__title">General</h2>
              <div class="settings-section__subtitle">Runtime behavior</div>
            </div>
            <v-btn
              size="small"
              variant="outlined"
              prepend-icon="mdi-backup-restore"
              :loading="savingUiSettings"
              @click="resetUiSettings"
            >
              Reset to defaults
            </v-btn>
          </div>

          <div class="settings-matrix">
            <div class="settings-cell settings-cell--label" title="How often the app polls running scan progress.">
              Progress polling
            </div>
            <div class="settings-cell settings-cell--control">
              <div class="settings-slider-control">
                <v-slider
                  v-model.number="uiForm.scanPollIntervalMs"
                  class="settings-slider"
                  color="primary"
                  density="compact"
                  hide-details
                  :min="scanPollMin"
                  :max="scanPollMax"
                  :step="50"
                  thumb-label
                  @update:model-value="scanPollChanged"
                ></v-slider>
                <span class="settings-readonly-value">{{ settingValue('scanPollIntervalMs') }}</span>
              </div>
            </div>
            <div class="settings-cell settings-cell--label" title="How long a completed status message remains visible before Idle returns.">
              Status message visibility
            </div>
            <div class="settings-cell settings-cell--control">
              <div class="settings-slider-control">
                <v-slider
                  v-model.number="uiForm.statusCompleteVisibleMs"
                  class="settings-slider"
                  color="primary"
                  density="compact"
                  hide-details
                  :min="statusVisibleMin"
                  :max="statusVisibleMax"
                  :step="500"
                  thumb-label
                  @update:model-value="statusVisibleChanged"
                ></v-slider>
                <span class="settings-readonly-value">{{ settingValue('statusCompleteVisibleMs') }}</span>
              </div>
            </div>
            <div class="settings-cell settings-cell--label" title="Places the status bar above or below the workspace panes.">
              Status bar location
            </div>
            <div class="settings-cell settings-cell--control">
              <v-btn-toggle
                v-model="uiForm.statusBarLocation"
                class="settings-toggle"
                color="primary"
                density="compact"
                mandatory
                variant="outlined"
                @update:model-value="statusBarLocationChanged"
              >
                <v-btn value="top">Top</v-btn>
                <v-btn value="bottom">Bottom</v-btn>
              </v-btn-toggle>
            </div>
          </div>
        </section>

        <section class="settings-section">
          <div class="settings-section__header">
            <div>
              <h2 class="settings-section__title">Workspace</h2>
              <div class="settings-section__subtitle">Pane controls and scan indicators</div>
            </div>
          </div>

          <div class="settings-matrix settings-matrix--workspace">
            <div class="settings-cell settings-cell--label" title="Shows a spinner before a collection while its collection scan is running.">
              Collection scan spinner
            </div>
            <div class="settings-cell settings-cell--control">
              <v-switch
                v-model="uiForm.collectionScanSpinnerEnabled"
                aria-label="Collection scan spinner"
                class="settings-switch"
                color="primary"
                density="compact"
                hide-details
                @update:model-value="collectionScanSpinnerChanged"
              ></v-switch>
            </div>
            <div class="settings-cell settings-cell--label" title="Pane width where collection row actions switch from icons to icon and text labels.">
              Collection action labels
            </div>
            <div class="settings-cell settings-cell--control">
              <div class="settings-slider-control">
                <v-slider
                  v-model.number="uiForm.actionLabelThresholds.collections"
                  class="settings-slider"
                  color="primary"
                  density="compact"
                  hide-details
                  :min="uiSettings.actionLabelThresholdConstraints.min.collections"
                  :max="uiSettings.actionLabelThresholdConstraints.max"
                  :step="uiSettings.actionLabelThresholdConstraints.step"
                  thumb-label
                  @update:model-value="actionLabelThresholdChanged('collections')"
                ></v-slider>
                <span class="settings-readonly-value">{{ actionLabelValue('collections') }}</span>
              </div>
            </div>
            <div class="settings-cell settings-cell--label" title="Pane width where title row actions switch from icons to icon and text labels.">
              Title action labels
            </div>
            <div class="settings-cell settings-cell--control">
              <div class="settings-slider-control">
                <v-slider
                  v-model.number="uiForm.actionLabelThresholds.titles"
                  class="settings-slider"
                  color="primary"
                  density="compact"
                  hide-details
                  :min="uiSettings.actionLabelThresholdConstraints.min.titles"
                  :max="uiSettings.actionLabelThresholdConstraints.max"
                  :step="uiSettings.actionLabelThresholdConstraints.step"
                  thumb-label
                  @update:model-value="actionLabelThresholdChanged('titles')"
                ></v-slider>
                <span class="settings-readonly-value">{{ actionLabelValue('titles') }}</span>
              </div>
            </div>

            <div class="settings-cell settings-cell--label" title="Shows a spinner before artists covered by a local or provider scan.">
              Artist scan spinner
            </div>
            <div class="settings-cell settings-cell--control">
              <v-switch
                v-model="uiForm.artistScanSpinnerEnabled"
                aria-label="Artist scan spinner"
                class="settings-switch"
                color="primary"
                density="compact"
                hide-details
                @update:model-value="artistScanSpinnerChanged"
              ></v-switch>
            </div>
            <div class="settings-cell settings-cell--label" title="Pane width where artist actions switch from icons to icon and text labels.">
              Artist action labels
            </div>
            <div class="settings-cell settings-cell--control">
              <div class="settings-slider-control">
                <v-slider
                  v-model.number="uiForm.actionLabelThresholds.artists"
                  class="settings-slider"
                  color="primary"
                  density="compact"
                  hide-details
                  :min="uiSettings.actionLabelThresholdConstraints.min.artists"
                  :max="uiSettings.actionLabelThresholdConstraints.max"
                  :step="uiSettings.actionLabelThresholdConstraints.step"
                  thumb-label
                  @update:model-value="actionLabelThresholdChanged('artists')"
                ></v-slider>
                <span class="settings-readonly-value">{{ actionLabelValue('artists') }}</span>
              </div>
            </div>
            <div class="settings-cell settings-cell--label" title="Pane width where album row actions switch from icons to icon and text labels.">
              Album action labels
            </div>
            <div class="settings-cell settings-cell--control">
              <div class="settings-slider-control">
                <v-slider
                  v-model.number="uiForm.actionLabelThresholds.albums"
                  class="settings-slider"
                  color="primary"
                  density="compact"
                  hide-details
                  :min="uiSettings.actionLabelThresholdConstraints.min.albums"
                  :max="uiSettings.actionLabelThresholdConstraints.max"
                  :step="uiSettings.actionLabelThresholdConstraints.step"
                  thumb-label
                  @update:model-value="actionLabelThresholdChanged('albums')"
                ></v-slider>
                <span class="settings-readonly-value">{{ actionLabelValue('albums') }}</span>
              </div>
            </div>
            <div class="settings-cell settings-cell--label" title="Shows the left-to-right scan progress fill behind a collection row.">
              Collection progress bar
            </div>
            <div class="settings-cell settings-cell--control">
              <v-switch
                v-model="uiForm.collectionScanProgressEnabled"
                aria-label="Collection progress bar"
                class="settings-switch"
                color="primary"
                density="compact"
                hide-details
                @update:model-value="collectionScanProgressChanged"
              ></v-switch>
            </div>
          </div>
        </section>

        <section class="settings-section">
          <div class="settings-section__header">
            <div>
              <h2 class="settings-section__title">Music Root</h2>
              <div class="mono-path">{{ musicRoot?.selectedRoot }}</div>
            </div>
            <div class="settings-section__chips">
              <v-chip v-if="musicRoot?.valid" color="success" size="small">valid</v-chip>
              <v-chip v-else color="error" size="small">invalid</v-chip>
              <v-chip v-if="musicRoot?.detected" color="primary" size="small">auto-detected</v-chip>
            </div>
          </div>

          <div class="settings-table-wrap">
            <v-table class="music-table" density="compact">
              <thead>
                <tr>
                  <th>Candidate</th>
                  <th>Resolved</th>
                  <th>Folder</th>
                  <th>Markers</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="candidate in musicRoot?.candidates ?? []" :key="candidate.configuredPath">
                  <td class="mono-path">{{ candidate.configuredPath }}</td>
                  <td class="mono-path">{{ candidate.resolvedPath }}</td>
                  <td>
                    <v-chip :color="candidate.exists ? 'success' : 'default'" size="small">
                      {{ candidate.exists ? 'exists' : 'missing' }}
                    </v-chip>
                  </td>
                  <td>
                    <v-chip :color="candidate.markersFound ? 'success' : 'warning'" size="small">
                      {{ candidate.markersFound ? 'found' : 'missing' }}
                    </v-chip>
                  </td>
                </tr>
              </tbody>
            </v-table>
          </div>
        </section>

        <section class="settings-section">
          <div class="settings-section__header">
            <div>
              <h2 class="settings-section__title">Provider Setup</h2>
              <div class="settings-section__subtitle">{{ artists.length }} artists</div>
            </div>
            <v-btn
              color="primary"
              variant="tonal"
              prepend-icon="mdi-account-search"
              :loading="bulkMatchLoading"
              :disabled="artists.length === 0 || bulkMatchLoading"
              @click="runBulkMusicBrainzMatch"
            >
              Bulk Match MusicBrainz
            </v-btn>
          </div>

          <div v-if="bulkMatchResult" class="dialog-chip-row">
            <v-chip size="small" color="success" variant="tonal">{{ bulkMatchResult.matchedCount }} matched</v-chip>
            <v-chip size="small" color="warning" variant="tonal">{{ bulkMatchResult.reviewCount }} review</v-chip>
            <v-chip size="small" variant="tonal">{{ bulkMatchResult.noMatchCount }} no match</v-chip>
            <v-chip size="small" color="info" variant="tonal">{{ bulkMatchResult.skippedCount }} skipped</v-chip>
            <v-chip size="small" color="error" variant="tonal">{{ bulkMatchResult.errorCount }} errors</v-chip>
          </div>
        </section>

        <section class="settings-section">
          <div class="settings-section__header">
            <div>
              <h2 class="settings-section__title">Collections</h2>
              <div class="settings-section__subtitle">{{ collections.length }} configured</div>
            </div>
          </div>

          <div class="settings-table-wrap">
            <v-table class="music-table" density="compact">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Type</th>
                  <th>Relative Path</th>
                  <th>Resolved Path</th>
                  <th>Last Scan</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="collection in collections" :key="collection.id">
                  <td class="cell-strong">{{ collection.name }}</td>
                  <td>
                    <v-chip size="small" variant="tonal" class="collection-type-chip">
                      <v-icon
                        :icon="collectionTypeIcon(collection.type)"
                        size="15"
                        class="collection-type-icon"
                        :class="collectionTypeIconClass(collection.type)"
                      ></v-icon>
                      <span>{{ collection.type.toLowerCase() }}</span>
                    </v-chip>
                  </td>
                  <td class="mono-path">{{ collection.relativePath }}</td>
                  <td>
                    <v-chip :color="collection.exists ? 'success' : 'warning'" size="small" class="mr-2">
                      {{ collection.exists ? 'exists' : 'missing' }}
                    </v-chip>
                    <span class="mono-path">{{ collection.resolvedPath ?? '' }}</span>
                  </td>
                  <td class="cell-muted">{{ collection.lastScanMessage ?? collection.lastScanStatus ?? '' }}</td>
                  <td class="text-right">
                    <v-btn
                      size="small"
                      variant="text"
                      prepend-icon="mdi-refresh"
                      :loading="collectionIsScanning(collection.id)"
                      :disabled="scanIsRunning"
                      @click="scanCollection(collection.id)"
                    >
                      Scan
                    </v-btn>
                  </td>
                </tr>
              </tbody>
            </v-table>
          </div>
        </section>

        <section class="settings-section settings-section--last">
          <div class="settings-section__header">
            <div>
              <h2 class="settings-section__title">Recent Scan Log</h2>
              <div class="settings-section__subtitle">{{ scanRuns.length }} recent runs</div>
            </div>
          </div>

          <v-expansion-panels variant="accordion">
            <v-expansion-panel v-for="run in scanRuns" :key="run.id" @group:selected="store.loadScanEvents(run.id)">
              <v-expansion-panel-title>
                <div class="d-flex align-center ga-3 w-100">
                  <v-chip
                    size="small"
                    :color="run.status === 'DONE' ? 'success' : run.status === 'FAILED' ? 'error' : 'warning'"
                  >
                    {{ run.status }}
                  </v-chip>
                  <span class="font-weight-medium">{{ run.collectionName ?? run.collectionId ?? 'All collections' }}</span>
                  <span class="text-medium-emphasis">
                    {{ run.parsedCount }} parsed,
                    {{ run.createdCount }} created,
                    {{ run.updatedCount }} updated,
                    {{ run.missingCount }} missing,
                    {{ run.skippedCount }} skipped
                  </span>
                  <span class="text-medium-emphasis ml-auto">{{ run.startedAt }}</span>
                </div>
              </v-expansion-panel-title>
              <v-expansion-panel-text>
                <div v-if="run.message" class="mb-3">{{ run.message }}</div>
                <v-list density="compact" lines="two">
                  <v-list-item v-for="event in scanEvents[run.id] ?? []" :key="event.id">
                    <template #prepend>
                      <v-chip
                        size="x-small"
                        :color="event.level === 'ERROR' ? 'error' : event.level === 'SKIPPED' ? 'warning' : 'primary'"
                      >
                        {{ event.level }}
                      </v-chip>
                    </template>
                    <v-list-item-title>{{ event.message }}</v-list-item-title>
                    <v-list-item-subtitle>{{ event.createdAt }}</v-list-item-subtitle>
                  </v-list-item>
                </v-list>
              </v-expansion-panel-text>
            </v-expansion-panel>
          </v-expansion-panels>
        </section>
      </div>
    </v-sheet>

    <v-dialog v-model="bulkMatchDialog" max-width="1100">
      <v-card class="dialog-card">
        <v-card-title>Bulk MusicBrainz Match</v-card-title>
        <v-card-text class="edit-form">
          <div v-if="bulkMatchResult" class="dialog-chip-row">
            <v-chip size="small" color="success" variant="tonal">{{ bulkMatchResult.matchedCount }} matched</v-chip>
            <v-chip size="small" color="warning" variant="tonal">{{ bulkMatchResult.reviewCount }} review</v-chip>
            <v-chip size="small" variant="tonal">{{ bulkMatchResult.noMatchCount }} no match</v-chip>
            <v-chip size="small" color="info" variant="tonal">{{ bulkMatchResult.skippedCount }} skipped</v-chip>
            <v-chip size="small" color="error" variant="tonal">{{ bulkMatchResult.errorCount }} errors</v-chip>
          </div>

          <div class="bulk-match-dialog__table">
            <v-table class="music-table" density="compact">
              <thead>
                <tr>
                  <th>Artist</th>
                  <th>Status</th>
                  <th>Candidate</th>
                  <th>Evidence</th>
                  <th>Message</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in bulkMatchItems" :key="item.artistId">
                  <td class="cell-strong">{{ item.artistName || `artist ${item.artistId}` }}</td>
                  <td>
                    <v-chip size="x-small" :color="bulkStatusColor(item.status)" variant="tonal">
                      {{ bulkStatusText(item.status) }}
                    </v-chip>
                  </td>
                  <td>
                    <template v-if="bulkCandidate(item)">
                      <div class="cell-strong">{{ bulkCandidate(item)?.providerArtistName }}</div>
                      <div class="mono-path">{{ bulkCandidate(item)?.providerArtistId }}</div>
                    </template>
                    <span v-else class="cell-muted">None</span>
                  </td>
                  <td>{{ bulkEvidenceText(item) }}</td>
                  <td>{{ item.message }}</td>
                  <td class="text-right">
                    <v-btn
                      size="small"
                      variant="text"
                      icon="mdi-open-in-new"
                      :disabled="!bulkCandidate(item)?.providerUrl"
                      @click="openExternal(bulkCandidate(item)?.providerUrl)"
                    ></v-btn>
                    <v-btn
                      v-if="item.status === 'NEEDS_REVIEW'"
                      size="small"
                      color="primary"
                      variant="text"
                      :disabled="!bulkCandidate(item)"
                      @click="useBulkCandidate(item)"
                    >
                      Use
                    </v-btn>
                  </td>
                </tr>
              </tbody>
            </v-table>
          </div>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn variant="text" @click="bulkMatchDialog = false">Close</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </v-container>
</template>
