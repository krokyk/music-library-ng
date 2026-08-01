<script setup lang="ts">
import { computed } from 'vue'
import AppSpinner from '@/components/AppSpinner.vue'
import ProviderChip from '@/components/ProviderChip.vue'
import { countryName } from '@/countries'
import { providerDefinition, providerDefinitions, providerExternalArtistUrl, type ProviderId } from '@/providers'
import type { ArtistProviderCandidate, ArtistProviderCandidateAlbum } from '@/types'

type CandidateMap = Partial<Record<ProviderId, ArtistProviderCandidate[]>>

const props = withDefaults(defineProps<{
  modelValue: boolean
  providerId: ProviderId
  candidates: ArtistProviderCandidate[]
  candidatesByProvider?: CandidateMap
  loadingProviderIds?: ProviderId[]
  selectedProviderCandidateIds?: Partial<Record<ProviderId, string | null>>
  multiProvider?: boolean
  artistName?: string | null
  loading: boolean
  saving: boolean
  disabled: boolean
  saveDisabled?: boolean
  showUrl: boolean
  url: string
  urlValidation: string
  emptyText?: string
  assignLabel?: string
}>(), {
  emptyText: 'No candidates found.',
  assignLabel: 'Assign selected',
  artistName: null,
  saveDisabled: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  'update:providerId': [value: ProviderId]
  'update:url': [value: string]
  'select-provider': [value: ProviderId]
  'use-candidate': [candidate: ArtistProviderCandidate]
  'toggle-candidate': [candidate: ArtistProviderCandidate]
  'refresh-all': []
  'assign-selected': []
  'save-url': []
  'open-external': [url: string]
  close: []
}>()

const selectedProvider = computed(() => providerDefinition(props.providerId))
const providerTabs = computed(() =>
  [...providerDefinitions].sort((left, right) => left.label.localeCompare(right.label)),
)
const activeCandidates = computed(() =>
  props.multiProvider
    ? props.candidatesByProvider?.[props.providerId] ?? []
    : props.candidates,
)
const activeProviderLoading = computed(() =>
  props.multiProvider
    ? Boolean(props.loadingProviderIds?.includes(props.providerId))
    : props.loading,
)
const dialogTitle = computed(() => props.artistName ? `Match Provider for ${props.artistName}` : 'Match Provider')

function close() {
  emit('update:modelValue', false)
  emit('close')
}

function selectProvider(providerId: ProviderId) {
  if (props.disabled || props.saving) {
    return
  }
  emit('update:providerId', providerId)
  emit('select-provider', providerId)
}

function candidateInfo(candidate: ArtistProviderCandidate) {
  const status = candidate.active === true ? 'Active' : candidate.active === false ? 'Inactive' : null
  return [candidate.country ? countryName(candidate.country) : null, status, candidate.disambiguation]
    .filter(Boolean)
    .join(' · ')
}

function candidateAlbums(candidate: ArtistProviderCandidate) {
  return candidate.albumEvidence
}

function sortedCandidateAlbums(candidate: ArtistProviderCandidate) {
  return candidateAlbums(candidate)
    .map((album, index) => ({ album, index }))
    .sort((left, right) => {
      const points = albumDisplayScore(right.album) - albumDisplayScore(left.album)
      if (points !== 0) {
        return points
      }
      const evidence = albumEvidencePriority(right.album) - albumEvidencePriority(left.album)
      if (evidence !== 0) {
        return evidence
      }
      const titleScore = right.album.titleScore - left.album.titleScore
      if (titleScore !== 0) {
        return titleScore
      }
      const yearDistance = albumYearDistance(left.album) - albumYearDistance(right.album)
      if (yearDistance !== 0) {
        return yearDistance
      }
      return left.index - right.index
    })
    .map((item) => item.album)
}

function albumDisplayScore(album: ArtistProviderCandidateAlbum) {
  return albumTitleDisplayPoints(album) + albumYearDisplayPoints(album)
}

function albumTitleDisplayPoints(album: ArtistProviderCandidateAlbum) {
  if (albumTitleFullMatch(album)) {
    return 2
  }
  if (album.matchType === 'fuzzy' && album.titleScore > 0) {
    return 1
  }
  return 0
}

function albumYearDisplayPoints(album: ArtistProviderCandidateAlbum) {
  const providerYear = album.providerReleaseYear
  const localYear = album.localReleaseYear
  if (providerYear == null || localYear == null) {
    return 0
  }
  return providerYear === localYear ? 2 : 1
}

function albumEvidencePriority(album: ArtistProviderCandidateAlbum) {
  if (album.localEvidenceKind === 'local') {
    return 3
  }
  if (album.localEvidenceKind === 'checked') {
    return 2
  }
  if (album.localEvidenceKind === 'unchecked') {
    return 1
  }
  return 0
}

function albumYearDistance(album: ArtistProviderCandidateAlbum) {
  const providerYear = album.providerReleaseYear
  const localYear = album.localReleaseYear
  if (providerYear == null || localYear == null) {
    return Number.MAX_SAFE_INTEGER
  }
  return Math.abs(Number(providerYear) - Number(localYear))
}

function candidateEvidenceSummary(candidate: ArtistProviderCandidate) {
  return candidate.evidenceSummary
}

function albumChipLabel(album: ArtistProviderCandidateAlbum) {
  return album.title
}

function albumHasBadge(album: ArtistProviderCandidateAlbum) {
  return albumIsPerfectMatch(album) || albumHasDisplayConflict(album)
}

function badgeClasses(album: ArtistProviderCandidateAlbum) {
  return {
    'provider-candidate-album-chip__badge--local': albumIsPerfectMatch(album),
    'provider-candidate-album-chip__badge--conflict': albumHasDisplayConflict(album),
  }
}

function visibleCandidateAlbums(candidate: ArtistProviderCandidate) {
  return sortedCandidateAlbums(candidate).slice(0, 5)
}

function hiddenCandidateAlbumCount(candidate: ArtistProviderCandidate) {
  return Math.max(0, sortedCandidateAlbums(candidate).length - 5)
}

function albumChipClasses(album: ArtistProviderCandidateAlbum) {
  return [
    'provider-candidate-album-chip',
    {
      'provider-candidate-album-chip--provider-only': album.localEvidenceKind === 'provider-only',
      'provider-candidate-album-chip--local': albumIsPerfectMatch(album),
      'provider-candidate-album-chip--checked': !albumIsPerfectMatch(album) && !albumHasDisplayConflict(album) && album.localEvidenceKind === 'checked',
      'provider-candidate-album-chip--unchecked': !albumIsPerfectMatch(album) && !albumHasDisplayConflict(album) && album.localEvidenceKind === 'unchecked',
      'provider-candidate-album-chip--conflict': albumHasDisplayConflict(album),
    },
  ]
}

function albumTooltip(album: ArtistProviderCandidateAlbum) {
  return `${albumTitleTooltip(album)}\n${albumYearTooltip(album)}`
}

function albumTitleTooltip(album: ArtistProviderCandidateAlbum) {
  if (album.matchType === 'exact') {
    return 'Title: 100% match'
  }
  if (album.matchType === 'normalized') {
    return 'Title: 100% normalized match'
  }
  if (album.localEvidenceKind === 'provider-only') {
    return 'Title: no local match'
  }
  if (album.matchType === 'fuzzy' && album.titleScore > 0) {
    return `Title: conflict (${album.titleScore}% match)`
  }
  return 'Title: conflict'
}

function albumYearTooltip(album: ArtistProviderCandidateAlbum) {
  const providerYear = album.providerReleaseYear
  const localYear = album.localReleaseYear
  if (providerYear != null && localYear != null && providerYear === localYear) {
    return 'Year: 100% match'
  }
  if (providerYear != null && localYear != null) {
    return `Year: conflict (${localYear} vs ${providerYear})`
  }
  if (album.localEvidenceKind === 'provider-only') {
    return providerYear ? `Year: provider ${providerYear}` : 'Year: unknown'
  }
  if (providerYear != null) {
    return `Year: local unknown, provider ${providerYear}`
  }
  if (localYear != null) {
    return `Year: local ${localYear}, provider unknown`
  }
  return 'Year: unknown'
}

function albumHasLocalEvidence(album: ArtistProviderCandidateAlbum) {
  return album.localEvidenceKind !== 'provider-only'
}

function albumTitleFullMatch(album: ArtistProviderCandidateAlbum) {
  return album.matchType === 'exact' || album.matchType === 'normalized'
}

function albumYearFullMatch(album: ArtistProviderCandidateAlbum) {
  const providerYear = album.providerReleaseYear
  const localYear = album.localReleaseYear
  return providerYear != null && localYear != null && providerYear === localYear
}

function albumTitleConflict(album: ArtistProviderCandidateAlbum) {
  return albumHasLocalEvidence(album) && !albumTitleFullMatch(album)
}

function albumYearConflict(album: ArtistProviderCandidateAlbum) {
  const providerYear = album.providerReleaseYear
  const localYear = album.localReleaseYear
  return album.releaseYearConflict || (providerYear != null && localYear != null && providerYear !== localYear)
}

function albumHasDisplayConflict(album: ArtistProviderCandidateAlbum) {
  return albumHasLocalEvidence(album) && (albumTitleConflict(album) || albumYearConflict(album))
}

function albumIsPerfectMatch(album: ArtistProviderCandidateAlbum) {
  return albumHasLocalEvidence(album) && albumTitleFullMatch(album) && albumYearFullMatch(album)
}

function useCandidate(candidate: ArtistProviderCandidate) {
  if (props.disabled || props.saving || activeProviderLoading.value) {
    return
  }
  if (props.multiProvider) {
    emit('toggle-candidate', candidate)
  } else {
    emit('use-candidate', candidate)
  }
}

function providerLoading(providerId: ProviderId) {
  return props.multiProvider
    ? Boolean(props.loadingProviderIds?.includes(providerId))
    : props.loading && providerId === props.providerId
}

function selectedCandidateId(providerId: ProviderId) {
  return props.selectedProviderCandidateIds?.[providerId] ?? null
}

function candidateSelected(candidate: ArtistProviderCandidate) {
  return selectedCandidateId(candidate.providerId as ProviderId) === candidate.providerArtistId
}

function refreshAll() {
  if (props.disabled || props.saving) {
    return
  }
  emit('refresh-all')
}
</script>

<template>
  <v-dialog
    :model-value="modelValue"
    content-class="large-dialog-content"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <v-card class="dialog-card provider-match-dialog">
      <v-card-title class="provider-match-dialog__title">
        <span>{{ dialogTitle }}</span>
      </v-card-title>
      <v-card-text class="edit-form">
        <div class="provider-chip-selector">
          <div class="provider-chip-selector__chips">
            <span
              v-for="provider in providerTabs"
              :key="provider.id"
              class="provider-action-chip-wrap"
            >
              <ProviderChip
                :provider-id="provider.id"
                :label="provider.label"
                action
                :selected="props.providerId === provider.id"
                :candidate-selected="Boolean(selectedCandidateId(provider.id))"
                :disabled="disabled || saving"
                @click="selectProvider(provider.id)"
              >
                <template #prepend>
                  <AppSpinner
                    v-if="providerLoading(provider.id)"
                    class="provider-action-chip__spinner"
                  />
                  <img
                    v-else-if="provider.iconSrc"
                    class="provider-chip__icon"
                    :src="provider.iconSrc"
                    alt=""
                  >
                </template>
              </ProviderChip>
              <span
                v-if="selectedCandidateId(provider.id)"
                class="provider-candidate-album-chip__badge provider-candidate-album-chip__badge--local"
              >
                <v-icon icon="mdi-check-bold" size="10"></v-icon>
              </span>
            </span>
          </div>
          <v-btn
            v-if="multiProvider"
            size="small"
            variant="text"
            prepend-icon="mdi-refresh"
            class="provider-match-refresh-button"
            :disabled="disabled || saving"
            @click="refreshAll"
          >
            Refresh all
          </v-btn>
        </div>

        <v-progress-linear v-if="activeProviderLoading" indeterminate color="primary"></v-progress-linear>
        <div v-if="!activeProviderLoading && activeCandidates.length === 0" class="cell-muted">{{ emptyText }}</div>
        <div v-if="!activeProviderLoading && activeCandidates.length > 0" class="provider-list">
          <div
            v-for="candidate in activeCandidates"
            :key="candidate.providerArtistId"
            class="provider-candidate-row"
            :class="{
              'provider-candidate-row--disabled': disabled || saving,
              'provider-candidate-row--selected': candidateSelected(candidate),
            }"
            @click="useCandidate(candidate)"
          >
            <div class="provider-candidate-row__content">
              <div class="provider-candidate-row__title">
                <span class="cell-strong">{{ candidate.providerArtistName }}</span>
                <v-chip size="x-small" color="primary" variant="tonal" class="ml-2">
                  Confidence {{ candidate.finalScore }}
                </v-chip>
              </div>
              <div class="provider-candidate-row__subtitle">
                {{ candidateInfo(candidate) }}
              </div>
              <div class="provider-candidate-row__evidence">
                {{ candidateEvidenceSummary(candidate) }}
              </div>
              <div class="mono-path">{{ candidate.providerArtistId }}</div>
              <div v-if="candidateAlbums(candidate).length" class="provider-candidate-albums">
                <v-tooltip
                  v-for="album in visibleCandidateAlbums(candidate)"
                  :key="`${album.title}:${album.providerReleaseYear ?? ''}:${album.localAlbumId ?? 'provider'}`"
                  :text="albumTooltip(album)"
                  location="top"
                >
                  <template #activator="{ props }">
                    <span v-bind="props" class="provider-candidate-album-chip-wrap">
                      <v-chip
                        size="small"
                        variant="tonal"
                        :class="albumChipClasses(album)"
                      >
                        {{ albumChipLabel(album) }}
                      </v-chip>
                      <span
                        v-if="albumHasBadge(album)"
                        class="provider-candidate-album-chip__badge"
                        :class="badgeClasses(album)"
                      >
                        <span v-if="albumHasDisplayConflict(album)">?</span>
                        <v-icon v-else icon="mdi-check-bold" size="10"></v-icon>
                      </span>
                    </span>
                  </template>
                </v-tooltip>
                <v-chip
                  v-if="hiddenCandidateAlbumCount(candidate) > 0"
                  size="small"
                  variant="tonal"
                  class="provider-candidate-album-chip provider-candidate-album-chip--more"
                >
                  +{{ hiddenCandidateAlbumCount(candidate) }}
                </v-chip>
              </div>
            </div>
            <div class="provider-candidate-row__actions">
              <v-tooltip text="Open artist page on provider site" location="top">
                <template #activator="{ props }">
                  <v-btn
                    v-bind="props"
                    size="small"
                    variant="text"
                    prepend-icon="mdi-open-in-new"
                    class="provider-candidate-open-button"
                    @click.stop="emit('open-external', providerExternalArtistUrl(candidate.providerId, candidate.providerUrl))"
                  >
                    Open
                  </v-btn>
                </template>
              </v-tooltip>
            </div>
          </div>
        </div>

        <div v-if="showUrl && providerId !== 'musicbrainz'" class="provider-setup-section">
          <v-text-field
            :model-value="url"
            :label="`${selectedProvider.label} URL`"
            prepend-inner-icon="mdi-link-variant"
            autofocus
            :disabled="disabled || saving"
            :error-messages="urlValidation ? [urlValidation] : []"
            hide-details="auto"
            @update:model-value="emit('update:url', String($event ?? ''))"
          ></v-text-field>
        </div>
      </v-card-text>
      <v-card-actions>
        <v-spacer></v-spacer>
        <v-btn variant="text" @click="close">Close</v-btn>
        <v-btn
          v-if="multiProvider"
          color="primary"
          :loading="saving"
          :disabled="disabled || saving || saveDisabled"
          @click="emit('assign-selected')"
        >
          {{ assignLabel }}
        </v-btn>
        <v-btn
          v-if="showUrl && providerId !== 'musicbrainz'"
          color="primary"
          :loading="saving"
          :disabled="disabled || !url.trim() || Boolean(urlValidation)"
          @click="emit('save-url')"
        >
          Save
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
