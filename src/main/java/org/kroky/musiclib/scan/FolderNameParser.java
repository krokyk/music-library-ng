package org.kroky.musiclib.scan;

import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.kroky.musiclib.db.Names;
import org.kroky.musiclib.model.ParseStatus;
import org.kroky.musiclib.model.ParsedAlbum;
import org.kroky.musiclib.model.ParsedTitleItem;
import org.kroky.musiclib.model.ParserType;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class FolderNameParser {

    private static final Pattern ARTIST_YEAR_ALBUM =
            Pattern.compile("^(.+?)\\s+-\\s+(\\d{4})\\s+-\\s+(.+)$");
    private static final Pattern TITLE_FINAL_ARTIST_YEAR =
            Pattern.compile("^(.+)\\s+\\(([^()]+),\\s*(\\d{4})\\)$");
    private static final Pattern TITLE_FINAL_YEAR =
            Pattern.compile("^(.+)\\s+\\((\\d{4})\\)$");
    private static final Pattern TITLE_DASH_YEAR_SUBTITLE =
            Pattern.compile("^(.+?)\\s+-\\s+(\\d{4})\\s+-\\s+(.+)$");
    private static final Pattern TITLE_DASH_YEAR =
            Pattern.compile("^(.+?)\\s+-\\s+(\\d{4})$");

    public Optional<ParsedAlbum> parse(Path folder, ParserType parserType, String collectionId) {
        String name = folder.getFileName().toString().trim();
        return switch (parserType) {
            case FLAT_ARTIST_YEAR_ALBUM -> parseArtistYearAlbum(name, folder, collectionId);
            case NESTED_ARTIST_ALBUM, TITLE_PIPELINE -> Optional.empty();
        };
    }

    public ParsedTitleItem parseTitleItem(Path folder, String collectionId) {
        String name = clean(folder.getFileName().toString());

        Matcher artistYear = TITLE_FINAL_ARTIST_YEAR.matcher(name);
        if (artistYear.matches()) {
            return new ParsedTitleItem(
                    name,
                    clean(artistYear.group(1)),
                    Names.chicagoStyle(clean(artistYear.group(2))),
                    Integer.parseInt(artistYear.group(3)),
                    folder,
                    collectionId,
                    ParseStatus.EXACT);
        }

        Matcher finalYear = TITLE_FINAL_YEAR.matcher(name);
        if (finalYear.matches()) {
            return new ParsedTitleItem(
                    name,
                    clean(finalYear.group(1)),
                    null,
                    Integer.parseInt(finalYear.group(2)),
                    folder,
                    collectionId,
                    ParseStatus.PARTIAL);
        }

        Matcher dashYearSubtitle = TITLE_DASH_YEAR_SUBTITLE.matcher(name);
        if (dashYearSubtitle.matches()) {
            return new ParsedTitleItem(
                    name,
                    clean(dashYearSubtitle.group(1)) + " - " + clean(dashYearSubtitle.group(3)),
                    null,
                    Integer.parseInt(dashYearSubtitle.group(2)),
                    folder,
                    collectionId,
                    ParseStatus.PARTIAL);
        }

        Matcher dashYear = TITLE_DASH_YEAR.matcher(name);
        if (dashYear.matches()) {
            return new ParsedTitleItem(
                    name,
                    clean(dashYear.group(1)),
                    null,
                    Integer.parseInt(dashYear.group(2)),
                    folder,
                    collectionId,
                    ParseStatus.PARTIAL);
        }

        return new ParsedTitleItem(
                name,
                name,
                null,
                null,
                folder,
                collectionId,
                ParseStatus.TITLE_ONLY);
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

    private static String clean(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }
}
