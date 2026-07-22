package org.kroky.musiclib.provider;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.kroky.musiclib.db.Names;

public final class ProviderTitles {

    public static final String MATCH_EXACT = "exact";
    public static final String MATCH_NORMALIZED = "normalized";
    public static final String MATCH_FUZZY = "fuzzy";
    public static final String MATCH_NONE = "none";
    public static final int FUZZY_REVIEW_THRESHOLD = 84;
    public static final int FUZZY_HIGH_CONFIDENCE_THRESHOLD = 92;
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private ProviderTitles() {
    }

    public static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .replaceAll("\\s+([?!.,;:])", "$1")
                .replaceAll("\\s+", " ");
    }

    public static boolean sameTitle(String localTitle, String providerTitle) {
        return MATCH_EXACT.equals(titleMatch(localTitle, providerTitle).type());
    }

    public static boolean sameProviderTitle(String left, String right) {
        String leftNormalized = Names.normalize(clean(left));
        String rightNormalized = Names.normalize(clean(right));
        return !leftNormalized.isBlank() && leftNormalized.equals(rightNormalized);
    }

    public static boolean sameProviderTitleText(String left, String right) {
        String leftClean = clean(left);
        String rightClean = clean(right);
        return !leftClean.isBlank() && leftClean.equalsIgnoreCase(rightClean);
    }

    public static boolean titleConflict(String localTitle, String providerTitle) {
        return !clean(providerTitle).isBlank() && !sameTitle(localTitle, providerTitle);
    }

    public static TitleMatch titleMatch(String localTitle, String providerTitle) {
        String localExact = compactWhitespace(localTitle);
        String providerExact = compactWhitespace(clean(providerTitle));
        if (!localExact.isBlank() && localExact.equalsIgnoreCase(providerExact)) {
            return new TitleMatch(MATCH_EXACT, 100);
        }
        String local = Names.normalize(localTitle);
        String provider = Names.normalize(clean(providerTitle));
        if (local.isBlank() || provider.isBlank()) {
            return new TitleMatch(MATCH_NONE, 0);
        }
        if (local.equals(provider)) {
            return new TitleMatch(MATCH_NORMALIZED, 96);
        }
        if (numbersDiffer(local, provider)) {
            return new TitleMatch(MATCH_NONE, 0);
        }
        int score = fuzzyScore(local, provider);
        if (score >= FUZZY_REVIEW_THRESHOLD) {
            return new TitleMatch(MATCH_FUZZY, score);
        }
        return new TitleMatch(MATCH_NONE, score);
    }

    private static boolean numbersDiffer(String left, String right) {
        List<String> leftNumbers = numbers(left);
        List<String> rightNumbers = numbers(right);
        return !leftNumbers.isEmpty() && !rightNumbers.isEmpty() && !leftNumbers.equals(rightNumbers);
    }

    private static List<String> numbers(String value) {
        return NUMBER.matcher(value).results()
                .map(result -> result.group().replaceFirst("^0+(?=\\d)", ""))
                .toList();
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

    private static String compactWhitespace(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private static int clampScore(int score) {
        return Math.min(100, Math.max(0, score));
    }

    public record TitleMatch(String type, int score) {
    }
}
