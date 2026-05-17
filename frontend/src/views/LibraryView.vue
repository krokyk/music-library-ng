<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useLibraryStore } from '@/stores/library'
import type { Album, AlbumStatus } from '@/types'

const store = useLibraryStore()
const { artists, albums, loading, error } = storeToRefs(store)

const artistName = ref('')
const albumArtistId = ref<number | null>(null)
const albumTitle = ref('')
const albumYear = ref<number | null>(null)
const albumStatus = ref<AlbumStatus>('CHECKED')
const albumSourceId = ref<string | null>(null)
const sourceFilter = ref<string | null>(null)
const search = ref('')

const statusItems: AlbumStatus[] = ['CHECKED', 'MISSING', 'WANTED', 'IGNORED']

const filteredAlbums = computed(() => {
  const needle = search.value.trim().toLowerCase()
  if (!needle) {
    return sourceFilter.value ? albums.value.filter((album) => album.sourceId === sourceFilter.value) : albums.value
  }
  return albums.value
    .filter((album) => !sourceFilter.value || album.sourceId === sourceFilter.value)
    .filter((album) =>
      `${album.artistName} ${album.title} ${album.releaseYear ?? ''} ${album.status} ${album.sourceName ?? ''}`
        .toLowerCase()
        .includes(needle),
    )
})

function artistOptions() {
  return artists.value.map((artist) => ({ title: artist.name, value: artist.id }))
}

function sourceOptions() {
  return [
    { title: 'Manual / ungrouped', value: null },
    ...store.sources.map((source) => ({ title: source.name, value: source.id })),
  ]
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
  await store.addAlbum(albumArtistId.value, albumTitle.value.trim(), albumYear.value, albumStatus.value, albumSourceId.value)
  albumTitle.value = ''
  albumYear.value = null
}

async function updateAlbum(album: Album, patch: Partial<Album>) {
  await store.updateAlbum({ ...album, ...patch })
}

onMounted(() => store.loadAll())
</script>

<template>
  <v-container fluid class="pa-5">
    <v-alert v-if="error" type="error" variant="tonal" class="mb-4">{{ error }}</v-alert>

    <v-row dense>
      <v-col cols="12" lg="4">
        <v-sheet border rounded class="pa-4">
          <div class="text-subtitle-1 mb-3">Add Artist</div>
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
        <v-sheet border rounded class="pa-4">
          <div class="text-subtitle-1 mb-3">Add Checked Album</div>
          <v-row dense>
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
              <v-text-field v-model.number="albumYear" type="number" density="compact" label="Year" hide-details></v-text-field>
            </v-col>
            <v-col cols="6" md="2">
              <v-select v-model="albumStatus" :items="statusItems" density="compact" label="Status" hide-details></v-select>
            </v-col>
            <v-col cols="12" md="4">
              <v-select
                v-model="albumSourceId"
                :items="sourceOptions()"
                density="compact"
                label="Collection group"
                hide-details
              ></v-select>
            </v-col>
          </v-row>
          <v-btn color="primary" class="mt-3" prepend-icon="mdi-album" @click="addAlbum">Add album</v-btn>
        </v-sheet>
      </v-col>
    </v-row>

    <v-sheet border rounded class="mt-4">
      <div class="d-flex ga-3 align-center pa-4">
        <v-text-field
          v-model="search"
          prepend-inner-icon="mdi-magnify"
          density="compact"
          label="Search library"
          hide-details
        ></v-text-field>
        <v-select
          v-model="sourceFilter"
          :items="sourceOptions()"
          density="compact"
          label="Group"
          hide-details
          style="max-width: 260px"
        ></v-select>
        <v-btn :loading="loading" prepend-icon="mdi-refresh" @click="store.loadAll()">Refresh</v-btn>
      </div>

      <v-table fixed-header height="calc(100vh - 350px)">
        <thead>
          <tr>
            <th>Artist</th>
            <th>Album</th>
            <th>Year</th>
            <th>Status</th>
            <th>Source</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="album in filteredAlbums" :key="album.id">
            <td>{{ album.artistName }}</td>
            <td>{{ album.title }}</td>
            <td style="width: 120px">
              <v-text-field
                :model-value="album.releaseYear"
                type="number"
                density="compact"
                hide-details
                @update:model-value="(value) => updateAlbum(album, { releaseYear: Number(value) || null })"
              ></v-text-field>
            </td>
            <td style="width: 170px">
              <v-select
                :model-value="album.status"
                :items="statusItems"
                density="compact"
                hide-details
                @update:model-value="(value) => updateAlbum(album, { status: value })"
              ></v-select>
            </td>
            <td>{{ album.sourceName ?? album.sourceId ?? 'manual' }}</td>
          </tr>
        </tbody>
      </v-table>
    </v-sheet>
  </v-container>
</template>
