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
import org.kroky.musiclib.provider.musicbrainz.MusicBrainzArtistResult;
import org.kroky.musiclib.provider.musicbrainz.MusicBrainzClient;
import org.kroky.musiclib.repository.AlbumRepository;
import org.kroky.musiclib.repository.ArtistRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ArtistProviderMatchService {

    @Inject
    ArtistRepository artists;

    @Inject
    AlbumRepository albums;

    @Inject
    MusicBrainzClient musicBrainz;

    @Inject
    MusicLibraryConfig config;

    public List<ArtistProviderCandidate> searchMusicBrainzCandidates(long artistId) throws ProviderException {
        Artist artist = artists.find(artistId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown artist: " + artistId));
        List<Album> localAlbums = albums.list(artist.id(), null, null, null, null).stream()
                .filter(ArtistProviderMatchService::isScoringAlbum)
                .toList();
        List<MusicBrainzArtistResult> results = musicBrainz.searchArtists(
                artist.name(), config.providers().musicbrainz().searchCandidateLimit());
        return results.stream()
                .map(result -> candidate(artist, localAlbums, result))
                .sorted(Comparator.comparingInt(ArtistProviderCandidate::matchScore).reversed()
                        .thenComparing(Comparator.comparingInt(ArtistProviderCandidate::providerScore).reversed())
                        .thenComparing(ArtistProviderCandidate::providerArtistName,
                                Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    private ArtistProviderCandidate candidate(Artist artist, List<Album> localAlbums, MusicBrainzArtistResult result) {
        List<RemoteReleaseGroup> releaseGroups;
        try {
            releaseGroups = musicBrainz.fetchReleaseGroups(result.id());
        } catch (ProviderException e) {
            releaseGroups = List.of();
        }
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
        int matchScore = matchScore(artist.name(), result, matchedAlbums.size(), titleAndYearMatches);
        return new ArtistProviderCandidate(
                MusicBrainzClient.PROVIDER_ID,
                result.id(),
                result.name(),
                musicBrainz.artistUrl(result.id()),
                result.type(),
                result.country(),
                result.disambiguation(),
                result.score(),
                matchScore,
                matchedAlbums,
                releaseGroups);
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

    private static int matchScore(String localArtistName, MusicBrainzArtistResult result, int titleMatches,
            int titleAndYearMatches) {
        String local = Names.normalize(localArtistName);
        String remote = Names.normalize(result.name());
        int nameScore = local.equals(remote) ? 35 : nameSimilarity(local, remote);
        int score = (int) Math.round(result.score() * 0.35)
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
