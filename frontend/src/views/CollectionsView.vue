<script setup lang="ts">
import { onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { useLibraryStore } from '@/stores/library'

const store = useLibraryStore()
const { collections, scanRuns, scanEvents, musicRoot, loading, error } = storeToRefs(store)

onMounted(() => store.loadAll())
</script>

<template>
  <v-container fluid class="app-page collections-page">
    <v-alert v-if="error" type="error" variant="tonal" class="mb-4">{{ error }}</v-alert>

    <div class="page-header">
      <div>
        <div class="page-title">Collections</div>
        <div class="page-kicker">Scan folders under the detected music root</div>
      </div>
      <v-btn color="primary" :loading="loading" prepend-icon="mdi-database-search" @click="store.scan()">Scan enabled collections</v-btn>
    </div>

    <v-sheet class="panel pa-4 mb-4">
      <div class="d-flex align-center ga-3 mb-3">
        <h2 class="panel-title mb-0">Music Root</h2>
        <v-chip v-if="musicRoot?.valid" color="success" size="small">valid</v-chip>
        <v-chip v-else color="error" size="small">not found</v-chip>
        <v-chip v-if="musicRoot?.detected" color="primary" size="small">auto-detected</v-chip>
      </div>
      <div class="mono-path mb-3">
        {{ musicRoot?.selectedRoot ?? 'No root selected. Scan is disabled until a valid root is configured.' }}
      </div>
      <v-table class="music-table" density="compact">
        <thead>
          <tr>
            <th>Candidate</th>
            <th>Resolved</th>
            <th>Folder</th>
            <th>Markers</th>
            <th></th>
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
            <td class="text-right">
              <v-btn
                size="small"
                variant="text"
                :disabled="!candidate.markersFound"
                @click="store.selectMusicRoot(candidate.configuredPath)"
              >
                Use
              </v-btn>
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
            <th>Parser</th>
            <th>Enabled</th>
            <th>Relative Path</th>
            <th>Resolved Path</th>
            <th>Last Scan</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="collection in collections" :key="collection.id">
            <td class="cell-strong">{{ collection.name }}</td>
            <td class="cell-muted">{{ collection.parser }}</td>
            <td>
              <v-chip :color="collection.enabled ? 'success' : 'default'" size="small">{{ collection.enabled ? 'yes' : 'no' }}</v-chip>
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
                :disabled="!collection.enabled"
                prepend-icon="mdi-play"
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
