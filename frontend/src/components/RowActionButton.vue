<script setup lang="ts">
withDefaults(defineProps<{
  color?: string
  disabled?: boolean
  icon: string
  label: string
  loading?: boolean
  showLabel?: boolean
}>(), {
  color: 'primary',
  disabled: false,
  loading: false,
  showLabel: false,
})
</script>

<template>
  <v-btn
    :prepend-icon="icon"
    size="x-small"
    variant="text"
    :color="color"
    :disabled="disabled"
    :loading="loading"
    data-adaptive-control
    :data-show-label="showLabel"
    class="workspace-row-action"
    :class="showLabel ? 'action-button--labeled' : 'action-button--icon-only'"
  >
    <span class="adaptive-control-label">{{ label }}</span>
  </v-btn>
</template>
