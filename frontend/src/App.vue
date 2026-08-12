<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useLibraryStore } from '@/stores/library'
import AppSpinner from '@/components/AppSpinner.vue'
import type {
  ReportArtifact,
  StatusHistoryEntry,
} from '@/types'

const store = useLibraryStore()
const {
  collections,
  deletingCollectionId,
  artistCountryConflicts,
  artistStatusConflicts,
  bulkMatchJob,
  bulkMatchProgressVisible,
  manualStatus,
  providerJob,
  providerReleaseYearConflicts,
  providerTitleConflicts,
  providerStatus,
  scanJob,
  statusHistory,
  uiSettings,
} = storeToRefs(store)

const historyDialog = ref(false)
const reportDialog = ref(false)
const activeReportKey = ref<string | null>(null)
const historyScrollElement = ref<HTMLElement | null>(null)
const historyPinnedToBottom = ref(true)
const completedStatus = ref('')
const completedStatusState = ref<'done' | 'warning' | 'failed' | 'info'>('done')
const completedStatusTimer = ref<number | null>(null)
const scanStartedAt = ref<number | null>(null)
const providerStartedAt = ref<number | null>(null)
const bulkMatchStartedAt = ref<number | null>(null)
const collectionScanCancelPending = ref(false)
const bulkMatchCancelPending = ref(false)

const scanCollectionName = computed(() => {
  const collectionId = scanJob.value?.activeCollectionId ?? scanJob.value?.requestedCollectionId
  return scanJob.value?.activeCollectionName
    ?? scanJob.value?.requestedCollectionName
    ?? collections.value.find((collection) => collection.id === collectionId)?.name
    ?? collectionId
    ?? 'collection'
})

const scanCollectionType = computed(() => {
  const collectionId = scanJob.value?.activeCollectionId ?? scanJob.value?.requestedCollectionId
  return collections.value.find((collection) => collection.id === collectionId)?.type ?? 'ARTIST'
})

const scanIsRunning = computed(() => scanJob.value?.status === 'RUNNING')
const collectionScanModalVisible = computed(() =>
  scanIsRunning.value && scanJob.value?.kind === 'COLLECTION',
)
const bulkMatchIsRunning = computed(() => bulkMatchJob.value?.status === 'RUNNING')
const bulkMatchModalVisible = computed(() => bulkMatchIsRunning.value && bulkMatchProgressVisible.value)
const workProgressModalVisible = computed(() => collectionScanModalVisible.value || bulkMatchModalVisible.value)

const scanRunningMessage = computed(() => {
  return `Scanning collection ${scanCollectionName.value}`
})

const collectionScanTitle = computed(() => `Scanning ${scanCollectionName.value}...`)
const collectionScanProcessed = computed(() => {
  const job = scanJob.value
  if (!job) {
    return 0
  }
  return job.itemTotal > 0 ? Math.min(job.itemProcessed, job.itemTotal) : job.itemProcessed
})
const collectionScanProgressText = computed(() => {
  const job = scanJob.value
  if (job && job.itemTotal <= 0 && job.message) {
    return job.message
  }
  return `${collectionScanProcessed.value} / ${job?.itemTotal ?? 0}`
})
const collectionScanProgressPercent = computed(() => {
  const total = scanJob.value?.itemTotal ?? 0
  return total > 0 ? Math.min(100, (collectionScanProcessed.value / total) * 100) : 0
})
const workProgressTitle = computed(() => bulkMatchModalVisible.value
  ? `Matching ${bulkMatchJob.value?.providerName ?? 'provider'}...`
  : collectionScanTitle.value)
const workProgressSubject = computed(() => bulkMatchModalVisible.value && bulkMatchJob.value?.activeArtistName
  ? `Processing ${bulkMatchJob.value.activeArtistName}`
  : '')
const workProgressProcessed = computed(() => bulkMatchModalVisible.value
  ? Math.min(
    (bulkMatchJob.value?.itemProcessed ?? 0) + (bulkMatchJob.value?.activeArtistId ? 1 : 0),
    bulkMatchJob.value?.itemTotal ?? 0,
  )
  : collectionScanProcessed.value)
