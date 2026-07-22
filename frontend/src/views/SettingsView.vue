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
  | 'artistScanSpinnerEnabled'
  | 'providerBatchRescanDelayMinutes'
  | 'statusBarLocation'

interface UiForm {
  statusCompleteVisibleMs: number
  scanPollIntervalMs: number
  artistScanSpinnerEnabled: boolean
  providerBatchRescanDelayMinutes: number
  statusBarLocation: 'top' | 'bottom'
}

const store = useLibraryStore()
const { providerJob, providerStatus, scanJob, musicRoot, uiSettings } = storeToRefs(store)

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
  statusCompleteVisibleMs: uiSettings.value.statusCompleteVisibleMs,
  scanPollIntervalMs: uiSettings.value.scanPollIntervalMs,
  artistScanSpinnerEnabled: uiSettings.value.artistScanSpinnerEnabled,
  providerBatchRescanDelayMinutes: uiSettings.value.providerBatchRescanDelayMinutes,
  statusBarLocation: uiSettings.value.statusBarLocation,
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
  uiForm.artistScanSpinnerEnabled = uiSettings.value.artistScanSpinnerEnabled
  uiForm.providerBatchRescanDelayMinutes = uiSettings.value.providerBatchRescanDelayMinutes
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
    if (key === 'artistScanSpinnerEnabled') payload.artistScanSpinnerEnabled = uiForm.artistScanSpinnerEnabled
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

function artistScanSpinnerChanged() {
  scheduleUiSettingsSave('artistScanSpinnerEnabled')
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
  await Promise.all([store.loadSettings(), store.loadScanJob(), store.loadProviderJob()])
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
              <div class="settings-section__subtitle">Pane controls</div>
            </div>
          </div>

          <div class="settings-matrix settings-matrix--workspace">
            <div class="settings-cell settings-cell--label">
              Artist scan spinner
              <v-tooltip activator="parent" text="Shows a spinner before artists covered by a provider scan." location="top"></v-tooltip>
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

      </div>
    </v-sheet>

  </v-container>
</template>
