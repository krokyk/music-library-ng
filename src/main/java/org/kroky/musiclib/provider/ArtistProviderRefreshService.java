package org.kroky.musiclib.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.kroky.musiclib.db.Names;
import org.kroky.musiclib.model.Album;
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

    public ProviderRefreshResult importMusicBrainz(ArtistProviderLink link, ProviderCheckReport report)
            throws ProviderException {
        return importMusicBrainz(link, null, report);
    }

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
        int ignored = 0;

        for (RemoteReleaseGroup releaseGroup : releaseGroups) {
            AlbumImportPlan plan = classify(releaseGroup, localAlbums);
            switch (plan.decision()) {
                case SKIP_ALREADY_LINKED -> {
                    assignToCollectionIfUnassigned(plan.album(), collectionId);
                    if (plan.album() != null && releaseDateConflict(plan.album().releaseDate(),
                            releaseGroup.releaseDate())) {
                        releaseDateConflicts++;
                        report.releaseDateConflict(releaseDateConflictRow(link.artistName(), "MusicBrainz",
                                plan.album(), releaseGroup));
                    } else {
                        alreadyInLibrary++;
                        report.alreadyInLibrary(providerAlbumRow(link.artistName(), "MusicBrainz", releaseGroup,
                                plan.album()));
                    }
                }
                case SKIP_UNSUPPORTED -> {
                    ignored++;
                    report.ignoredProviderRecord(link.artistName() + " (MusicBrainz): " + releaseGroup.title()
                            + " | reason: " + plan.reason());
                }
                case AUTO_MATCH_EXISTING -> {
                    Album album = plan.album();
                    boolean conflict = releaseDateConflict(album.releaseDate(), releaseGroup.releaseDate());
                    albums.updateReleaseDateIfMissing(album.id(), releaseGroup.releaseDate());
                    linkAlbum(album.id(), releaseGroup);
                    assignToCollectionIfUnassigned(album, collectionId);
                    if (conflict) {
                        releaseDateConflicts++;
                        report.releaseDateConflict(releaseDateConflictRow(link.artistName(), "MusicBrainz", album,
                                releaseGroup));
                    } else {
                        alreadyInLibrary++;
                        report.alreadyInLibrary(providerAlbumRow(link.artistName(), "MusicBrainz", releaseGroup,
                                album));
                    }
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

        if (createdAlbums == 0 && releaseDateConflicts == 0 && ignored == 0) {
            report.noChange(link.artistName() + " (MusicBrainz)");
        }
        String message = "MusicBrainz refresh for " + link.artistName() + " read " + releaseGroups.size()
                + " albums, already in library " + alreadyInLibrary
                + ", release date conflicts " + releaseDateConflicts
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
                ignored,
                List.of(message),
                List.of());
    }

    private AlbumImportPlan classify(RemoteReleaseGroup releaseGroup, List<Album> localAlbums) {
        Optional<Long> linkedAlbumId = albumProviderLinks.findAlbumId(
                releaseGroup.providerId(), releaseGroup.providerReleaseGroupId());
        if (linkedAlbumId.isPresent()) {
            Album album = albums.find(linkedAlbumId.get()).orElse(null);
            return new AlbumImportPlan(AlbumImportDecision.SKIP_ALREADY_LINKED, album, "Already in library");
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

        // Keep provider import deterministic: exact title matches are existing albums; everything else is a new unchecked full album.
        return localAlbums.stream()
                .filter(album -> Names.normalize(album.title()).equals(Names.normalize(releaseGroup.title())))
                .findFirst()
                .map(album -> new AlbumImportPlan(AlbumImportDecision.AUTO_MATCH_EXISTING, album, "Exact title match"))
                .orElseGet(() -> new AlbumImportPlan(AlbumImportDecision.AUTO_CREATE, null, "New full album"));
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
}