const workProgressTotal = computed(() => bulkMatchModalVisible.value
  ? bulkMatchJob.value?.itemTotal ?? 0
  : scanJob.value?.itemTotal ?? 0)
const workProgressText = computed(() => bulkMatchModalVisible.value
  ? `${workProgressProcessed.value} / ${workProgressTotal.value} artists`
  : collectionScanProgressText.value)
const workProgressPercent = computed(() => workProgressTotal.value > 0
  ? Math.min(100, (workProgressProcessed.value / workProgressTotal.value) * 100)
  : 0)

const activeStatusMessage = computed(() => {
  if (scanIsRunning.value) {
    return scanRunningMessage.value
  }
  if (providerJob.value?.status === 'RUNNING' && providerJob.value.message) {
    return providerJob.value.message
  }
  if (providerStatus.value.running && providerStatus.value.message) {
    return providerStatus.value.message
  }
  if (bulkMatchIsRunning.value && bulkMatchJob.value?.message) {
    return bulkMatchJob.value.message
  }
  return completedStatus.value || 'Idle (click for history)'
})

const statusState = computed(() => {
  if (scanIsRunning.value || providerJob.value?.status === 'RUNNING'
    || providerStatus.value.running || bulkMatchIsRunning.value) {
    return 'running'
  }
  return completedStatus.value ? completedStatusState.value : 'idle'
})

const statusIcon = computed(() => {
  if (statusState.value === 'failed') {
    return 'mdi-alert-circle'
  }
  if (statusState.value === 'warning') {
    return 'mdi-alert-outline'
  }
  if (statusState.value === 'done') {
    return 'mdi-check-circle-outline'
  }
  return 'mdi-information-outline'
})

const statusTransitionKey = computed(() => statusState.value)
const statusBarLocation = computed(() => uiSettings.value.statusBarLocation === 'bottom' ? 'bottom' : 'top')
const statusHistoryOverlayClasses = computed(() => [
  'status-history-overlay',
  `status-history-overlay--${statusBarLocation.value}`,
])
const providerConflictCount = computed(() =>
  artistCountryConflicts.value.length
  + artistStatusConflicts.value.length
  + providerReleaseYearConflicts.value.length
  + providerTitleConflicts.value.length,
)
const hasProviderConflicts = computed(() => providerConflictCount.value > 0)

const reportEntries = computed(() => statusHistory.value.flatMap((entry) =>
  (entry.reports ?? []).map((report, index) => ({
    key: `${entry.id}:${index}`,
    report,
    entry,
  })),
))

const activeReportIndex = computed(() =>
  reportEntries.value.findIndex((item) => item.key === activeReportKey.value),
)

const activeReportEntry = computed(() =>
  activeReportIndex.value >= 0 ? reportEntries.value[activeReportIndex.value] : null,
)

const activeReportPaging = computed(() => {
  if (!reportEntries.value.length) {
    return '0/0'
  }
  const current = activeReportIndex.value >= 0 ? activeReportIndex.value + 1 : 1
  return `${current}/${reportEntries.value.length}`
})

const activeReportText = computed(() => activeReportEntry.value?.report.text ?? '')
const activeReportTitle = computed(() => activeReportEntry.value?.report.title ?? 'Report')

function scrollHistoryToBottom() {
  void nextTick(() => {
    if (!historyScrollElement.value) {
      return
    }
    historyScrollElement.value.scrollTop = historyScrollElement.value.scrollHeight
  })
}

function updateHistoryPinned() {
  if (!historyScrollElement.value) {
    historyPinnedToBottom.value = true
    return
  }
  const distanceFromBottom = historyScrollElement.value.scrollHeight
    - historyScrollElement.value.scrollTop
    - historyScrollElement.value.clientHeight
  historyPinnedToBottom.value = distanceFromBottom < 24
}

function formatElapsed(startedAt: number | null) {
  return startedAt === null ? null : `${Math.max(0, Date.now() - startedAt)} ms`
}

function withElapsed(message: string, elapsed: string | null) {
  if (!elapsed) {
    return message
  }
  return `${message} (took ${elapsed})`
}

