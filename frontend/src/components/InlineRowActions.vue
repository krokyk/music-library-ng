<script setup lang="ts">
import AdaptiveRowActions from '@/components/AdaptiveRowActions.vue'

defineOptions({ inheritAttrs: false })

defineProps<{
  availableWidth: number
}>()

const emit = defineEmits<{
  measured: [value: { labeled: number, icon: number }]
}>()
</script>

<template>
  <AdaptiveRowActions
    v-bind="$attrs"
    :available-width="availableWidth"
    @measured="emit('measured', $event)"
  >
    <template #default="{ showLabels }">
      <slot :show-labels="showLabels"></slot>
    </template>
  </AdaptiveRowActions>
</template>
