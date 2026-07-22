# Evolution 12: Canonical Album Homes And Provider Reconciliation

Historical note: This evolution document is archival context, not current source of truth.
Use `docs/current-application.md` and the current code when behavior conflicts.

## Scope

This document records the decisions that replaced multi-collection album membership, full release-date precision, independent artist collection membership, and the separate Library screen.
These decisions are related because they make one album the canonical unit for physical storage, collection organization, provider reconciliation, and artist presence.
Future proposals that restore an earlier model should identify the reversal and reconsider the reasons below before implementation.

This evolution supersedes the full and partial release-date model in Evolution 02.
It supersedes the multi-collection album membership and separate local-path model in Evolution 04.
It supersedes independent or scan-state artist collection membership described in Evolution 10 and Evolution 11.

## One Canonical Album Home

Every album has exactly one required home collection.
An album may have at most one physical folder across the complete music root.
The album row stores both its home collection and its optional collection-relative local path, so separate album-membership and local-path tables are unnecessary.
Physical discovery is stronger evidence than a non-local assignment and may rehome a matching provider-only album to the collection where its folder is found.
A second physical folder for an already-local album is duplicate storage and must be reported instead of becoming another membership.
Non-local albums can be reassigned manually only between collections of the same type.
Local albums cannot be reassigned because their physical location determines their home.
An album cannot deliberately be made collectionless, including during manual creation or reassignment.

The previous multi-collection model reflected derivative uses such as `METAL BALLADS` and part of `EPIC` that copied tracks already stored in genre collections.
Those copies were intended to be identical, so they consumed storage, required repeated tagging and repair, and could drift when only one copy changed.
Keeping one canonical file removes those failure modes and lets the same future track participate in any number of playlists without another physical copy.
Filesystem visibility of derivative membership was accepted as unnecessary because playlist files and future playlist management can expose that information directly.
Hard links and symbolic links were rejected because the music root is synchronized through Google Drive.

## Artist Collection Presence

An artist belongs to a collection when at least one linked album has that collection as its home.
Artist collection presence, collection filtering, and collection-scoped unchecked counts are derived from album homes rather than stored as an independent relationship.
Moving the artist's final album out of a collection removes the artist from that collection automatically.
Artists without albums are not a supported steady library state, and an albumless artist has no collection presence.
Every album creation path must therefore assign the new album to a home collection.
This avoids two membership sources that can disagree and removes the need to synchronize artist membership after album changes.

## Release Year Precision

Release metadata is stored as a nullable integer year from `1000` through `9999`.
Folder parsing, display, sorting, filtering, provider comparison, and conflict resolution use year precision only.
Month and day precision did not affect the application's collection decisions and made parsing, storage, comparison, and conflict handling more complicated.
Folder names always contain only the year.
When conflict resolution writes audio tags, an existing value that begins with the selected four-digit year is preserved so manually maintained values such as `YYYY-MM-DD` are not needlessly reduced.
An absent or mismatching audio year is written as exactly `YYYY`.

## Provider Discography Intake

Provider intake reconciles one complete artist discography for one provider in one database transaction.
An artist with dozens of albums is small enough for this local single-user application, so splitting the write into album-sized microtransactions would add recovery states without useful concurrency.
The provider adapter first fetches and filters the eligible remote releases, then the transaction updates provider metadata, album rows, provider links, collection homes, duplicate merges, and the successful-check timestamp together.
A failure rolls back that provider's complete artist reconciliation while leaving completed providers and artists intact.
Provider scans reuse exact, normalized, and sufficiently strong fuzzy matches instead of creating duplicate album rows.
Different digit sequences are a strong mismatch, so releases such as `Origins` and `Origins II`, `Vol. 1` and `Vol. 2`, or `Part 1` and `Part 2` remain distinct albums.
Provider scans do not import compilations or other unsupported release types merely to fill discography gaps.

## Immediate Conflict Resolution

Each conflict tile is the action itself and applies its value immediately without a separate Apply button.
The user resolves one concrete artist field or album field at a time, so a staged multi-conflict submission would add interaction and state without improving safety.
A year conflict changes only the release year, while a title conflict changes only the title.
An album conflict action uses one rollback-capable transaction for the database changes and its one optional folder rename.
The database changes run first, the folder is renamed before commit, and any transactional failure restores the original folder and rolls back the database.
Audio-tag writes run after commit because tag failures should be visible warnings rather than undoing a valid database and folder decision.
Opening the resolver removes stale local paths once before conflicts are loaded, while hover behavior remains read-only.
Every click is written to application logs and the status history with the conflict kind, chosen source, old value, resulting value, and folder paths where applicable.

## Collections And Artists As The Working Surfaces

The separate Library page was removed because it duplicated partially refined browsing and editing behavior while Collections and Artists already cover the active workflows.
Collections owns collection scanning, provider scanning in collection context, checked state, and non-local album rehoming.
Artists owns artist metadata, provider setup and matching, complete known discographies, and conflict resolution.
Collection album metadata remains read-only so the two screens do not provide competing edit paths.
Reintroducing a Library page should be treated as a new milestone with a concrete workflow that Collections and Artists cannot express, not as automatic restoration of the removed page.

## Reversal Guidance

A future need for one album in multiple genres should first be tested against track-level playlist membership because copying or multiplying album homes restores the original duplication problem.
A future need for full release dates should identify a concrete application behavior that uses month or day precision rather than preserving precision only because a provider supplies it.
A future need for independent artist collection membership should explain how it cannot be derived from required album homes without creating collectionless albums or conflicting membership sources.
A future Library page should have an owned workflow and data boundary rather than mirror the Collections or Artists screens.
These decisions can be changed, but the change should explicitly acknowledge which earlier problem has become acceptable or is solved differently.
