package org.kroky.musiclib.model;

public record UiSettings(
        int statusCompleteVisibleMs,
        int scanPollIntervalMs,
        boolean collectionScanSpinnerEnabled,
        boolean artistScanSpinnerEnabled,
        boolean collectionScanProgressEnabled,
        String statusHistoryDateFormat,
        String releaseDateDisplayFormat,
        String statusBarLocation,
        ActionLabelThresholds actionLabelThresholds,
        WorkspaceColumnWidths workspaceColumnDefaults,
        ArtistsScreenColumnWidths artistsScreenColumnDefaults,
        int tableGridColumnMinWidth,
        ActionLabelThresholdConstraints actionLabelThresholdConstraints,
        Values defaults,
        Overrides overrides) {

    public record Values(
            int statusCompleteVisibleMs,
            int scanPollIntervalMs,
            boolean collectionScanSpinnerEnabled,
            boolean artistScanSpinnerEnabled,
            boolean collectionScanProgressEnabled,
            String statusHistoryDateFormat,
            String releaseDateDisplayFormat,
            String statusBarLocation,
            ActionLabelThresholds actionLabelThresholds,
            WorkspaceColumnWidths workspaceColumnDefaults,
            ArtistsScreenColumnWidths artistsScreenColumnDefaults,
            int tableGridColumnMinWidth) {
    }

    public record Overrides(
            boolean statusCompleteVisibleMs,
            boolean scanPollIntervalMs,
            boolean collectionScanSpinnerEnabled,
            boolean artistScanSpinnerEnabled,
            boolean collectionScanProgressEnabled,
            boolean statusHistoryDateFormat,
            boolean releaseDateDisplayFormat,
            boolean statusBarLocation,
            boolean collectionActionLabelThreshold,
            boolean artistActionLabelThreshold,
            boolean albumActionLabelThreshold,
            boolean titleActionLabelThreshold) {
    }

    public record ActionLabelThresholds(
            int collections,
            int artists,
            int albums,
            int titles) {
    }

    public record ActionLabelThresholdConstraints(
            ActionLabelThresholds min,
            int max,
            int step) {
    }

    public record WorkspaceColumnWidths(
            ArtistColumns artist,
            AlbumColumns album,
            TitleColumns title) {
    }

    public record ArtistColumns(
            int name) {
    }

    public record AlbumColumns(
            int name,
            int releaseDate,
            int checked,
            int collections,
            int action) {
    }

    public record TitleColumns(
            int title,
            int artist,
            int releaseDate,
            int action) {
    }

    public record ArtistsScreenColumnWidths(
            int name,
            int country,
            int type,
            int status,
            int albums,
            int unchecked,
            int local,
            int provider,
            int action) {
    }
}
