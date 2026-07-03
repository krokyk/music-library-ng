<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useLibraryStore } from '@/stores/library'
import { providerDefinition } from '@/providers'
import type {
  AlbumReleaseDateConflictPlan,
  ProviderReleaseDateConflict,
  ReportArtifact,
  StatusHistoryEntry,
} from '@/types'

const store = useLibraryStore()
const {
  collectionArtists,
  collections,
  manualStatus,
  providerJob,
  providerReleaseDateConflicts,
  providerReleaseDateConflictDialogRequest,
  providerStatus,
  scanJob,
  statusHistory,
  uiSettings,
} = storeToRefs(store)

const historyDialog = ref(false)
const reportDialog = ref(false)
const providerConflictDialog = ref(false)
const providerConflictPlanDialog = ref(false)
const providerConflictPlan = ref<AlbumReleaseDateConflictPlan | null>(null)
const providerConflictPlanLoading = ref(false)
const providerConflictActionConflict = ref<ProviderReleaseDateConflict | null>(null)
const providerConflictActionKey = ref('')
const activeReportKey = ref<string | null>(null)
const historyScrollElement = ref<HTMLElement | null>(null)
const historyPinnedToBottom = ref(true)
const completedStatus = ref('')
const completedStatusState = ref<'done' | 'warning' | 'failed' | 'info'>('done')
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
    return `${scanRunningMessage.value}: ${scanJob.value.itemProcessed}/${scanJob.value.itemTotal} folders scanned`
  }
  if (providerJob.value?.status === 'RUNNING' && providerJob.value.message) {
    return providerJob.value.message
  }
  if (providerStatus.value.running && providerStatus.value.message) {
    return providerStatus.value.message
  }
  return completedStatus.value || 'Idle (click for history)'
})

