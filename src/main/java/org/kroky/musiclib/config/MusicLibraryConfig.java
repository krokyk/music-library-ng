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

    Scan scan();

    @WithName("root-detection")
    RootDetection rootDetection();

    @WithName("collections")
    List<MusicCollectionConfig> collections();

    interface Scan {
        @WithName("default-status")
        @WithDefault("CHECKED")
        String defaultStatus();
    }

    interface RootDetection {
        List<String> candidates();

        List<String> markers();
    }

    interface MusicCollectionConfig {
        String id();

        String name();

        @WithName("relative-path")
        String relativePath();

        String parser();

        @WithDefault("true")
        boolean enabled();
    }
}
