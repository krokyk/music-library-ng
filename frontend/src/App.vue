<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useLibraryStore } from '@/stores/library'
import type { StatusHistoryEntry } from '@/types'

const store = useLibraryStore()
const {
  collectionArtists,
  collections,
  providerStatus,
  scanJob,
  scanReports,
  scanReportsLoading,
  statusHistory,
  uiSettings,
} = storeToRefs(store)

const historyDialog = ref(false)
const reportDialog = ref(false)
const activeReportRunId = ref<number | null>(null)
const historyScrollElement = ref<HTMLElement | null>(null)
const historyPinnedToBottom = ref(true)
const completedStatus = ref('')
const completedStatusState = ref<'done' | 'warning' | 'failed'>('done')
const completedStatusTimer = ref<number | null>(null)
const scanStartedAt = ref<number | null>(null)
const providerStartedAt = ref<number | null>(null)

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

const scanEntityName = computed(() => (scanCollectionType.value === 'TITLE' ? 'titles' : 'artists'))
const scanIsLocalAlbums = computed(() => scanJob.value?.kind === 'LOCAL_ALBUMS')
const scanArtistName = computed(() => {
  const artistId = scanJob.value?.requestedArtistId
  if (!artistId) {
    return null
  }
  return collectionArtists.value.find((artist) => artist.id === artistId)?.name ?? `artist ${artistId}`
})

const scanRunningMessage = computed(() => {
  if (scanIsLocalAlbums.value) {
    return scanArtistName.value
      ? `Scanning local albums in ${scanCollectionName.value} for ${scanArtistName.value}`
      : `Scanning local albums for ${scanCollectionName.value}`
  }
  return `Scanning collection ${scanCollectionName.value}`
})

const activeStatusMessage = computed(() => {
  if (scanJob.value?.status === 'RUNNING') {
    return `${scanRunningMessage.value}: ${scanJob.value.itemProcessed}/${scanJob.value.itemTotal} dirs scanned`
  }
  if (providerStatus.value.running && providerStatus.value.message) {
    return providerStatus.value.message
  }
  return completedStatus.value || 'Idle (click for history)'
})

const statusState = computed(() => {
  if (scanJob.value?.status === 'RUNNING' || providerStatus.value.running) {
    return 'running'
  }
  return completedStatus.value ? completedStatusState.value : 'idle'
})

