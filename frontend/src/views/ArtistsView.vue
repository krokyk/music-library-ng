<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useLibraryStore } from '@/stores/library'
import type {
  Artist,
  ArtistProviderCandidate,
} from '@/types'

type ProviderId = 'musicbrainz' | 'spirit_of_metal' | 'metal_archives'

interface ProviderDefinition {
  id: ProviderId
  label: string
  icon: string
  color: string
}

const providerDefinitions: ProviderDefinition[] = [
  { id: 'musicbrainz', label: 'MusicBrainz', icon: 'mdi-music-circle', color: 'primary' },
  { id: 'spirit_of_metal', label: 'Spirit of Metal', icon: 'mdi-fire', color: 'warning' },
  { id: 'metal_archives', label: 'Metal Archives', icon: 'mdi-archive', color: 'info' },
]

const store = useLibraryStore()
const { artists, albums, collections, providerLinks, error } = storeToRefs(store)

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
const providerUrlDialog = ref(false)
const providerUrlSaving = ref(false)
const providerUrlArtist = ref<Artist | null>(null)
const providerUrlProviderId = ref<ProviderId | null>(null)
const providerUrl = ref('')

const filteredArtists = computed(() => {
  const needle = search.value.trim().toLowerCase()
  if (!needle) return artists.value
  return artists.value.filter((artist) => artist.name.toLowerCase().includes(needle))
})

const selectedArtist = computed(() => artists.value.find((artist) => artist.id === selectedArtistId.value) ?? null)
const selectedProvider = computed(() => selectedArtist.value ? providerForArtist(selectedArtist.value) : null)
const selectedAlbums = computed(() => {
  if (!selectedArtist.value) {
    return []
  }
  const artistId = selectedArtist.value.id
  return albums.value
    .filter((album) => album.artistIds.includes(artistId))
    .slice()
    .sort((left, right) =>
      (left.releaseDate ?? '').localeCompare(right.releaseDate ?? '')
      || left.title.localeCompare(right.title),
    )
})
const providerUrlDefinition = computed(() =>
  providerUrlProviderId.value ? providerDefinition(providerUrlProviderId.value) : null,
)

async function selectArtist(artist: Artist) {
  if (deletingArtistId.value === artist.id) {
    return
  }
  selectedArtistId.value = artist.id
  await store.loadArtistProvider(artist.id)
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
    providerArtistType: candidate.type,
    providerArtistCountry: candidate.country,
    providerArtistDisambiguation: candidate.disambiguation,
    providerArtistActive: candidate.active,
    providerUrl: candidate.providerUrl,
    enabled: true,
  })
  matchDialog.value = false
  lastMessage.value = `MusicBrainz provider saved for ${candidate.providerArtistName}.`
}

async function startProviderSetup(artist: Artist, providerId: ProviderId) {
  await selectArtist(artist)
  if (providerId === 'musicbrainz') {
    await openMusicBrainzMatch(artist)
    return
  }
  providerUrlArtist.value = artist
  providerUrlProviderId.value = providerId
  providerUrl.value = ''
  providerUrlDialog.value = true
}

async function saveUrlProvider() {
  if (!providerUrlArtist.value || !providerUrlProviderId.value) {
    return
  }
  const url = providerUrl.value.trim()
  if (!url) {
    return
  }
  providerUrlSaving.value = true
  try {
    const provider = providerDefinition(providerUrlProviderId.value)
    await store.saveArtistProvider(providerUrlArtist.value.id, {
      providerId: providerUrlProviderId.value,
      providerUrl: url,
      enabled: true,
    })
    lastMessage.value = `${provider.label} provider saved for ${providerUrlArtist.value.name}.`
    providerUrlDialog.value = false
  } catch (error) {
    store.error = error instanceof Error ? error.message : String(error)
  } finally {
    providerUrlSaving.value = false
  }
}

async function clearArtistProvider(artist: Artist) {
  await store.clearArtistProvider(artist.id)
  lastMessage.value = `Provider cleared for ${artist.name}.`
}

function providerForArtist(artist: Artist) {
  const cached = providerLinks.value[artist.id]?.[0]
  if (cached) {
    return cached
  }
  if (!artist.providerId) {
    return null
  }
  return {
    providerId: artist.providerId,
    providerArtistId: artist.providerArtistId,
    providerArtistName: artist.providerArtistName,
    providerArtistType: artist.providerArtistType,
    providerArtistCountry: artist.providerArtistCountry,
    providerArtistDisambiguation: artist.providerArtistDisambiguation,
    providerArtistActive: artist.providerArtistActive,
    providerUrl: artist.providerUrl,
    lastErrorMessage: null,
  }
}

