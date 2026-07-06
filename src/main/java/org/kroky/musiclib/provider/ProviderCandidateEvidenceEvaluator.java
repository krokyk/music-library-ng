package org.kroky.musiclib.provider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.kroky.musiclib.db.Names;
import org.kroky.musiclib.model.Album;
import org.kroky.musiclib.model.ArtistProviderCandidate;
import org.kroky.musiclib.model.ArtistProviderCandidateAlbum;
import org.kroky.musiclib.model.RemoteReleaseGroup;

final class ProviderCandidateEvidenceEvaluator {

    static final String MATCH_EXACT = ProviderTitles.MATCH_EXACT;
    static final String MATCH_NORMALIZED = ProviderTitles.MATCH_NORMALIZED;
    static final String MATCH_FUZZY = ProviderTitles.MATCH_FUZZY;
    static final String MATCH_NONE = ProviderTitles.MATCH_NONE;

    static final String KIND_LOCAL = "local";
    static final String KIND_CHECKED = "checked";
    static final String KIND_UNCHECKED = "unchecked";
    static final String KIND_PROVIDER_ONLY = "provider-only";

    static final int FUZZY_REVIEW_THRESHOLD = ProviderTitles.FUZZY_REVIEW_THRESHOLD;
    static final int FUZZY_HIGH_CONFIDENCE_THRESHOLD = ProviderTitles.FUZZY_HIGH_CONFIDENCE_THRESHOLD;

    private static final int LOCAL_EXACT_OR_NORMALIZED_WEIGHT = 40;
    private static final int LOCAL_HIGH_FUZZY_WEIGHT = 32;
    private static final int CHECKED_EXACT_OR_NORMALIZED_WEIGHT = 24;
    private static final int CHECKED_HIGH_FUZZY_WEIGHT = 18;
    private static final int YEAR_BONUS_PER_ALBUM = 3;
    private static final int MAX_ALBUM_EVIDENCE_SCORE = 100;
    private static final int MAX_YEAR_BONUS = 10;

    private static final Set<String> GENERIC_TITLES = Set.of(
            "Greatest Hits",
            "Best Of",
            "Live",
            "Anthology",
            "Collection",
            "The Collection",
            "Essential",
            "The Essential").stream()
            .map(Names::normalize)
            .collect(Collectors.toUnmodifiableSet());

    private ProviderCandidateEvidenceEvaluator() {
    }

    static CandidateEvidence evaluate(String localArtistName, List<Album> artistAlbums, String providerArtistName,
            int providerScore, List<String> providerAliases, List<RemoteReleaseGroup> releaseGroups) {
        List<ArtistProviderCandidateAlbum> albumEvidence = albumEvidence(
                artistAlbums == null ? List.of() : artistAlbums,
                releaseGroups == null ? List.of() : releaseGroups);
        int nameScore = artistNameScore(localArtistName, providerArtistName, providerAliases);
        int albumEvidenceScore = albumEvidenceScore(albumEvidence);
        int yearBonus = yearBonus(albumEvidence);
        int finalScore = clampScore((int) Math.round(
                providerScore * 0.25
                        + nameScore * 0.30
                        + albumEvidenceScore * 0.40
                        + yearBonus));
        return new CandidateEvidence(
                finalScore,
                nameScore,
                albumEvidenceScore,
                yearBonus,
                evidenceSummary(finalScore, nameScore, albumEvidence),
                matchedLocalAlbums(albumEvidence),
                albumEvidence);
    }

