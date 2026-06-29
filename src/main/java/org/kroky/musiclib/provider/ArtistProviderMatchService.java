package org.kroky.musiclib.provider;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.kroky.musiclib.config.MusicLibraryConfig;
import org.kroky.musiclib.db.Names;
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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ArtistProviderMatchService {

    private static final int HTML_SEARCH_CANDIDATE_LIMIT = 10;

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

    public List<ArtistProviderCandidate> searchMusicBrainzCandidates(long artistId) throws ProviderException {
        return searchCandidates(artistId, MusicBrainzClient.PROVIDER_ID);
    }

    public List<ArtistProviderCandidate> searchCandidates(long artistId, String providerId) throws ProviderException {
        Artist artist = artists.find(artistId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown artist: " + artistId));
        List<Album> localAlbums = albums.list(artist.id(), null, null, null, null).stream()
                .filter(ArtistProviderMatchService::isScoringAlbum)
                .toList();
        return searchProviderResults(artist.name(), providerId).stream()
                .map(result -> candidate(artist, localAlbums, result))
                .sorted(Comparator.comparingInt(ArtistProviderCandidate::matchScore).reversed()
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

    private ArtistProviderCandidate candidate(Artist artist, List<Album> localAlbums,
            ProviderArtistSearchResult result) {
        CandidateDetails details;
        try {
            details = fetchCandidateDetails(result);
        } catch (ProviderException e) {
            details = new CandidateDetails(result.country(), result.active(), List.of());
        }
        List<RemoteReleaseGroup> releaseGroups = details.releaseGroups();
        Set<String> remoteTitles = releaseGroups.stream()
                .map(RemoteReleaseGroup::title)
                .map(Names::normalize)
                .collect(HashSet::new, Set::add, Set::addAll);
        List<String> matchedAlbums = localAlbums.stream()
                .filter(album -> remoteTitles.contains(Names.normalize(album.title())))
                .map(Album::title)
                .distinct()
                .toList();
        int titleAndYearMatches = titleAndYearMatches(localAlbums, releaseGroups);
        int matchScore = matchScore(
                artist.name(),
                result.providerArtistName(),
                result.providerScore(),
                matchedAlbums.size(),
                titleAndYearMatches);
        return new ArtistProviderCandidate(
                result.providerId(),
                result.providerArtistId(),
                result.providerArtistName(),
                result.providerUrl(),
                details.country(),
                result.disambiguation(),
                details.active(),
                result.providerScore(),
                matchScore,
                matchedAlbums,
                releaseGroups);
    }

    private CandidateDetails fetchCandidateDetails(ProviderArtistSearchResult result) throws ProviderException {
        if (MusicBrainzClient.PROVIDER_ID.equals(result.providerId())) {
            return new CandidateDetails(
                    result.country(),
                    result.active(),
                    musicBrainz.fetchReleaseGroups(result.providerArtistId()));
        }
        DiscographyProvider provider = ProviderUrlNormalizer.SPIRIT_OF_METAL.equals(result.providerId())
                ? spiritOfMetal
                : metalArchives;
        ProviderArtistDetails details = provider.fetchArtistDetails(result.providerUrl());
        List<RemoteReleaseGroup> releaseGroups = details.albums().stream()
                .map(album -> new RemoteReleaseGroup(
                        result.providerId(),
                        album.sourceUrl() == null || album.sourceUrl().isBlank() ? album.title() : album.sourceUrl(),
                        album.title(),
                        album.releaseDate(),
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

    private static int titleAndYearMatches(List<Album> localAlbums, List<RemoteReleaseGroup> releaseGroups) {
        int matches = 0;
        for (Album album : localAlbums) {
            String albumTitle = Names.normalize(album.title());
            String albumYear = releaseYear(album.releaseDate());
            for (RemoteReleaseGroup releaseGroup : releaseGroups) {
                if (albumTitle.equals(Names.normalize(releaseGroup.title()))
                        && albumYear != null
                        && albumYear.equals(releaseYear(releaseGroup.releaseDate()))) {
                    matches++;
                    break;
                }
            }
        }
        return matches;
    }

    private static int matchScore(String localArtistName, String providerArtistName, int providerScore,
            int titleMatches, int titleAndYearMatches) {
        String local = Names.normalize(localArtistName);
        String remote = Names.normalize(providerArtistName);
        int nameScore = local.equals(remote) ? 35 : nameSimilarity(local, remote);
        int score = (int) Math.round(providerScore * 0.35)
                + nameScore
                + Math.min(20, titleMatches * 5)
                + Math.min(10, titleAndYearMatches * 3);
        return Math.min(100, Math.max(0, score));
    }

    private static int nameSimilarity(String local, String remote) {
        if (local.isBlank() || remote.isBlank()) {
            return 0;
        }
        if (local.contains(remote) || remote.contains(local)) {
            return 25;
        }
        Set<String> localTokens = Arrays.stream(local.split(" ")).collect(Collectors.toSet());
        Set<String> remoteTokens = Arrays.stream(remote.split(" ")).collect(Collectors.toSet());
        long overlap = localTokens.stream().filter(remoteTokens::contains).count();
        long total = Math.max(localTokens.size(), remoteTokens.size());
        return total == 0 ? 0 : (int) Math.round(25.0 * overlap / total);
    }

    private static boolean isScoringAlbum(Album album) {
        String normalizedTitle = Names.normalize(album.title());
        if (normalizedTitle.startsWith("ui test ")) {
            return false;
        }
        String year = releaseYear(album.releaseDate());
        return year == null || Integer.parseInt(year) <= LocalDate.now().getYear() + 1;
    }

    private static String releaseYear(String releaseDate) {
        if (releaseDate == null || releaseDate.length() < 4) {
            return null;
        }
        return releaseDate.substring(0, 4);
    }
}