function completeStatus(
  message: string,
  state: 'done' | 'warning' | 'failed' | 'info' = 'done',
  historyMessage = message,
  reports: ReportArtifact[] = [],
) {
  completedStatus.value = message
  completedStatusState.value = state
  store.addStatusHistory(historyMessage, state, {
    reports,
  })
  if (completedStatusTimer.value !== null) {
    window.clearTimeout(completedStatusTimer.value)
  }
  completedStatusTimer.value = window.setTimeout(() => {
    completedStatus.value = ''
    completedStatusTimer.value = null
  }, uiSettings.value.statusCompleteVisibleMs)
}

function entryHasDetail(entry: StatusHistoryEntry) {
  return (entry.reports?.length ?? 0) > 0
}

function openHistoryEntry(entry: StatusHistoryEntry) {
  const firstIndex = reportEntries.value.findIndex((item) => item.entry.id === entry.id)
  if (firstIndex < 0) {
    return
  }
  openReportAtIndex(firstIndex)
}

function openReportAtIndex(index: number) {
  const entry = reportEntries.value[index]
  if (!entry) {
    return
  }
  activeReportKey.value = entry.key
  reportDialog.value = true
}

function showAdjacentReport(delta: number) {
  const entries = reportEntries.value
  if (!entries.length) {
    return
  }
  const current = activeReportIndex.value < 0 ? 0 : activeReportIndex.value
  const nextIndex = (current + delta + entries.length) % entries.length
  openReportAtIndex(nextIndex)
}

async function refreshProviderConflicts() {
  try {
    await store.loadProviderConflicts()
  } catch (error) {
    store.showErrorStatus(error, 'Unable to load provider conflicts')
  }
}

function scanCompletionMessage() {
  const job = scanJob.value
  if (!job) {
    return `${scanCollectionName.value} scan complete`
  }
  if (job.message) {
    return job.message
  }
  if (scanCollectionType.value === 'TITLE') {
    return `${scanCollectionName.value} scan complete: ${job.itemProcessed}/${job.itemTotal} titles scanned, `
      + `${countWithLabel(job.parsedCount, 'title')} parsed, ${job.createdCount} new`
  }
  return `${scanCollectionName.value} scan complete: ${job.itemProcessed}/${job.itemTotal} albums scanned, `
    + `${countWithLabel(job.artistCount, 'artist')}, ${countWithLabel(job.parsedCount, 'album')} parsed, `
    + `${job.createdCount} new`
}

function countWithLabel(count: number, singular: string, plural = `${singular}s`) {
  return `${count} ${count === 1 ? singular : plural}`
}

async function copyActiveReport() {
  const text = activeReportText.value
  if (!text) {
    return
  }
  try {
    await navigator.clipboard.writeText(text)
    store.showStatus('Report copied to clipboard', 'done')
  } catch (error) {
    store.showStatus('Unable to copy report to clipboard', 'failed')
  }
}

async function cancelCollectionScan() {
  const job = scanJob.value
  if (!job || job.cancelRequested || collectionScanCancelPending.value) {
    return
  }
  collectionScanCancelPending.value = true
  try {
    await store.cancelScanJob()
  } catch (error) {
    store.showErrorStatus(error, 'Unable to cancel scan')
  } finally {
    collectionScanCancelPending.value = false
  }
}

async function cancelBulkMatch() {
  if (!bulkMatchIsRunning.value || bulkMatchJob.value?.cancelRequested || bulkMatchCancelPending.value) {
    return
  }
  bulkMatchCancelPending.value = true
  try {
    await store.cancelBulkMatchJob()
  } catch (error) {
    store.showErrorStatus(error, 'Unable to cancel bulk provider match')
  } finally {
    bulkMatchCancelPending.value = false
  }
}

