package org.kroky.musiclib.provider;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.kroky.musiclib.model.ArtistProviderLink;

public final class ArtistProviderMetadata {

    private static final String UNKNOWN = "";
    private static final String INTERNATIONAL = "XW";

    private ArtistProviderMetadata() {
    }

    public static String countryConsensus(List<ArtistProviderLink> links) {
        return countryVote(links).value();
    }

    public static boolean countryConflict(List<ArtistProviderLink> links) {
        return countryVote(links).conflict();
    }

    public static Boolean activeConsensus(List<ArtistProviderLink> links) {
        return activeVote(links).value();
    }

    public static boolean activeConflict(List<ArtistProviderLink> links) {
        return activeVote(links).conflict();
    }

    private static CountryVote countryVote(List<ArtistProviderLink> links) {
        Map<String, Set<String>> evidence = providerEvidence(links, link -> {
            String country = link.providerCountry();
            return country == null || country.isBlank() ? UNKNOWN : country;
        });
        int total = evidence.size();
        Map<String, Integer> counts = new HashMap<>();
        boolean providerConflict = false;
        for (Set<String> values : evidence.values()) {
            providerConflict |= values.size() > 1;
            String value = values.size() == 1 ? values.iterator().next() : UNKNOWN;
            counts.merge(value, 1, Integer::sum);
        }
        if (total == 0) {
            return new CountryVote(null, false);
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            String value = entry.getKey();
            if (!UNKNOWN.equals(value) && !INTERNATIONAL.equals(value) && hasStrictMajority(entry.getValue(), total)) {
                return new CountryVote(value, false);
            }
        }
        if (counts.getOrDefault(INTERNATIONAL, 0) == total) {
            return new CountryVote(INTERNATIONAL, false);
        }
        return new CountryVote(null, providerConflict || total > 1);
    }

    private static ActiveVote activeVote(List<ArtistProviderLink> links) {
        Map<String, Set<Boolean>> evidence = providerEvidence(links, ArtistProviderLink::providerActive);
        int total = evidence.size();
        int active = 0;
        int inactive = 0;
        boolean providerConflict = false;
        for (Set<Boolean> values : evidence.values()) {
            providerConflict |= values.size() > 1;
            Boolean value = values.size() == 1 ? values.iterator().next() : null;
            if (Boolean.TRUE.equals(value)) {
                active++;
            } else if (Boolean.FALSE.equals(value)) {
                inactive++;
            }
        }
        if (total == 0) {
            return new ActiveVote(null, false);
        }
        if (hasStrictMajority(active, total)) {
            return new ActiveVote(Boolean.TRUE, false);
        }
        if (hasStrictMajority(inactive, total)) {
            return new ActiveVote(Boolean.FALSE, false);
        }
        return new ActiveVote(null, providerConflict || total > 1);
    }

    private static <T> Map<String, Set<T>> providerEvidence(List<ArtistProviderLink> links,
            Function<ArtistProviderLink, T> value) {
        Map<String, Set<T>> evidence = new HashMap<>();
        for (ArtistProviderLink link : links) {
            if (link.enabled()) {
                evidence.computeIfAbsent(link.providerId(), ignored -> new HashSet<>()).add(value.apply(link));
            }
        }
        return evidence;
    }

    private static boolean hasStrictMajority(int count, int total) {
        return count * 2 > total;
    }

    private record CountryVote(String value, boolean conflict) {
    }

    private record ActiveVote(Boolean value, boolean conflict) {
    }
}
