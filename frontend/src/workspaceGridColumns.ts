import type { CSSProperties } from 'vue'

interface WorkspaceGridColumnsOptions<Key extends string> {
  columnKeys: () => readonly Key[]
  widths: Partial<Record<Key, number>>
  minimumWidth: (key: Key) => number
  gridElement: () => HTMLElement | null
  saveWidth: (key: Key, width: number) => void
  suppressHeaderClick: (event?: Event) => void
}

export function useWorkspaceGridColumns<Key extends string>(options: WorkspaceGridColumnsOptions<Key>) {
  function renderedWidth(key: Key) {
    return Math.max(options.minimumWidth(key), options.widths[key] ?? options.minimumWidth(key))
  }

  function fixedWidth() {
    return options.columnKeys()
      .slice(0, -1)
      .reduce((sum, key) => sum + renderedWidth(key), 0)
  }

  function minimumGridWidth() {
    const keys = options.columnKeys()
    return fixedWidth() + options.minimumWidth(keys[keys.length - 1])
  }

  function gridStyle() {
    const keys = options.columnKeys()
    return {
      '--workspace-grid-columns': keys
        .map((key, index) => (
          index === keys.length - 1
            ? `minmax(${options.minimumWidth(key)}px, 1fr)`
            : `${renderedWidth(key)}px`
        ))
        .join(' '),
      '--workspace-grid-min-width': `${minimumGridWidth()}px`,
    } satisfies CSSProperties
  }

  function availableFlexibleWidth() {
    const grid = options.gridElement()
    if (!grid) {
      return options.minimumWidth(options.columnKeys().at(-1)!)
    }
    return Math.max(
      options.minimumWidth(options.columnKeys().at(-1)!),
      grid.clientWidth - fixedWidth(),
    )
  }

  function startResize(key: Key, event: PointerEvent) {
    event.preventDefault()
    event.stopPropagation()
    options.suppressHeaderClick(event)
    const keys = options.columnKeys()
    const leftIndex = keys.indexOf(key)
    if (leftIndex < 0 || leftIndex >= keys.length - 1) {
      return
    }

    const rendered = Object.fromEntries(keys.map((column) => [column, renderedWidth(column)])) as Record<Key, number>
    const leftStart = rendered[key]
    const startX = event.clientX
    const beforeWidth = keys.slice(0, leftIndex).reduce((sum, column) => sum + rendered[column], 0)
    const rightDataWidth = keys.slice(leftIndex + 1, -1).reduce((sum, column) => sum + rendered[column], 0)
    const leftMinimum = options.minimumWidth(key)
    const rightmostMinimum = options.minimumWidth(keys[keys.length - 1])
    const available = options.gridElement()?.clientWidth ?? 0
    const leftMaximum = available > 0
      ? Math.max(leftMinimum, available - beforeWidth - rightDataWidth - rightmostMinimum)
      : Number.POSITIVE_INFINITY

    function move(pointerEvent: PointerEvent) {
      options.widths[key] = Math.min(
        Math.max(leftMinimum, Math.round(leftStart + pointerEvent.clientX - startX)),
        leftMaximum,
      )
    }

    function stop() {
      window.removeEventListener('pointermove', move)
      window.removeEventListener('pointerup', stop)
      window.removeEventListener('pointercancel', stop)
      document.body.classList.remove('is-column-resizing')
      options.suppressHeaderClick()
      options.saveWidth(key, options.widths[key] ?? leftMinimum)
    }

    document.body.classList.add('is-column-resizing')
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', stop)
    window.addEventListener('pointercancel', stop)
  }

  return {
    availableFlexibleWidth,
    gridStyle,
    minimumGridWidth,
    renderedWidth,
    startResize,
  }
}
