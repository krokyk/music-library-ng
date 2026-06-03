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
        @WithDefault("10000")
        int defaultStatusCompleteVisibleMs();

        @WithName("default-scan-poll-interval-ms")
        @WithDefault("200")
        int defaultScanPollIntervalMs();

        @WithName("default-collection-scan-spinner-enabled")
        @WithDefault("true")
        boolean defaultCollectionScanSpinnerEnabled();

        @WithName("default-collection-scan-progress-enabled")
        @WithDefault("true")
        boolean defaultCollectionScanProgressEnabled();

        @WithName("default-status-history-date-format")
        @WithDefault("yyyy-MM-dd HH:mm:ss.SSS")
        String defaultStatusHistoryDateFormat();

        @WithName("default-status-bar-location")
        @WithDefault("top")
        String defaultStatusBarLocation();

        @WithName("default-workspace-column-widths")
        WorkspaceColumnWidths defaultWorkspaceColumnWidths();
    }

    interface WorkspaceColumnWidths {
        ArtistColumns artist();

        AlbumColumns album();

        TitleColumns title();
    }

    interface ArtistColumns {
        @WithDefault("280")
        int name();
    }

    interface AlbumColumns {
        @WithDefault("360")
        int name();

        @WithDefault("100")
        int year();

        @WithDefault("120")
        int checked();
    }

    interface TitleColumns {
        @WithDefault("460")
        int title();

        @WithDefault("220")
        int artist();

        @WithDefault("90")
        int year();

        @WithDefault("120")
        int status();
    }
}
