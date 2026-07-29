<script setup lang="ts">
import AdaptiveRowActions from '@/components/AdaptiveRowActions.vue'

defineProps<{
  column?: string
}>()

const emit = defineEmits<{
  measured: [value: { labeled: number, icon: number }]
}>()
</script>

<template>
  <div :data-column="column" class="workspace-grid__cell row-action-cell">
    <AdaptiveRowActions @measured="emit('measured', $event)">
      <template #default="{ showLabels }">
        <slot :show-labels="showLabels"></slot>
      </template>
    </AdaptiveRowActions>
  </div>
</template>
