<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useLibraryStore } from '@/stores/library'
import { providerDefinition } from '@/providers'
import type {
  AlbumReleaseDateConflictPlan,
  ProviderCheckEvent,
  ProviderReleaseDateConflict,
  StatusHistoryEntry,
} from '@/types'

interface ProviderArtistOutcome {
  artistName: string
  providerName: string
  recordsRead: number
  newAlbums: number
  alreadyInLibrary: number
  releaseDateConflicts: number
  ignoredRecords: number
  errors: string[]
}

const store = useLibraryStore()
const {
  collectionArtists,
  collections,
  manualStatus,
  providerCheckEvents,
  providerCheckEventsLoading,
  providerCheckRuns,
  providerJob,
  providerReleaseDateConflicts,
  providerReleaseDateConflictDialogRequest,
  providerStatus,
  scanJob,
  scanReports,
  scanReportsLoading,
  statusHistory,
  uiSettings,
} = storeToRefs(store)

const historyDialog = ref(false)
const reportDialog = ref(false)
const providerDetailDialog = ref(false)
const providerConflictDialog = ref(false)
const providerConflictPlanDialog = ref(false)
const providerConflictPlan = ref<AlbumReleaseDateConflictPlan | null>(null)
const providerConflictPlanLoading = ref(false)
const providerConflictActionConflict = ref<ProviderReleaseDateConflict | null>(null)
const providerConflictActionKey = ref('')
const activeReportRunId = ref<number | null>(null)
const activeProviderRunId = ref<number | null>(null)
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
  (entry.scanRunIds ?? []).map((runId) => ({
    runId,
    entry,
  })),
))

