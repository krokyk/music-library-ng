package org.kroky.musiclib.provider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        int total = 0;
        Map<String, Integer> counts = new HashMap<>();
        for (ArtistProviderLink link : links) {
            if (!link.enabled()) {
                continue;
            }
            total++;
            String country = link.providerCountry();
            String value = country == null || country.isBlank() ? UNKNOWN : country;
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
        return new CountryVote(null, total > 1);
    }

    private static ActiveVote activeVote(List<ArtistProviderLink> links) {
        int total = 0;
        int active = 0;
        int inactive = 0;
        for (ArtistProviderLink link : links) {
            if (!link.enabled()) {
                continue;
            }
            total++;
            if (Boolean.TRUE.equals(link.providerActive())) {
                active++;
            } else if (Boolean.FALSE.equals(link.providerActive())) {
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
        return new ActiveVote(null, total > 1);
    }

    private static boolean hasStrictMajority(int count, int total) {
        return count * 2 > total;
    }

    private record CountryVote(String value, boolean conflict) {
    }

    private record ActiveVote(Boolean value, boolean conflict) {
    }
}