    static boolean isHighConfidenceMatch(ArtistProviderCandidate candidate, ArtistProviderCandidate runnerUp) {
        if (candidate == null) {
            return false;
        }
        if (candidate.providerScore() < 80
                || candidate.nameScore() < 65
                || candidate.albumEvidenceScore() < 64
                || candidate.finalScore() < 82) {
            return false;
        }
        int margin = runnerUp == null ? 100 : candidate.finalScore() - runnerUp.finalScore();
        if (margin < 8) {
            return false;
        }
        List<ArtistProviderCandidateAlbum> scoringEvidence = scoringEvidence(candidate.albumEvidence());
        if (scoringEvidence.isEmpty() || scoringEvidence.stream().allMatch(ArtistProviderCandidateAlbum::genericTitle)) {
            return false;
        }
        long localStrongMatches = uniqueLocalAlbumCount(scoringEvidence.stream()
                .filter(evidence -> KIND_LOCAL.equals(evidence.localEvidenceKind()))
                .toList());
        if (localStrongMatches >= 2) {
            return true;
        }
        boolean oneLocalExactOrNormalized = scoringEvidence.stream()
                .anyMatch(evidence -> KIND_LOCAL.equals(evidence.localEvidenceKind())
                        && !evidence.genericTitle()
                        && (MATCH_EXACT.equals(evidence.matchType())
                                || MATCH_NORMALIZED.equals(evidence.matchType())));
        return oneLocalExactOrNormalized && margin >= 12;
    }

    static TitleMatch titleMatch(String localTitle, String providerTitle) {
        var match = ProviderTitles.titleMatch(localTitle, providerTitle);
        return new TitleMatch(match.type(), match.score());
    }

    static int artistNameScore(String localArtistName, String providerArtistName, List<String> providerAliases) {
        String local = Names.normalize(localArtistName);
        if (local.isBlank()) {
            return 0;
        }
        List<String> candidates = new ArrayList<>();
        candidates.add(providerArtistName);
        if (providerAliases != null) {
            candidates.addAll(providerAliases);
        }
        int best = 0;
        for (String candidate : candidates) {
            String remote = Names.normalize(candidate);
            if (remote.isBlank()) {
                continue;
            }
            int score = local.equals(remote) ? 100 : fuzzyScore(local, remote);
            best = Math.max(best, score);
        }
        return best;
    }

    static boolean releaseYearsScoreCompatible(String localReleaseDate, String providerReleaseDate) {
        Integer localYear = releaseYearValue(localReleaseDate);
        Integer providerYear = releaseYearValue(providerReleaseDate);
        return localYear == null || providerYear == null || Math.abs(localYear - providerYear) <= 1;
    }

    static String cleanProviderTitle(String value) {
        return ProviderTitles.clean(value);
    }

    private static List<ArtistProviderCandidateAlbum> albumEvidence(List<Album> artistAlbums,
            List<RemoteReleaseGroup> releaseGroups) {
        return releaseGroups.stream()
                .map(releaseGroup -> albumEvidence(artistAlbums, releaseGroup))
                .toList();
    }

    static ArtistProviderCandidateAlbum albumEvidence(List<Album> artistAlbums, RemoteReleaseGroup releaseGroup) {
        String providerTitle = cleanProviderTitle(releaseGroup.title());
        LocalAlbumEvidence best = artistAlbums.stream()
                .map(album -> localAlbumEvidence(album, releaseGroup, providerTitle))
                .filter(evidence -> !MATCH_NONE.equals(evidence.match().type()))
                .max(Comparator
                        .comparingInt(LocalAlbumEvidence::evidenceStrength)
                        .thenComparingInt(LocalAlbumEvidence::localKindRank)
                        .thenComparingInt(evidence -> evidence.match().score())
                        .thenComparingInt(LocalAlbumEvidence::compatibleYearRank))
                .orElse(null);
        if (best == null) {
            return new ArtistProviderCandidateAlbum(
                    providerTitle,
                    releaseGroup.releaseDate(),
                    releaseGroup.providerUrl(),
                    null,
                    null,
                    null,
                    false,
                    false,
                    MATCH_NONE,
                    0,
                    0,
                    KIND_PROVIDER_ONLY,
                    genericTitle(null, providerTitle));
        }
        Album album = best.album();
        return new ArtistProviderCandidateAlbum(
                providerTitle,
                releaseGroup.releaseDate(),
                releaseGroup.providerUrl(),
                album.id(),
                album.title(),
                album.releaseDate(),
                album.onDisk(),
                releaseDateConflict(album.releaseDate(), releaseGroup.releaseDate()),
                best.match().type(),
                best.match().score(),
                best.evidenceStrength(),
                localEvidenceKind(album),
                genericTitle(album.title(), providerTitle));
    }

