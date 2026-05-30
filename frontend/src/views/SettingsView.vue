<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useLibraryStore } from '@/stores/library'
import type { UiSettingsValues } from '@/types'

const store = useLibraryStore()
const { collections, scanRuns, scanEvents, musicRoot, uiSettings, error } = storeToRefs(store)

const scanPollMin = 100
const scanPollMax = 2000
const statusVisibleMin = 0
const statusVisibleMax = 30000
const uiSettingKeys: Array<keyof UiSettingsValues> = [
  'statusCompleteVisibleMs',
  'scanPollIntervalMs',
  'collectionScanSpinnerEnabled',
  'collectionScanProgressEnabled',
  'statusHistoryDateFormat',
  'statusBarLocation',
]

const uiForm = reactive<UiSettingsValues>({
  statusCompleteVisibleMs: 4000,
  scanPollIntervalMs: 100,
  collectionScanSpinnerEnabled: true,
  collectionScanProgressEnabled: true,
  statusHistoryDateFormat: 'yyyy-MM-dd HH:mm:ss.SSS',
  statusBarLocation: 'top',
})
const savingUiSettings = ref(false)
const uiSaveTimer = ref<number | null>(null)

const customUiSettingCount = computed(() =>
  uiSettingKeys.filter((key) => uiSettings.value.overrides[key]).length,
)

function syncUiForm() {
  uiForm.statusCompleteVisibleMs = uiSettings.value.statusCompleteVisibleMs
  uiForm.scanPollIntervalMs = uiSettings.value.scanPollIntervalMs
  uiForm.collectionScanSpinnerEnabled = uiSettings.value.collectionScanSpinnerEnabled
  uiForm.collectionScanProgressEnabled = uiSettings.value.collectionScanProgressEnabled
  uiForm.statusHistoryDateFormat = uiSettings.value.statusHistoryDateFormat
  uiForm.statusBarLocation = uiSettings.value.statusBarLocation
}

function settingSource(key: keyof UiSettingsValues) {
  return uiSettings.value.overrides[key] ? 'custom' : 'default'
}

function settingDefault(key: keyof UiSettingsValues) {
  const value = uiSettings.value.defaults[key]
  if (typeof value === 'boolean') {
    return value ? 'on' : 'off'
  }
  if (typeof value === 'string') {
    return value
  }
  return `${value} ms`
}

function normalizeUiForm() {
  uiForm.statusCompleteVisibleMs = normalizeNumber(uiForm.statusCompleteVisibleMs, 4000, statusVisibleMin, statusVisibleMax)
  uiForm.scanPollIntervalMs = normalizeNumber(uiForm.scanPollIntervalMs, 100, scanPollMin, scanPollMax)
  uiForm.statusHistoryDateFormat = uiForm.statusHistoryDateFormat.trim() || uiSettings.value.defaults.statusHistoryDateFormat
  uiForm.statusBarLocation = uiForm.statusBarLocation === 'bottom' ? 'bottom' : 'top'
}

function normalizeNumber(value: unknown, fallback: number, min: number, max: number) {
  const numberValue = Number(value)
  if (!Number.isFinite(numberValue)) {
    return fallback
  }
  return Math.min(max, Math.max(min, Math.round(numberValue)))
}

function scheduleUiSettingsSave() {
  if (uiSaveTimer.value !== null) {
    window.clearTimeout(uiSaveTimer.value)
  }
  uiSaveTimer.value = window.setTimeout(saveUiSettingsNow, 250)
}

async function saveUiSettingsNow() {
  if (uiSaveTimer.value !== null) {
    window.clearTimeout(uiSaveTimer.value)
    uiSaveTimer.value = null
  }
  normalizeUiForm()
  savingUiSettings.value = true
  try {
    await store.saveUiSettings({ ...uiForm })
    syncUiForm()
  } catch (saveError) {
    store.error = saveError instanceof Error ? saveError.message : String(saveError)
  } finally {
    savingUiSettings.value = false
  }
}

