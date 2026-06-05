<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useLibraryStore } from '@/stores/library'
import type { Album } from '@/types'

const store = useLibraryStore()
const { artists, albums, loading, error } = storeToRefs(store)

const artistName = ref('')
const albumArtistId = ref<number | null>(null)
const albumTitle = ref('')
const albumReleaseDate = ref('')
const albumChecked = ref(true)
const localFilter = ref<'all' | 'local' | 'not-local'>('all')
const checkedFilter = ref<'all' | 'checked' | 'unchecked'>('all')
const search = ref('')

const localFilterItems = [
  { title: 'All locations', value: 'all' },
  { title: 'On disk', value: 'local' },
  { title: 'No local files', value: 'not-local' },
]

const checkedFilterItems = [
  { title: 'All listening states', value: 'all' },
  { title: 'Listened', value: 'checked' },
  { title: 'Not listened', value: 'unchecked' },
]

const filteredAlbums = computed(() => {
  const needle = search.value.trim().toLowerCase()
  return albums.value
    .filter((album) => {
      if (checkedFilter.value === 'checked') return album.checked
      if (checkedFilter.value === 'unchecked') return !album.checked
      return true
    })
    .filter((album) => {
      if (localFilter.value === 'local') return album.hasLocalPath
      if (localFilter.value === 'not-local') return !album.hasLocalPath
      return true
    })
    .filter((album) => {
      if (!needle) return true
      return `${album.artistName} ${album.title} ${album.releaseDate ?? ''} ${album.localPaths.map((path) => path.collectionName).join(' ')}`
        .toLowerCase()
        .includes(needle)
    })
})

function artistOptions() {
  return artists.value.map((artist) => ({ title: artist.name, value: artist.id }))
}

function localLabel(album: Album) {
  const activePath = album.localPaths.find((path) => !path.missingSince)
  return activePath?.collectionName ?? (album.hasLocalPath ? 'local' : 'none')
}

function localTooltip(album: Album) {
  const activePaths = album.localPaths.filter((path) => !path.missingSince)
  if (activePaths.length === 0) {
    return 'No active local folder'
  }
  return activePaths.map((path) => path.resolvedPath ?? path.relativePath).join('\n')
}

async function addArtist() {
  if (!artistName.value.trim()) {
    return
  }
  await store.addArtist(artistName.value.trim())
  artistName.value = ''
}

async function addAlbum() {
  if (!albumArtistId.value || !albumTitle.value.trim()) {
    return
  }
  await store.addAlbum(albumArtistId.value, albumTitle.value.trim(), albumReleaseDate.value.trim() || null, albumChecked.value)
  albumTitle.value = ''
  albumReleaseDate.value = ''
  albumChecked.value = true
}

async function updateAlbum(album: Album, patch: Partial<Album>) {
  await store.updateAlbum({ ...album, ...patch })
}

function albumReleaseDateValue(album: Album) {
  return album.releaseDate ?? ''
}

function normalizeReleaseDateInput(value: unknown) {
  const text = value == null ? '' : String(value).trim()
  return text || null
}

onMounted(() => store.loadAll())
</script>

