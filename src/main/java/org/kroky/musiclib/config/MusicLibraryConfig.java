package org.kroky.musiclib.config;

import java.util.List;
import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "music-library")
public interface MusicLibraryConfig {

    @WithName("db.path")
    String dbPath();

    @WithName("backup.directory")
    String backupDirectory();

    @WithName("music-root")
    Optional<String> musicRoot();

    @WithName("root-detection")
    RootDetection rootDetection();

    @WithName("ui")
    Ui ui();

    interface RootDetection {
        List<String> candidates();

        List<String> markers();
    }

    interface Ui {
        @WithName("default-status-complete-visible-ms")
        @WithDefault("4000")
        int defaultStatusCompleteVisibleMs();

        @WithName("default-scan-poll-interval-ms")
        @WithDefault("100")
        int defaultScanPollIntervalMs();

        @WithName("default-collection-scan-spinner-enabled")
        @WithDefault("true")
        boolean defaultCollectionScanSpinnerEnabled();

        @WithName("default-collection-scan-progress-enabled")
        @WithDefault("true")
        boolean defaultCollectionScanProgressEnabled();
    }
}
