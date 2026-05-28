package org.kroky.musiclib.scan;

import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.kroky.musiclib.db.Names;
import org.kroky.musiclib.model.ParsedAlbum;
import org.kroky.musiclib.model.ParserType;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class FolderNameParser {

    private static final Pattern ARTIST_YEAR_ALBUM =
            Pattern.compile("^(.+?)\\s+-\\s+(\\d{4})\\s+-\\s+(.+)$");
    private static final Pattern TITLE_ARTIST_YEAR =
            Pattern.compile("^(.+?)\\s*\\((.+),\\s*(\\d{4})\\)$");

    public Optional<ParsedAlbum> parse(Path folder, ParserType parserType, String collectionId) {
        String name = folder.getFileName().toString().trim();
        return switch (parserType) {
            case ARTIST_YEAR_ALBUM -> parseArtistYearAlbum(name, folder, collectionId);
            case TITLE_ARTIST_YEAR -> parseTitleArtistYear(name, folder, collectionId);
        };
    }

    private Optional<ParsedAlbum> parseArtistYearAlbum(String name, Path folder, String collectionId) {
        Matcher matcher = ARTIST_YEAR_ALBUM.matcher(name);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new ParsedAlbum(
                Names.chicagoStyle(clean(matcher.group(1))),
                clean(matcher.group(3)),
                Integer.parseInt(matcher.group(2)),
                folder,
                collectionId));
    }

    private Optional<ParsedAlbum> parseTitleArtistYear(String name, Path folder, String collectionId) {
        Matcher matcher = TITLE_ARTIST_YEAR.matcher(name);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new ParsedAlbum(
                Names.chicagoStyle(clean(matcher.group(2))),
                clean(matcher.group(1)),
                Integer.parseInt(matcher.group(3)),
                folder,
                collectionId));
    }

    private static String clean(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }
}