<template>
  <v-container fluid class="app-page library-page">
    <v-alert v-if="error" type="error" variant="tonal" class="mb-4">{{ error }}</v-alert>

    <div class="page-header">
      <div>
        <div class="page-title">Library</div>
        <div class="stat-strip">
          <span>{{ artists.length }} artists</span>
          <span>{{ albums.length }} albums</span>
          <span>{{ albums.filter((album) => album.checked).length }} listened</span>
          <span>{{ albums.filter((album) => !album.checked).length }} unchecked</span>
          <span>{{ filteredAlbums.length }} visible</span>
        </div>
      </div>
      <v-btn :loading="loading" prepend-icon="mdi-refresh" variant="tonal" @click="store.loadAll()">Refresh</v-btn>
    </div>

    <v-row dense class="mb-4">
      <v-col cols="12" lg="4">
        <v-sheet class="panel pa-4">
          <div class="panel-title">Add Artist</div>
          <v-text-field
            v-model="artistName"
            density="compact"
            label="Artist"
            hide-details
            @keyup.enter="addArtist"
          ></v-text-field>
          <v-btn block color="primary" class="mt-3" prepend-icon="mdi-plus" @click="addArtist">Add artist</v-btn>
        </v-sheet>
      </v-col>

      <v-col cols="12" lg="8">
        <v-sheet class="panel pa-4">
          <div class="panel-title">Add Album</div>
          <v-row dense align="center">
            <v-col cols="12" md="4">
              <v-select
                v-model="albumArtistId"
                :items="artistOptions()"
                density="compact"
                label="Artist"
                hide-details
              ></v-select>
            </v-col>
            <v-col cols="12" md="4">
              <v-text-field v-model="albumTitle" density="compact" label="Album" hide-details></v-text-field>
            </v-col>
            <v-col cols="6" md="2">
              <v-text-field v-model="albumReleaseDate" density="compact" label="Release date" hide-details></v-text-field>
            </v-col>
            <v-col cols="6" md="2">
              <v-checkbox v-model="albumChecked" density="compact" label="Listened" hide-details></v-checkbox>
            </v-col>
          </v-row>
          <v-btn color="primary" class="mt-3" prepend-icon="mdi-album" @click="addAlbum">Add album</v-btn>
        </v-sheet>
      </v-col>
    </v-row>

    <v-sheet class="panel table-wrap">
      <div class="data-toolbar">
        <v-text-field
          v-model="search"
          class="data-toolbar__search"
          prepend-inner-icon="mdi-magnify"
          density="compact"
          label="Search library"
          hide-details
        ></v-text-field>
        <v-select
          v-model="checkedFilter"
          class="data-toolbar__filter"
          :items="checkedFilterItems"
          density="compact"
          label="Listened"
          hide-details
        ></v-select>
        <v-select
          v-model="localFilter"
          class="data-toolbar__filter"
          :items="localFilterItems"
          density="compact"
          label="Local"
          hide-details
        ></v-select>
      </div>

      <v-table class="music-table" density="compact" fixed-header height="calc(var(--app-vh) - 355px)">
        <thead>
          <tr>
            <th>Artist</th>
            <th>Album</th>
            <th>Release date</th>
            <th>Listened</th>
            <th>Local</th>
            <th>Path</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="album in filteredAlbums" :key="album.id">
            <td class="cell-strong">{{ album.artistName }}</td>
            <td>{{ album.title }}</td>
            <td style="width: 120px">
              <v-text-field
                :model-value="albumReleaseDateValue(album)"
                density="compact"
                hide-details
                @update:model-value="(value) => updateAlbum(album, { releaseDate: normalizeReleaseDateInput(value) })"
              ></v-text-field>
            </td>
            <td style="width: 112px">
              <v-checkbox
                :model-value="album.checked"
                density="compact"
                hide-details
                @update:model-value="(value) => updateAlbum(album, { checked: Boolean(value) })"
              ></v-checkbox>
            </td>
            <td style="width: 120px">
              <v-tooltip :text="localTooltip(album)" location="top">
                <template #activator="{ props }">
                  <v-chip
                    v-bind="props"
                    :color="album.onDisk ? 'success' : album.hasLocalPath ? 'warning' : 'default'"
                    :prepend-icon="album.onDisk ? 'mdi-harddisk' : album.hasLocalPath ? 'mdi-folder-alert' : 'mdi-minus'"
                    size="small"
                    variant="tonal"
                  >
                    {{ album.onDisk ? 'on disk' : album.hasLocalPath ? 'missing' : 'none' }}
                  </v-chip>
                </template>
              </v-tooltip>
            </td>
            <td class="cell-muted">{{ localLabel(album) }}</td>
          </tr>
        </tbody>
      </v-table>
    </v-sheet>
  </v-container>
</template>