async function resetUiSettings() {
  if (uiSaveTimer.value !== null) {
    window.clearTimeout(uiSaveTimer.value)
    uiSaveTimer.value = null
  }
  savingUiSettings.value = true
  try {
    await store.resetUiSettings()
    syncUiForm()
  } catch (resetError) {
    store.error = resetError instanceof Error ? resetError.message : String(resetError)
  } finally {
    savingUiSettings.value = false
  }
}

onMounted(async () => {
  await Promise.all([store.loadSettings(), store.loadUiSettings()])
  syncUiForm()
})

onBeforeUnmount(() => {
  if (uiSaveTimer.value !== null) {
    window.clearTimeout(uiSaveTimer.value)
    void saveUiSettingsNow()
  }
})
</script>

<template>
  <v-container fluid class="app-page settings-page">
    <v-alert v-if="error" type="error" variant="tonal" class="mb-4">{{ error }}</v-alert>

    <div class="page-header">
      <div>
        <div class="page-title">Settings</div>
        <div class="page-kicker">Effective runtime configuration</div>
      </div>
    </div>

    <v-sheet class="panel pa-4 mb-4">
      <div class="settings-panel-header">
        <div class="d-flex align-center ga-3">
          <h2 class="panel-title mb-0">Interface</h2>
          <v-chip size="small" :color="customUiSettingCount > 0 ? 'primary' : 'default'">
            {{ customUiSettingCount > 0 ? `${customUiSettingCount} custom` : 'defaults' }}
          </v-chip>
        </div>
        <v-btn
          size="small"
          variant="text"
          prepend-icon="mdi-backup-restore"
          :disabled="customUiSettingCount === 0 || savingUiSettings"
          :loading="savingUiSettings"
          @click="resetUiSettings"
        >
          Reset
        </v-btn>
      </div>

      <div class="settings-grid">
        <div class="settings-control">
          <div class="settings-control__header">
            <span>Scan progress polling</span>
            <v-chip size="x-small" :color="uiSettings.overrides.scanPollIntervalMs ? 'primary' : 'default'">
              {{ settingSource('scanPollIntervalMs') }}
            </v-chip>
          </div>
          <v-slider
            v-model.number="uiForm.scanPollIntervalMs"
            color="primary"
            density="compact"
            hide-details
            :min="scanPollMin"
            :max="scanPollMax"
            :step="50"
            thumb-label
            @update:model-value="scheduleUiSettingsSave"
          >
            <template #append>
              <v-text-field
                v-model.number="uiForm.scanPollIntervalMs"
                class="settings-number-field"
                density="compact"
                hide-details
                max-width="118"
                suffix="ms"
                type="number"
                variant="outlined"
                :min="scanPollMin"
                :max="scanPollMax"
                @blur="saveUiSettingsNow"
                @keydown.enter="saveUiSettingsNow"
              ></v-text-field>
            </template>
          </v-slider>
          <div class="settings-control__meta">Default {{ settingDefault('scanPollIntervalMs') }}</div>
        </div>

        <div class="settings-control">
          <div class="settings-control__header">
            <span>Completed status visibility</span>
            <v-chip size="x-small" :color="uiSettings.overrides.statusCompleteVisibleMs ? 'primary' : 'default'">
              {{ settingSource('statusCompleteVisibleMs') }}
            </v-chip>
          </div>
          <v-text-field
            v-model.number="uiForm.statusCompleteVisibleMs"
            density="compact"
            hide-details
            suffix="ms"
            type="number"
            variant="outlined"
            :min="statusVisibleMin"
            :max="statusVisibleMax"
            @update:model-value="scheduleUiSettingsSave"
            @blur="saveUiSettingsNow"
            @keydown.enter="saveUiSettingsNow"
          ></v-text-field>
          <div class="settings-control__meta">Default {{ settingDefault('statusCompleteVisibleMs') }}</div>
        </div>

        <div class="settings-control settings-control--switches">
          <v-switch
            v-model="uiForm.collectionScanSpinnerEnabled"
            color="primary"
            density="compact"
            hide-details
            label="Collection scan spinner"
            @update:model-value="scheduleUiSettingsSave"
          ></v-switch>
          <div class="settings-control__meta">
            {{ settingSource('collectionScanSpinnerEnabled') }} · default {{ settingDefault('collectionScanSpinnerEnabled') }}
          </div>
        </div>

        <div class="settings-control settings-control--switches">
          <v-switch
            v-model="uiForm.collectionScanProgressEnabled"
            color="primary"
            density="compact"
            hide-details
            label="Collection progress bar"
            @update:model-value="scheduleUiSettingsSave"
          ></v-switch>
          <div class="settings-control__meta">
            {{ settingSource('collectionScanProgressEnabled') }} · default {{ settingDefault('collectionScanProgressEnabled') }}
          </div>
        </div>

        <div class="settings-control settings-control--wide">
          <div class="settings-control__header">
            <span>Status history date format</span>
            <v-chip size="x-small" :color="uiSettings.overrides.statusHistoryDateFormat ? 'primary' : 'default'">
              {{ settingSource('statusHistoryDateFormat') }}
            </v-chip>
          </div>
          <v-text-field
            v-model="uiForm.statusHistoryDateFormat"
            density="compact"
            hide-details
            variant="outlined"
            @update:model-value="scheduleUiSettingsSave"
            @blur="saveUiSettingsNow"
            @keydown.enter="saveUiSettingsNow"
          ></v-text-field>
          <div class="settings-control__meta">Default {{ settingDefault('statusHistoryDateFormat') }}</div>
        </div>

        <div class="settings-control">
          <div class="settings-control__header">
            <span>Status bar location</span>
            <v-chip size="x-small" :color="uiSettings.overrides.statusBarLocation ? 'primary' : 'default'">
              {{ settingSource('statusBarLocation') }}
            </v-chip>
          </div>
          <v-btn-toggle
            v-model="uiForm.statusBarLocation"
            color="primary"
            density="compact"
            mandatory
            variant="outlined"
            @update:model-value="scheduleUiSettingsSave"
          >
            <v-btn value="top">Top</v-btn>
            <v-btn value="bottom">Bottom</v-btn>
          </v-btn-toggle>
          <div class="settings-control__meta">Default {{ settingDefault('statusBarLocation') }}</div>
        </div>
      </div>
    </v-sheet>

    <v-sheet class="panel pa-4 mb-4">
      <div class="d-flex align-center ga-3 mb-3">
        <h2 class="panel-title mb-0">Music Root</h2>
        <v-chip v-if="musicRoot?.valid" color="success" size="small">valid</v-chip>
        <v-chip v-else color="error" size="small">invalid</v-chip>
        <v-chip v-if="musicRoot?.detected" color="primary" size="small">auto-detected</v-chip>
      </div>
      <div class="mono-path mb-3">{{ musicRoot?.selectedRoot }}</div>

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
    </v-sheet>

    <v-sheet class="panel table-wrap">
      <v-table class="music-table" density="compact">
        <thead>
          <tr>
            <th>Name</th>
            <th>Relative Path</th>
            <th>Resolved Path</th>
            <th>Last Scan</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="collection in collections" :key="collection.id">
            <td class="cell-strong">{{ collection.name }}</td>
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
                @click="store.scan(collection.id)"
              >
                Scan
              </v-btn>
            </td>
          </tr>
        </tbody>
      </v-table>
    </v-sheet>

    <v-sheet class="panel mt-4">
      <div class="pa-4">
        <h2 class="panel-title mb-0">Recent Scan Log</h2>
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
    </v-sheet>
  </v-container>
</template>
