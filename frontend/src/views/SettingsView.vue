<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useLibraryStore } from '@/stores/library'
import type {
  UiSettingsValues,
} from '@/types'

type EditableUiSettingKey =
  | 'statusCompleteVisibleMs'
  | 'scanPollIntervalMs'
  | 'collectionScanSpinnerEnabled'
  | 'artistScanSpinnerEnabled'
  | 'collectionScanProgressEnabled'
  | 'providerBatchRescanDelayMinutes'
  | 'statusBarLocation'

interface UiForm {
  statusCompleteVisibleMs: number
  scanPollIntervalMs: number
  collectionScanSpinnerEnabled: boolean
  artistScanSpinnerEnabled: boolean
  collectionScanProgressEnabled: boolean
  providerBatchRescanDelayMinutes: number
  statusHistoryDateFormat: string
  statusBarLocation: 'top' | 'bottom'
}

const store = useLibraryStore()
const { collections, providerJob, providerStatus, scanJob, scanRuns, scanEvents, musicRoot, uiSettings } = storeToRefs(store)

const scanPollMin = 100
const scanPollMax = 2000
const statusVisibleMin = 0
const statusVisibleMax = 30000
const providerBatchRescanOptions = [
  { label: 'Off', minutes: 0 },
  { label: '30 min', minutes: 30 },
  { label: '1 hour', minutes: 60 },
  { label: '12 hours', minutes: 720 },
  { label: '1 day', minutes: 1440 },
  { label: '7 days', minutes: 10080 },
  { label: '30 days', minutes: 43200 },
]

const uiForm = reactive<UiForm>({
  statusCompleteVisibleMs: 10000,
  scanPollIntervalMs: 200,
  collectionScanSpinnerEnabled: true,
  artistScanSpinnerEnabled: true,
  collectionScanProgressEnabled: true,
  providerBatchRescanDelayMinutes: 60,
  statusHistoryDateFormat: 'yyyy-MM-dd HH:mm:ss.SSS',
  statusBarLocation: 'top',
})
const savingUiSettings = ref(false)
const uiSaveTimer = ref<number | null>(null)
const pendingUiSettingKeys = new Set<EditableUiSettingKey>()
const scanIsRunning = computed(() => scanJob.value?.status === 'RUNNING')
const providerIsRunning = computed(() => providerJob.value?.status === 'RUNNING' || providerStatus.value.running)
const scanActionsDisabled = computed(() => scanIsRunning.value || providerIsRunning.value)

function syncUiForm() {
  uiForm.statusCompleteVisibleMs = uiSettings.value.statusCompleteVisibleMs
  uiForm.scanPollIntervalMs = uiSettings.value.scanPollIntervalMs
  uiForm.collectionScanSpinnerEnabled = uiSettings.value.collectionScanSpinnerEnabled
  uiForm.artistScanSpinnerEnabled = uiSettings.value.artistScanSpinnerEnabled
  uiForm.collectionScanProgressEnabled = uiSettings.value.collectionScanProgressEnabled
  uiForm.providerBatchRescanDelayMinutes = uiSettings.value.providerBatchRescanDelayMinutes
  uiForm.statusHistoryDateFormat = uiSettings.value.statusHistoryDateFormat
  uiForm.statusBarLocation = uiSettings.value.statusBarLocation
}

function settingValue(key: EditableUiSettingKey) {
  const value = key in uiForm ? uiForm[key as keyof UiForm] : null
  if (typeof value === 'boolean') {
    return value ? 'on' : 'off'
  }
  if (key === 'scanPollIntervalMs' || key === 'statusCompleteVisibleMs') {
    return `${value} ms`
  }
  if (key === 'providerBatchRescanDelayMinutes') {
    return providerBatchRescanLabel(uiForm.providerBatchRescanDelayMinutes)
  }
  return value
}

const providerBatchRescanIndex = computed({
  get: () => providerBatchRescanIndexFor(uiForm.providerBatchRescanDelayMinutes),
  set: (value: number) => {
    const index = normalizeProviderBatchRescanIndex(value)
    uiForm.providerBatchRescanDelayMinutes = providerBatchRescanOptions[index].minutes
    scheduleUiSettingsSave('providerBatchRescanDelayMinutes')
  },
})

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
  uiForm.providerBatchRescanDelayMinutes = providerBatchRescanOptions[
    providerBatchRescanIndexFor(uiForm.providerBatchRescanDelayMinutes)
  ].minutes
  uiForm.statusBarLocation = uiForm.statusBarLocation === 'bottom' ? 'bottom' : 'top'
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
    if (key === 'providerBatchRescanDelayMinutes') payload.providerBatchRescanDelayMinutes = uiForm.providerBatchRescanDelayMinutes
    if (key === 'statusBarLocation') payload.statusBarLocation = uiForm.statusBarLocation
  })
  return payload
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

function normalizeProviderBatchRescanIndex(value: unknown) {
  const numberValue = Number(value)
  const index = Number.isFinite(numberValue) ? Math.round(numberValue) : 0
  return Math.min(providerBatchRescanOptions.length - 1, Math.max(0, index))
}

