package org.kroky.musiclib.model;

public record UiSettings(
        int statusCompleteVisibleMs,
        int scanPollIntervalMs,
        boolean artistScanSpinnerEnabled,
        int providerBatchRescanDelayMinutes,
        String statusHistoryDateFormat,
        String releaseDateDisplayFormat,
        String statusBarLocation,
        WorkspaceColumnWidths workspaceColumnDefaults,
        ArtistsScreenColumnWidths artistsScreenColumnDefaults,
        int tableGridColumnMinWidth,
        Values defaults,
        Overrides overrides) {

    public record Values(
            int statusCompleteVisibleMs,
            int scanPollIntervalMs,
            boolean artistScanSpinnerEnabled,
            int providerBatchRescanDelayMinutes,
            String statusHistoryDateFormat,
            String releaseDateDisplayFormat,
            String statusBarLocation,
            WorkspaceColumnWidths workspaceColumnDefaults,
            ArtistsScreenColumnWidths artistsScreenColumnDefaults,
            int tableGridColumnMinWidth) {
    }

    public record Overrides(
            boolean statusCompleteVisibleMs,
            boolean scanPollIntervalMs,
            boolean artistScanSpinnerEnabled,
            boolean providerBatchRescanDelayMinutes,
            boolean statusHistoryDateFormat,
            boolean releaseDateDisplayFormat,
            boolean statusBarLocation) {
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
            int status,
            int albums,
            int unchecked,
            int local,
            int provider,
            int action) {
    }
}
