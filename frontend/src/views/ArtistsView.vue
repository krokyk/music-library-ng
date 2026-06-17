<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useLibraryStore } from '@/stores/library'
import type { AlbumReviewDecision, Artist, ArtistProviderCandidate, AlbumImportCandidate } from '@/types'

const store = useLibraryStore()
const { artists, collections, providerLinks, providerCheckRuns, loading, error } = storeToRefs(store)

const search = ref('')
const selectedArtistId = ref<number | null>(null)
const lastMessage = ref('')
const artistToDelete = ref<Artist | null>(null)
const deleteArtistDialog = ref(false)
const deleteArtistWarningDialog = ref(false)
const deletingArtist = ref(false)
const deletingArtistId = ref<number | null>(null)
const matchDialog = ref(false)
const matchLoading = ref(false)
const providerCandidates = ref<ArtistProviderCandidate[]>([])
const matchingArtistId = ref<number | null>(null)
const refreshingArtistId = ref<number | null>(null)
const reviewDialog = ref(false)
const reviewArtistId = ref<number | null>(null)
const reviewCandidates = ref<AlbumImportCandidate[]>([])
const reviewActions = ref<Record<string, AlbumReviewDecision['action']>>({})
const reviewAlbumIds = ref<Record<string, number | null>>({})
const applyingReview = ref(false)

const filteredArtists = computed(() => {
  const needle = search.value.trim().toLowerCase()
  if (!needle) return artists.value
  return artists.value.filter((artist) => artist.name.toLowerCase().includes(needle))
})

const selectedArtist = computed(() => artists.value.find((artist) => artist.id === selectedArtistId.value) ?? null)
const selectedProvider = computed(() => {
  if (!selectedArtist.value) return null
  return providerLinks.value[selectedArtist.value.id]?.[0] ?? null
})

async function selectArtist(artist: Artist) {
  selectedArtistId.value = artist.id
  await store.loadArtistProvider(artist.id)
}

async function checkArtist(artistId: number) {
  const summary = await store.checkArtistProvider(artistId)
  lastMessage.value = summary.messages.join(' ')
}

async function checkAll() {
  const summary = await store.checkAllProviders()
  lastMessage.value = summary.messages.join(' ')
}

async function openMusicBrainzMatch(artist: Artist) {
  selectedArtistId.value = artist.id
  matchingArtistId.value = artist.id
  matchDialog.value = true
  matchLoading.value = true
  providerCandidates.value = []
  try {
    providerCandidates.value = await store.searchMusicBrainzCandidates(artist.id)
  } catch (error) {
    store.error = error instanceof Error ? error.message : String(error)
  } finally {
    matchLoading.value = false
    matchingArtistId.value = null
  }
}

async function useCandidate(candidate: ArtistProviderCandidate) {
  if (!selectedArtistId.value) return
  await store.saveArtistProvider(selectedArtistId.value, {
    providerId: 'musicbrainz',
    providerArtistId: candidate.providerArtistId,
    providerArtistName: candidate.providerArtistName,
    providerUrl: candidate.providerUrl,
    enabled: true,
  })
  matchDialog.value = false
  lastMessage.value = `MusicBrainz provider saved for ${candidate.providerArtistName}.`
}

async function clearSelectedProvider() {
  if (!selectedArtist.value) return
  await store.clearArtistProvider(selectedArtist.value.id)
  lastMessage.value = `Provider cleared for ${selectedArtist.value.name}.`
}

async function refreshProvider(artist: Artist) {
  selectedArtistId.value = artist.id
  refreshingArtistId.value = artist.id
  try {
    const provider = await store.loadArtistProvider(artist.id)
    if (provider?.providerId === 'musicbrainz') {
      const result = await store.refreshArtistProvider(artist.id)
      lastMessage.value = result.messages.join(' ')
      if (result.reviewCandidates.length > 0) {
        openReviewDialog(artist.id, result.reviewCandidates)
      }
      return
    }
    await checkArtist(artist.id)
  } catch (error) {
    store.error = error instanceof Error ? error.message : String(error)
  } finally {
    refreshingArtistId.value = null
  }
}

