export type ProviderId = 'musicbrainz' | 'spirit_of_metal' | 'metal_archives'

export interface ProviderDefinition {
  id: ProviderId
  label: string
  actionIcon: string
  iconSrc: string
  chipClass: string
}

export const providerDefinitions: ProviderDefinition[] = [
  {
    id: 'musicbrainz',
    label: 'MusicBrainz',
    actionIcon: 'mdi-music-circle',
    iconSrc: '/provider-icons/musicbrainz.svg',
    chipClass: 'artists-provider-chip--musicbrainz',
  },
  {
    id: 'spirit_of_metal',
    label: 'Spirit of Metal',
    actionIcon: 'mdi-fire',
    iconSrc: '/provider-icons/spirit-of-metal.png',
    chipClass: 'artists-provider-chip--spirit-of-metal',
  },
  {
    id: 'metal_archives',
    label: 'Metal Archives',
    actionIcon: 'mdi-archive',
    iconSrc: '/provider-icons/metal-archives.ico',
    chipClass: 'artists-provider-chip--metal-archives',
  },
]

export function providerDefinition(providerId?: string | null) {
  return providerDefinitions.find((provider) => provider.id === providerId)
    ?? {
      id: 'musicbrainz' as ProviderId,
      label: providerId ?? 'Provider',
      actionIcon: 'mdi-link-variant',
      iconSrc: '',
      chipClass: 'artists-provider-chip--generic',
    }
}

export function validateProviderUrl(providerId: ProviderId | null, value: string) {
  const url = value.trim()
  if (!providerId || providerId === 'musicbrainz') {
    return ''
  }
  if (!url) {
    return 'URL is required.'
  }
  let parsed: URL
  try {
    parsed = new URL(url)
  } catch (error) {
    return 'Enter a valid URL.'
  }
  if (parsed.protocol !== 'https:') {
    return 'Use an https URL.'
  }
  if (providerId === 'spirit_of_metal') {
    if (
      parsed.hostname !== 'www.spirit-of-metal.com'
      || !/^\/en\/band\/[^/]+\/?$/.test(parsed.pathname)
      || parsed.search
      || parsed.hash
    ) {
      return 'Use https://www.spirit-of-metal.com/en/band/<name>.'
    }
    return ''
  }
  if (
    parsed.hostname !== 'www.metal-archives.com'
    && parsed.hostname !== 'metal-archives.com'
  ) {
    return 'Use a metal-archives.com band URL.'
  }
  if (parsed.search || parsed.hash) {
    return 'Remove query or fragment text from the URL.'
  }
  if (
    /^\/bands\/[^/]+\/\d+\/?$/.test(parsed.pathname)
    || /^\/band\/discography\/id\/\d+(?:\/tab\/main)?\/?$/.test(parsed.pathname)
  ) {
    return ''
  }
  return 'Use a Metal Archives band page URL.'
}
