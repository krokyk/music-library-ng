<script setup lang="ts">
import { computed } from 'vue'
import { providerDefinition, providerExternalArtistUrl } from '@/providers'

const props = withDefaults(defineProps<{
  providerId?: string | null
  label?: string | null
  iconSrc?: string | null
  showLabel?: boolean
  count?: string | number | null
  externalUrl?: string | null
  openExternal?: boolean
  action?: boolean
  selected?: boolean
  compact?: boolean
  candidateSelected?: boolean
  compactRow?: boolean
  error?: boolean
  disabled?: boolean
  size?: string
  variant?: 'flat' | 'text' | 'elevated' | 'outlined' | 'plain' | 'tonal'
}>(), {
  showLabel: true,
  openExternal: false,
  action: false,
  selected: false,
  compact: false,
  candidateSelected: false,
  compactRow: false,
  error: false,
  disabled: false,
  size: 'small',
  variant: 'flat',
})

const emit = defineEmits<{
  click: [event: MouseEvent | KeyboardEvent]
  openExternal: [url: string]
}>()

const definition = computed(() => providerDefinition(props.providerId))
const chipLabel = computed(() => props.label ?? definition.value.label)
const chipIconSrc = computed(() => props.iconSrc ?? definition.value.iconSrc)
const externalArtistUrl = computed(() => providerExternalArtistUrl(props.providerId, props.externalUrl))
const chipClasses = computed(() => [
  'provider-chip',
  definition.value.chipClass,
  {
    'provider-action-chip': props.action,
    'provider-action-chip--selected': props.selected,
    'provider-action-chip--compact': props.compact,
    'provider-action-chip--candidate-selected': props.candidateSelected,
    'provider-chip--compact-row': props.compactRow,
    'provider-chip--error': props.error,
    'provider-chip--external': props.openExternal && Boolean(externalArtistUrl.value),
  },
])

function handleClick(event: MouseEvent | KeyboardEvent) {
  if (props.disabled) {
    return
  }
  emit('click', event)
  if (props.openExternal && externalArtistUrl.value) {
    window.open(externalArtistUrl.value, '_blank', 'noopener')
    emit('openExternal', externalArtistUrl.value)
  }
}
</script>

<template>
  <v-chip
    :size="size"
    :variant="variant"
    :class="chipClasses"
    :aria-label="showLabel ? undefined : chipLabel"
    data-adaptive-control
    :data-show-label="showLabel"
    :disabled="disabled"
    @click="handleClick"
  >
    <slot name="prepend">
      <img
        v-if="chipIconSrc"
        class="provider-chip__icon"
        :src="chipIconSrc"
        alt=""
        aria-hidden="true"
      >
    </slot>
    <span class="provider-chip__text adaptive-control-label">{{ chipLabel }}</span>
    <span v-if="count !== null && count !== undefined" class="provider-chip__count">{{ count }}</span>
    <v-icon
      v-if="openExternal && externalArtistUrl"
      class="provider-chip__open-icon"
      icon="mdi-open-in-new"
      size="13"
    ></v-icon>
    <slot></slot>
  </v-chip>
</template>
