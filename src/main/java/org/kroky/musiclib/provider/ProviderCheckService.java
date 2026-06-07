package org.kroky.musiclib.provider;

import java.util.ArrayList;
import java.util.List;

import org.kroky.musiclib.model.ArtistProviderLink;
import org.kroky.musiclib.model.MusicCollection;
import org.kroky.musiclib.model.ProviderCheckSummary;
import org.kroky.musiclib.repository.AlbumRepository;
import org.kroky.musiclib.repository.ArtistRepository;
import org.kroky.musiclib.repository.ArtistProviderLinkRepository;
import org.kroky.musiclib.repository.MusicCollectionRepository;
import org.kroky.musiclib.repository.ProviderCheckRunRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ProviderCheckService {

    @Inject
    ArtistProviderLinkRepository providerLinks;

    @Inject
    ArtistRepository artists;

    @Inject
    MusicCollectionRepository collections;

    @Inject
    AlbumRepository albums;

    @Inject
    ProviderRegistry providers;

    @Inject
    ProviderCheckRunRepository runs;

    public ProviderCheckSummary checkLink(long linkId) {
        ArtistProviderLink link = providerLinks.find(linkId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown provider link: " + linkId));
        long runId = runs.start(link.artistId(), link.id());
        return checkLinks(runId, List.of(link), 0, null);
    }

    public ProviderCheckSummary checkArtist(long artistId) {
        return checkArtist(artistId, null);
    }

    public ProviderCheckSummary checkArtist(long artistId, String collectionId) {
        if (collectionId != null && collections.find(collectionId).isEmpty()) {
            throw new IllegalArgumentException("Unknown collection: " + collectionId);
        }
        List<ArtistProviderLink> links = providerLinks.listByArtist(artistId).stream()
                .filter(ArtistProviderLink::enabled)
                .toList();
        long runId = runs.start(artistId, null);
        int skippedArtists = links.isEmpty() ? 1 : 0;
        return checkLinks(runId, links, skippedArtists, collectionId);
    }

    public ProviderCheckSummary checkCollection(String collectionId) {
        MusicCollection collection = collections.find(collectionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown collection: " + collectionId));
        List<ArtistProviderLink> links = providerLinks.listEnabledByCollection(collection.id());
        int artistCount = artists.list(null, collection.id()).size();
        int linkedArtistCount = (int) links.stream().map(ArtistProviderLink::artistId).distinct().count();
        int skippedArtists = Math.max(0, artistCount - linkedArtistCount);
        long runId = runs.start(null, null);
        return checkLinks(runId, links, skippedArtists, collection.id());
    }

    public ProviderCheckSummary checkAll() {
        long runId = runs.start(null, null);
        return checkLinks(runId, providerLinks.listEnabled(), 0, null);
    }

    private ProviderCheckSummary checkLinks(long runId, List<ArtistProviderLink> links, int skippedArtists,
            String collectionId) {
        int processedArtists = 0;
        int foundAlbums = 0;
        int newAlbums = 0;
        int existingAlbums = 0;
        int errors = 0;
        List<String> messages = new ArrayList<>();

        if (links.isEmpty()) {
            String message = skippedArtists > 0
                    ? "No enabled provider links found; skipped " + skippedArtists + " artists."
                    : "No enabled provider links found.";
            runs.event(runId, null, null, "WARN", message);
            runs.finish(runId, "SKIPPED", 0, 0, 0, 0, 0, message);
            return new ProviderCheckSummary(runId, 0, skippedArtists, 0, 0, 0, 0, List.of(message));
        }

        for (ArtistProviderLink link : links) {
            processedArtists++;
            try {
                DiscographyProvider provider = providers.find(link.providerId(), link.providerUrl());
                List<RemoteAlbum> remoteAlbums = provider.fetchAlbums(link.providerUrl());
                foundAlbums += remoteAlbums.size();
                runs.event(runId, link.artistId(), link.id(), "INFO",
                        "Found " + remoteAlbums.size() + " albums for " + link.artistName());
                for (RemoteAlbum remoteAlbum : remoteAlbums) {
                    var existing = albums.findDuplicate(link.artistId(), remoteAlbum.title(), remoteAlbum.releaseDate());
                    if (existing.isPresent()) {
                        if (collectionId != null) {
                            albums.assignToCollection(existing.get().id(), collectionId);
                        }
                        existingAlbums++;
                        continue;
                    }
                    albums.create(link.artistId(), remoteAlbum.title(), remoteAlbum.releaseDate(), false, null, collectionId);
                    newAlbums++;
                    runs.event(runId, link.artistId(), link.id(), "INFO",
                            "Added unchecked album: " + remoteAlbum.title());
                }
                providerLinks.markSuccess(link.id());
            } catch (Exception e) {
                errors++;
                String message = "Provider check failed for " + link.artistName() + ": " + e.getMessage();
                messages.add(message);
                providerLinks.markError(link.id(), e.getMessage());
                runs.event(runId, link.artistId(), link.id(), "ERROR", message);
            }
        }

        String status = errors == 0 ? "DONE" : "FAILED";
        String message = "Checked " + processedArtists + " provider links, found " + foundAlbums
                + " albums, added " + newAlbums + " new unchecked albums"
                + (skippedArtists > 0 ? ", skipped " + skippedArtists + " artists without enabled links." : ".");
        runs.finish(runId, status, processedArtists, foundAlbums, newAlbums, existingAlbums, errors, message);
        messages.add(message);
        return new ProviderCheckSummary(runId, processedArtists, skippedArtists, foundAlbums, newAlbums,
                existingAlbums, errors, messages);
    }
}
