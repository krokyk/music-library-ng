<script setup lang="ts">
import { computed } from 'vue'
import { countryName } from '@/countries'
import { providerDefinition, providerDefinitions, type ProviderId } from '@/providers'
import type { ArtistProviderCandidate, ArtistProviderCandidateAlbum } from '@/types'

const props = withDefaults(defineProps<{
  modelValue: boolean
  providerId: ProviderId
  candidates: ArtistProviderCandidate[]
  loading: boolean
  saving: boolean
  disabled: boolean
  showUrl: boolean
  url: string
  urlValidation: string
  emptyText?: string
}>(), {
  emptyText: 'No candidates found.',
})

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  'update:providerId': [value: ProviderId]
  'update:url': [value: string]
  'select-provider': [value: ProviderId]
  'use-candidate': [candidate: ArtistProviderCandidate]
  'save-url': []
  'open-external': [url: string]
  close: []
}>()

const selectedProvider = computed(() => providerDefinition(props.providerId))

function close() {
  emit('update:modelValue', false)
  emit('close')
}

function selectProvider(providerId: ProviderId) {
  if (props.disabled || props.saving || props.loading) {
    return
  }
  emit('update:providerId', providerId)
  emit('select-provider', providerId)
}

function candidateInfo(candidate: ArtistProviderCandidate) {
  const status = candidate.active === true ? 'Active' : candidate.active === false ? 'Split-up' : null
  return [candidate.country ? countryName(candidate.country) : null, status, candidate.disambiguation]
    .filter(Boolean)
    .join(' · ')
}

function candidateAlbums(candidate: ArtistProviderCandidate) {
  return candidate.albums.length > 0
    ? candidate.albums
    : candidate.releaseGroups.map((group) => ({
        title: group.title,
        providerReleaseDate: group.releaseDate,
        providerUrl: group.providerUrl,
        localAlbumId: null,
        localReleaseDate: null,
        localOnDisk: false,
        releaseDateConflict: false,
      }))
}

function visibleCandidateAlbums(candidate: ArtistProviderCandidate) {
  return candidateAlbums(candidate).slice(0, 5)
}

function hiddenCandidateAlbumCount(candidate: ArtistProviderCandidate) {
  return Math.max(0, candidateAlbums(candidate).length - 5)
}

function albumChipClasses(album: ArtistProviderCandidateAlbum) {
  return [
    'provider-candidate-album-chip',
    {
      'provider-candidate-album-chip--provider-only': !album.localOnDisk,
      'provider-candidate-album-chip--local': album.localOnDisk && !album.releaseDateConflict,
      'provider-candidate-album-chip--conflict': album.localOnDisk && album.releaseDateConflict,
    },
  ]
}

function albumTooltip(album: ArtistProviderCandidateAlbum) {
  const providerYear = releaseYear(album.providerReleaseDate)
  const localYear = releaseYear(album.localReleaseDate)
  const detail = album.releaseDateConflict
    ? `Local year ${localYear ?? 'unknown'}, provider year ${providerYear ?? 'unknown'}`
    : album.localOnDisk ? 'Found locally'
      : 'Provider album not found on disk'
  return `${album.title}${providerYear ? ` (${providerYear})` : ''} - ${detail}`
}

function releaseYear(releaseDate: string | null | undefined) {
  return releaseDate && /^\d{4}/.test(releaseDate) ? releaseDate.slice(0, 4) : null
}

function providerChipClasses(providerId: ProviderId) {
  return [
    'artists-provider-chip',
    'provider-action-chip',
    providerDefinition(providerId).chipClass,
    { 'provider-action-chip--selected': props.providerId === providerId },
  ]
}

function useCandidate(candidate: ArtistProviderCandidate) {
  if (props.disabled || props.saving || props.loading) {
    return
  }
  emit('use-candidate', candidate)
}
</script>

<template>
  <v-dialog :model-value="modelValue" max-width="900" @update:model-value="emit('update:modelValue', $event)">
    <v-card class="dialog-card provider-match-dialog">
      <v-card-title>Match Provider</v-card-title>
      <v-card-text class="edit-form">
        <div class="provider-chip-selector">
          <v-chip
            v-for="provider in providerDefinitions"
            :key="provider.id"
            size="small"
            variant="flat"
            :class="providerChipClasses(provider.id)"
            :disabled="disabled || saving || loading"
            @click="selectProvider(provider.id)"
          >
            <v-progress-circular
              v-if="loading && provider.id === providerId"
              indeterminate
              size="14"
              width="2"
              class="provider-action-chip__spinner"
            ></v-progress-circular>
            <img
              v-else-if="provider.iconSrc"
              class="artists-provider-chip__icon"
              :src="provider.iconSrc"
              alt=""
              aria-hidden="true"
            >
            <span class="artists-provider-chip__text">{{ provider.label }}</span>
          </v-chip>
        </div>

        <v-progress-linear v-if="loading" indeterminate color="primary"></v-progress-linear>
        <div v-if="!loading && candidates.length === 0" class="cell-muted">{{ emptyText }}</div>
        <div v-if="!loading && candidates.length > 0" class="provider-list">
          <div
            v-for="candidate in candidates"
            :key="candidate.providerArtistId"
            class="provider-candidate-row"
            :class="{ 'provider-candidate-row--disabled': disabled || saving }"
            @click="useCandidate(candidate)"
          >
            <div class="provider-candidate-row__content">
              <div class="provider-candidate-row__title">
                <span class="cell-strong">{{ candidate.providerArtistName }}</span>
                <v-chip size="x-small" color="primary" variant="tonal" class="ml-2">{{ candidate.matchScore }}</v-chip>
              </div>
              <div class="provider-candidate-row__subtitle">
                {{ candidateInfo(candidate) }}
              </div>
              <div class="mono-path">{{ candidate.providerArtistId }}</div>
              <div v-if="candidateAlbums(candidate).length" class="provider-candidate-albums">
                <v-tooltip
                  v-for="album in visibleCandidateAlbums(candidate)"
                  :key="`${album.title}:${album.providerReleaseDate ?? ''}:${album.localAlbumId ?? 'provider'}`"
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
                        {{ album.title }}
                      </v-chip>
                      <span
                        v-if="album.localOnDisk"
                        class="provider-candidate-album-chip__badge"
                        :class="{
                          'provider-candidate-album-chip__badge--local': !album.releaseDateConflict,
                          'provider-candidate-album-chip__badge--conflict': album.releaseDateConflict,
                        }"
                      >
                        <span v-if="album.releaseDateConflict">?</span>
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
                    @click.stop="emit('open-external', candidate.providerUrl)"
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
