package org.kroky.musiclib.provider;

import java.util.ArrayList;
import java.util.List;

import org.kroky.musiclib.model.ArtistProviderLink;
import org.kroky.musiclib.model.ProviderCheckSummary;
import org.kroky.musiclib.repository.AlbumRepository;
import org.kroky.musiclib.repository.ArtistProviderLinkRepository;
import org.kroky.musiclib.repository.ProviderCheckRunRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ProviderCheckService {

    @Inject
    ArtistProviderLinkRepository providerLinks;

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
        return checkLinks(runId, List.of(link));
    }

    public ProviderCheckSummary checkArtist(long artistId) {
        List<ArtistProviderLink> links = providerLinks.listByArtist(artistId).stream()
                .filter(ArtistProviderLink::enabled)
                .toList();
        long runId = runs.start(artistId, null);
        return checkLinks(runId, links);
    }

    public ProviderCheckSummary checkAll() {
        long runId = runs.start(null, null);
        return checkLinks(runId, providerLinks.listEnabled());
    }

    private ProviderCheckSummary checkLinks(long runId, List<ArtistProviderLink> links) {
        int processedArtists = 0;
        int skippedArtists = 0;
        int foundAlbums = 0;
        int newAlbums = 0;
        int existingAlbums = 0;
        int errors = 0;
        List<String> messages = new ArrayList<>();

        if (links.isEmpty()) {
            String message = "No enabled provider links found.";
            runs.event(runId, null, null, "WARN", message);
            runs.finish(runId, "SKIPPED", 0, 0, 0, 0, 0, message);
            return new ProviderCheckSummary(runId, 0, 0, 0, 0, 0, 0, List.of(message));
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
                    if (albums.findDuplicate(link.artistId(), remoteAlbum.title(), remoteAlbum.releaseYear()).isPresent()) {
                        existingAlbums++;
                        continue;
                    }
                    albums.create(link.artistId(), remoteAlbum.title(), remoteAlbum.releaseYear(),
                            remoteAlbum.releaseDate() == null ? null : remoteAlbum.releaseDate().toString(),
                            false, null);
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
                + " albums, added " + newAlbums + " new unchecked albums.";
        runs.finish(runId, status, processedArtists, foundAlbums, newAlbums, existingAlbums, errors, message);
        messages.add(message);
        return new ProviderCheckSummary(runId, processedArtists, skippedArtists, foundAlbums, newAlbums,
                existingAlbums, errors, messages);
    }
}
