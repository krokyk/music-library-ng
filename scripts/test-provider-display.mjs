import assert from 'node:assert/strict'
import { displayedArtistProviders } from '../frontend/src/providers.ts'

const oldPage = { id: 144, artistId: 311, providerId: 'spirit_of_metal' }
const currentPage = { id: 145, artistId: 312, providerId: 'spirit_of_metal' }
const sharedPage = { id: 281, artistId: 312, providerId: 'metal_archives' }
const links = [currentPage, sharedPage, oldPage]
const oldName = { id: 311, canonicalArtistId: 312, providerLinks: links }
const currentName = { ...oldName, id: 312 }
const selectedPage = (artist, credits) => displayedArtistProviders(artist, credits)
  .find((link) => link.providerId === 'spirit_of_metal')

assert.equal(selectedPage(oldName).id, oldPage.id)
assert.equal(selectedPage(currentName).id, currentPage.id)
assert.equal(displayedArtistProviders(oldName).length, 2)
assert.equal(selectedPage(oldName, [312]).id, currentPage.id)
assert.equal(selectedPage(currentName, [311]).id, oldPage.id)
assert.equal(selectedPage({ ...oldName, id: 999 }).id, currentPage.id)
assert.deepEqual(displayedArtistProviders({ ...oldName, providerLinks: [] }), [])
assert.deepEqual(oldName.providerLinks, [currentPage, sharedPage, oldPage])
console.log('PASS: one provider per context, owned and canonical fallbacks, album credits, and raw evidence preserved.')
