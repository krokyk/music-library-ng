package org.kroky.musiclib.resource;

import org.jboss.logging.Logger;
import org.kroky.musiclib.config.MusicLibraryConfig;
import org.kroky.musiclib.model.MusicRootInfo;
import org.kroky.musiclib.model.UiSettings;
import org.kroky.musiclib.provider.ProviderSettingsService;
import org.kroky.musiclib.repository.UserPreferenceRepository;
import org.kroky.musiclib.scan.MusicRootService;

import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;

@Path("/api/settings")
public class SettingsResource {

    private static final Logger LOG = Logger.getLogger(SettingsResource.class);
    private static final String STATUS_VISIBLE_KEY = "ui.status-message.visible-ms";
    private static final String SCAN_POLL_KEY = "ui.scan-progress.poll-interval-ms";
    private static final String COLLECTION_SPINNER_KEY = "collections-screen.collections-pane.scan-spinner-enabled";
    private static final String ARTIST_SPINNER_KEY = "collections-screen.artists-pane.scan-spinner-enabled";
    private static final String COLLECTION_PROGRESS_KEY = "collections-screen.collections-pane.scan-progress-enabled";
    private static final String STATUS_BAR_LOCATION_KEY = "ui.status-bar.location";
    private static final String[] OBSOLETE_ACTION_LABEL_THRESHOLD_KEYS = {
            "collections-screen.collections-pane.action-label-threshold",
            "collections-screen.artists-pane.action-label-threshold",
            "collections-screen.albums-pane.action-label-threshold",
            "collections-screen.titles-pane.action-label-threshold"
    };
    private static final int STATUS_VISIBLE_MIN = 0;
    private static final int STATUS_VISIBLE_MAX = 30_000;
    private static final int SCAN_POLL_MIN = 100;
    private static final int SCAN_POLL_MAX = 2_000;

    @Inject
    MusicRootService musicRootService;

    @Inject
    MusicLibraryConfig config;

    @Inject
    UserPreferenceRepository preferences;

    @Inject
    ProviderSettingsService providerSettings;

    @GET
    @Path("/music-root")
    public MusicRootInfo musicRoot() {
        LOG.info("Loading music root settings");
        return musicRootService.info();
    }

    @GET
    @Path("/ui")
    public UiSettings ui() {
        LOG.debug("Loading UI settings");
        return effectiveUiSettings();
    }

    @PUT
    @Path("/ui")
    public UiSettings updateUi(UiSettingsRequest request) {
        if (request == null) {
            return effectiveUiSettings();
        }
        if (request.statusCompleteVisibleMs() != null) {
            int value = clamp(
                    request.statusCompleteVisibleMs(),
                    STATUS_VISIBLE_MIN,
                    STATUS_VISIBLE_MAX);
            saveOrDelete(STATUS_VISIBLE_KEY, String.valueOf(value), String.valueOf(defaultStatusVisible()));
        }
        if (request.scanPollIntervalMs() != null) {
            int value = clamp(
                    request.scanPollIntervalMs(),
                    SCAN_POLL_MIN,
                    SCAN_POLL_MAX);
            saveOrDelete(SCAN_POLL_KEY, String.valueOf(value), String.valueOf(defaultScanPoll()));
        }
        if (request.collectionScanSpinnerEnabled() != null) {
            saveOrDelete(
                    COLLECTION_SPINNER_KEY,
                    String.valueOf(request.collectionScanSpinnerEnabled()),
                    String.valueOf(config.ui().defaultCollectionScanSpinnerEnabled()));
        }
        if (request.artistScanSpinnerEnabled() != null) {
            saveOrDelete(
                    ARTIST_SPINNER_KEY,
                    String.valueOf(request.artistScanSpinnerEnabled()),
                    String.valueOf(config.ui().defaultArtistScanSpinnerEnabled()));
        }
        if (request.collectionScanProgressEnabled() != null) {
            saveOrDelete(
                    COLLECTION_PROGRESS_KEY,
                    String.valueOf(request.collectionScanProgressEnabled()),
                    String.valueOf(config.ui().defaultCollectionScanProgressEnabled()));
        }
        if (request.providerBatchRescanDelayMinutes() != null) {
            int value = providerSettings.normalizeBatchRescanDelayMinutes(request.providerBatchRescanDelayMinutes());
            saveOrDelete(
                    ProviderSettingsService.BATCH_RESCAN_DELAY_KEY,
                    String.valueOf(value),
                    String.valueOf(providerSettings.defaultBatchRescanDelayMinutes()));
        }
        if (request.statusBarLocation() != null && !request.statusBarLocation().isBlank()) {
            saveOrDelete(
                    STATUS_BAR_LOCATION_KEY,
                    normalizeStatusBarLocation(request.statusBarLocation(), "top"),
                    defaultStatusBarLocation());
        }
        return effectiveUiSettings();
    }

