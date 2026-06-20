package org.kroky.musiclib.config;

import java.util.regex.Pattern;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class MusicBrainzConfigValidator {

    private static final Pattern USER_AGENT = Pattern.compile(
            "^music-library-ng \\([A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@"
                    + "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+\\)$");

    @Inject
    MusicLibraryConfig config;

    void validate(@Observes StartupEvent event) {
        String userAgent = config.providers().musicbrainz().userAgent().orElse("");
        if (!isValidUserAgent(userAgent)) {
            throw new IllegalStateException(
                    "music-library.providers.musicbrainz.user-agent is required and must match "
                            + "'music-library-ng (<email>)'. Put it in ./config/application.properties "
                            + "or another external Quarkus config source.");
        }
    }

    static boolean isValidUserAgent(String value) {
        return value != null && USER_AGENT.matcher(value).matches();
    }
}
