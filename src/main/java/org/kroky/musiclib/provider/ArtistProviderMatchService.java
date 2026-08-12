package org.kroky.musiclib.provider;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.kroky.musiclib.config.MusicLibraryConfig;
import org.kroky.musiclib.model.Album;
import org.kroky.musiclib.model.Artist;
import org.kroky.musiclib.model.ArtistProviderCandidate;
import org.kroky.musiclib.model.RemoteReleaseGroup;
import org.kroky.musiclib.provider.html.MetalArchivesProvider;
import org.kroky.musiclib.provider.html.SpiritOfMetalProvider;
import org.kroky.musiclib.provider.musicbrainz.MusicBrainzArtistResult;
import org.kroky.musiclib.provider.musicbrainz.MusicBrainzClient;
import org.kroky.musiclib.repository.AlbumRepository;
import org.kroky.musiclib.repository.ArtistRepository;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ArtistProviderMatchService {

    private static final int HTML_SEARCH_CANDIDATE_LIMIT = 10;
    private final ExecutorService htmlCandidateExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Inject
    ArtistRepository artists;

    @Inject
    AlbumRepository albums;

    @Inject
    MusicBrainzClient musicBrainz;

    @Inject
    SpiritOfMetalProvider spiritOfMetal;

    @Inject
    MetalArchivesProvider metalArchives;

    @Inject
    MusicLibraryConfig config;

    @PreDestroy
    void shutdown() {
        htmlCandidateExecutor.shutdownNow();
    }

    public List<ArtistProviderCandidate> searchCandidates(long artistId, String providerId) throws ProviderException {
        return searchCandidates(artistId, providerId, false);
    }

    public List<ArtistProviderCandidate> searchBulkCandidates(long artistId, String providerId) throws ProviderException {
        return searchCandidates(artistId, providerId, true);
    }

    private List<ArtistProviderCandidate> searchCandidates(long artistId, String providerId, boolean bulk)
            throws ProviderException {
        Artist artist = artists.find(artistId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown artist: " + artistId));
        if (albums.majorArtistCollection(artistId) == null) {
            throw new IllegalArgumentException("Provider matching is not available for title-centric artists.");
        }
        List<Album> artistAlbums = albums.list(artist.id(), null, null, null, null);
        List<ProviderArtistSearchResult> results = searchProviderResults(artist.name(), providerId);
        List<ArtistProviderCandidate> candidates = MusicBrainzClient.PROVIDER_ID.equals(providerId)
                ? results.stream().map(result -> candidate(artist, artistAlbums, result, bulk)).toList()
                : results.stream()
                        .map(result -> CompletableFuture.supplyAsync(
                                () -> candidate(artist, artistAlbums, result, bulk), htmlCandidateExecutor))
                        .map(CompletableFuture::join)
                        .toList();
        return candidates.stream()
                .sorted(Comparator.comparingInt(ArtistProviderCandidate::finalScore).reversed()
                        .thenComparing(Comparator.comparingInt(ArtistProviderCandidate::albumEvidenceScore).reversed())
                        .thenComparing(Comparator.comparingInt(ArtistProviderCandidate::providerScore).reversed())
                        .thenComparing(ArtistProviderCandidate::providerArtistName,
                                Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    private List<ProviderArtistSearchResult> searchProviderResults(String artistName, String providerId)
            throws ProviderException {
        if (MusicBrainzClient.PROVIDER_ID.equals(providerId)) {
            return searchMusicBrainzResults(artistName);
        }
        if (ProviderUrlNormalizer.SPIRIT_OF_METAL.equals(providerId)) {
            return spiritOfMetal.searchArtists(artistName, HTML_SEARCH_CANDIDATE_LIMIT);
        }
        if (ProviderUrlNormalizer.METAL_ARCHIVES.equals(providerId)) {
            return metalArchives.searchArtists(artistName, HTML_SEARCH_CANDIDATE_LIMIT);
        }
        throw new ProviderException("Unsupported provider: " + providerId);
    }

    private List<ProviderArtistSearchResult> searchMusicBrainzResults(String artistName) throws ProviderException {
        List<MusicBrainzArtistResult> results = musicBrainz.searchArtists(
                artistName, config.providers().musicbrainz().searchCandidateLimit());
        return results.stream()
                .map(result -> new ProviderArtistSearchResult(
                        MusicBrainzClient.PROVIDER_ID,
                        result.id(),
                        result.name(),
                        musicBrainz.artistUrl(result.id()),
                        result.country(),
                        result.disambiguation(),
                        result.active(),
                        result.score()))
                .toList();
    }

    private ArtistProviderCandidate candidate(Artist artist, List<Album> artistAlbums,
            ProviderArtistSearchResult result, boolean bulk) {
        CandidateDetails details;
        try {
            details = fetchCandidateDetails(result, bulk);
        } catch (ProviderException e) {
            details = new CandidateDetails(result.country(), result.active(), List.of());
        }
        List<RemoteReleaseGroup> releaseGroups = details.releaseGroups();
        var evidence = ProviderCandidateEvidenceEvaluator.evaluate(
                artist.name(),
                artistAlbums,
                result.providerArtistName(),
                result.providerScore(),
                providerAliases(result),
                releaseGroups);
        return new ArtistProviderCandidate(
                result.providerId(),
                result.providerArtistId(),
                result.providerArtistName(),
                result.providerUrl(),
                details.country(),
                result.disambiguation(),
                details.active(),
                result.providerScore(),
                evidence.finalScore(),
                evidence.nameScore(),
                evidence.albumEvidenceScore(),
                evidence.yearBonus(),
                evidence.evidenceSummary(),
                evidence.albumEvidence());
    }

    private CandidateDetails fetchCandidateDetails(ProviderArtistSearchResult result, boolean bulk)
            throws ProviderException {
        if (MusicBrainzClient.PROVIDER_ID.equals(result.providerId())) {
            return new CandidateDetails(
                    result.country(),
                    result.active(),
                    musicBrainz.fetchReleaseGroups(result.providerArtistId()));
        }
        ProviderArtistDetails details;
        if (ProviderUrlNormalizer.METAL_ARCHIVES.equals(result.providerId()) && bulk) {
            details = new ProviderArtistDetails(result.country(), result.active(), metalArchives.fetchAlbums(result.providerUrl()));
        } else {
            DiscographyProvider provider = ProviderUrlNormalizer.SPIRIT_OF_METAL.equals(result.providerId())
                    ? spiritOfMetal
                    : metalArchives;
            details = provider.fetchArtistDetails(result.providerUrl());
        }
        List<RemoteReleaseGroup> releaseGroups = details.albums().stream()
                .map(album -> new RemoteReleaseGroup(
                        result.providerId(),
                        album.sourceUrl() == null || album.sourceUrl().isBlank() ? album.title() : album.sourceUrl(),
                        album.title(),
                        album.releaseYear(),
                        "Album",
                        List.of(),
                        album.sourceUrl()))
                .toList();
        return new CandidateDetails(
                details.country() == null ? result.country() : details.country(),
                details.active() == null ? result.active() : details.active(),
                releaseGroups);
    }

    private record CandidateDetails(String country, Boolean active, List<RemoteReleaseGroup> releaseGroups) {
    }

    public ArtistProviderCandidate enrichSelectedCandidate(ArtistProviderCandidate candidate) {
        if (candidate == null || !ProviderUrlNormalizer.METAL_ARCHIVES.equals(candidate.providerId())) {
            return candidate;
        }
        SelectedMetadata metadata = enrichSelectedMetadata(
                candidate.providerId(), candidate.providerUrl(), candidate.country(), candidate.active());
        return new ArtistProviderCandidate(
                candidate.providerId(),
                candidate.providerArtistId(),
                candidate.providerArtistName(),
                candidate.providerUrl(),
                metadata.country(),
                candidate.disambiguation(),
                metadata.active(),
                candidate.providerScore(),
                candidate.finalScore(),
                candidate.nameScore(),
                candidate.albumEvidenceScore(),
                candidate.yearBonus(),
                candidate.evidenceSummary(),
                candidate.albumEvidence());
    }

    public SelectedMetadata enrichSelectedMetadata(String providerId, String providerUrl, String country,
            Boolean active) {
        if (!ProviderUrlNormalizer.METAL_ARCHIVES.equals(providerId) || (country != null && active != null)) {
            return new SelectedMetadata(country, active);
        }
        try {
            ProviderArtistDetails profile = metalArchives.fetchArtistProfile(providerUrl);
            return new SelectedMetadata(
                    profile.country() == null ? country : profile.country(),
                    profile.active() == null ? active : profile.active());
        } catch (ProviderException ignored) {
            return new SelectedMetadata(country, active);
        }
    }

    public record SelectedMetadata(String country, Boolean active) {
    }

    private static List<String> providerAliases(ProviderArtistSearchResult result) {
        if (result.disambiguation() == null || result.disambiguation().isBlank()) {
            return List.of();
        }
        String cleaned = result.disambiguation()
                .replaceFirst("(?i)^a\\.k\\.a\\.\\s*", "")
                .trim();
        if (cleaned.isBlank()) {
            return List.of();
        }
        return List.of(cleaned);
    }

    static boolean titleMatchesForScoring(Album album, RemoteReleaseGroup releaseGroup) {
        return ProviderCandidateEvidenceEvaluator.titleMatch(album.title(), releaseGroup.title()).score() >= 92;
    }

    static boolean releaseYearsScoreCompatible(Integer localReleaseYear, Integer providerReleaseYear) {
        return ProviderCandidateEvidenceEvaluator.releaseYearsScoreCompatible(localReleaseYear, providerReleaseYear);
    }
}