    private static LocalAlbumEvidence localAlbumEvidence(Album album, RemoteReleaseGroup releaseGroup,
            String providerTitle) {
        TitleMatch match = titleMatch(album.title(), providerTitle);
        boolean generic = genericTitle(album.title(), providerTitle);
        return new LocalAlbumEvidence(
                album,
                match,
                evidenceStrength(album, match, generic),
                releaseYearsScoreCompatible(album.releaseDate(), releaseGroup.releaseDate()) ? 1 : 0);
    }

    static boolean canAutoLinkAlbum(ArtistProviderCandidateAlbum evidence) {
        return evidence != null
                && evidence.localAlbumId() != null
                && evidence.evidenceStrength() > 0;
    }

    private static int evidenceStrength(Album album, TitleMatch match, boolean genericTitle) {
        if (album == null || MATCH_NONE.equals(match.type())) {
            return 0;
        }
        boolean exactOrNormalized = MATCH_EXACT.equals(match.type()) || MATCH_NORMALIZED.equals(match.type());
        boolean highConfidenceFuzzy = MATCH_FUZZY.equals(match.type())
                && match.score() >= FUZZY_HIGH_CONFIDENCE_THRESHOLD;
        if (!exactOrNormalized && !highConfidenceFuzzy) {
            return 0;
        }
        int base;
        if (album.onDisk()) {
            base = exactOrNormalized ? LOCAL_EXACT_OR_NORMALIZED_WEIGHT : LOCAL_HIGH_FUZZY_WEIGHT;
        } else if (album.checked()) {
            base = exactOrNormalized ? CHECKED_EXACT_OR_NORMALIZED_WEIGHT : CHECKED_HIGH_FUZZY_WEIGHT;
        } else {
            return 0;
        }
        return genericTitle ? base / 2 : base;
    }

    private static int albumEvidenceScore(List<ArtistProviderCandidateAlbum> albumEvidence) {
        return Math.min(MAX_ALBUM_EVIDENCE_SCORE, scoringEvidence(albumEvidence).stream()
                .mapToInt(ArtistProviderCandidateAlbum::evidenceStrength)
                .sum());
    }

    private static int yearBonus(List<ArtistProviderCandidateAlbum> albumEvidence) {
        int bonus = scoringEvidence(albumEvidence).stream()
                .filter(evidence -> knownReleaseYearsScoreCompatible(
                        evidence.localReleaseDate(),
                        evidence.providerReleaseDate()))
                .mapToInt(evidence -> YEAR_BONUS_PER_ALBUM)
                .sum();
        return Math.min(MAX_YEAR_BONUS, bonus);
    }

    private static List<ArtistProviderCandidateAlbum> scoringEvidence(List<ArtistProviderCandidateAlbum> albumEvidence) {
        if (albumEvidence == null || albumEvidence.isEmpty()) {
            return List.of();
        }
        Map<Long, ArtistProviderCandidateAlbum> strongestByAlbum = new LinkedHashMap<>();
        for (ArtistProviderCandidateAlbum evidence : albumEvidence) {
            if (evidence.localAlbumId() == null || evidence.evidenceStrength() <= 0) {
                continue;
            }
            ArtistProviderCandidateAlbum existing = strongestByAlbum.get(evidence.localAlbumId());
            if (existing == null || evidence.evidenceStrength() > existing.evidenceStrength()) {
                strongestByAlbum.put(evidence.localAlbumId(), evidence);
            }
        }
        return List.copyOf(strongestByAlbum.values());
    }

    private static List<String> matchedLocalAlbums(List<ArtistProviderCandidateAlbum> albumEvidence) {
        LinkedHashSet<String> titles = new LinkedHashSet<>();
        for (ArtistProviderCandidateAlbum evidence : scoringEvidence(albumEvidence)) {
            if (KIND_LOCAL.equals(evidence.localEvidenceKind()) && evidence.localTitle() != null) {
                titles.add(evidence.localTitle());
            }
        }
        return List.copyOf(titles);
    }

