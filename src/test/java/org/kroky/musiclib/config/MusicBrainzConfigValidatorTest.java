package org.kroky.musiclib.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MusicBrainzConfigValidatorTest {

    @Test
    void acceptsRequiredMusicLibraryUserAgentWithEmail() {
        assertTrue(MusicBrainzConfigValidator.isValidUserAgent("music-library-ng (person@example.com)"));
        assertTrue(MusicBrainzConfigValidator.isValidUserAgent("music-library-ng (person.name+tag@example.co.uk)"));
    }

    @Test
    void rejectsMissingOrUnexpectedUserAgents() {
        assertFalse(MusicBrainzConfigValidator.isValidUserAgent(null));
        assertFalse(MusicBrainzConfigValidator.isValidUserAgent(""));
        assertFalse(MusicBrainzConfigValidator.isValidUserAgent("music-library-ng/dev ( https://github.com/krokyk/music-library-ng )"));
        assertFalse(MusicBrainzConfigValidator.isValidUserAgent("other-app (person@example.com)"));
    }

    @Test
    void rejectsInvalidEmailShapes() {
        assertFalse(MusicBrainzConfigValidator.isValidUserAgent("music-library-ng (person)"));
        assertFalse(MusicBrainzConfigValidator.isValidUserAgent("music-library-ng (person@example)"));
        assertFalse(MusicBrainzConfigValidator.isValidUserAgent("music-library-ng (person@.example.com)"));
        assertFalse(MusicBrainzConfigValidator.isValidUserAgent("music-library-ng (person@example.com extra)"));
    }
}
