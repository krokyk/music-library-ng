<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps<{
  availableWidth?: number
}>()

const emit = defineEmits<{
  measured: [value: { labeled: number, icon: number }]
}>()

const actions = ref<HTMLElement | null>(null)
const showLabels = ref(false)
let resizeObserver: ResizeObserver | null = null
let mutationObserver: MutationObserver | null = null
let measureFrame: number | null = null

function scheduleMeasure() {
  if (measureFrame !== null) {
    return
  }
  measureFrame = requestAnimationFrame(measure)
}

function measuredWidth(labeled: boolean) {
  if (!actions.value) {
    return 0
  }
  const clone = actions.value.cloneNode(true) as HTMLElement
  clone.querySelectorAll<HTMLElement>('[data-adaptive-control]').forEach((control) => {
    control.dataset.showLabel = String(labeled)
    control.classList.toggle('action-button--labeled', labeled)
    control.classList.toggle('action-button--icon-only', !labeled)
  })
  clone.classList.add('adaptive-row-actions--measuring')
  document.body.append(clone)
  const width = Math.ceil(clone.getBoundingClientRect().width)
  clone.remove()
  return width
}

function availableWidth() {
  if (props.availableWidth !== undefined) {
    return Math.max(0, props.availableWidth)
  }
  const container = actions.value?.parentElement
  if (!container) {
    return 0
  }
  const style = getComputedStyle(container)
  return Math.max(
    0,
    container.clientWidth - Number.parseFloat(style.paddingLeft) - Number.parseFloat(style.paddingRight),
  )
}

function measure() {
  measureFrame = null
  const labeled = measuredWidth(true)
  const icon = measuredWidth(false)
  showLabels.value = labeled > 0 && availableWidth() >= labeled
  emit('measured', { labeled, icon })
}

watch(() => props.availableWidth, scheduleMeasure)

onMounted(() => {
  resizeObserver = new ResizeObserver(scheduleMeasure)
  if (actions.value?.parentElement) {
    resizeObserver.observe(actions.value.parentElement)
  }
  mutationObserver = new MutationObserver(scheduleMeasure)
  if (actions.value) {
    mutationObserver.observe(actions.value, { childList: true, subtree: true })
  }
  void nextTick(scheduleMeasure)
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  mutationObserver?.disconnect()
  if (measureFrame !== null) {
    cancelAnimationFrame(measureFrame)
  }
})
</script>

<template>
  <div ref="actions" class="row-actions adaptive-row-actions">
    <slot :show-labels="showLabels"></slot>
  </div>
</template>
