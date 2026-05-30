package org.kroky.musiclib.model;

public record UiSettings(
        int statusCompleteVisibleMs,
        int scanPollIntervalMs,
        boolean collectionScanSpinnerEnabled,
        boolean collectionScanProgressEnabled,
        Values defaults,
        Overrides overrides) {

    public record Values(
            int statusCompleteVisibleMs,
            int scanPollIntervalMs,
            boolean collectionScanSpinnerEnabled,
            boolean collectionScanProgressEnabled) {
    }

    public record Overrides(
            boolean statusCompleteVisibleMs,
            boolean scanPollIntervalMs,
            boolean collectionScanSpinnerEnabled,
            boolean collectionScanProgressEnabled) {
    }
}