    @DELETE
    @Path("/ui")
    public UiSettings resetUi() {
        preferences.delete(STATUS_VISIBLE_KEY);
        preferences.delete(SCAN_POLL_KEY);
        preferences.delete(COLLECTION_SPINNER_KEY);
        preferences.delete(ARTIST_SPINNER_KEY);
        preferences.delete(COLLECTION_PROGRESS_KEY);
        preferences.delete(ProviderSettingsService.BATCH_RESCAN_DELAY_KEY);
        preferences.delete(STATUS_BAR_LOCATION_KEY);
        deleteObsoleteActionLabelThresholdPreferences();
        return effectiveUiSettings();
    }

    private UiSettings effectiveUiSettings() {
        deleteObsoleteActionLabelThresholdPreferences();
        int defaultStatusVisible = defaultStatusVisible();
        int defaultScanPoll = defaultScanPoll();
        boolean defaultCollectionSpinner = config.ui().defaultCollectionScanSpinnerEnabled();
        boolean defaultArtistSpinner = config.ui().defaultArtistScanSpinnerEnabled();
        boolean defaultProgress = config.ui().defaultCollectionScanProgressEnabled();
        int defaultProviderBatchRescanDelay = providerSettings.defaultBatchRescanDelayMinutes();
        String defaultDateFormat = config.ui().defaultStatusHistoryDateFormat();
        String defaultReleaseDateDisplayFormat = config.release().date().display().format();
        String defaultStatusBarLocation = defaultStatusBarLocation();
        int tableGridColumnMinWidth = Math.max(1, config.ui().tableGridColumnMinWidth());

        var statusVisible = preferences.find(STATUS_VISIBLE_KEY);
        var scanPoll = preferences.find(SCAN_POLL_KEY);
        var collectionSpinner = preferences.find(COLLECTION_SPINNER_KEY);
        var artistSpinner = preferences.find(ARTIST_SPINNER_KEY);
        var progress = preferences.find(COLLECTION_PROGRESS_KEY);
        var providerBatchRescanDelay = preferences.find(ProviderSettingsService.BATCH_RESCAN_DELAY_KEY);
        var statusBarLocation = preferences.find(STATUS_BAR_LOCATION_KEY);

        return new UiSettings(
                statusVisible.map(value -> parseInt(value.value(), defaultStatusVisible, STATUS_VISIBLE_MIN, STATUS_VISIBLE_MAX))
                        .orElse(defaultStatusVisible),
                scanPoll.map(value -> parseInt(value.value(), defaultScanPoll, SCAN_POLL_MIN, SCAN_POLL_MAX))
                        .orElse(defaultScanPoll),
                collectionSpinner.map(value -> Boolean.parseBoolean(value.value())).orElse(defaultCollectionSpinner),
                artistSpinner.map(value -> Boolean.parseBoolean(value.value())).orElse(defaultArtistSpinner),
                progress.map(value -> Boolean.parseBoolean(value.value())).orElse(defaultProgress),
                providerBatchRescanDelay
                        .map(value -> parseInt(
                                value.value(),
                                defaultProviderBatchRescanDelay,
                                ProviderSettingsService.BATCH_RESCAN_DELAY_MIN,
                                ProviderSettingsService.BATCH_RESCAN_DELAY_MAX))
                        .orElse(defaultProviderBatchRescanDelay),
                defaultDateFormat,
                defaultReleaseDateDisplayFormat,
                statusBarLocation.map(value -> normalizeStatusBarLocation(value.value(), defaultStatusBarLocation))
                        .orElse(defaultStatusBarLocation),
                defaultWorkspaceColumnWidths(),
                defaultArtistsScreenColumnWidths(),
                tableGridColumnMinWidth,
                new UiSettings.Values(
                        defaultStatusVisible,
                        defaultScanPoll,
                        defaultCollectionSpinner,
                        defaultArtistSpinner,
                        defaultProgress,
                        defaultProviderBatchRescanDelay,
                        defaultDateFormat,
                        defaultReleaseDateDisplayFormat,
                        defaultStatusBarLocation,
                        defaultWorkspaceColumnWidths(),
                        defaultArtistsScreenColumnWidths(),
                        tableGridColumnMinWidth),
                new UiSettings.Overrides(
                        statusVisible
                                .map(value -> parseInt(
                                        value.value(),
                                        defaultStatusVisible,
                                        STATUS_VISIBLE_MIN,
                                        STATUS_VISIBLE_MAX) != defaultStatusVisible)
                                .orElse(false),
                        scanPoll
                                .map(value -> parseInt(
                                        value.value(),
                                        defaultScanPoll,
                                        SCAN_POLL_MIN,
                                        SCAN_POLL_MAX) != defaultScanPoll)
                                .orElse(false),
                        collectionSpinner.map(value -> Boolean.parseBoolean(value.value()) != defaultCollectionSpinner)
                                .orElse(false),
                        artistSpinner.map(value -> Boolean.parseBoolean(value.value()) != defaultArtistSpinner)
                                .orElse(false),
                        progress.map(value -> Boolean.parseBoolean(value.value()) != defaultProgress).orElse(false),
                        providerBatchRescanDelay
                                .map(value -> parseInt(
                                        value.value(),
                                        defaultProviderBatchRescanDelay,
                                        ProviderSettingsService.BATCH_RESCAN_DELAY_MIN,
                                        ProviderSettingsService.BATCH_RESCAN_DELAY_MAX) != defaultProviderBatchRescanDelay)
                                .orElse(false),
                        false,
                        false,
                        statusBarLocation
                                .map(value -> !normalizeStatusBarLocation(value.value(), defaultStatusBarLocation)
                                        .equals(defaultStatusBarLocation))
                                .orElse(false)));
    }