    private static String evidenceSummary(int finalScore, int nameScore, List<ArtistProviderCandidateAlbum> albumEvidence) {
        List<ArtistProviderCandidateAlbum> scoringEvidence = scoringEvidence(albumEvidence);
        long local = uniqueLocalAlbumCount(scoringEvidence.stream()
                .filter(evidence -> KIND_LOCAL.equals(evidence.localEvidenceKind()))
                .toList());
        long checked = uniqueLocalAlbumCount(scoringEvidence.stream()
                .filter(evidence -> KIND_CHECKED.equals(evidence.localEvidenceKind()))
                .toList());
        long review = albumEvidence == null ? 0 : albumEvidence.stream()
                .filter(evidence -> MATCH_FUZZY.equals(evidence.matchType())
                        && evidence.titleScore() >= FUZZY_REVIEW_THRESHOLD
                        && evidence.titleScore() < FUZZY_HIGH_CONFIDENCE_THRESHOLD)
                .count();
        List<String> parts = new ArrayList<>();
        if (local > 0) {
            parts.add(local + " local");
        }
        if (checked > 0) {
            parts.add(checked + " checked");
        }
        if (parts.isEmpty() && review > 0) {
            parts.add(review + " review");
        }
        String albums = parts.isEmpty() ? "no evidence" : String.join(", ", parts);
        return "Confidence " + finalScore + " / Name " + nameScore + " / Albums " + albums;
    }

    private static long uniqueLocalAlbumCount(List<ArtistProviderCandidateAlbum> albumEvidence) {
        return albumEvidence.stream()
                .map(ArtistProviderCandidateAlbum::localAlbumId)
                .filter(id -> id != null)
                .collect(Collectors.toSet())
                .size();
    }

    private static boolean knownReleaseYearsScoreCompatible(String localReleaseDate, String providerReleaseDate) {
        Integer localYear = releaseYearValue(localReleaseDate);
        Integer providerYear = releaseYearValue(providerReleaseDate);
        return localYear != null
                && providerYear != null
                && Math.abs(localYear - providerYear) <= 1;
    }

    private static boolean releaseDateConflict(String localReleaseDate, String providerReleaseDate) {
        String localYear = releaseYear(localReleaseDate);
        String providerYear = releaseYear(providerReleaseDate);
        return localYear != null && providerYear != null && !localYear.equals(providerYear);
    }

