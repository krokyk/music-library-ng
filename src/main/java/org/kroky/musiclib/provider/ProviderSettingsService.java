package org.kroky.musiclib.provider;

import org.kroky.musiclib.config.MusicLibraryConfig;
import org.kroky.musiclib.repository.UserPreferenceRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ProviderSettingsService {

    public static final String BATCH_RESCAN_DELAY_KEY = "providers.batch-rescan-delay-minutes";
    public static final int BATCH_RESCAN_DELAY_MIN = 0;
    public static final int BATCH_RESCAN_DELAY_MAX = 43_200;

    @Inject
    MusicLibraryConfig config;

    @Inject
    UserPreferenceRepository preferences;

    public int defaultBatchRescanDelayMinutes() {
        return normalizeBatchRescanDelayMinutes(config.providers().defaultBatchRescanDelayMinutes());
    }

    public int batchRescanDelayMinutes() {
        int defaultValue = defaultBatchRescanDelayMinutes();
        return preferences.find(BATCH_RESCAN_DELAY_KEY)
                .map(value -> parseInt(value.value(), defaultValue))
                .orElse(defaultValue);
    }

    public int normalizeBatchRescanDelayMinutes(Integer value) {
        return normalizeBatchRescanDelayMinutes(value == null ? defaultBatchRescanDelayMinutes() : value);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return normalizeBatchRescanDelayMinutes(Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int normalizeBatchRescanDelayMinutes(int value) {
        return Math.min(BATCH_RESCAN_DELAY_MAX, Math.max(BATCH_RESCAN_DELAY_MIN, value));
    }
}
