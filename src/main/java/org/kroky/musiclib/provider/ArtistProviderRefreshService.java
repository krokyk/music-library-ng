package org.kroky.musiclib.provider;

import java.util.ArrayList;
import java.util.List;

import org.kroky.musiclib.model.Album;
import org.kroky.musiclib.model.ArtistProviderCandidateAlbum;
import org.kroky.musiclib.model.ArtistProviderLink;
import org.kroky.musiclib.model.ProviderRefreshResult;
import org.kroky.musiclib.model.RemoteReleaseGroup;
import org.kroky.musiclib.provider.musicbrainz.MusicBrainzArtistResult;
import org.kroky.musiclib.provider.musicbrainz.MusicBrainzClient;
import org.kroky.musiclib.repository.AlbumProviderLinkRepository;
import org.kroky.musiclib.repository.AlbumRepository;
import org.kroky.musiclib.repository.ArtistProviderLinkRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ArtistProviderRefreshService {

    @Inject
    ArtistProviderLinkRepository providerLinks;

    @Inject
    AlbumRepository albums;

    @Inject
    AlbumProviderLinkRepository albumProviderLinks;

    @Inject
    MusicBrainzClient musicBrainz;

    public ProviderRefreshResult importMusicBrainz(ArtistProviderLink link, String collectionId,
            ProviderCheckReport report)
            throws ProviderException {
        requireMusicBrainzIdentity(link);
        MusicBrainzArtistResult artist = musicBrainz.fetchArtist(link.providerArtistId());
        providerLinks.updateProviderMetadata(link.id(), artist.country(), artist.active());
        List<RemoteReleaseGroup> releaseGroups = musicBrainz.fetchReleaseGroups(link.providerArtistId());
        List<Album> localAlbums = new ArrayList<>(albums.list(link.artistId(), null, null, null, null));
        int alreadyInLibrary = 0;
        int createdAlbums = 0;
        int releaseDateConflicts = 0;
        int titleConflicts = 0;
        int ignored = 0;

        for (RemoteReleaseGroup releaseGroup : releaseGroups) {
            AlbumImportPlan plan = classify(releaseGroup, localAlbums);
            switch (plan.decision()) {
                case SKIP_ALREADY_LINKED -> {
                    mergeProviderOnlyDuplicates(plan.album(), releaseGroup);
                    assignToCollectionIfUnassigned(plan.album(), collectionId);
                    ProviderAlbumOutcome outcome = reportProviderAlbumOutcome(link.artistName(), "MusicBrainz",
                            plan.album(), releaseGroup, report);
                    alreadyInLibrary += outcome.existingAlbums();
                    releaseDateConflicts += outcome.releaseDateConflicts();
                    titleConflicts += outcome.titleConflicts();
                }
                case SKIP_UNSUPPORTED -> {
                    ignored++;
                    report.ignoredProviderRecord(link.artistName() + " (MusicBrainz): " + releaseGroup.title()
                            + " | reason: " + plan.reason());
                }
                case AUTO_MATCH_EXISTING -> {
                    Album album = plan.album();
                    mergeProviderOnlyDuplicates(album, releaseGroup);
                    albums.updateReleaseDateIfMissing(album.id(), releaseGroup.releaseDate());
                    linkAlbum(album.id(), releaseGroup);
                    album = albums.find(album.id()).orElse(album);
                    assignToCollectionIfUnassigned(album, collectionId);
                    ProviderAlbumOutcome outcome = reportProviderAlbumOutcome(link.artistName(), "MusicBrainz",
                            album, releaseGroup, report);
                    alreadyInLibrary += outcome.existingAlbums();
                    releaseDateConflicts += outcome.releaseDateConflicts();
                    titleConflicts += outcome.titleConflicts();
                }
                case AUTO_CREATE -> {
                    Album album = albums.create(link.artistId(), releaseGroup.title(), releaseGroup.releaseDate(),
                            false, null, collectionId);
                    linkAlbum(album.id(), releaseGroup);
                    localAlbums.add(album);
                    createdAlbums++;
                    report.addedAsUnchecked(providerAlbumRow(link.artistName(), "MusicBrainz", releaseGroup, album));
                }
            }
        }

        if (createdAlbums == 0 && releaseDateConflicts == 0 && titleConflicts == 0 && ignored == 0) {
            report.noChange(link.artistName() + " (MusicBrainz)");
        }
        String message = "MusicBrainz refresh for " + link.artistName() + " read " + releaseGroups.size()
                + " albums, already in library " + alreadyInLibrary
                + ", release date conflicts " + releaseDateConflicts
                + ", title conflicts " + titleConflicts
                + ", added " + createdAlbums + " unchecked albums"
                + ", ignored " + ignored + ".";
        return new ProviderRefreshResult(
                link.artistId(),
                link.artistName(),
                link.providerId(),
                releaseGroups.size(),
                alreadyInLibrary,
                createdAlbums,
                releaseDateConflicts,
                titleConflicts,
                ignored,
                List.of(message),
                List.of());
    }

    private AlbumImportPlan classify(RemoteReleaseGroup releaseGroup, List<Album> localAlbums) {
        var linkedAlbumId = albumProviderLinks.findAlbumId(
                releaseGroup.providerId(), releaseGroup.providerReleaseGroupId());
        if (linkedAlbumId.isPresent()) {
            Album linkedAlbum = albums.find(linkedAlbumId.get()).orElse(null);
            AlbumImportPlan preferred = autoMatchExisting(releaseGroup, localAlbums);
            if (preferred.album() != null
                    && linkedAlbum != null
                    && preferred.album().id() != linkedAlbum.id()
                    && linkedAlbum.localPaths().isEmpty()) {
                return preferred;
            }
            return new AlbumImportPlan(AlbumImportDecision.SKIP_ALREADY_LINKED, linkedAlbum, "Already in library");
        }
        if (releaseGroup.title() == null || releaseGroup.title().isBlank()) {
            return new AlbumImportPlan(AlbumImportDecision.SKIP_UNSUPPORTED, null, "Blank provider album title");
        }
        if (!isSupportedPrimaryType(releaseGroup.primaryType())) {
            return new AlbumImportPlan(AlbumImportDecision.SKIP_UNSUPPORTED, null,
                    "Unsupported primary type: " + releaseGroup.primaryType());
        }
        if (hasSecondaryTypes(releaseGroup)) {
            return new AlbumImportPlan(AlbumImportDecision.SKIP_UNSUPPORTED, null,
                    "Secondary type: " + String.join(", ", releaseGroup.secondaryTypes()));
        }

        AlbumImportPlan matched = autoMatchExisting(releaseGroup, localAlbums);
        if (matched.album() != null) {
            return matched;
        }
        return new AlbumImportPlan(AlbumImportDecision.AUTO_CREATE, null, "New full album");
    }

    private AlbumImportPlan autoMatchExisting(RemoteReleaseGroup releaseGroup, List<Album> localAlbums) {
        ArtistProviderCandidateAlbum evidence = ProviderCandidateEvidenceEvaluator.albumEvidence(localAlbums, releaseGroup);
        Album evidenceAlbum = evidence.localAlbumId() == null
                ? null
                : localAlbums.stream()
                        .filter(album -> evidence.localAlbumId().equals(album.id()))
                        .findFirst()
                        .orElse(null);
        if (ProviderCandidateEvidenceEvaluator.canAutoLinkProviderImportAlbum(evidence, evidenceAlbum)) {
            return new AlbumImportPlan(AlbumImportDecision.AUTO_MATCH_EXISTING, evidenceAlbum,
                    evidence.matchType() + " title match");
        }
        return new AlbumImportPlan(AlbumImportDecision.SKIP_UNSUPPORTED, null, "No supported existing album match");
    }

    private void assignToCollectionIfUnassigned(Album album, String collectionId) {
        if (album != null && collectionId != null && album.collections().isEmpty() && album.localPaths().isEmpty()) {
            albums.assignToCollection(album.id(), collectionId);
        }
    }

    private void linkAlbum(long albumId, RemoteReleaseGroup releaseGroup) {
        albumProviderLinks.linkAlbum(
                albumId,
                releaseGroup.providerId(),
                releaseGroup.providerReleaseGroupId(),
                releaseGroup.title(),
                releaseGroup.releaseDate(),
                releaseGroup.providerUrl());
    }

    private void mergeProviderOnlyDuplicates(Album album, RemoteReleaseGroup releaseGroup) {
        if (album == null) {
            return;
        }
        for (long artistId : album.artistIds()) {
            albums.mergeProviderOnlyDuplicates(
                    album.id(),
                    artistId,
                    releaseGroup.title(),
                    releaseGroup.releaseDate());
        }
    }

    private static void requireMusicBrainzIdentity(ArtistProviderLink link) {
        if (!MusicBrainzClient.PROVIDER_ID.equals(link.providerId())) {
            throw new IllegalArgumentException("Artist provider is not MusicBrainz");
        }
        if (link.providerArtistId() == null || link.providerArtistId().isBlank()) {
            throw new IllegalArgumentException("MusicBrainz artist MBID is required");
        }
    }

    private static boolean isSupportedPrimaryType(String primaryType) {
        return "Album".equalsIgnoreCase(primaryType);
    }

    private static boolean hasSecondaryTypes(RemoteReleaseGroup releaseGroup) {
        return releaseGroup.secondaryTypes() != null && !releaseGroup.secondaryTypes().isEmpty();
    }

    private static boolean releaseDateConflict(String localReleaseDate, String providerReleaseDate) {
        String localYear = releaseYear(localReleaseDate);
        String providerYear = releaseYear(providerReleaseDate);
        return localYear != null && providerYear != null && !localYear.equals(providerYear);
    }

    private static String releaseYear(String releaseDate) {
        String normalized = releaseDate == null || releaseDate.isBlank() ? null : releaseDate.trim();
        if (normalized == null || normalized.length() < 4) {
            return null;
        }
        return normalized.substring(0, 4);
    }

    private static String releaseDateConflictRow(String artistName, String providerName, Album album,
            RemoteReleaseGroup releaseGroup) {
        return artistName + " (" + providerName + "): " + album.title()
                + " | local: " + blankValue(album.releaseDate())
                + " | provider: " + blankValue(releaseGroup.releaseDate())
                + " | provider title: " + blankValue(releaseGroup.title());
    }

    private static String titleConflictRow(String artistName, String providerName, Album album,
            RemoteReleaseGroup releaseGroup) {
        return artistName + " (" + providerName + "): " + album.title()
                + " | provider title: " + blankValue(releaseGroup.title())
                + " | release: " + blankValue(releaseGroup.releaseDate());
    }

    private static ProviderAlbumOutcome reportProviderAlbumOutcome(String artistName, String providerName, Album album,
            RemoteReleaseGroup releaseGroup, ProviderCheckReport report) {
        if (album == null) {
            report.alreadyInLibrary(providerAlbumRow(artistName, providerName, releaseGroup, null));
            return new ProviderAlbumOutcome(1, 0, 0);
        }
        boolean releaseDateConflict = releaseDateConflict(album.releaseDate(), releaseGroup.releaseDate());
        boolean titleConflict = ProviderTitles.titleConflict(album.title(), releaseGroup.title());
        if (releaseDateConflict) {
            report.releaseDateConflict(releaseDateConflictRow(artistName, providerName, album, releaseGroup));
        }
        if (titleConflict) {
            report.titleConflict(titleConflictRow(artistName, providerName, album, releaseGroup));
        }
        if (!releaseDateConflict && !titleConflict) {
            report.alreadyInLibrary(providerAlbumRow(artistName, providerName, releaseGroup, album));
        }
        return new ProviderAlbumOutcome(
                releaseDateConflict || titleConflict ? 0 : 1,
                releaseDateConflict ? 1 : 0,
                titleConflict ? 1 : 0);
    }

    private static String providerAlbumRow(String artistName, String providerName, RemoteReleaseGroup releaseGroup,
            Album album) {
        return artistName + " (" + providerName + "): " + blankValue(releaseGroup.title())
                + " | release: " + blankValue(releaseGroup.releaseDate())
                + (album == null ? "" : " | local album: " + album.title());
    }

    private static String blankValue(String value) {
        return value == null || value.isBlank() ? "<blank>" : value;
    }

    private enum AlbumImportDecision {
        SKIP_ALREADY_LINKED,
        SKIP_UNSUPPORTED,
        AUTO_MATCH_EXISTING,
        AUTO_CREATE
    }

    private record AlbumImportPlan(AlbumImportDecision decision, Album album, String reason) {
    }

    private record ProviderAlbumOutcome(int existingAlbums, int releaseDateConflicts, int titleConflicts) {
    }
}
