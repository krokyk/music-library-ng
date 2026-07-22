# Music Library

Music Library describes one user's local music collection and the external provider evidence used to maintain it.

## Language

**Collection**:
A typed music-root partition that serves as the required home classification for albums; local albums are physically stored within their home collection.
Its type is inferred once when its folder is added, can be corrected while it contains no albums, and is fixed after its first album.
Artist collections can mix flat and nested album-folder layouts because layout is detected per folder rather than stored on the collection.
_Avoid_: Playlist, derivative collection

**Playlist**:
An app-owned selection of tracks from any collections, independent of where the tracks are physically stored.
_Avoid_: Collection, duplicate collection

**Artist**:
A library artist linked to at least one album.
_Avoid_: Albumless artist

**Title album**:
A title-centric album that may have zero or more contributor artists because the folder format can legitimately omit contributor metadata.
_Avoid_: Synthetic unknown artist

**Provider-eligible artist**:
An artist whose albums belong to artist-centric collections; artists whose albums belong to title-centric collections do not participate in provider workflows.
_Avoid_: Provider-enabled composer

**Artist collection presence**:
An artist appears in every collection that is home to at least one of their albums; an artist has no independent collection membership.
_Avoid_: Artist collection membership

**Album home collection**:
The album's single required collection; disk evidence determines it for a local album, while a non-local album may be reassigned only to another collection of the same type and never left without a home.
_Avoid_: Album collections

**Local album**:
An album represented by exactly one physical folder in the music root; additional folders for the same album are duplicate-storage errors.
_Avoid_: Album copies

**Provider discography reconciliation**:
The process of applying one provider's eligible discography evidence to one artist's library state, including album identity matches and metadata conflicts.
_Avoid_: Provider album intake
