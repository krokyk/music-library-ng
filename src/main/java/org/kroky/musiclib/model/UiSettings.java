package org.kroky.musiclib.model;

public record UiSettings(
        int statusCompleteVisibleMs,
        int scanPollIntervalMs,
        boolean collectionScanSpinnerEnabled,
        boolean collectionScanProgressEnabled,
        String statusHistoryDateFormat,
        String statusBarLocation,
        WorkspaceColumnWidths workspaceColumnDefaults,
        Values defaults,
        Overrides overrides) {

    public record Values(
            int statusCompleteVisibleMs,
            int scanPollIntervalMs,
            boolean collectionScanSpinnerEnabled,
            boolean collectionScanProgressEnabled,
            String statusHistoryDateFormat,
            String statusBarLocation,
            WorkspaceColumnWidths workspaceColumnDefaults) {
    }

    public record Overrides(
            boolean statusCompleteVisibleMs,
            boolean scanPollIntervalMs,
            boolean collectionScanSpinnerEnabled,
            boolean collectionScanProgressEnabled,
            boolean statusHistoryDateFormat,
            boolean statusBarLocation) {
    }

    public record WorkspaceColumnWidths(
            ArtistColumns artist,
            AlbumColumns album,
            TitleColumns title) {
    }

    public record ArtistColumns(
            int name,
            int actions) {
    }

    public record AlbumColumns(
            int name,
            int year,
            int checked,
            int actions) {
    }

    public record TitleColumns(
            int title,
            int artist,
            int year,
            int status,
            int actions) {
    }
}