function providerBatchRescanIndexFor(minutes: number) {
  const normalized = normalizeNumber(minutes, uiSettings.value.defaults.providerBatchRescanDelayMinutes, 0, 43200)
  let closestIndex = 0
  let closestDistance = Number.POSITIVE_INFINITY
  providerBatchRescanOptions.forEach((option, index) => {
    const distance = Math.abs(option.minutes - normalized)
    if (distance < closestDistance) {
      closestDistance = distance
      closestIndex = index
    }
  })
  return closestIndex
}

function providerBatchRescanLabel(minutes: number) {
  return providerBatchRescanOptions[providerBatchRescanIndexFor(minutes)].label
}

function savePendingUiSettingsOnUnmount() {
  if (uiSaveTimer.value !== null) {
    window.clearTimeout(uiSaveTimer.value)
    void saveUiSettingsNow()
  }
}

async function resetUiSettings() {
  if (scanActionsDisabled.value) {
    return
  }
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
  await Promise.all([store.loadSettings(), store.loadUiSettings(), store.loadScanJob(), store.loadProviderJob()])
  if (scanIsRunning.value) {
    store.startScanJobPolling()
  }
  if (providerJob.value?.status === 'RUNNING') {
    store.startProviderJobPolling()
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
              :disabled="scanActionsDisabled"
              @click="resetUiSettings"
            >
              Reset to defaults
            </v-btn>
          </div>

          <div class="settings-matrix">
            <div class="settings-cell settings-cell--label">
              Progress polling
              <v-tooltip activator="parent" text="How often the app polls running scan progress." location="top"></v-tooltip>
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
                  :disabled="scanActionsDisabled"
                  thumb-label
                  @update:model-value="scanPollChanged"
                ></v-slider>
                <span class="settings-readonly-value">{{ settingValue('scanPollIntervalMs') }}</span>
              </div>
            </div>
            <div class="settings-cell settings-cell--label">
              Status message visibility
              <v-tooltip activator="parent" text="How long a completed status message remains visible before Idle returns." location="top"></v-tooltip>
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
                  :disabled="scanActionsDisabled"
                  thumb-label
                  @update:model-value="statusVisibleChanged"
                ></v-slider>
                <span class="settings-readonly-value">{{ settingValue('statusCompleteVisibleMs') }}</span>
              </div>
            </div>
            <div class="settings-cell settings-cell--label">
              Status bar location
              <v-tooltip activator="parent" text="Places the status bar above or below the workspace panes." location="top"></v-tooltip>
            </div>
            <div class="settings-cell settings-cell--control">
              <v-btn-toggle
                v-model="uiForm.statusBarLocation"
                class="settings-toggle"
                color="primary"
                density="compact"
                mandatory
                variant="outlined"
                :disabled="scanActionsDisabled"
                @update:model-value="statusBarLocationChanged"
              >
                <v-btn value="top">Top</v-btn>
                <v-btn value="bottom">Bottom</v-btn>
              </v-btn-toggle>
            </div>
            <div class="settings-cell settings-cell--label">
              Batch provider rescan delay
              <v-tooltip activator="parent" text="Batch provider scans skip links checked more recently than this. Off disables the recent-check skip." location="top"></v-tooltip>
            </div>
            <div class="settings-cell settings-cell--control">
              <div class="settings-slider-control">
                <v-slider
                  v-model.number="providerBatchRescanIndex"
                  class="settings-slider"
                  color="primary"
                  density="compact"
                  hide-details
                  :min="0"
                  :max="providerBatchRescanOptions.length - 1"
                  :step="1"
                  :disabled="scanActionsDisabled"
                  show-ticks="always"
                ></v-slider>
                <span class="settings-readonly-value">{{ settingValue('providerBatchRescanDelayMinutes') }}</span>
              </div>
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
            <div class="settings-cell settings-cell--label">
              Collection scan spinner
              <v-tooltip activator="parent" text="Shows a spinner before a collection while its collection scan is running." location="top"></v-tooltip>
            </div>
            <div class="settings-cell settings-cell--control">
              <v-switch
                v-model="uiForm.collectionScanSpinnerEnabled"
                aria-label="Collection scan spinner"
                class="settings-switch"
                color="primary"
                density="compact"
                :disabled="scanActionsDisabled"
                hide-details
                @update:model-value="collectionScanSpinnerChanged"
              ></v-switch>
            </div>

            <div class="settings-cell settings-cell--label">
              Artist scan spinner
              <v-tooltip activator="parent" text="Shows a spinner before artists covered by a local or provider scan." location="top"></v-tooltip>
            </div>
            <div class="settings-cell settings-cell--control">
              <v-switch
                v-model="uiForm.artistScanSpinnerEnabled"
                aria-label="Artist scan spinner"
                class="settings-switch"
                color="primary"
                density="compact"
                :disabled="scanActionsDisabled"
                hide-details
                @update:model-value="artistScanSpinnerChanged"
              ></v-switch>
            </div>
            <div class="settings-cell settings-cell--label">
              Collection progress bar
              <v-tooltip activator="parent" text="Shows the left-to-right scan progress fill behind a collection row." location="top"></v-tooltip>
            </div>
            <div class="settings-cell settings-cell--control">
              <v-switch
                v-model="uiForm.collectionScanProgressEnabled"
                aria-label="Collection progress bar"
                class="settings-switch"
                color="primary"
                density="compact"
                :disabled="scanActionsDisabled"
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
                      :disabled="scanActionsDisabled"
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
                    {{ run.missingCount }} removed,
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

  </v-container>
</template>
