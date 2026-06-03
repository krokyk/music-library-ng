package org.kroky.musiclib.resource;

import org.jboss.logging.Logger;
import org.kroky.musiclib.config.MusicLibraryConfig;
import org.kroky.musiclib.model.MusicRootInfo;
import org.kroky.musiclib.model.UiSettings;
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
    private static final String STATUS_VISIBLE_KEY = "ui.statusCompleteVisibleMs";
    private static final String SCAN_POLL_KEY = "ui.scanPollIntervalMs";
    private static final String COLLECTION_SPINNER_KEY = "ui.collectionScanSpinnerEnabled";
    private static final String COLLECTION_PROGRESS_KEY = "ui.collectionScanProgressEnabled";
    private static final String STATUS_BAR_LOCATION_KEY = "ui.statusBarLocation";
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
        if (request.collectionScanProgressEnabled() != null) {
            saveOrDelete(
                    COLLECTION_PROGRESS_KEY,
                    String.valueOf(request.collectionScanProgressEnabled()),
                    String.valueOf(config.ui().defaultCollectionScanProgressEnabled()));
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
        preferences.delete(COLLECTION_PROGRESS_KEY);
        preferences.delete(STATUS_BAR_LOCATION_KEY);
        return effectiveUiSettings();
    }

    private UiSettings effectiveUiSettings() {
        int defaultStatusVisible = defaultStatusVisible();
        int defaultScanPoll = defaultScanPoll();
        boolean defaultSpinner = config.ui().defaultCollectionScanSpinnerEnabled();
        boolean defaultProgress = config.ui().defaultCollectionScanProgressEnabled();
        String defaultDateFormat = config.ui().defaultStatusHistoryDateFormat();
        String defaultStatusBarLocation = defaultStatusBarLocation();

        var statusVisible = preferences.find(STATUS_VISIBLE_KEY);
        var scanPoll = preferences.find(SCAN_POLL_KEY);
        var spinner = preferences.find(COLLECTION_SPINNER_KEY);
        var progress = preferences.find(COLLECTION_PROGRESS_KEY);
        var statusBarLocation = preferences.find(STATUS_BAR_LOCATION_KEY);

        return new UiSettings(
                statusVisible.map(value -> parseInt(value.value(), defaultStatusVisible, STATUS_VISIBLE_MIN, STATUS_VISIBLE_MAX))
                        .orElse(defaultStatusVisible),
                scanPoll.map(value -> parseInt(value.value(), defaultScanPoll, SCAN_POLL_MIN, SCAN_POLL_MAX))
                        .orElse(defaultScanPoll),
                spinner.map(value -> Boolean.parseBoolean(value.value())).orElse(defaultSpinner),
                progress.map(value -> Boolean.parseBoolean(value.value())).orElse(defaultProgress),
                defaultDateFormat,
                statusBarLocation.map(value -> normalizeStatusBarLocation(value.value(), defaultStatusBarLocation))
                        .orElse(defaultStatusBarLocation),
                defaultWorkspaceColumnWidths(),
                new UiSettings.Values(
                        defaultStatusVisible,
                        defaultScanPoll,
                        defaultSpinner,
                        defaultProgress,
                        defaultDateFormat,
                        defaultStatusBarLocation,
                        defaultWorkspaceColumnWidths()),
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
                        spinner.map(value -> Boolean.parseBoolean(value.value()) != defaultSpinner).orElse(false),
                        progress.map(value -> Boolean.parseBoolean(value.value()) != defaultProgress).orElse(false),
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
                        defaults.album().year(),
                        defaults.album().checked()),
                new UiSettings.TitleColumns(
                        defaults.title().title(),
                        defaults.title().artist(),
                        defaults.title().year(),
                        defaults.title().status()));
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
            Boolean collectionScanProgressEnabled,
            String statusBarLocation) {
    }
}