function providerDefinition(providerId?: string | null) {
  return providerDefinitions.find((provider) => provider.id === providerId)
    ?? { id: 'musicbrainz' as ProviderId, label: providerId ?? 'Provider', icon: 'mdi-link-variant', color: 'default' }
}

function providerActionsForArtist(artist: Artist) {
  const selectedProviderId = providerForArtist(artist)?.providerId
  return providerDefinitions.filter((provider) => provider.id !== selectedProviderId)
}

function providerChipText(artist: Artist) {
  const provider = providerForArtist(artist)
  return provider ? providerDefinition(provider.providerId).label : 'None'
}

function providerChipColor(artist: Artist) {
  const provider = providerForArtist(artist)
  if (provider?.lastErrorMessage) return 'error'
  return provider ? providerDefinition(provider.providerId).color : 'default'
}

function providerChipIcon(artist: Artist) {
  const provider = providerForArtist(artist)
  return provider ? providerDefinition(provider.providerId).icon : undefined
}

function providerIdentityLabel() {
  if (!selectedProvider.value) {
    return null
  }
  return selectedProvider.value.providerArtistName
    || selectedProvider.value.providerArtistId
    || selectedProvider.value.providerUrl
    || null
}

function artistCountry(artist: Artist) {
  return providerForArtist(artist)?.providerArtistCountry ?? artist.providerArtistCountry ?? null
}

function artistCountryLabel(artist: Artist) {
  const country = artistCountry(artist)
  if (!country) {
    return 'Unknown'
  }
  const normalized = country.trim().toUpperCase()
  const flag = countryFlag(normalized)
  return flag ? `${flag} ${normalized}` : normalized
}

function countryFlag(country: string) {
  if (!/^[A-Z]{2}$/.test(country)) {
    return ''
  }
  return String.fromCodePoint(
    ...[...country].map((character) => 0x1f1e6 + character.charCodeAt(0) - 65),
  )
}

function artistType(artist: Artist) {
  return providerForArtist(artist)?.providerArtistType ?? artist.providerArtistType ?? null
}

function artistStatus(artist: Artist) {
  const active = providerForArtist(artist)?.providerArtistActive ?? artist.providerArtistActive
  if (active === true) {
    return 'Active'
  }
  if (active === false) {
    return 'Inactive'
  }
  return 'Unknown'
}

