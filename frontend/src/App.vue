<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useLibraryStore } from '@/stores/library'

const store = useLibraryStore()
const { collections, providerStatus, scanJob, statusHistory, uiSettings } = storeToRefs(store)

const historyDialog = ref(false)
const completedStatus = ref('')
const completedStatusState = ref<'done' | 'failed'>('done')
const completedStatusTimer = ref<number | null>(null)

const scanCollectionName = computed(() => {
  const collectionId = scanJob.value?.activeCollectionId ?? scanJob.value?.requestedCollectionId
  return collections.value.find((collection) => collection.id === collectionId)?.name ?? collectionId ?? 'collection'
})

const activeStatusMessage = computed(() => {
  if (scanJob.value?.status === 'RUNNING') {
    return `Scanning collection ${scanCollectionName.value}: ${scanJob.value.artistProcessed}/${scanJob.value.artistTotal} dirs scanned`
  }
  if (providerStatus.value.running && providerStatus.value.message) {
    return providerStatus.value.message
  }
  return completedStatus.value
})

const statusState = computed(() => {
  if (scanJob.value?.status === 'RUNNING' || providerStatus.value.running) {
    return 'running'
  }
  return completedStatus.value ? completedStatusState.value : 'idle'
})

function completeStatus(message: string, state: 'done' | 'failed' = 'done') {
  completedStatus.value = message
  completedStatusState.value = state
  store.addStatusHistory(message, state)
  if (completedStatusTimer.value !== null) {
    window.clearTimeout(completedStatusTimer.value)
  }
  completedStatusTimer.value = window.setTimeout(() => {
    completedStatus.value = ''
    completedStatusTimer.value = null
  }, uiSettings.value.statusCompleteVisibleMs)
}

watch(
  () => scanJob.value?.status ?? 'IDLE',
  (status, previousStatus) => {
    if (status === 'RUNNING' && previousStatus !== 'RUNNING') {
      store.addStatusHistory(`Scanning collection ${scanCollectionName.value}`, 'running')
      completedStatus.value = ''
    }
    if (previousStatus === 'RUNNING' && status !== 'RUNNING' && scanJob.value) {
      if (status === 'DONE') {
        completeStatus(
          `${scanCollectionName.value} scan complete: ${scanJob.value.parsedCount} artists, ${scanJob.value.createdCount} new`,
        )
      } else if (status === 'FAILED' || status === 'CANCELLED') {
        completeStatus(scanJob.value.message ?? `${scanCollectionName.value} scan ${status.toLowerCase()}`, 'failed')
      }
    }
  },
)

watch(
  () => providerStatus.value.running,
  (running, wasRunning) => {
    const message = providerStatus.value.message
    if (running && message) {
      store.addStatusHistory(message, 'running')
      completedStatus.value = ''
      return
    }
    if (wasRunning && message) {
      completeStatus(message, message.toLowerCase().includes('failed') ? 'failed' : 'done')
    }
  },
)

onMounted(() => {
  void store.loadUiSettings()
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
        v-if="activeStatusMessage"
        class="global-status-bar"
        :class="`global-status-bar--${statusState}`"
        type="button"
        @click="historyDialog = true"
      >
        <v-progress-circular
          v-if="statusState === 'running'"
          indeterminate
          size="16"
          width="2"
        ></v-progress-circular>
        <span>{{ activeStatusMessage }}</span>
      </button>
      <router-view />
    </v-main>

    <v-dialog v-model="historyDialog" max-width="680">
      <v-card class="dialog-card">
        <v-card-title>Status History</v-card-title>
        <v-card-text>
          <v-list density="compact" lines="two">
            <v-list-item v-for="entry in statusHistory" :key="entry.id">
              <template #prepend>
                <v-chip
                  size="x-small"
                  :color="entry.state === 'failed' ? 'error' : entry.state === 'done' ? 'success' : 'primary'"
                >
                  {{ entry.state }}
                </v-chip>
              </template>
              <v-list-item-title>{{ entry.message }}</v-list-item-title>
              <v-list-item-subtitle>{{ entry.createdAt }}</v-list-item-subtitle>
            </v-list-item>
            <div v-if="statusHistory.length === 0" class="pane-empty pane-empty--compact">
              No status history yet.
            </div>
          </v-list>
        </v-card-text>
      </v-card>
    </v-dialog>
  </v-app>
</template>
