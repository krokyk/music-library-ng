package org.kroky.musiclib.scan;

import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.kroky.musiclib.db.Names;
import org.kroky.musiclib.db.TitleSortNames;
import org.kroky.musiclib.model.ParsedAlbum;
import org.kroky.musiclib.model.ParserType;
import org.kroky.musiclib.model.ReleaseDates;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class FolderNameParser {

    private static final String RELEASE_DATE = "\\d{4}(?:-\\d{2}(?:-\\d{2})?)?";
    private static final Pattern ARTIST_YEAR_ALBUM =
            Pattern.compile("^(.+?)\\s+-\\s+(" + RELEASE_DATE + ")\\s+-\\s+(.+)$");
    private static final Pattern TITLE_FINAL_ARTIST_YEAR =
            Pattern.compile("^(.+)\\s+\\(([^()]+),\\s*(" + RELEASE_DATE + ")\\)$");
    private static final Pattern TITLE_FINAL_YEAR =
            Pattern.compile("^(.+)\\s+\\((" + RELEASE_DATE + ")\\)$");
    private static final Pattern TITLE_DASH_YEAR_SUBTITLE =
            Pattern.compile("^(.+?)\\s+-\\s+(" + RELEASE_DATE + ")\\s+-\\s+(.+)$");
    private static final Pattern TITLE_DASH_YEAR =
            Pattern.compile("^(.+?)\\s+-\\s+(" + RELEASE_DATE + ")$");

    public Optional<ParsedAlbum> parse(Path folder, ParserType parserType, String collectionId) {
        String name = folder.getFileName().toString().trim();
        return switch (parserType) {
            case FLAT_ARTIST_YEAR_ALBUM -> parseArtistYearAlbum(name, folder, collectionId);
            case NESTED_ARTIST_ALBUM, TITLE_PIPELINE -> Optional.empty();
        };
    }

    public ParsedAlbum parseTitleAlbum(Path folder, String collectionId) {
        String name = clean(folder.getFileName().toString());

        Matcher artistYear = TITLE_FINAL_ARTIST_YEAR.matcher(name);
        if (artistYear.matches()) {
            String title = clean(artistYear.group(1));
            String releaseDate = clean(artistYear.group(3));
            return new ParsedAlbum(
                    Names.chicagoStyle(clean(artistYear.group(2))),
                    title,
                    releaseDate,
                    TitleSortNames.create(title, releaseDate),
                    folder,
                    collectionId);
        }

        Matcher finalYear = TITLE_FINAL_YEAR.matcher(name);
        if (finalYear.matches()) {
            String title = clean(finalYear.group(1));
            String releaseDate = clean(finalYear.group(2));
            return new ParsedAlbum(
                    null,
                    title,
                    releaseDate,
                    TitleSortNames.create(title, releaseDate),
                    folder,
                    collectionId);
        }

        Matcher dashYearSubtitle = TITLE_DASH_YEAR_SUBTITLE.matcher(name);
        if (dashYearSubtitle.matches()) {
            String baseTitle = clean(dashYearSubtitle.group(1));
            String releaseDate = clean(dashYearSubtitle.group(2));
            String subtitle = clean(dashYearSubtitle.group(3));
            String title = baseTitle + " - " + subtitle;
            return new ParsedAlbum(
                    null,
                    title,
                    releaseDate,
                    TitleSortNames.create(baseTitle, releaseDate, subtitle),
                    folder,
                    collectionId);
        }

        Matcher dashYear = TITLE_DASH_YEAR.matcher(name);
        if (dashYear.matches()) {
            String title = clean(dashYear.group(1));
            String releaseDate = clean(dashYear.group(2));
            return new ParsedAlbum(
                    null,
                    title,
                    releaseDate,
                    TitleSortNames.create(title, releaseDate),
                    folder,
                    collectionId);
        }

        return new ParsedAlbum(
                null,
                name,
                null,
                TitleSortNames.create(name, null),
                folder,
                collectionId);
    }

    private Optional<ParsedAlbum> parseArtistYearAlbum(String name, Path folder, String collectionId) {
        Matcher matcher = ARTIST_YEAR_ALBUM.matcher(name);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new ParsedAlbum(
                Names.chicagoStyle(clean(matcher.group(1))),
                clean(matcher.group(3)),
                ReleaseDates.normalize(matcher.group(2)),
                TitleSortNames.create(clean(matcher.group(3)), ReleaseDates.normalize(matcher.group(2))),
                folder,
                collectionId));
    }

    private static String clean(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

}