function openReviewDialog(artistId: number, candidates: AlbumImportCandidate[]) {
  reviewArtistId.value = artistId
  reviewCandidates.value = candidates
  const actions: Record<string, AlbumReviewDecision['action']> = {}
  const albumIds: Record<string, number | null> = {}
  for (const candidate of candidates) {
    const key = reviewKey(candidate)
    actions[key] = candidate.matchedAlbumId ? 'LINK_EXISTING' : 'SKIP'
    albumIds[key] = candidate.matchedAlbumId ?? candidate.options[0]?.albumId ?? null
  }
  reviewActions.value = actions
  reviewAlbumIds.value = albumIds
  reviewDialog.value = true
}

async function applyReviewDecisions() {
  if (!reviewArtistId.value) return
  applyingReview.value = true
  try {
    const decisions = reviewCandidates.value.map((candidate) => {
      const key = reviewKey(candidate)
      const action = reviewActions.value[key] ?? 'SKIP'
      return {
        providerId: candidate.releaseGroup.providerId,
        providerReleaseGroupId: candidate.releaseGroup.providerReleaseGroupId,
        action,
        albumId: action === 'LINK_EXISTING' ? reviewAlbumIds.value[key] : null,
      }
    })
    const result = await store.applyProviderAlbumDecisions(reviewArtistId.value, decisions)
    lastMessage.value = result.messages.join(' ')
    reviewDialog.value = false
  } catch (error) {
    store.error = error instanceof Error ? error.message : String(error)
  } finally {
    applyingReview.value = false
  }
}

function reviewKey(candidate: AlbumImportCandidate) {
  return candidate.releaseGroup.providerReleaseGroupId
}

function providerChipText(artist: Artist) {
  const cached = providerLinks.value[artist.id]?.[0]
  if (cached?.lastErrorMessage) return 'Error'
  if (cached?.providerId === 'musicbrainz') return 'MB'
  if (cached) return 'Provider'
  return artist.providerLinkCount > 0 ? 'Provider' : 'None'
}

function providerChipColor(artist: Artist) {
  const label = providerChipText(artist)
  if (label === 'Error') return 'error'
  if (label === 'MB') return 'primary'
  if (label === 'Provider') return 'info'
  return 'default'
}

function openExternal(url?: string | null) {
  if (url) {
    window.open(url, '_blank', 'noopener')
  }
}

function askDeleteArtist(artist: Artist) {
  artistToDelete.value = artist
  deleteArtistDialog.value = true
}

function artistDeleteNeedsWarning(artist: Artist | null) {
  return Boolean(artist && (artist.collectionIds.length > 0 || artist.localAlbumCount > 0))
}

function artistDeleteWarningLines(artist: Artist | null) {
  if (!artist) {
    return []
  }
  const lines: string[] = []
  if (artist.localAlbumCount > 0) {
    lines.push(`Local albums in the library DB: ${artist.localAlbumCount}`)
  }
  return lines
}

function artistDeleteCollections(artist: Artist | null) {
  if (!artist) {
    return []
  }
  return artist.collectionIds.map((collectionId) => ({
    id: collectionId,
    name: collections.value.find((collection) => collection.id === collectionId)?.name ?? collectionId,
  }))
}

function confirmDeleteArtist() {
  deleteArtistDialog.value = false
  if (artistDeleteNeedsWarning(artistToDelete.value)) {
    deleteArtistWarningDialog.value = true
    return
  }
  void deleteArtist()
}

async function deleteArtist() {
  if (!artistToDelete.value) {
    return
  }
  deletingArtist.value = true
  deletingArtistId.value = artistToDelete.value.id
  try {
    const artistId = artistToDelete.value.id
    await store.deleteArtist(artistId)
    if (selectedArtistId.value === artistId) {
      selectedArtistId.value = null
    }
    deleteArtistDialog.value = false
    deleteArtistWarningDialog.value = false
    artistToDelete.value = null
  } catch (error) {
    store.error = error instanceof Error ? error.message : String(error)
  } finally {
    deletingArtist.value = false
    deletingArtistId.value = null
  }
}

