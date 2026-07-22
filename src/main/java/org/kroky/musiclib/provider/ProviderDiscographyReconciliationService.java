package org.kroky.musiclib.provider;

import java.util.ArrayList;
import java.util.List;

import org.kroky.musiclib.model.Album;
import org.kroky.musiclib.model.ArtistProviderCandidateAlbum;
import org.kroky.musiclib.model.ArtistProviderLink;
import org.kroky.musiclib.model.RemoteReleaseGroup;
import org.kroky.musiclib.repository.AlbumProviderLinkRepository;
import org.kroky.musiclib.repository.AlbumRepository;
import org.kroky.musiclib.repository.ArtistProviderLinkRepository;
import org.kroky.musiclib.repository.MusicCollectionRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class ProviderDiscographyReconciliationService {

    @Inject
    ArtistProviderLinkRepository providerLinks;

    @Inject
    AlbumRepository albums;

    @Inject
    AlbumProviderLinkRepository albumProviderLinks;

    @Inject
    MusicCollectionRepository collections;

    @Transactional(value = Transactional.TxType.REQUIRES_NEW, rollbackOn = Exception.class)
    public Result reconcile(ArtistProviderLink link, String country, Boolean active,
            List<RemoteReleaseGroup> releases, String collectionId) {
        List<RemoteReleaseGroup> eligibleReleases = releases == null ? List.of() : List.copyOf(releases);
        providerLinks.updateProviderMetadata(link.id(), country, active);

        String albumCollectionId = collectionId == null
                ? albums.majorArtistCollection(link.artistId())
                : collectionId;
        if (albumCollectionId == null) {
            throw new IllegalArgumentException("Artist has no artist-centric album collection.");
        }
        if (collections.find(albumCollectionId).orElseThrow(
                () -> new IllegalArgumentException("Unknown collection: " + albumCollectionId)).type()
                != org.kroky.musiclib.model.CollectionType.ARTIST) {
            throw new IllegalArgumentException("Provider albums require an artist-centric collection.");
        }
        List<AlbumOutcome> outcomes = new ArrayList<>();
        List<Album> artistAlbums = albums.list(link.artistId(), null, null, null, null);
        for (RemoteReleaseGroup release : eligibleReleases) {
            requireEligibleRelease(link, release);
            outcomes.add(reconcileRelease(link, release, albumCollectionId, artistAlbums));
            // ponytail: reload after merges so later releases cannot match an album deleted in this transaction;
            // replace with targeted cache updates only if provider discographies become large enough to measure.
            artistAlbums = albums.list(link.artistId(), null, null, null, null);
        }

        providerLinks.markSuccess(link.id());
        return new Result(List.copyOf(outcomes));
    }

    private AlbumOutcome reconcileRelease(ArtistProviderLink link, RemoteReleaseGroup release, String collectionId,
            List<Album> artistAlbums) {
        ArtistProviderCandidateAlbum evidence = ProviderCandidateEvidenceEvaluator.albumEvidence(artistAlbums, release);
        Album evidenceAlbum = evidence.localAlbumId() == null
                ? null
                : artistAlbums.stream()
                        .filter(album -> evidence.localAlbumId().equals(album.id()))
                        .findFirst()
                        .orElse(null);
        var linkedAlbumId = albumProviderLinks.findAlbumId(release.providerId(), release.providerReleaseGroupId());

        if (linkedAlbumId.isEmpty()
                && !ProviderCandidateEvidenceEvaluator.canAutoLinkProviderImportAlbum(evidence, evidenceAlbum)) {
            Album created = albums.create(link.artistId(), release.title(), release.releaseYear(), false, null,
                    collectionId);
            linkAlbum(created.id(), release);
            return new AlbumOutcome(release, albums.find(created.id()).orElseThrow(), true, false, false, false);
        }

        Album linkedAlbum = linkedAlbumId.isEmpty()
                ? null
                : albums.find(linkedAlbumId.get()).orElseThrow();
        Album target = shouldRelinkProviderOnlyAlbum(linkedAlbum, evidenceAlbum, evidence)
                ? evidenceAlbum
                : linkedAlbum != null ? linkedAlbum : evidenceAlbum;
        if (target == null) {
            throw new IllegalStateException("Matched local album is no longer available: " + release.title());
        }

        albums.mergeProviderOnlyDuplicates(target.id(), link.artistId(), release.title(), release.releaseYear());
        linkAlbum(target.id(), release);
        boolean releaseYearFilled = target.releaseYear() == null && release.releaseYear() != null;
        albums.updateReleaseYearIfMissing(target.id(), release.releaseYear());

        Album canonical = albums.find(target.id()).orElseThrow();
        return new AlbumOutcome(
                release,
                canonical,
                false,
                releaseYearFilled,
                releaseYearConflict(canonical.releaseYear(), release.releaseYear()),
                ProviderTitles.titleConflict(canonical.title(), release.title()));
    }

    private void linkAlbum(long albumId, RemoteReleaseGroup release) {
        albumProviderLinks.linkAlbum(
                albumId,
                release.providerId(),
                release.providerReleaseGroupId(),
                release.title(),
                release.releaseYear(),
                release.providerUrl());
    }

    private static boolean shouldRelinkProviderOnlyAlbum(Album linkedAlbum, Album evidenceAlbum,
            ArtistProviderCandidateAlbum evidence) {
        return linkedAlbum != null
                && evidenceAlbum != null
                && linkedAlbum.id() != evidenceAlbum.id()
                && linkedAlbum.localRelativePath() == null
                && ProviderCandidateEvidenceEvaluator.canAutoLinkProviderImportAlbum(evidence, evidenceAlbum);
    }

    private static void requireEligibleRelease(ArtistProviderLink link, RemoteReleaseGroup release) {
        if (release == null
                || !link.providerId().equals(release.providerId())
                || isBlank(release.providerReleaseGroupId())
                || isBlank(release.title())) {
            throw new IllegalArgumentException("Invalid eligible release for " + link.providerId());
        }
    }

    private static boolean releaseYearConflict(Integer localYear, Integer providerYear) {
        return localYear != null && providerYear != null && !localYear.equals(providerYear);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record Result(List<AlbumOutcome> outcomes) {
        public int createdAlbumCount() {
            return (int) outcomes.stream().filter(AlbumOutcome::created).count();
        }

        public int existingAlbumCount() {
            return (int) outcomes.stream()
                    .filter(outcome -> !outcome.created()
                            && !outcome.releaseYearConflict()
                            && !outcome.titleConflict())
                    .count();
        }

        public int releaseYearConflictCount() {
            return (int) outcomes.stream().filter(AlbumOutcome::releaseYearConflict).count();
        }

        public int titleConflictCount() {
            return (int) outcomes.stream().filter(AlbumOutcome::titleConflict).count();
        }

        public boolean changedLibraryMetadata() {
            return outcomes.stream().anyMatch(outcome -> outcome.created() || outcome.releaseYearFilled());
        }
    }

    public record AlbumOutcome(
            RemoteReleaseGroup release,
            Album album,
            boolean created,
            boolean releaseYearFilled,
            boolean releaseYearConflict,
            boolean titleConflict) {
    }
}
