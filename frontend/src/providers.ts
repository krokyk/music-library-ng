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
    chipClass: 'provider-chip--musicbrainz',
  },
  {
    id: 'spirit_of_metal',
    label: 'Spirit of Metal',
    actionIcon: 'mdi-fire',
    iconSrc: '/provider-icons/spirit-of-metal.png',
    chipClass: 'provider-chip--spirit-of-metal',
  },
  {
    id: 'metal_archives',
    label: 'Metal Archives',
    actionIcon: 'mdi-archive',
    iconSrc: '/provider-icons/metal-archives.ico',
    chipClass: 'provider-chip--metal-archives',
  },
]

export function providerDefinition(providerId?: string | null) {
  return providerDefinitions.find((provider) => provider.id === providerId)
    ?? {
      id: 'musicbrainz' as ProviderId,
      label: providerId ?? 'Provider',
      actionIcon: 'mdi-link-variant',
      iconSrc: '',
      chipClass: 'provider-chip--generic',
    }
}

export function providerExternalArtistUrl(providerId?: string | null, providerUrl?: string | null) {
  if (!providerUrl) {
    return ''
  }
  if (providerId === 'metal_archives') {
    return metalArchivesExternalArtistUrl(providerUrl)
  }
  return providerUrl
}

function metalArchivesExternalArtistUrl(providerUrl: string) {
  let parsed: URL
  try {
    parsed = new URL(providerUrl)
  } catch (error) {
    return providerUrl
  }
  const host = parsed.hostname.toLowerCase()
  if (host !== 'www.metal-archives.com' && host !== 'metal-archives.com') {
    return providerUrl
  }
  const bandMatch = parsed.pathname.match(/^\/bands\/([^/]+)\/(\d+)\/?$/)
  if (bandMatch) {
    return `https://www.metal-archives.com/bands/${bandMatch[1]}/${bandMatch[2]}#band_tab_discography`
  }
  const discographyMatch = parsed.pathname.match(/^\/band\/discography\/id\/(\d+)(?:\/tab\/main)?\/?$/)
  if (discographyMatch) {
    return `https://www.metal-archives.com/bands/_/${discographyMatch[1]}#band_tab_discography`
  }
  return providerUrl
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
