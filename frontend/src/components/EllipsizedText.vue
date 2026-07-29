<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, useAttrs, watch } from 'vue'

defineOptions({ inheritAttrs: false })

const props = defineProps<{
  text: string
}>()

const element = ref<HTMLElement | null>(null)
const truncated = ref(false)
const attrs = useAttrs()
let observer: ResizeObserver | null = null

function update() {
  truncated.value = Boolean(element.value && element.value.scrollWidth > element.value.clientWidth)
}

function activatorAttributes(activatorProps: Record<string, unknown>) {
  const { ref: _activatorRef, ...tooltipAttrs } = activatorProps
  return { ...tooltipAttrs, ...attrs }
}

watch(() => props.text, () => void nextTick(update))

onMounted(() => {
  observer = new ResizeObserver(update)
  if (element.value) {
    observer.observe(element.value)
  }
  void nextTick(update)
})

onBeforeUnmount(() => observer?.disconnect())
</script>

<template>
  <v-tooltip :text="text" location="top" :disabled="!truncated">
    <template #activator="{ props }">
      <span
        ref="element"
        v-bind="activatorAttributes(props)"
        :data-ellipsized="truncated"
      >{{ text }}</span>
    </template>
  </v-tooltip>
</template>
