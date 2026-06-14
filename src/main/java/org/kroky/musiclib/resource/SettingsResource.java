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
    private static final String ARTIST_SPINNER_KEY = "ui.artistScanSpinnerEnabled";
    private static final String COLLECTION_PROGRESS_KEY = "ui.collectionScanProgressEnabled";
    private static final String STATUS_BAR_LOCATION_KEY = "ui.statusBarLocation";
    private static final String COLLECTION_ACTION_LABEL_THRESHOLD_KEY = "ui.actionLabelThreshold.collections";
    private static final String ARTIST_ACTION_LABEL_THRESHOLD_KEY = "ui.actionLabelThreshold.artists";
    private static final String ALBUM_ACTION_LABEL_THRESHOLD_KEY = "ui.actionLabelThreshold.albums";
    private static final String TITLE_ACTION_LABEL_THRESHOLD_KEY = "ui.actionLabelThreshold.titles";
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
        if (request.statusBarLocation() != null && !request.statusBarLocation().isBlank()) {
            saveOrDelete(
                    STATUS_BAR_LOCATION_KEY,
                    normalizeStatusBarLocation(request.statusBarLocation(), "top"),
                    defaultStatusBarLocation());
        }
        if (request.actionLabelThresholds() != null) {
            updateActionLabelThresholds(request.actionLabelThresholds());
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
        preferences.delete(STATUS_BAR_LOCATION_KEY);
        preferences.delete(COLLECTION_ACTION_LABEL_THRESHOLD_KEY);
        preferences.delete(ARTIST_ACTION_LABEL_THRESHOLD_KEY);
        preferences.delete(ALBUM_ACTION_LABEL_THRESHOLD_KEY);
        preferences.delete(TITLE_ACTION_LABEL_THRESHOLD_KEY);
        return effectiveUiSettings();
    }

    private UiSettings effectiveUiSettings() {
        int defaultStatusVisible = defaultStatusVisible();
        int defaultScanPoll = defaultScanPoll();
        boolean defaultCollectionSpinner = config.ui().defaultCollectionScanSpinnerEnabled();
        boolean defaultArtistSpinner = config.ui().defaultArtistScanSpinnerEnabled();
        boolean defaultProgress = config.ui().defaultCollectionScanProgressEnabled();
        String defaultDateFormat = config.ui().defaultStatusHistoryDateFormat();
        String defaultReleaseDateDisplayFormat = config.release().date().display().format();
        String defaultStatusBarLocation = defaultStatusBarLocation();
        UiSettings.ActionLabelThresholds defaultActionLabelThresholds = defaultActionLabelThresholds();
        UiSettings.ActionLabelThresholds minActionLabelThresholds = minActionLabelThresholds();
        int actionLabelThresholdMax = actionLabelThresholdMax(minActionLabelThresholds);
        int actionLabelThresholdStep = actionLabelThresholdStep();
        int tableGridColumnMinWidth = Math.max(1, config.ui().tableGridColumnMinWidth());

        var statusVisible = preferences.find(STATUS_VISIBLE_KEY);
        var scanPoll = preferences.find(SCAN_POLL_KEY);
        var collectionSpinner = preferences.find(COLLECTION_SPINNER_KEY);
        var artistSpinner = preferences.find(ARTIST_SPINNER_KEY);
        var progress = preferences.find(COLLECTION_PROGRESS_KEY);
        var statusBarLocation = preferences.find(STATUS_BAR_LOCATION_KEY);
        var collectionThreshold = preferences.find(COLLECTION_ACTION_LABEL_THRESHOLD_KEY);
        var artistThreshold = preferences.find(ARTIST_ACTION_LABEL_THRESHOLD_KEY);
        var albumThreshold = preferences.find(ALBUM_ACTION_LABEL_THRESHOLD_KEY);
        var titleThreshold = preferences.find(TITLE_ACTION_LABEL_THRESHOLD_KEY);
        var effectiveActionLabelThresholds = new UiSettings.ActionLabelThresholds(
                collectionThreshold
                        .map(value -> parseInt(
                                value.value(),
                                defaultActionLabelThresholds.collections(),
                                minActionLabelThresholds.collections(),
                                actionLabelThresholdMax))
                        .orElse(defaultActionLabelThresholds.collections()),
                artistThreshold
                        .map(value -> parseInt(
                                value.value(),
                                defaultActionLabelThresholds.artists(),
                                minActionLabelThresholds.artists(),
                                actionLabelThresholdMax))
                        .orElse(defaultActionLabelThresholds.artists()),
                albumThreshold
                        .map(value -> parseInt(
                                value.value(),
                                defaultActionLabelThresholds.albums(),
                                minActionLabelThresholds.albums(),
                                actionLabelThresholdMax))
                        .orElse(defaultActionLabelThresholds.albums()),
                titleThreshold
                        .map(value -> parseInt(
                                value.value(),
                                defaultActionLabelThresholds.titles(),
                                minActionLabelThresholds.titles(),
                                actionLabelThresholdMax))
                        .orElse(defaultActionLabelThresholds.titles()));

        return new UiSettings(
                statusVisible.map(value -> parseInt(value.value(), defaultStatusVisible, STATUS_VISIBLE_MIN, STATUS_VISIBLE_MAX))
                        .orElse(defaultStatusVisible),
                scanPoll.map(value -> parseInt(value.value(), defaultScanPoll, SCAN_POLL_MIN, SCAN_POLL_MAX))
                        .orElse(defaultScanPoll),
                collectionSpinner.map(value -> Boolean.parseBoolean(value.value())).orElse(defaultCollectionSpinner),
                artistSpinner.map(value -> Boolean.parseBoolean(value.value())).orElse(defaultArtistSpinner),
                progress.map(value -> Boolean.parseBoolean(value.value())).orElse(defaultProgress),
                defaultDateFormat,
                defaultReleaseDateDisplayFormat,
                statusBarLocation.map(value -> normalizeStatusBarLocation(value.value(), defaultStatusBarLocation))
                        .orElse(defaultStatusBarLocation),
                effectiveActionLabelThresholds,
                defaultWorkspaceColumnWidths(),
                tableGridColumnMinWidth,
                new UiSettings.ActionLabelThresholdConstraints(
                        minActionLabelThresholds,
                        actionLabelThresholdMax,
                        actionLabelThresholdStep),
                new UiSettings.Values(
                        defaultStatusVisible,
                        defaultScanPoll,
                        defaultCollectionSpinner,
                        defaultArtistSpinner,
                        defaultProgress,
                        defaultDateFormat,
                        defaultReleaseDateDisplayFormat,
                        defaultStatusBarLocation,
                        defaultActionLabelThresholds,
                        defaultWorkspaceColumnWidths(),
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
                        false,
                        false,
                        statusBarLocation
                                .map(value -> !normalizeStatusBarLocation(value.value(), defaultStatusBarLocation)
                                        .equals(defaultStatusBarLocation))
                                .orElse(false),
                        collectionThreshold
                                .map(value -> parseInt(
                                        value.value(),
                                        defaultActionLabelThresholds.collections(),
                                        minActionLabelThresholds.collections(),
                                        actionLabelThresholdMax) != defaultActionLabelThresholds.collections())
                                .orElse(false),
                        artistThreshold
                                .map(value -> parseInt(
                                        value.value(),
                                        defaultActionLabelThresholds.artists(),
                                        minActionLabelThresholds.artists(),
                                        actionLabelThresholdMax) != defaultActionLabelThresholds.artists())
                                .orElse(false),
                        albumThreshold
                                .map(value -> parseInt(
                                        value.value(),
                                        defaultActionLabelThresholds.albums(),
                                        minActionLabelThresholds.albums(),
                                        actionLabelThresholdMax) != defaultActionLabelThresholds.albums())
                                .orElse(false),
                        titleThreshold
                                .map(value -> parseInt(
                                        value.value(),
                                        defaultActionLabelThresholds.titles(),
                                        minActionLabelThresholds.titles(),
                                        actionLabelThresholdMax) != defaultActionLabelThresholds.titles())
                                .orElse(false)));
    }

    private void updateActionLabelThresholds(ActionLabelThresholdRequest request) {
        UiSettings.ActionLabelThresholds defaults = defaultActionLabelThresholds();
        UiSettings.ActionLabelThresholds min = minActionLabelThresholds();
        int max = actionLabelThresholdMax(min);
        if (request.collections() != null) {
            int value = clamp(request.collections(), min.collections(), max);
            saveOrDelete(COLLECTION_ACTION_LABEL_THRESHOLD_KEY, String.valueOf(value),
                    String.valueOf(defaults.collections()));
        }
        if (request.artists() != null) {
            int value = clamp(request.artists(), min.artists(), max);
            saveOrDelete(ARTIST_ACTION_LABEL_THRESHOLD_KEY, String.valueOf(value),
                    String.valueOf(defaults.artists()));
        }
        if (request.albums() != null) {
            int value = clamp(request.albums(), min.albums(), max);
            saveOrDelete(ALBUM_ACTION_LABEL_THRESHOLD_KEY, String.valueOf(value),
                    String.valueOf(defaults.albums()));
        }
        if (request.titles() != null) {
            int value = clamp(request.titles(), min.titles(), max);
            saveOrDelete(TITLE_ACTION_LABEL_THRESHOLD_KEY, String.valueOf(value),
                    String.valueOf(defaults.titles()));
        }
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

    private UiSettings.ActionLabelThresholds defaultActionLabelThresholds() {
        UiSettings.ActionLabelThresholds min = minActionLabelThresholds();
        int max = actionLabelThresholdMax(min);
        var defaults = config.ui().defaultActionLabelThresholds();
        return new UiSettings.ActionLabelThresholds(
                clamp(defaults.collections(), min.collections(), max),
                clamp(defaults.artists(), min.artists(), max),
                clamp(defaults.albums(), min.albums(), max),
                clamp(defaults.titles(), min.titles(), max));
    }

    private UiSettings.ActionLabelThresholds minActionLabelThresholds() {
        var min = config.ui().actionLabelThresholdMin();
        return new UiSettings.ActionLabelThresholds(
                Math.max(0, min.collections()),
                Math.max(0, min.artists()),
                Math.max(0, min.albums()),
                Math.max(0, min.titles()));
    }

    private int actionLabelThresholdMax(UiSettings.ActionLabelThresholds min) {
        int largestMin = Math.max(Math.max(min.collections(), min.artists()), Math.max(min.albums(), min.titles()));
        return Math.max(largestMin, config.ui().actionLabelThresholdMax());
    }

    private int actionLabelThresholdStep() {
        return Math.max(1, config.ui().actionLabelThresholdStep());
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
            Boolean artistScanSpinnerEnabled,
            Boolean collectionScanProgressEnabled,
            String statusBarLocation,
            ActionLabelThresholdRequest actionLabelThresholds) {
    }

    public record ActionLabelThresholdRequest(
            Integer collections,
            Integer artists,
            Integer albums,
            Integer titles) {
    }
}
