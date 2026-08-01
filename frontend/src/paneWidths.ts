export function fitFixedPaneWidths(
  preferredWidths: number[],
  minimumWidths: number[],
  flexibleMinimumWidth: number,
  availableWidth: number,
) {
  const preferred = preferredWidths.map((width, index) => Math.max(minimumWidths[index] ?? 0, width))
  const minimums = minimumWidths.map((width) => Math.max(0, width))
  const flexibleMinimum = Math.max(0, flexibleMinimumWidth)
  const available = Math.max(0, availableWidth)
  const totalMinimum = minimums.reduce((sum, width) => sum + width, flexibleMinimum)

  if (available < totalMinimum) {
    const scale = totalMinimum > 0 ? available / totalMinimum : 0
    return minimums.map((width) => width * scale)
  }

  let overflow = preferred.reduce((sum, width) => sum + width, flexibleMinimum) - available
  for (let index = preferred.length - 1; index >= 0 && overflow > 0; index -= 1) {
    const reduction = Math.min(overflow, preferred[index] - minimums[index])
    preferred[index] -= reduction
    overflow -= reduction
  }
  return preferred
}