const statusIcon = computed(() => {
  if (statusState.value === 'failed') {
    return 'mdi-alert-circle-outline'
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

const reportEntries = computed(() => statusHistory.value.flatMap((entry) =>
  (entry.scanRunIds ?? []).map((runId) => ({
    runId,
    entry,
  })),
))

const activeReportIndex = computed(() =>
  reportEntries.value.findIndex((item) => item.runId === activeReportRunId.value),
)

const activeReportEntry = computed(() =>
  activeReportIndex.value >= 0 ? reportEntries.value[activeReportIndex.value] : null,
)

const activeReportText = computed(() =>
  activeReportRunId.value === null ? '' : scanReports.value[activeReportRunId.value] ?? '',
)

const activeReportLoading = computed(() =>
  activeReportRunId.value !== null && scanReportsLoading.value[activeReportRunId.value],
)

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
  state: 'done' | 'warning' | 'failed' = 'done',
  historyMessage = message,
  scanRunIds: number[] = [],
) {
  completedStatus.value = message
  completedStatusState.value = state
  store.addStatusHistory(historyMessage, state, { scanRunIds: scanRunIds.length ? scanRunIds : undefined })
  if (completedStatusTimer.value !== null) {
    window.clearTimeout(completedStatusTimer.value)
  }
  completedStatusTimer.value = window.setTimeout(() => {
    completedStatus.value = ''
    completedStatusTimer.value = null
  }, uiSettings.value.statusCompleteVisibleMs)
}

function entryHasReport(entry: StatusHistoryEntry) {
  return (entry.scanRunIds?.length ?? 0) > 0
}

function openReport(entry: StatusHistoryEntry) {
  const firstRunId = entry.scanRunIds?.[0]
  if (!firstRunId) {
    return
  }
  openReportRun(firstRunId)
}

function openReportRun(runId: number) {
  activeReportRunId.value = runId
  reportDialog.value = true
  void store.loadScanReport(runId).catch((error) => {
    store.error = error instanceof Error ? error.message : String(error)
  })
}

function showAdjacentReport(delta: number) {
  const entries = reportEntries.value
  if (!entries.length) {
    return
  }
  const current = activeReportIndex.value < 0 ? 0 : activeReportIndex.value
  const nextIndex = (current + delta + entries.length) % entries.length
  openReportRun(entries[nextIndex].runId)
}

watch(
  () => scanJob.value?.status ?? 'IDLE',
  (status, previousStatus) => {
    if (status === 'RUNNING' && previousStatus !== 'RUNNING') {
      scanStartedAt.value = Date.now()
      store.addStatusHistory(scanRunningMessage.value, 'running')
      completedStatus.value = ''
    }
    if (previousStatus === 'RUNNING' && status !== 'RUNNING' && scanJob.value) {
      const elapsed = formatElapsed(scanStartedAt.value)
      scanStartedAt.value = null
      if (status === 'DONE') {
        const message = scanIsLocalAlbums.value
          ? `${scanCollectionName.value} local album scan complete: ${scanJob.value.parsedCount} albums, ${scanJob.value.createdCount} new, ${scanJob.value.skippedCount} skipped`
          : `${scanCollectionName.value} scan complete: ${scanJob.value.itemProcessed}/${scanJob.value.itemTotal} dirs scanned, ${scanJob.value.parsedCount} ${scanEntityName.value}, ${scanJob.value.createdCount} new`
        completeStatus(
          message,
          'done',
          withElapsed(message, elapsed),
          scanJob.value.runIds,
        )
      } else if (status === 'FAILED' || status === 'CANCELLED') {
        const message = scanJob.value.message ?? `${scanCollectionName.value} scan ${status.toLowerCase()}`
        completeStatus(message, 'failed', withElapsed(message, elapsed), scanJob.value.runIds)
      }
    }
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
  await Promise.all([store.loadUiSettings(), store.loadCollections(), store.loadScanJob()])
  if (scanJob.value?.status === 'RUNNING') {
    store.startScanJobPolling()
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
        <v-tab prepend-icon="mdi-account-music" to="/artists">Artists</v-tab>
        <v-tab prepend-icon="mdi-music-box-multiple" to="/library">Library</v-tab>
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
            <v-progress-circular
              v-if="statusState === 'running'"
              indeterminate
              size="16"
              width="2"
            ></v-progress-circular>
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
            <v-progress-circular
              v-if="statusState === 'running'"
              indeterminate
              size="16"
              width="2"
            ></v-progress-circular>
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

    <v-dialog v-model="historyDialog" max-width="1080" :class="statusHistoryOverlayClasses">
      <v-card class="dialog-card status-history-dialog">
        <v-card-title>Status History</v-card-title>
        <v-card-text class="status-history-dialog__text">
          <div
            ref="historyScrollElement"
            class="status-history-dialog__body"
            @scroll="updateHistoryPinned"
          >
            <div class="status-history-list" role="list">
              <button
                v-for="entry in statusHistory"
                :key="entry.id"
                class="status-history-entry"
                :class="{ 'status-history-entry--clickable': entryHasReport(entry) }"
                type="button"
                role="listitem"
                :disabled="!entryHasReport(entry)"
                @click="openReport(entry)"
              >
                <v-chip
                  class="status-history-entry__state"
                  size="x-small"
                  :color="entry.state === 'failed' ? 'error' : entry.state === 'warning' ? 'warning' : entry.state === 'done' ? 'success' : 'primary'"
                >
                  {{ entry.state }}
                </v-chip>
                <span class="status-history-entry__time">{{ entry.createdAt }}</span>
                <span class="status-history-entry__message">{{ entry.message }}</span>
              </button>
              <div v-if="statusHistory.length === 0" class="pane-empty pane-empty--compact">
                No status history yet.
              </div>
            </div>
          </div>
        </v-card-text>
      </v-card>
    </v-dialog>

    <v-dialog v-model="reportDialog" max-width="1180" class="scan-report-overlay">
      <v-card class="dialog-card scan-report-dialog">
        <v-card-title class="scan-report-dialog__title">
          <span>Scan Report</span>
          <span v-if="activeReportEntry" class="scan-report-dialog__meta">
            {{ activeReportEntry.entry.createdAt }} - {{ activeReportEntry.entry.message }}
          </span>
          <v-spacer></v-spacer>
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
        </v-card-title>
        <v-card-text class="scan-report-dialog__body">
          <div v-if="activeReportLoading" class="scan-report-dialog__loading">
            <v-progress-circular indeterminate size="18" width="2"></v-progress-circular>
            <span>Loading report</span>
          </div>
          <pre v-else class="scan-report-dialog__content">{{ activeReportText || 'Report is not available.' }}</pre>
        </v-card-text>
      </v-card>
    </v-dialog>
  </v-app>
</template>