const providerEntries = computed(() => statusHistory.value.flatMap((entry) =>
  (entry.providerRunIds ?? []).map((runId) => ({
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

const activeReportPaging = computed(() => {
  if (!reportEntries.value.length) {
    return '0/0'
  }
  const current = activeReportIndex.value >= 0 ? activeReportIndex.value + 1 : 1
  return `${current}/${reportEntries.value.length}`
})

const activeReportText = computed(() =>
  activeReportRunId.value === null ? '' : scanReports.value[activeReportRunId.value] ?? '',
)

const activeReportLoading = computed(() =>
  activeReportRunId.value !== null && scanReportsLoading.value[activeReportRunId.value],
)

const activeProviderIndex = computed(() =>
  providerEntries.value.findIndex((item) => item.runId === activeProviderRunId.value),
)

const activeProviderEntry = computed(() =>
  activeProviderIndex.value >= 0 ? providerEntries.value[activeProviderIndex.value] : null,
)

const activeProviderRun = computed(() =>
  activeProviderRunId.value === null
    ? null
    : providerCheckRuns.value.find((run) => run.id === activeProviderRunId.value) ?? null,
)

const activeProviderEvents = computed(() =>
  activeProviderRunId.value === null ? [] : providerCheckEvents.value[activeProviderRunId.value] ?? [],
)

const activeProviderLoading = computed(() =>
  activeProviderRunId.value !== null && providerCheckEventsLoading.value[activeProviderRunId.value],
)

const activeProviderPaging = computed(() => {
  if (!providerEntries.value.length) {
    return '0/0'
  }
  const current = activeProviderIndex.value >= 0 ? activeProviderIndex.value + 1 : 1
  return `${current}/${providerEntries.value.length}`
})

const activeProviderReportText = computed(() => {
  if (activeProviderLoading.value) {
    return ''
  }
  const run = activeProviderRun.value
  const events = activeProviderEvents.value
  const outcomes = providerArtistOutcomes(events)
  const errors = outcomes
    .filter((outcome) => outcome.errors.length > 0)
    .sort(compareOutcomeName)
  const ignoredProviderRecords = outcomes
    .filter((outcome) => outcome.ignoredRecords > 0)
    .sort(compareOutcomeName)
  const artistSkipped = providerArtistSkippedEvents(events)
  const artistsWithoutProviders = run ? providerRunSkippedWithoutProviders(run.message ?? '') : 0
  const artistsSkippedByCooldown = run ? providerRunSkippedByCooldown(run.message ?? '') : artistSkipped.length
  const artistSkippedTotal = artistsWithoutProviders + artistsSkippedByCooldown
  const newAlbums = outcomes
    .filter((outcome) => outcome.newAlbums > 0)
    .sort(compareOutcomeName)
  const noChange = outcomes
    .filter((outcome) =>
      outcome.recordsRead > 0
      && outcome.newAlbums === 0
      && outcome.releaseDateConflicts === 0
      && outcome.ignoredRecords === 0
      && outcome.errors.length === 0,
    )
    .sort(compareOutcomeName)
  const providerBreakdown = providerBreakdownText(outcomes)
  const recordsRead = run?.foundAlbumCount ?? sumBy(outcomes, (outcome) => outcome.recordsRead)
  const alreadyInLibrary = sumBy(outcomes, (outcome) => outcome.alreadyInLibrary)
  const releaseDateConflicts = run?.releaseDateConflictCount
    ?? sumBy(outcomes, (outcome) => outcome.releaseDateConflicts)
  const createdAlbums = sumBy(outcomes, (outcome) => outcome.newAlbums) || run?.newAlbumCount || 0
  const ignoredCount = sumBy(outcomes, (outcome) => outcome.ignoredRecords)
  const errorCount = run?.errorCount ?? errors.length
  const lines: string[] = []
  lines.push('Provider Check Report')
  lines.push('=====================')
  lines.push('')
  if (run) {
    lines.push(`Started: ${run.startedAt}`)
    if (run.finishedAt) {
      lines.push(`Finished: ${run.finishedAt}`)
    }
    lines.push(`Status: ${run.status}`)
    lines.push('')
    lines.push('Summary')
    lines.push('-------')
    lines.push(`Artists checked: ${run.processedArtistCount}${providerBreakdown}`)
    if (artistSkippedTotal > 0) {
      lines.push(`Artists skipped: ${artistSkippedTotal}${providerSkippedBreakdown(artistsWithoutProviders, artistsSkippedByCooldown)}`)
    }
    lines.push(`Provider albums found: ${recordsRead}`)
    lines.push(`Already in library: ${alreadyInLibrary}`)
    lines.push(`Release date conflicts: ${releaseDateConflicts}`)
    lines.push(`Added as unchecked: ${createdAlbums}`)
    lines.push(`Errors: ${errorCount}`)
  }
  appendOutcomeSection(lines, 'Errors', errors, (outcome) =>
    outcome.errors.map((error) => `${outcome.artistName} (${outcome.providerName}): ${error}`),
  )
  appendOutcomeSection(lines, 'Added As Unchecked', newAlbums, (outcome) => [
    `${outcome.artistName} (${outcome.newAlbums})`,
  ])
  if (ignoredProviderRecords.length > 0) {
    appendOutcomeSection(lines, 'Provider Records Ignored', ignoredProviderRecords, (outcome) => [
      `${outcome.artistName} (${outcome.ignoredRecords})`,
    ])
  }
  appendTextSection(lines, 'Artists Skipped', artistSkipped)
  appendOutcomeSection(lines, 'No Changes', noChange, (outcome) => [
    outcome.artistName,
  ])
  lines.push('')
  lines.push('Diagnostics')
  lines.push('-----------')
  lines.push(run ? `Run id: ${run.id}` : 'Run id: <unknown>')
  if (ignoredCount > 0) {
    lines.push(`Provider records ignored: ${ignoredCount}`)
  }
  lines.push(`Provider events recorded: ${events.length}`)
  lines.push('Use the run id to correlate provider errors with Quarkus log lines.')
  return lines.join('\n')
})

function appendOutcomeSection(
  lines: string[],
  title: string,
  outcomes: ProviderArtistOutcome[],
  format: (outcome: ProviderArtistOutcome) => string[],
) {
  const entries = outcomes.flatMap(format)
  lines.push('')
  lines.push(`${title} (${entries.length})`)
  lines.push('-'.repeat(title.length + 4 + String(entries.length).length))
  if (entries.length === 0) {
    lines.push('<none>')
    return
  }
  entries.forEach((entry) => {
    lines.push(`- ${entry}`)
  })
}

function providerArtistOutcomes(events: ProviderCheckEvent[]) {
  const outcomes = new Map<string, ProviderArtistOutcome>()
  let currentArtistName = ''
  events.forEach((event) => {
    const errorMatch = event.message.match(/^Provider check failed for (.+?): (.+)$/)
    if (errorMatch) {
      const outcome = providerOutcome(outcomes, errorMatch[1], providerFromMessage(errorMatch[2]))
      outcome.errors.push(errorMatch[2])
      currentArtistName = outcome.artistName
      return
    }

    let match = event.message.match(/^Found (\d+) MusicBrainz release groups for (.+)$/)
    if (match) {
      const outcome = providerOutcome(outcomes, match[2], 'MusicBrainz')
      outcome.recordsRead = Number(match[1])
      currentArtistName = outcome.artistName
      return
    }

    match = event.message.match(/^Read (\d+) (.+) albums for (.+)$/)
    if (match) {
      const outcome = providerOutcome(outcomes, match[3], match[2])
      outcome.recordsRead = Number(match[1])
      currentArtistName = outcome.artistName
      return
    }

    match = event.message.match(/^Found (\d+) albums for (.+)$/)
    if (match) {
      const outcome = providerOutcome(outcomes, match[2], 'Provider')
      outcome.recordsRead = Number(match[1])
      currentArtistName = outcome.artistName
      return
    }

    match = event.message.match(/^MusicBrainz refresh for (.+) read (\d+) albums, already in library (\d+), added (\d+) unchecked albums, ignored (\d+)\.$/)
    if (match) {
      applyProviderCounts(providerOutcome(outcomes, match[1], 'MusicBrainz'), match[2], match[3], '0', match[4], match[5])
      currentArtistName = match[1]
      return
    }

    match = event.message.match(/^Provider check for (.+) read (\d+) (.+) albums, already in library (\d+), release date conflicts (\d+), added (\d+) unchecked albums\.$/)
    if (match) {
      const outcome = providerOutcome(outcomes, match[1], match[3])
      applyProviderCounts(outcome, match[2], match[4], match[5], match[6], '0')
      return
    }

    match = event.message.match(/^Provider check for (.+) read (\d+) (.+) albums, already in library (\d+), added (\d+) unchecked albums\.$/)
    if (match) {
      const outcome = providerOutcome(outcomes, match[1], match[3])
      applyProviderCounts(outcome, match[2], match[4], '0', match[5], '0')
    }
  })
  return [...outcomes.values()]
}

function providerOutcome(outcomes: Map<string, ProviderArtistOutcome>, artistName: string, providerName: string) {
  const key = artistName.toLocaleLowerCase()
  const existing = outcomes.get(key)
  if (existing) {
    if (existing.providerName === 'Provider' && providerName !== 'Provider') {
      existing.providerName = providerName
    }
    return existing
  }
  const outcome: ProviderArtistOutcome = {
    artistName,
    providerName,
    recordsRead: 0,
    newAlbums: 0,
    alreadyInLibrary: 0,
    releaseDateConflicts: 0,
    ignoredRecords: 0,
    errors: [],
  }
  outcomes.set(key, outcome)
  return outcome
}

function applyProviderCounts(
  outcome: ProviderArtistOutcome,
  recordsRead: string,
  alreadyInLibrary: string,
  releaseDateConflicts: string,
  newAlbums: string,
  ignored: string,
) {
  outcome.recordsRead = Number(recordsRead)
  outcome.alreadyInLibrary = Number(alreadyInLibrary)
  outcome.releaseDateConflicts = Number(releaseDateConflicts)
  outcome.newAlbums = Number(newAlbums)
  outcome.ignoredRecords = Number(ignored)
}

function providerFromMessage(message: string) {
  if (message.includes('Metal Archives')) {
    return 'Metal Archives'
  }
  if (message.includes('Spirit of Metal')) {
    return 'Spirit of Metal'
  }
  if (message.includes('MusicBrainz')) {
    return 'MusicBrainz'
  }
  return 'Provider'
}

function providerBreakdownText(outcomes: ProviderArtistOutcome[]) {
  const counts = new Map<string, number>()
  outcomes.forEach((outcome) => {
    counts.set(outcome.providerName, (counts.get(outcome.providerName) ?? 0) + 1)
  })
  if (counts.size === 0) {
    return ''
  }
  return ` (${[...counts.entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([provider, count]) => `${count}x ${provider}`)
    .join(', ')})`
}

function providerArtistSkippedEvents(events: ProviderCheckEvent[]) {
  return events.flatMap((event) => {
    const match = event.message.match(/^Skipped (.+?): (.+)\.$/)
    return match ? [`${match[1]}: ${match[2]}`] : []
  }).sort((left, right) => left.localeCompare(right))
}

function providerRunSkippedWithoutProviders(message: string) {
  const withoutProviders = message.match(/skipped (\d+) artists? without providers/)
  if (withoutProviders) {
    return Number(withoutProviders[1])
  }
  const mixed = message.match(/(\d+) without (?:enabled links|providers)/)
  return mixed ? Number(mixed[1]) : 0
}

function providerRunSkippedByCooldown(message: string) {
  const mixed = message.match(/skipped \d+ artists? \((\d+) (?:already checked successfully|recently checked),/)
  if (mixed) {
    return Number(mixed[1])
  }
  const cooldown = message.match(/skipped (\d+) artists? (?:already checked successfully|recently checked)/)
  return cooldown ? Number(cooldown[1]) : 0
}

function providerSkippedBreakdown(withoutProviders: number, cooldown: number) {
  const parts: string[] = []
  if (withoutProviders > 0) {
    parts.push(`${withoutProviders} without providers`)
  }
  if (cooldown > 0) {
    parts.push(`${cooldown} recently checked`)
  }
  return parts.length ? ` (${parts.join(', ')})` : ''
}

function appendTextSection(lines: string[], title: string, entries: string[]) {
  lines.push('')
  lines.push(`${title} (${entries.length})`)
  lines.push('-'.repeat(title.length + 4 + String(entries.length).length))
  if (entries.length === 0) {
    lines.push('<none>')
    return
  }
  entries.forEach((entry) => {
    lines.push(`- ${entry}`)
  })
}

function sumBy<T>(items: T[], value: (item: T) => number) {
  return items.reduce((sum, item) => sum + value(item), 0)
}

function compareOutcomeName(left: ProviderArtistOutcome, right: ProviderArtistOutcome) {
  return left.artistName.localeCompare(right.artistName)
}

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
  scanRunIds: number[] = [],
  providerRunIds: number[] = [],
) {
  completedStatus.value = message
  completedStatusState.value = state
  store.addStatusHistory(historyMessage, state, {
    scanRunIds: scanRunIds.length ? scanRunIds : undefined,
    providerRunIds: providerRunIds.length ? providerRunIds : undefined,
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
  return (entry.scanRunIds?.length ?? 0) > 0 || (entry.providerRunIds?.length ?? 0) > 0
}

function openHistoryEntry(entry: StatusHistoryEntry) {
  if ((entry.providerRunIds?.length ?? 0) > 0) {
    openProviderRun(entry.providerRunIds![0])
    return
  }
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
    store.showErrorStatus(error, 'Unable to load scan report')
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

function openProviderRun(runId: number) {
  activeProviderRunId.value = runId
  providerDetailDialog.value = true
  void store.loadProviderCheckEvents(runId).catch((error) => {
    store.showErrorStatus(error, 'Unable to load provider check details')
  })
}

function showAdjacentProviderRun(delta: number) {
  const entries = providerEntries.value
  if (!entries.length) {
    return
  }
  const current = activeProviderIndex.value < 0 ? 0 : activeProviderIndex.value
  const nextIndex = (current + delta + entries.length) % entries.length
  openProviderRun(entries[nextIndex].runId)
}

function historyIncludesProviderRun(runIds: number[]) {
  return runIds.some((runId) =>
    statusHistory.value.some((entry) => entry.providerRunIds?.includes(runId)),
  )
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
    store.showStatus('Scan report copied to clipboard', 'done')
  } catch (error) {
    store.showStatus('Unable to copy scan report to clipboard', 'failed')
  }
}

async function copyActiveProviderReport() {
  const text = activeProviderReportText.value
  if (!text) {
    return
  }
  try {
    await navigator.clipboard.writeText(text)
    store.showStatus('Provider check report copied to clipboard', 'done')
  } catch (error) {
    store.showStatus('Unable to copy provider check report to clipboard', 'failed')
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
      completeStatus(message, state, withElapsed(message, elapsed), [], providerStatus.value.runIds ?? [])
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
      completeStatus(message, state, withElapsed(message, elapsed), [], providerJob.value.runIds)
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
  } else if (providerJob.value?.status && providerJob.value.status !== 'IDLE' && providerJob.value.runIds.length > 0) {
    const message = providerJob.value.message ?? `Provider check ${providerJob.value.status.toLowerCase()}`
    const state = providerJob.value.status === 'FAILED' || providerJob.value.errorCount > 0 ? 'failed'
      : providerJob.value.status === 'CANCELLED' ? 'warning'
        : 'done'
    if (!historyIncludesProviderRun(providerJob.value.runIds)) {
      completeStatus(message, state, message, [], providerJob.value.runIds)
    }
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
              <span>Scan Report</span>
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
                    :disabled="activeReportLoading || !activeReportText"
                    @click="copyActiveReport"
                  ></v-btn>
                </template>
              </v-tooltip>
            </div>
          </div>
        </v-card-title>
        <v-card-text class="scan-report-dialog__body">
          <div class="scan-report-dialog__scroller">
            <div v-if="activeReportLoading" class="scan-report-dialog__loading">
              <v-progress-circular indeterminate size="18" width="2"></v-progress-circular>
              <span>Loading report</span>
            </div>
            <pre v-else class="scan-report-dialog__content">{{ activeReportText || 'Report is not available.' }}</pre>
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

    <v-dialog
      v-model="providerDetailDialog"
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
              <span>Provider Check Report</span>
              <span v-if="activeProviderEntry" class="scan-report-dialog__timestamp">
                {{ activeProviderEntry.entry.createdAt }}
              </span>
            </div>
            <div v-if="activeProviderEntry" class="scan-report-dialog__message">
              {{ activeProviderEntry.entry.message }}
            </div>
          </div>
          <div class="scan-report-dialog__controls">
            <div class="scan-report-dialog__navigation">
              <v-btn
                icon="mdi-chevron-left"
                variant="text"
                density="comfortable"
                :disabled="providerEntries.length <= 1"
                @click="showAdjacentProviderRun(-1)"
              ></v-btn>
              <v-btn
                icon="mdi-chevron-right"
                variant="text"
                density="comfortable"
                :disabled="providerEntries.length <= 1"
                @click="showAdjacentProviderRun(1)"
              ></v-btn>
              <span class="scan-report-dialog__paging">{{ activeProviderPaging }}</span>
            </div>
            <div class="scan-report-dialog__copy">
              <v-tooltip text="Copy report" location="bottom">
                <template #activator="{ props }">
                  <v-btn
                    v-bind="props"
                    icon="mdi-content-copy"
                    variant="text"
                    density="comfortable"
                    :disabled="activeProviderLoading || !activeProviderReportText"
                    @click="copyActiveProviderReport"
                  ></v-btn>
                </template>
              </v-tooltip>
            </div>
          </div>
        </v-card-title>
        <v-card-text class="scan-report-dialog__body">
          <div class="scan-report-dialog__scroller">
            <div v-if="activeProviderLoading" class="scan-report-dialog__loading">
              <v-progress-circular indeterminate size="18" width="2"></v-progress-circular>
              <span>Loading provider details</span>
            </div>
            <pre v-else class="scan-report-dialog__content">{{ activeProviderReportText || 'Provider check report is not available.' }}</pre>
          </div>
        </v-card-text>
      </v-card>
    </v-dialog>
  </v-app>
</template>