    private UiSettings.WorkspaceColumnWidths defaultWorkspaceColumnWidths() {
        var defaults = config.ui().defaultWorkspaceColumnWidths();
        return new UiSettings.WorkspaceColumnWidths(
                new UiSettings.ArtistColumns(
                        defaults.artist().name()),
                new UiSettings.AlbumColumns(
                        defaults.album().name(),
                        defaults.album().releaseDate(),
                        defaults.album().checked(),
                        defaults.album().collections(),
                        defaults.album().action()),
                new UiSettings.TitleColumns(
                        defaults.title().title(),
                        defaults.title().artist(),
                        defaults.title().releaseDate(),
                        defaults.title().action()));
    }

    private UiSettings.ArtistsScreenColumnWidths defaultArtistsScreenColumnWidths() {
        var defaults = config.ui().defaultArtistsScreenColumnWidths();
        return new UiSettings.ArtistsScreenColumnWidths(
                defaults.name(),
                defaults.country(),
                defaults.type(),
                defaults.status(),
                defaults.albums(),
                defaults.unchecked(),
                defaults.local(),
                defaults.provider(),
                defaults.action());
    }

    private int defaultStatusVisible() {
        return clamp(config.ui().defaultStatusCompleteVisibleMs(), STATUS_VISIBLE_MIN, STATUS_VISIBLE_MAX);
    }

    private int defaultScanPoll() {
        return clamp(config.ui().defaultScanPollIntervalMs(), SCAN_POLL_MIN, SCAN_POLL_MAX);
    }

    private String defaultStatusBarLocation() {
        return normalizeStatusBarLocation(config.ui().defaultStatusBarLocation(), "top");
    }

    private void saveOrDelete(String key, String value, String defaultValue) {
        if (value.equals(defaultValue)) {
            preferences.delete(key);
        } else {
            preferences.save(key, value);
        }
    }

    private void deleteObsoleteActionLabelThresholdPreferences() {
        for (String key : OBSOLETE_ACTION_LABEL_THRESHOLD_KEYS) {
            preferences.delete(key);
        }
    }

    private static int parseInt(String value, int fallback, int min, int max) {
        try {
            return clamp(Integer.parseInt(value), min, max);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }

    private static String normalizeStatusBarLocation(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase();
        return "bottom".equals(normalized) || "top".equals(normalized) ? normalized : fallback;
    }

    public record UiSettingsRequest(
            Integer statusCompleteVisibleMs,
            Integer scanPollIntervalMs,
            Boolean collectionScanSpinnerEnabled,
            Boolean artistScanSpinnerEnabled,
            Boolean collectionScanProgressEnabled,
            Integer providerBatchRescanDelayMinutes,
            String statusBarLocation) {
    }
}