const statusState = computed(() => {
  if (scanJob.value?.status === 'RUNNING' || providerJob.value?.status === 'RUNNING' || providerStatus.value.running) {
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
const hasProviderReleaseDateConflicts = computed(() => providerReleaseDateConflicts.value.length > 0)
const providerConflictPlanBlocked = computed(() =>
  (providerConflictPlan.value?.warnings ?? []).some((warning) =>
    warning.startsWith('Source folder') || warning.startsWith('Target folder'),
  ),
)

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

function providerConflictKey(conflict: ProviderReleaseDateConflict) {
  return `${conflict.albumId}:${conflict.providerLinkId}`
}

function providerConflictProviderLabel(conflict: ProviderReleaseDateConflict) {
  const sources = conflict.sources?.length
    ? conflict.sources
    : [{ providerId: conflict.providerId }]
  return [...new Set(sources.map((source) => providerDefinition(source.providerId).label))]
    .sort((left, right) => left.localeCompare(right))
    .join(', ')
}

function releaseYearLabel(releaseDate: string | null | undefined) {
  return releaseDate && /^\d{4}/.test(releaseDate) ? releaseDate.slice(0, 4) : 'No year'
}

async function refreshProviderConflicts(openDialog: boolean) {
  try {
    await store.loadProviderReleaseDateConflicts()
    if (openDialog && providerReleaseDateConflicts.value.length > 0) {
      providerConflictDialog.value = true
    }
  } catch (error) {
    store.showErrorStatus(error, 'Unable to load provider conflicts')
  }
}

async function keepLocalConflict(conflict: ProviderReleaseDateConflict) {
  providerConflictActionKey.value = `keep:${providerConflictKey(conflict)}`
  try {
    await store.keepLocalReleaseDate(conflict)
    store.showStatus(`Kept local release year for ${conflict.artistName} - ${conflict.albumTitle}.`, 'done')
    if (providerReleaseDateConflicts.value.length === 0) {
      providerConflictDialog.value = false
    }
  } catch (error) {
    store.showErrorStatus(error, 'Unable to keep local release year')
  } finally {
    providerConflictActionKey.value = ''
  }
}

async function openUseProviderConflictPlan(conflict: ProviderReleaseDateConflict) {
  providerConflictActionConflict.value = conflict
  providerConflictPlan.value = null
  providerConflictPlanDialog.value = true
  providerConflictPlanLoading.value = true
  providerConflictActionKey.value = `plan:${providerConflictKey(conflict)}`
  try {
    providerConflictPlan.value = await store.planUseProviderReleaseDate(conflict)
  } catch (error) {
    providerConflictPlanDialog.value = false
    store.showErrorStatus(error, 'Unable to preview provider release year')
  } finally {
    providerConflictPlanLoading.value = false
    providerConflictActionKey.value = ''
  }
}

async function confirmUseProviderConflictPlan() {
  const conflict = providerConflictActionConflict.value
  if (!conflict || providerConflictPlanBlocked.value) {
    return
  }
  providerConflictActionKey.value = `use:${providerConflictKey(conflict)}`
  try {
    const result = await store.useProviderReleaseDate(conflict)
    providerConflictPlanDialog.value = false
    providerConflictActionConflict.value = null
    providerConflictPlan.value = null
    store.showStatus(
      `Renamed ${result.folderCount || 1} album folder${(result.folderCount || 1) === 1 ? '' : 's'} and updated ${result.tagFilesUpdated} audio tag${result.tagFilesUpdated === 1 ? '' : 's'}.`,
      result.warnings.length > 0 ? 'warning' : 'done',
    )
    if (providerReleaseDateConflicts.value.length === 0) {
      providerConflictDialog.value = false
    }
  } catch (error) {
    store.showErrorStatus(error, 'Unable to use provider release year')
  } finally {
    providerConflictActionKey.value = ''
  }
}

function scanCompletionMessage() {
  const job = scanJob.value
  if (!job) {
    return `${scanCollectionName.value} scan complete`
  }
  if (scanIsLocalAlbums.value) {
    const artistPrefix = job.requestedArtistId ? '' : `${countWithLabel(job.artistCount, 'artist')}, `
    return `${scanCollectionName.value} local album scan complete: ${artistPrefix}`
      + `${countWithLabel(job.parsedCount, 'album')}, ${job.createdCount} new, ${job.skippedCount} skipped`
  }
  if (scanCollectionType.value === 'TITLE') {
    return `${scanCollectionName.value} scan complete: ${job.itemProcessed}/${job.itemTotal} folders scanned, `
      + `${countWithLabel(job.parsedCount, 'title')} parsed, ${job.createdCount} new`
  }
  return `${scanCollectionName.value} scan complete: ${job.itemProcessed}/${job.itemTotal} folders scanned, `
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
        const message = scanCompletionMessage()
        completeStatus(
          message,
          'done',
          withElapsed(message, elapsed),
          scanJob.value.reports,
        )
      } else if (status === 'FAILED' || status === 'CANCELLED') {
        const message = scanJob.value.message ?? `${scanCollectionName.value} scan ${status.toLowerCase()}`
        completeStatus(message, 'failed', withElapsed(message, elapsed), scanJob.value.reports)
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
      void refreshProviderConflicts(state !== 'failed')
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
          : providerJob.value.releaseDateConflictCount > 0 ? 'warning'
          : 'done'
      completeStatus(message, state, withElapsed(message, elapsed), providerJob.value.reports)
      void refreshProviderConflicts(status === 'DONE')
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

watch(providerReleaseDateConflictDialogRequest, async (request) => {
  if (!request) {
    return
  }
  try {
    await store.loadProviderReleaseDateConflicts()
    if (providerReleaseDateConflicts.value.length > 0) {
      providerConflictDialog.value = true
    } else {
      store.showStatus('No unresolved provider release date conflicts.', 'done')
    }
  } catch (error) {
    store.showErrorStatus(error, 'Unable to load provider conflicts')
  }
})

onMounted(async () => {
  await Promise.all([
    store.loadUiSettings(),
    store.loadCollections(),
    store.loadScanJob(),
    store.loadProviderJob(),
    store.loadProviderReleaseDateConflicts(),
  ])
  if (scanJob.value?.status === 'RUNNING') {
    store.startScanJobPolling()
  }
  if (providerJob.value?.status === 'RUNNING') {
    store.startProviderJobPolling()
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
        <v-tab to="/artists">
          <v-icon icon="mdi-account-music" start></v-icon>
          <span>Artists</span>
          <v-tooltip
            v-if="hasProviderReleaseDateConflicts"
            :text="`${providerReleaseDateConflicts.length} unresolved provider release date conflict${providerReleaseDateConflicts.length === 1 ? '' : 's'}`"
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
                :class="{ 'status-history-entry--clickable': entryHasDetail(entry) }"
                type="button"
                role="listitem"
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

    <v-dialog
      v-model="reportDialog"
      width="1280"
      max-width="calc(100vw - 64px)"
      class="scan-report-overlay"
      content-class="scan-report-dialog-content"
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

    <v-dialog v-model="providerConflictDialog" max-width="820">
      <v-card class="dialog-card provider-conflict-dialog">
        <v-card-title class="provider-conflict-dialog__title">
          <span>Release Date Conflicts</span>
          <v-chip size="x-small" color="warning" variant="tonal">{{ providerReleaseDateConflicts.length }}</v-chip>
        </v-card-title>
        <v-card-text>
          <div v-if="providerReleaseDateConflicts.length === 0" class="pane-empty pane-empty--compact">
            No unresolved provider release date conflicts.
          </div>
          <div v-else class="provider-conflict-dialog__body">
            <div
              v-for="conflict in providerReleaseDateConflicts"
              :key="providerConflictKey(conflict)"
              class="provider-conflict-row"
            >
              <div class="provider-conflict-row__main">
                <div class="provider-conflict-row__meta">
                  <v-icon icon="mdi-alert-outline" size="16" color="warning"></v-icon>
                  <span class="cell-strong">{{ conflict.artistName }}</span>
                  <span class="cell-muted">{{ providerConflictProviderLabel(conflict) }}</span>
                </div>
                <div class="provider-conflict-row__title">
                  {{ conflict.albumTitle }}
                </div>
                <div class="provider-conflict-row__years">
                  <v-chip size="x-small" class="release-date-chip" variant="tonal">
                    {{ releaseYearLabel(conflict.localReleaseDate) }}
                  </v-chip>
                  <v-icon icon="mdi-arrow-right-thin" size="16"></v-icon>
                  <v-chip size="x-small" class="release-date-chip release-date-chip--warning" variant="tonal">
                    {{ releaseYearLabel(conflict.providerReleaseDate) }}
                  </v-chip>
                  <span class="cell-muted">{{ conflict.providerTitle }}</span>
                </div>
                <div v-if="conflict.localRelativePath" class="mono-path provider-conflict-row__path">
                  {{ conflict.localRelativePath }}
                </div>
              </div>
              <div class="provider-conflict-row__actions">
                <v-btn
                  size="small"
                  variant="tonal"
                  :loading="providerConflictActionKey === `keep:${providerConflictKey(conflict)}`"
                  :disabled="providerConflictActionKey !== ''"
                  @click="keepLocalConflict(conflict)"
                >
                  Keep Local
                </v-btn>
                <v-btn
                  size="small"
                  color="warning"
                  variant="tonal"
                  :loading="providerConflictActionKey === `plan:${providerConflictKey(conflict)}`"
                  :disabled="providerConflictActionKey !== ''"
                  @click="openUseProviderConflictPlan(conflict)"
                >
                  Use Provider Year
                </v-btn>
              </div>
            </div>
          </div>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn variant="text" @click="providerConflictDialog = false">Close</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog v-model="providerConflictPlanDialog" max-width="760">
      <v-card class="dialog-card provider-conflict-plan-dialog">
        <v-card-title>Use Provider Year</v-card-title>
        <v-card-text>
          <div v-if="providerConflictPlanLoading" class="scan-report-dialog__loading">
            <v-progress-circular indeterminate size="18" width="2"></v-progress-circular>
            <span>Preparing rename plan</span>
          </div>
          <div v-else-if="providerConflictPlan" class="provider-conflict-plan">
            <div class="provider-conflict-plan__warning">
              This will rename {{ providerConflictPlan.folderCount }} album folder{{ providerConflictPlan.folderCount === 1 ? '' : 's' }} on disk, update local paths in the database, keep one album record, and write the provider year to supported audio tags.
            </div>
            <div class="provider-conflict-plan__grid">
              <span class="cell-muted">Current folder</span>
              <span class="mono-path">{{ providerConflictPlan.sourceRelativePath }}</span>
              <span class="cell-muted">New folder</span>
              <span class="mono-path">{{ providerConflictPlan.targetRelativePath }}</span>
              <span class="cell-muted">Track tags</span>
              <span>{{ providerConflictPlan.audioFileCount }} supported, {{ providerConflictPlan.unsupportedFileCount }} unsupported</span>
            </div>
            <div v-if="providerConflictPlan.folders.length > 1" class="provider-conflict-plan__folders">
              <div
                v-for="folder in providerConflictPlan.folders"
                :key="folder.localPathId"
                class="provider-conflict-plan__folder"
              >
                <div class="cell-muted">{{ folder.collectionName }}</div>
                <div class="mono-path">{{ folder.sourceRelativePath }}</div>
                <v-icon icon="mdi-arrow-right-thin" size="16"></v-icon>
                <div class="mono-path">{{ folder.targetRelativePath }}</div>
              </div>
            </div>
            <div v-if="providerConflictPlan.warnings.length" class="provider-conflict-plan__warnings">
              <div
                v-for="warning in providerConflictPlan.warnings"
                :key="warning"
                class="provider-conflict-plan__warning-line"
              >
                <v-icon icon="mdi-alert-outline" size="16" color="warning"></v-icon>
                <span>{{ warning }}</span>
              </div>
            </div>
          </div>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn variant="text" @click="providerConflictPlanDialog = false">Cancel</v-btn>
          <v-btn
            color="warning"
            variant="flat"
            :loading="providerConflictActionKey.startsWith('use:')"
            :disabled="providerConflictPlanLoading || !providerConflictPlan || providerConflictPlanBlocked"
            @click="confirmUseProviderConflictPlan"
          >
            Rename Folder And Update Tags
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

  </v-app>
</template>