function candidateInfo(candidate: ArtistProviderCandidate) {
  const status = candidate.active === true ? 'Active' : candidate.active === false ? 'Inactive' : null
  return [candidate.type, candidate.country, status, candidate.disambiguation].filter(Boolean).join(' · ')
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
                <th>Country</th>
                <th>Type</th>
                <th>Status</th>
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
                :class="{
                  'music-table-row--deleting': deletingArtistId === artist.id,
                  'music-table-row--selectable': deletingArtistId !== artist.id,
                  'music-table-row--selected': selectedArtistId === artist.id,
                }"
                @click="selectArtist(artist)"
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
                <td>
                  <span :class="{ 'cell-muted': !artistCountry(artist) }">{{ artistCountryLabel(artist) }}</span>
                </td>
                <td>
                  <span :class="{ 'cell-muted': !artistType(artist) }">{{ artistType(artist) || 'Unknown' }}</span>
                </td>
                <td>
                  <v-chip
                    v-if="artistStatus(artist) !== 'Unknown'"
                    :color="artistStatus(artist) === 'Active' ? 'success' : 'default'"
                    size="x-small"
                    variant="tonal"
                  >
                    {{ artistStatus(artist) }}
                  </v-chip>
                  <span v-else class="cell-muted">Unknown</span>
                </td>
                <td>{{ artist.albumCount }}</td>
                <td>
                  <v-chip :color="artist.uncheckedAlbumCount > 0 ? 'warning' : 'default'" size="small" variant="tonal">
                    {{ artist.uncheckedAlbumCount }}
                  </v-chip>
                </td>
                <td>{{ artist.localAlbumCount }}</td>
                <td>
                  <v-chip
                    v-if="providerForArtist(artist)"
                    :color="providerChipColor(artist)"
                    size="small"
                    variant="tonal"
                    :prepend-icon="providerChipIcon(artist)"
                    closable
                    close-icon="mdi-trash-can-outline"
                    @click:close.stop="clearArtistProvider(artist)"
                  >
                    {{ providerChipText(artist) }}
                  </v-chip>
                  <span v-else class="cell-muted">None</span>
                </td>
                <td class="text-right">
                  <div class="artist-provider-actions">
                    <v-btn
                      v-for="provider in providerActionsForArtist(artist)"
                      :key="provider.id"
                      class="artist-provider-action"
                      size="small"
                      variant="text"
                      :prepend-icon="provider.icon"
                      :loading="provider.id === 'musicbrainz' && matchingArtistId === artist.id"
                      :disabled="deletingArtistId === artist.id"
                      @click.stop="startProviderSetup(artist, provider.id)"
                    >
                      <span class="artist-provider-action__label">{{ provider.label }}</span>
                    </v-btn>
                  </div>
                  <v-btn
                    size="small"
                    variant="text"
                    color="error"
                    prepend-icon="mdi-trash-can-outline"
                    :loading="deletingArtistId === artist.id"
                    :disabled="deletingArtistId !== null && deletingArtistId !== artist.id"
                    @click.stop="askDeleteArtist(artist)"
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
          <div class="panel-title">Artist Info</div>
          <div v-if="!selectedArtist" class="cell-muted">Select an artist row to view details.</div>
          <template v-else>
            <div class="mb-3 cell-strong">{{ selectedArtist.name }}</div>
            <div class="artist-info-grid">
              <div class="cell-muted">Sort name</div>
              <div>{{ selectedArtist.sortName || 'None' }}</div>
              <div class="cell-muted">Country</div>
              <div :class="{ 'cell-muted': !artistCountry(selectedArtist) }">{{ artistCountryLabel(selectedArtist) }}</div>
              <div class="cell-muted">Type</div>
              <div :class="{ 'cell-muted': !artistType(selectedArtist) }">{{ artistType(selectedArtist) || 'Unknown' }}</div>
              <div class="cell-muted">Status</div>
              <div :class="{ 'cell-muted': artistStatus(selectedArtist) === 'Unknown' }">{{ artistStatus(selectedArtist) }}</div>
              <div class="cell-muted">Collections</div>
              <div class="dialog-chip-row">
                <v-chip
                  v-for="collectionId in selectedArtist.collectionIds"
                  :key="collectionId"
                  size="x-small"
                  variant="tonal"
                >
                  {{ collections.find((collection) => collection.id === collectionId)?.name ?? collectionId }}
                </v-chip>
                <span v-if="selectedArtist.collectionIds.length === 0" class="cell-muted">None</span>
              </div>
              <div class="cell-muted">Albums</div>
              <div>{{ selectedArtist.albumCount }} total, {{ selectedArtist.uncheckedAlbumCount }} unchecked</div>
              <div class="cell-muted">Provider</div>
              <div>
                <v-chip
                  v-if="selectedProvider"
                  size="small"
                  variant="tonal"
                  :color="providerChipColor(selectedArtist)"
                  :prepend-icon="providerChipIcon(selectedArtist)"
                >
                  {{ providerChipText(selectedArtist) }}
                </v-chip>
                <span v-else class="cell-muted">None</span>
              </div>
            </div>

            <div v-if="selectedProvider" class="provider-list mt-4">
              <div v-if="providerIdentityLabel()" class="cell-strong">{{ providerIdentityLabel() }}</div>
              <div v-if="selectedProvider.providerUrl" class="mono-path">{{ selectedProvider.providerUrl }}</div>
              <div v-if="selectedProvider.lastErrorMessage" class="text-error">{{ selectedProvider.lastErrorMessage }}</div>
              <v-btn
                class="mt-2"
                size="small"
                variant="text"
                prepend-icon="mdi-open-in-new"
                :disabled="!selectedProvider.providerUrl"
                @click="openExternal(selectedProvider.providerUrl)"
              >
                Open
              </v-btn>
            </div>

            <div class="panel-title mt-5">Known Albums</div>
            <div v-if="selectedAlbums.length === 0" class="cell-muted">No known albums.</div>
            <v-list v-else density="compact" class="provider-list artist-info-albums">
              <v-list-item v-for="album in selectedAlbums" :key="album.id">
                <v-list-item-title>{{ album.title }}</v-list-item-title>
                <v-list-item-subtitle>{{ album.releaseDate || 'No date' }}</v-list-item-subtitle>
              </v-list-item>
            </v-list>
          </template>
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
                {{ candidateInfo(candidate) }}
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

    <v-dialog v-model="providerUrlDialog" max-width="560">
      <v-card class="dialog-card">
        <v-card-title>{{ providerUrlDefinition?.label }} Provider</v-card-title>
        <v-card-text class="edit-form">
          <div class="cell-muted">{{ providerUrlArtist?.name }}</div>
          <v-text-field
            v-model="providerUrl"
            label="URL"
            prepend-inner-icon="mdi-link-variant"
            autofocus
            hide-details
            @keyup.enter="saveUrlProvider"
          ></v-text-field>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn variant="text" @click="providerUrlDialog = false">Cancel</v-btn>
          <v-btn color="primary" :loading="providerUrlSaving" :disabled="!providerUrl.trim()" @click="saveUrlProvider">
            Save
          </v-btn>
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
