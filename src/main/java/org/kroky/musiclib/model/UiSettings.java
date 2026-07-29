package org.kroky.musiclib.model;

public record UiSettings(
        int statusCompleteVisibleMs,
        int scanPollIntervalMs,
        boolean artistScanSpinnerEnabled,
        int providerBatchRescanDelayMinutes,
        String statusHistoryDateFormat,
        String statusBarLocation,
        WorkspaceColumnWidths workspaceColumnDefaults,
        ArtistsScreenColumnWidths artistsScreenColumnDefaults,
        int tableGridColumnMinWidth,
        Values defaults) {

    public record Values(
            int statusCompleteVisibleMs,
            int scanPollIntervalMs,
            int providerBatchRescanDelayMinutes) {
    }

    public record WorkspaceColumnWidths(
            AlbumColumns album,
            TitleColumns title) {
    }

    public record AlbumColumns(
            int name,
            int releaseYear,
            int checked,
            int home) {
    }

    public record TitleColumns(
            int title,
            int artist,
            int releaseYear) {
    }

    public record ArtistsScreenColumnWidths(
            int name,
            int country,
            int status,
            int albums,
            int unchecked,
            int local,
            int provider) {
    }
}