watch(
  () => scanJob.value?.status ?? 'IDLE',
  (status, previousStatus) => {
    if (status === 'RUNNING' && previousStatus !== 'RUNNING') {
      scanStartedAt.value = Date.now()
      if (!collectionScanModalVisible.value) {
        store.addStatusHistory(scanRunningMessage.value, 'running')
      }
      completedStatus.value = ''
    }
    if (previousStatus === 'RUNNING' && status !== 'RUNNING' && scanJob.value) {
      const elapsed = formatElapsed(scanStartedAt.value)
      scanStartedAt.value = null
      if (status === 'DONE') {
        const message = scanCompletionMessage()
        completeStatus(
          message,
          'done',
          withElapsed(message, elapsed),
          scanJob.value.reports,
        )
      } else if (status === 'FAILED' || status === 'CANCELLED') {
        const message = scanJob.value.message ?? `${scanCollectionName.value} scan ${status.toLowerCase()}`
        completeStatus(
          message,
          status === 'FAILED' ? 'failed' : 'warning',
          withElapsed(message, elapsed),
          scanJob.value.reports,
        )
      }
    }
  },
)

watch(
  () => manualStatus.value?.id,
  () => {
    if (!manualStatus.value) {
      return
    }
    completeStatus(manualStatus.value.message, manualStatus.value.state)
  },
)

watch(
  () => providerStatus.value.running,
  (running, wasRunning) => {
    const message = providerStatus.value.message
    if (running && message) {
      providerStartedAt.value = Date.now()
      store.addStatusHistory(message, 'running')
      completedStatus.value = ''
      return
    }
    if (wasRunning && message) {
      const elapsed = formatElapsed(providerStartedAt.value)
      providerStartedAt.value = null
      const state = providerStatus.value.state === 'warning' ? 'warning'
        : providerStatus.value.state === 'failed' || message.toLowerCase().includes('failed') ? 'failed'
          : 'done'
      completeStatus(message, state, withElapsed(message, elapsed), providerStatus.value.reports ?? [])
      void refreshProviderConflicts()
    }
  },
)

watch(
  () => providerJob.value?.status ?? 'IDLE',
  (status, previousStatus) => {
    if (status === 'RUNNING' && previousStatus !== 'RUNNING') {
      providerStartedAt.value = Date.now()
      store.addStatusHistory(providerJob.value?.message ?? 'Checking providers...', 'running')
      completedStatus.value = ''
      return
    }
    if (previousStatus === 'RUNNING' && status !== 'RUNNING' && providerJob.value) {
      const elapsed = formatElapsed(providerStartedAt.value)
      providerStartedAt.value = null
      const message = providerJob.value.message ?? `Provider check ${status.toLowerCase()}`
      const state = status === 'FAILED' || providerJob.value.errorCount > 0 ? 'failed'
        : status === 'CANCELLED' ? 'warning'
          : providerJob.value.releaseYearConflictCount > 0 || providerJob.value.titleConflictCount > 0 ? 'warning'
          : 'done'
      completeStatus(message, state, withElapsed(message, elapsed), providerJob.value.reports)
      void refreshProviderConflicts()
    }
  },
)

watch(
  () => bulkMatchJob.value?.status ?? 'IDLE',
  (status, previousStatus) => {
    if (status === 'RUNNING' && previousStatus !== 'RUNNING') {
      bulkMatchStartedAt.value = Date.now()
      store.addStatusHistory(bulkMatchJob.value?.message ?? 'Matching providers...', 'running')
      completedStatus.value = ''
      return
    }
    if (previousStatus === 'RUNNING' && status !== 'RUNNING' && bulkMatchJob.value) {
      const elapsed = formatElapsed(bulkMatchStartedAt.value)
      bulkMatchStartedAt.value = null
      const message = bulkMatchJob.value.message ?? `Bulk provider match ${status.toLowerCase()}`
      const state = status === 'FAILED' || bulkMatchJob.value.errorCount > 0 ? 'failed'
        : status === 'CANCELLED' ? 'warning'
          : bulkMatchJob.value.manualCount > 0 || bulkMatchJob.value.noMatchCount > 0 ? 'warning'
            : 'done'
      completeStatus(message, state, withElapsed(message, elapsed))
    }
  },
)

watch(historyDialog, (open) => {
  if (open) {
    historyPinnedToBottom.value = true
    scrollHistoryToBottom()
  }
})

watch(
  () => statusHistory.value.length,
  () => {
    if (historyDialog.value && historyPinnedToBottom.value) {
      scrollHistoryToBottom()
    }
  },
)