    private static Integer releaseYearValue(String releaseDate) {
        String year = releaseYear(releaseDate);
        if (year == null) {
            return null;
        }
        try {
            return Integer.parseInt(year);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String releaseYear(String releaseDate) {
        if (releaseDate == null || releaseDate.length() < 4) {
            return null;
        }
        return releaseDate.substring(0, 4);
    }

    private static boolean genericTitle(String localTitle, String providerTitle) {
        return GENERIC_TITLES.contains(Names.normalize(localTitle))
                || GENERIC_TITLES.contains(Names.normalize(providerTitle));
    }

    private static String localEvidenceKind(Album album) {
        if (album == null) {
            return KIND_PROVIDER_ONLY;
        }
        if (album.onDisk()) {
            return KIND_LOCAL;
        }
        return album.checked() ? KIND_CHECKED : KIND_UNCHECKED;
    }

    private static int fuzzyScore(String left, String right) {
        if (left.isBlank() || right.isBlank()) {
            return 0;
        }
        return Math.max(
                Math.max(jaroWinklerScore(left, right), levenshteinRatio(left, right)),
                Math.max(tokenSetRatio(left, right), tokenSortRatio(left, right)));
    }

    private static int tokenSortRatio(String left, String right) {
        return levenshteinRatio(sortedTokens(left), sortedTokens(right));
    }

    private static int tokenSetRatio(String left, String right) {
        Set<String> leftTokens = tokens(left);
        Set<String> rightTokens = tokens(right);
        Set<String> intersection = new LinkedHashSet<>(leftTokens);
        intersection.retainAll(rightTokens);
        if (intersection.isEmpty()) {
            return 0;
        }
        Set<String> leftOnly = new LinkedHashSet<>(leftTokens);
        leftOnly.removeAll(intersection);
        Set<String> rightOnly = new LinkedHashSet<>(rightTokens);
        rightOnly.removeAll(intersection);
        String shared = sortedTokens(intersection);
        String leftCombined = sortedTokens(union(intersection, leftOnly));
        String rightCombined = sortedTokens(union(intersection, rightOnly));
        return Math.max(
                Math.max(levenshteinRatio(shared, leftCombined), levenshteinRatio(shared, rightCombined)),
                levenshteinRatio(leftCombined, rightCombined));
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.addAll(right);
        return result;
    }

    private static String sortedTokens(String value) {
        return sortedTokens(tokens(value));
    }

    private static String sortedTokens(Set<String> tokens) {
        return tokens.stream()
                .sorted()
                .collect(Collectors.joining(" "));
    }

    private static Set<String> tokens(String value) {
        return Arrays.stream(value.split(" "))
                .filter(token -> !token.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static int levenshteinRatio(String left, String right) {
        if (left.equals(right)) {
            return 100;
        }
        int maxLength = Math.max(left.length(), right.length());
        if (maxLength == 0) {
            return 100;
        }
        return clampScore((int) Math.round((1.0 - (double) levenshteinDistance(left, right) / maxLength) * 100));
    }

    private static int levenshteinDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(
                        Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private static int jaroWinklerScore(String left, String right) {
        if (left.equals(right)) {
            return 100;
        }
        int leftLength = left.length();
        int rightLength = right.length();
        if (leftLength == 0 || rightLength == 0) {
            return 0;
        }
        int matchDistance = Math.max(leftLength, rightLength) / 2 - 1;
        boolean[] leftMatches = new boolean[leftLength];
        boolean[] rightMatches = new boolean[rightLength];
        int matches = 0;
        for (int i = 0; i < leftLength; i++) {
            int start = Math.max(0, i - matchDistance);
            int end = Math.min(i + matchDistance + 1, rightLength);
            for (int j = start; j < end; j++) {
                if (rightMatches[j] || left.charAt(i) != right.charAt(j)) {
                    continue;
                }
                leftMatches[i] = true;
                rightMatches[j] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) {
            return 0;
        }
        double transpositions = 0;
        int rightIndex = 0;
        for (int i = 0; i < leftLength; i++) {
            if (!leftMatches[i]) {
                continue;
            }
            while (!rightMatches[rightIndex]) {
                rightIndex++;
            }
            if (left.charAt(i) != right.charAt(rightIndex)) {
                transpositions++;
            }
            rightIndex++;
        }
        transpositions /= 2.0;
        double jaro = ((double) matches / leftLength
                + (double) matches / rightLength
                + (matches - transpositions) / matches) / 3.0;
        int prefix = 0;
        int maxPrefix = Math.min(4, Math.min(leftLength, rightLength));
        while (prefix < maxPrefix && left.charAt(prefix) == right.charAt(prefix)) {
            prefix++;
        }
        double winkler = jaro + prefix * 0.1 * (1.0 - jaro);
        return clampScore((int) Math.round(winkler * 100));
    }

    private static int clampScore(int score) {
        return Math.min(100, Math.max(0, score));
    }

    record CandidateEvidence(
            int finalScore,
            int nameScore,
            int albumEvidenceScore,
            int yearBonus,
            String evidenceSummary,
            List<String> matchedLocalAlbums,
            List<ArtistProviderCandidateAlbum> albumEvidence) {
    }

    record TitleMatch(String type, int score) {
    }

    private record LocalAlbumEvidence(
            Album album,
            TitleMatch match,
            int evidenceStrength,
            int compatibleYearRank) {

        int localKindRank() {
            return switch (localEvidenceKind(album)) {
                case KIND_LOCAL -> 3;
                case KIND_CHECKED -> 2;
                case KIND_UNCHECKED -> 1;
                default -> 0;
            };
        }
    }
}
