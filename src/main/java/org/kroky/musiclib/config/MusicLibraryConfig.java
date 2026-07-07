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

    @WithName("report.directory")
    @WithDefault("data/reports")
    String reportDirectory();

    @WithName("music-root")
    Optional<String> musicRoot();

    @WithName("root-detection")
    RootDetection rootDetection();

    @WithName("ui")
    Ui ui();

    Providers providers();

    Release release();

    interface Release {
        Date date();
    }

    interface Date {
        Display display();
    }

    interface Display {
        @WithDefault("yyyy-MM-dd")
        String format();
    }

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

        @WithName("default-artist-scan-spinner-enabled")
        @WithDefault("true")
        boolean defaultArtistScanSpinnerEnabled();

        @WithName("default-status-history-date-format")
        @WithDefault("yyyy-MM-dd HH:mm:ss.SSS")
        String defaultStatusHistoryDateFormat();

        @WithName("default-status-bar-location")
        @WithDefault("top")
        String defaultStatusBarLocation();

        @WithName("table-grid-column-min-width")
        @WithDefault("40")
        int tableGridColumnMinWidth();

        @WithName("default-workspace-column-widths")
        WorkspaceColumnWidths defaultWorkspaceColumnWidths();

        @WithName("default-artists-screen-column-widths")
        ArtistsScreenColumnWidths defaultArtistsScreenColumnWidths();
    }

    interface Providers {
        @WithName("default-batch-rescan-delay-minutes")
        @WithDefault("60")
        int defaultBatchRescanDelayMinutes();

        MusicBrainz musicbrainz();
    }

    interface MusicBrainz {
        @WithName("base-url")
        @WithDefault("https://musicbrainz.org/ws/2")
        String baseUrl();

        @WithName("site-url")
        @WithDefault("https://musicbrainz.org")
        String siteUrl();

        @WithName("user-agent")
        Optional<String> userAgent();

        @WithName("request-min-interval-ms")
        @WithDefault("1100")
        long requestMinIntervalMs();

        @WithName("search-candidate-limit")
        @WithDefault("5")
        int searchCandidateLimit();

        @WithName("release-group-page-size")
        @WithDefault("100")
        int releaseGroupPageSize();
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
        int releaseDate();

        @WithDefault("120")
        int checked();

        @WithDefault("180")
        int collections();

        @WithDefault("122")
        int action();
    }

    interface TitleColumns {
        @WithDefault("460")
        int title();

        @WithDefault("220")
        int artist();

        @WithDefault("120")
        int releaseDate();

        @WithDefault("178")
        int action();
    }

    interface ArtistsScreenColumnWidths {
        @WithDefault("250")
        int name();

        @WithDefault("190")
        int country();

        @WithDefault("110")
        int status();

        @WithDefault("68")
        int albums();

        @WithDefault("86")
        int unchecked();

        @WithDefault("64")
        int local();

        @WithDefault("110")
        int provider();

        @WithDefault("104")
        int action();
    }
}