onMounted(async () => {
  await Promise.all([
    store.loadCollections(),
    store.loadScanJob(),
    store.loadProviderJob(),
    store.loadBulkMatchJob(),
    store.loadProviderConflicts(),
  ])
  if (scanJob.value?.status === 'RUNNING') {
    store.startScanJobPolling()
  }
  if (providerJob.value?.status === 'RUNNING') {
    store.startProviderJobPolling()
  }
  if (bulkMatchJob.value?.status === 'RUNNING') {
    store.startBulkMatchJobPolling()
  }
})
</script>

<template>
  <v-app class="music-app">
    <v-app-bar class="app-bar" flat height="64">
      <div class="app-brand">
        <v-icon icon="mdi-music-circle" size="30"></v-icon>
        <div>
          <div class="app-brand__title">Music Library NG</div>
          <div class="app-brand__subtitle">Local collection</div>
        </div>
      </div>

      <v-tabs class="app-tabs" color="primary" density="comfortable">
        <v-tab prepend-icon="mdi-folder-music" to="/">Collections</v-tab>
        <v-tab to="/artists" :disabled="Boolean(deletingCollectionId)">
          <v-icon icon="mdi-account-music" start></v-icon>
          <span>Artists</span>
          <v-tooltip
            v-if="hasProviderConflicts"
            :text="`${providerConflictCount} unresolved provider conflict${providerConflictCount === 1 ? '' : 's'}`"
            location="bottom"
          >
            <template #activator="{ props }">
              <v-icon
                v-bind="props"
                icon="mdi-alert"
                size="17"
                color="warning"
                class="provider-conflict-triangle app-tab-warning"
              ></v-icon>
            </template>
          </v-tooltip>
        </v-tab>
        <v-tab prepend-icon="mdi-cog" to="/settings">Settings</v-tab>
      </v-tabs>

      <v-spacer></v-spacer>
      <v-chip class="app-status" size="small" prepend-icon="mdi-database">SQLite</v-chip>
    </v-app-bar>

    <v-main class="app-main">
      <button
        v-if="statusBarLocation === 'top'"
        class="global-status-bar"
        :class="`global-status-bar--${statusState}`"
        type="button"
        @click="historyDialog = true"
      >
        <Transition name="status-content" mode="out-in">
          <span :key="statusTransitionKey" class="global-status-bar__content">
            <AppSpinner
              v-if="statusState === 'running'"
            />
            <v-icon
              v-else
              :icon="statusIcon"
              size="18"
            ></v-icon>
            <span>{{ activeStatusMessage }}</span>
          </span>
        </Transition>
      </button>
      <router-view />
      <button
        v-if="statusBarLocation === 'bottom'"
        class="global-status-bar"
        :class="`global-status-bar--${statusState}`"
        type="button"
        @click="historyDialog = true"
      >
        <Transition name="status-content" mode="out-in">
          <span :key="statusTransitionKey" class="global-status-bar__content">
            <AppSpinner
              v-if="statusState === 'running'"
            />
            <v-icon
              v-else
              :icon="statusIcon"
              size="18"
            ></v-icon>
            <span>{{ activeStatusMessage }}</span>
          </span>
        </Transition>
      </button>
    </v-main>

    <v-dialog
      :model-value="workProgressModalVisible"
      max-width="none"
      persistent
      no-click-animation
    >
      <v-card
        class="dialog-card collection-scan-dialog"
        :class="{ 'collection-scan-dialog--bulk': bulkMatchModalVisible }"
      >
        <v-card-title class="collection-scan-dialog__title">
          {{ workProgressTitle }}
        </v-card-title>
        <v-card-text class="collection-scan-dialog__body">
          <div v-if="workProgressSubject" class="collection-scan-dialog__subject">
            {{ workProgressSubject }}
          </div>
          <div class="collection-scan-progress">
            <v-progress-linear
              :model-value="workProgressPercent"
              color="primary"
              bg-color="surface-variant"
              height="30"
              rounded
            ></v-progress-linear>
            <span class="collection-scan-progress__label">{{ workProgressText }}</span>
          </div>
        </v-card-text>
        <v-card-actions class="collection-scan-dialog__actions">
          <v-spacer></v-spacer>
          <v-btn
            v-if="bulkMatchModalVisible"
            variant="text"
            @click="store.hideBulkMatchProgress()"
          >
            Close
          </v-btn>
          <v-btn
            color="warning"
            variant="text"
            :disabled="bulkMatchModalVisible
              ? bulkMatchJob?.cancelRequested || bulkMatchCancelPending
              : scanJob?.cancelRequested || collectionScanCancelPending"
            @click="bulkMatchModalVisible ? cancelBulkMatch() : cancelCollectionScan()"
          >
            {{ (bulkMatchModalVisible ? bulkMatchJob?.cancelRequested : scanJob?.cancelRequested) ? 'Cancelling' : 'Cancel' }}
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog v-model="historyDialog" max-width="none" :class="statusHistoryOverlayClasses">
      <v-card class="dialog-card status-history-dialog">
        <v-card-title>Status History</v-card-title>
        <v-card-text class="status-history-dialog__text">
          <div
            ref="historyScrollElement"
            class="status-history-dialog__body"
            @scroll="updateHistoryPinned"
          >
            <div class="status-history-list">
              <button
                v-for="entry in statusHistory"
                :key="entry.id"
                class="status-history-entry"
                :class="{ 'status-history-entry--clickable': entryHasDetail(entry) }"
                type="button"
                :disabled="!entryHasDetail(entry)"
                @click="openHistoryEntry(entry)"
              >
                <v-chip
                  class="status-history-entry__state"
                  size="x-small"
                  :color="entry.state === 'failed' ? 'error' : entry.state === 'warning' ? 'warning' : entry.state === 'done' ? 'success' : 'primary'"
                >
                  {{ entry.state }}
                </v-chip>
                <span class="status-history-entry__time">{{ entry.createdAt }}</span>
                <span class="status-history-entry__message">
                  <v-icon v-if="entryHasDetail(entry)" icon="mdi-file-document-outline" size="15"></v-icon>
                  <span>{{ entry.message }}</span>
                </span>
              </button>
              <div v-if="statusHistory.length === 0" class="pane-empty pane-empty--compact">
                No status history yet.
              </div>
            </div>
          </div>
        </v-card-text>
      </v-card>
    </v-dialog>

    <v-dialog
      v-model="reportDialog"
      content-class="scan-report-dialog-content large-dialog-content"
      scrollable
    >
      <v-card class="dialog-card scan-report-dialog">
        <v-card-title class="scan-report-dialog__title">
          <div class="scan-report-dialog__heading">
            <div class="scan-report-dialog__heading-main">
              <span>{{ activeReportTitle }}</span>
              <span v-if="activeReportEntry" class="scan-report-dialog__timestamp">
                {{ activeReportEntry.entry.createdAt }}
              </span>
            </div>
            <div v-if="activeReportEntry" class="scan-report-dialog__message">
              {{ activeReportEntry.entry.message }}
            </div>
          </div>
          <div class="scan-report-dialog__controls">
            <div class="scan-report-dialog__navigation">
              <v-btn
                icon="mdi-chevron-left"
                variant="text"
                density="comfortable"
                :disabled="reportEntries.length <= 1"
                @click="showAdjacentReport(-1)"
              ></v-btn>
              <v-btn
                icon="mdi-chevron-right"
                variant="text"
                density="comfortable"
                :disabled="reportEntries.length <= 1"
                @click="showAdjacentReport(1)"
              ></v-btn>
              <span class="scan-report-dialog__paging">{{ activeReportPaging }}</span>
            </div>
            <div class="scan-report-dialog__copy">
              <v-tooltip text="Copy report" location="bottom">
                <template #activator="{ props }">
                  <v-btn
                    v-bind="props"
                    icon="mdi-content-copy"
                    variant="text"
                    density="comfortable"
                    :disabled="!activeReportText"
                    @click="copyActiveReport"
                  ></v-btn>
                </template>
              </v-tooltip>
            </div>
          </div>
        </v-card-title>
        <v-card-text class="scan-report-dialog__body">
          <div class="scan-report-dialog__scroller">
            <pre class="scan-report-dialog__content">{{ activeReportText || 'Report is not available.' }}</pre>
          </div>
        </v-card-text>
      </v-card>
    </v-dialog>

  </v-app>
</template>
