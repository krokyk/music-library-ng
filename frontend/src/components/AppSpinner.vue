<script setup lang="ts">
const props = withDefaults(defineProps<{
  variant?: 'pane' | 'inline' | 'control'
}>(), {
  variant: 'inline',
})

const metrics = {
  pane: { size: 60, width: 5 },
  inline: { size: 16, width: 2 },
  control: { size: 22, width: 2 },
} as const
</script>

<template>
  <v-progress-circular
    indeterminate
    color="primary"
    :size="metrics[props.variant].size"
    :width="metrics[props.variant].width"
  ></v-progress-circular>
</template>
