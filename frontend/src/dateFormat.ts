export function formatDateWithJavaPattern(date: Date, pattern: string, fallback = 'yyyy-MM-dd') {
  const safePattern = pattern || fallback
  let formatted = ''
  let quoteOpen = false

  for (let index = 0; index < safePattern.length;) {
    const char = safePattern[index]
    if (char === "'") {
      if (safePattern[index + 1] === "'") {
        formatted += "'"
        index += 2
      } else {
        quoteOpen = !quoteOpen
        index++
      }
      continue
    }
    if (quoteOpen) {
      formatted += char
      index++
      continue
    }

    let end = index + 1
    while (end < safePattern.length && safePattern[end] === char) {
      end++
    }
    formatted += formatDateToken(date, char, end - index)
    index = end
  }

  return formatted
}

function formatDateToken(date: Date, char: string, length: number) {
  switch (char) {
    case 'y':
    case 'Y':
      return length === 2 ? pad(date.getFullYear() % 100, 2) : pad(date.getFullYear(), length)
    case 'M':
      return pad(date.getMonth() + 1, length)
    case 'd':
      return pad(date.getDate(), length)
    case 'H':
      return pad(date.getHours(), length)
    case 'm':
      return pad(date.getMinutes(), length)
    case 's':
      return pad(date.getSeconds(), length)
    case 'S':
      return pad(date.getMilliseconds(), 3).slice(0, Math.max(1, Math.min(length, 3)))
    default:
      return char.repeat(length)
  }
}

function pad(value: number, length: number) {
  return String(value).padStart(length, '0')
}
