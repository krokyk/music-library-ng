package org.kroky.musiclib.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jboss.logging.Logger;
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
import org.kroky.musiclib.repository.ProviderCheckRunRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ArtistProviderRefreshService {

    private static final Logger LOG = Logger.getLogger(ArtistProviderRefreshService.class);

    @Inject
    ArtistProviderLinkRepository providerLinks;

    @Inject
    AlbumRepository albums;

    @Inject
    AlbumProviderLinkRepository albumProviderLinks;

    @Inject
    ProviderCheckRunRepository runs;

    @Inject
    MusicBrainzClient musicBrainz;

    public ProviderRefreshResult refreshArtist(long artistId) throws ProviderException {
        ArtistProviderLink link = enabledMusicBrainzProvider(artistId);
        long runId = runs.start(artistId, link.id());
        try {
            ProviderRefreshResult result = importMusicBrainz(runId, link);
            runs.finish(runId, "DONE", 1, result.foundReleaseGroupCount(), result.createdAlbumCount(),
                    result.existingAlbumCount(), 0, 0, result.messages().get(result.messages().size() - 1));
            providerLinks.markSuccess(link.id());
            return result;
        } catch (Exception e) {
            String errorDetail = ProviderException.describe(e);
            String message = "MusicBrainz refresh failed for " + link.artistName() + ": " + errorDetail;
            runs.event(runId, link.artistId(), link.id(), "ERROR", message);
            runs.finish(runId, "FAILED", 1, 0, 0, 0, 0, 1, message);
            providerLinks.markError(link.id(), errorDetail);
            LOG.error(message, e);
            if (e instanceof ProviderException providerException) {
                throw providerException;
            }
            throw new ProviderException(message, e);
        }
    }

    public ProviderRefreshResult importMusicBrainz(long runId, ArtistProviderLink link) throws ProviderException {
        return importMusicBrainz(runId, link, null);
    }

    public ProviderRefreshResult importMusicBrainz(long runId, ArtistProviderLink link, String collectionId)
            throws ProviderException {
        requireMusicBrainzIdentity(link);
        MusicBrainzArtistResult artist = musicBrainz.fetchArtist(link.providerArtistId());
        providerLinks.updateProviderMetadata(link.id(), artist.country(), artist.active());
        List<RemoteReleaseGroup> releaseGroups = musicBrainz.fetchReleaseGroups(link.providerArtistId());
        List<Album> localAlbums = new ArrayList<>(albums.list(link.artistId(), null, null, null, null));
        int alreadyInLibrary = 0;
        int createdAlbums = 0;
        int ignored = 0;

        runs.event(runId, link.artistId(), link.id(), "INFO",
                "Read " + releaseGroups.size() + " MusicBrainz albums for " + link.artistName());

        for (RemoteReleaseGroup releaseGroup : releaseGroups) {
            AlbumImportPlan plan = classify(releaseGroup, localAlbums);
            switch (plan.decision()) {
                case SKIP_ALREADY_LINKED -> {
                    assignToCollectionIfUnassigned(plan.album(), collectionId);
                    alreadyInLibrary++;
                }
                case SKIP_UNSUPPORTED -> {
                    ignored++;
                    runs.event(runId, link.artistId(), link.id(), "INFO",
                            "Ignored MusicBrainz provider record for " + link.artistName() + ": "
                                    + plan.reason());
                }
                case AUTO_MATCH_EXISTING -> {
                    Album album = plan.album();
                    albums.updateReleaseDateIfMissing(album.id(), releaseGroup.releaseDate());
                    linkAlbum(album.id(), releaseGroup);
                    assignToCollectionIfUnassigned(album, collectionId);
                    alreadyInLibrary++;
                    runs.event(runId, link.artistId(), link.id(), "INFO",
                            "MusicBrainz album already in library: " + album.title());
                }
                case AUTO_CREATE -> {
                    Album album = albums.create(link.artistId(), releaseGroup.title(), releaseGroup.releaseDate(),
                            false, null, collectionId);
                    linkAlbum(album.id(), releaseGroup);
                    localAlbums.add(album);
                    createdAlbums++;
                    runs.event(runId, link.artistId(), link.id(), "INFO",
                            "Added unchecked MusicBrainz album: " + album.title());
                }
            }
        }

        String message = "MusicBrainz refresh for " + link.artistName() + " read " + releaseGroups.size()
                + " albums, already in library " + alreadyInLibrary
                + ", added " + createdAlbums + " unchecked albums"
                + ", ignored " + ignored + ".";
        runs.event(runId, link.artistId(), link.id(), "INFO", message);
        return new ProviderRefreshResult(
                runId,
                link.artistId(),
                link.artistName(),
                link.providerId(),
                releaseGroups.size(),
                alreadyInLibrary,
                createdAlbums,
                ignored,
                List.of(message));
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

    private ArtistProviderLink enabledMusicBrainzProvider(long artistId) {
        ArtistProviderLink link = providerLinks.findByArtist(artistId)
                .orElseThrow(() -> new IllegalArgumentException("No provider assigned to artist " + artistId));
        if (!link.enabled()) {
            throw new IllegalArgumentException("Artist provider is disabled");
        }
        requireMusicBrainzIdentity(link);
        return link;
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

    private enum AlbumImportDecision {
        SKIP_ALREADY_LINKED,
        SKIP_UNSUPPORTED,
        AUTO_MATCH_EXISTING,
        AUTO_CREATE
    }

    private record AlbumImportPlan(AlbumImportDecision decision, Album album, String reason) {
    }
}