onMounted(() => store.loadAll())
</script>

<template>
  <v-container fluid class="app-page artists-page">
    <v-alert v-if="error" type="error" variant="tonal" class="mb-4">{{ error }}</v-alert>
    <v-alert v-if="lastMessage" type="info" variant="tonal" class="mb-4">{{ lastMessage }}</v-alert>

    <div class="page-header">
      <div>
        <div class="page-title">Artists</div>
        <div class="stat-strip">
          <span>{{ artists.length }} artists</span>
          <span>{{ artists.reduce((sum, artist) => sum + artist.uncheckedAlbumCount, 0) }} unchecked albums</span>
          <span>{{ artists.reduce((sum, artist) => sum + artist.providerLinkCount, 0) }} providers</span>
        </div>
      </div>
      <v-btn :loading="loading" color="primary" prepend-icon="mdi-cloud-search" @click="checkAll">
        Check all
      </v-btn>
    </div>

    <v-row dense>
      <v-col cols="12" lg="8">
        <v-sheet class="panel table-wrap">
          <div class="data-toolbar">
            <v-text-field
              v-model="search"
              class="data-toolbar__search"
              prepend-inner-icon="mdi-magnify"
              density="compact"
              label="Search artists"
              hide-details
            ></v-text-field>
          </div>

          <v-table class="music-table" density="compact" fixed-header height="calc(var(--app-vh) - 230px)">
            <thead>
              <tr>
                <th>Artist</th>
                <th>Albums</th>
                <th>Unchecked</th>
                <th>Local</th>
                <th>Provider</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="artist in filteredArtists"
                :key="artist.id"
                :class="{ 'music-table-row--deleting': deletingArtistId === artist.id }"
              >
                <td class="cell-strong">
                  <span class="artist-delete-cell">
                    <v-progress-circular
                      v-if="deletingArtistId === artist.id"
                      indeterminate
                      size="14"
                      width="2"
                    ></v-progress-circular>
                    <span>{{ artist.name }}</span>
                  </span>
                </td>
                <td>{{ artist.albumCount }}</td>
                <td>
                  <v-chip :color="artist.uncheckedAlbumCount > 0 ? 'warning' : 'default'" size="small" variant="tonal">
                    {{ artist.uncheckedAlbumCount }}
                  </v-chip>
                </td>
                <td>{{ artist.localAlbumCount }}</td>
                <td>
                  <v-chip :color="providerChipColor(artist)" size="small" variant="tonal">
                    {{ providerChipText(artist) }}
                  </v-chip>
                </td>
                <td class="text-right">
                  <v-btn
                    size="small"
                    variant="text"
                    :disabled="deletingArtistId === artist.id"
                    @click="selectArtist(artist)"
                  >
                    Provider
                  </v-btn>
                  <v-btn
                    size="small"
                    variant="text"
                    prepend-icon="mdi-magnify"
                    :loading="matchingArtistId === artist.id"
                    :disabled="deletingArtistId === artist.id"
                    @click="openMusicBrainzMatch(artist)"
                  >
                    Match MB
                  </v-btn>
                  <v-btn
                    size="small"
                    variant="text"
                    prepend-icon="mdi-cloud-search"
                    :loading="refreshingArtistId === artist.id"
                    :disabled="deletingArtistId === artist.id || refreshingArtistId !== null"
                    @click="refreshProvider(artist)"
                  >
                    Refresh
                  </v-btn>
                  <v-btn
                    size="small"
                    variant="text"
                    color="error"
                    prepend-icon="mdi-trash-can-outline"
                    :loading="deletingArtistId === artist.id"
                    :disabled="deletingArtistId !== null && deletingArtistId !== artist.id"
                    @click="askDeleteArtist(artist)"
                  >
                    Delete
                  </v-btn>
                </td>
              </tr>
            </tbody>
          </v-table>
        </v-sheet>
      </v-col>

      <v-col cols="12" lg="4">
        <v-sheet class="panel pa-4 mb-4">
          <div class="panel-title">Provider</div>
          <div v-if="!selectedArtist" class="cell-muted">Select an artist to manage its provider.</div>
          <template v-else>
            <div class="mb-3 cell-strong">{{ selectedArtist.name }}</div>
            <template v-if="selectedProvider">
              <div class="provider-list mb-3">
                <div class="cell-muted">{{ selectedProvider.providerId === 'musicbrainz' ? 'MusicBrainz' : selectedProvider.providerId }}</div>
                <div class="cell-strong">{{ selectedProvider.providerArtistName || selectedProvider.artistName }}</div>
                <div v-if="selectedProvider.providerArtistId" class="mono-path">{{ selectedProvider.providerArtistId }}</div>
                <div v-if="selectedProvider.lastErrorMessage" class="text-error">{{ selectedProvider.lastErrorMessage }}</div>
              </div>
              <div class="dialog-chip-row">
                <v-btn size="small" color="primary" prepend-icon="mdi-magnify" @click="openMusicBrainzMatch(selectedArtist)">
                  Change
                </v-btn>
                <v-btn size="small" color="primary" variant="tonal" prepend-icon="mdi-cloud-search" @click="refreshProvider(selectedArtist)">
                  Refresh
                </v-btn>
                <v-btn size="small" variant="text" prepend-icon="mdi-open-in-new" :disabled="!selectedProvider.providerUrl" @click="openExternal(selectedProvider.providerUrl)">
                  Open
                </v-btn>
                <v-btn size="small" color="error" variant="text" prepend-icon="mdi-link-off" @click="clearSelectedProvider">
                  Clear
                </v-btn>
              </div>
            </template>
            <template v-else>
              <div class="cell-muted mb-3">No provider</div>
              <v-btn color="primary" prepend-icon="mdi-magnify" @click="openMusicBrainzMatch(selectedArtist)">
                Match MusicBrainz
              </v-btn>
            </template>
          </template>
        </v-sheet>

        <v-sheet class="panel pa-4">
          <div class="panel-title">Recent Checks</div>
          <v-list density="compact" class="provider-list">
            <v-list-item v-for="run in providerCheckRuns" :key="run.id">
              <template #prepend>
                <v-chip
                  size="x-small"
                  :color="run.status === 'DONE' ? 'success' : run.status === 'FAILED' ? 'error' : 'warning'"
                >
                  {{ run.status }}
                </v-chip>
              </template>
              <v-list-item-title>{{ run.message ?? `${run.newAlbumCount} new albums` }}</v-list-item-title>
              <v-list-item-subtitle>{{ run.startedAt }}</v-list-item-subtitle>
            </v-list-item>
          </v-list>
        </v-sheet>
      </v-col>
    </v-row>

    <v-dialog v-model="matchDialog" max-width="900">
      <v-card class="dialog-card">
        <v-card-title>Match MusicBrainz</v-card-title>
        <v-card-text class="edit-form">
          <v-progress-linear v-if="matchLoading" indeterminate color="primary"></v-progress-linear>
          <div v-if="!matchLoading && providerCandidates.length === 0" class="cell-muted">No candidates found.</div>
          <v-list v-else density="compact" class="provider-list">
            <v-list-item v-for="candidate in providerCandidates" :key="candidate.providerArtistId">
              <v-list-item-title>
                <span class="cell-strong">{{ candidate.providerArtistName }}</span>
                <v-chip size="x-small" color="primary" variant="tonal" class="ml-2">{{ candidate.matchScore }}</v-chip>
              </v-list-item-title>
              <v-list-item-subtitle>
                {{ [candidate.type, candidate.country, candidate.disambiguation].filter(Boolean).join(' · ') }}
              </v-list-item-subtitle>
              <div class="mono-path">{{ candidate.providerArtistId }}</div>
              <div v-if="candidate.matchedLocalAlbums.length" class="dialog-chip-row mt-2">
                <v-chip
                  v-for="album in candidate.matchedLocalAlbums.slice(0, 6)"
                  :key="album"
                  size="small"
                  variant="tonal"
                >
                  {{ album }}
                </v-chip>
              </div>
              <div v-if="candidate.releaseGroups.length" class="cell-muted mt-2">
                {{ candidate.releaseGroups.slice(0, 5).map((group) => group.title).join(' · ') }}
              </div>
              <template #append>
                <v-btn size="small" variant="text" prepend-icon="mdi-open-in-new" @click="openExternal(candidate.providerUrl)">
                  Open
                </v-btn>
                <v-btn size="small" color="primary" @click="useCandidate(candidate)">Use</v-btn>
              </template>
            </v-list-item>
          </v-list>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn variant="text" @click="matchDialog = false">Close</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog v-model="reviewDialog" max-width="1100">
      <v-card class="dialog-card">
        <v-card-title>MusicBrainz Review</v-card-title>
        <v-card-text class="edit-form">
          <v-table class="music-table" density="compact">
            <thead>
              <tr>
                <th>Remote title</th>
                <th>Date</th>
                <th>Type</th>
                <th>Reason</th>
                <th>Action</th>
                <th>Album</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="candidate in reviewCandidates" :key="candidate.releaseGroup.providerReleaseGroupId">
                <td class="cell-strong">{{ candidate.releaseGroup.title }}</td>
                <td>{{ candidate.releaseGroup.releaseDate || '' }}</td>
                <td>
                  {{ [candidate.releaseGroup.primaryType, ...candidate.releaseGroup.secondaryTypes].filter(Boolean).join(', ') }}
                </td>
                <td>{{ candidate.reason }}</td>
                <td>
                  <v-select
                    v-model="reviewActions[reviewKey(candidate)]"
                    :items="['LINK_EXISTING', 'CREATE', 'SKIP']"
                    density="compact"
                    hide-details
                  ></v-select>
                </td>
                <td>
                  <v-select
                    v-model="reviewAlbumIds[reviewKey(candidate)]"
                    :items="candidate.options"
                    item-title="title"
                    item-value="albumId"
                    density="compact"
                    hide-details
                    :disabled="reviewActions[reviewKey(candidate)] !== 'LINK_EXISTING'"
                  ></v-select>
                </td>
                <td class="text-right">
                  <v-btn
                    size="small"
                    variant="text"
                    icon="mdi-open-in-new"
                    :disabled="!candidate.releaseGroup.providerUrl"
                    @click="openExternal(candidate.releaseGroup.providerUrl)"
                  ></v-btn>
                </td>
              </tr>
            </tbody>
          </v-table>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn variant="text" @click="reviewDialog = false">Cancel</v-btn>
          <v-btn color="primary" :loading="applyingReview" @click="applyReviewDecisions">Apply</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog v-model="deleteArtistDialog" max-width="460">
      <v-card class="dialog-card">
        <v-card-title>Delete Artist</v-card-title>
        <v-card-text>
          Delete <span class="dialog-entity-name">{{ artistToDelete?.name }}</span> from the library database?
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn variant="text" @click="deleteArtistDialog = false">Cancel</v-btn>
          <v-btn color="error" :loading="deletingArtist" @click="confirmDeleteArtist">Delete</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog v-model="deleteArtistWarningDialog" max-width="520">
      <v-card class="dialog-card">
        <v-card-title>Delete Artist With Library Data</v-card-title>
        <v-card-text class="edit-form">
          <div>
            <span class="dialog-entity-name">{{ artistToDelete?.name }}</span> is linked to existing library data.
            Deleting it removes related database records only; files on disk are not touched.
          </div>
          <div class="cell-muted">
            <div v-if="artistDeleteCollections(artistToDelete).length" class="dialog-chip-row">
              <v-chip
                v-for="collection in artistDeleteCollections(artistToDelete)"
                :key="collection.id"
                size="small"
                color="primary"
                variant="tonal"
              >
                {{ collection.name }}
              </v-chip>
            </div>
            <div v-for="line in artistDeleteWarningLines(artistToDelete)" :key="line">{{ line }}</div>
          </div>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn variant="text" @click="deleteArtistWarningDialog = false">Cancel</v-btn>
          <v-btn color="error" :loading="deletingArtist" @click="deleteArtist">Delete anyway</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </v-container>
</template>
