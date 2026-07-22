package org.kroky.musiclib.scan;

import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.kroky.musiclib.db.Names;
import org.kroky.musiclib.db.TitleSortNames;
import org.kroky.musiclib.model.ParsedAlbum;
import org.kroky.musiclib.model.ReleaseYears;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class FolderNameParser {

    private static final String RELEASE_YEAR = "\\d{4}";
    private static final Pattern ARTIST_YEAR_ALBUM =
            Pattern.compile("^(.+?)\\s+-\\s+(" + RELEASE_YEAR + ")\\s+-\\s+(.+)$");
    private static final Pattern YEAR_ALBUM =
            Pattern.compile("^(" + RELEASE_YEAR + ")\\s+-\\s+(.+)$");
    private static final Pattern TITLE_FINAL_ARTIST_YEAR =
            Pattern.compile("^(.+)\\s+\\(([^()]+),\\s*(" + RELEASE_YEAR + ")\\)$");
    private static final Pattern TITLE_FINAL_YEAR =
            Pattern.compile("^(.+)\\s+\\((" + RELEASE_YEAR + ")\\)$");
    private static final Pattern TITLE_DASH_YEAR_SUBTITLE =
            Pattern.compile("^(.+?)\\s+-\\s+(" + RELEASE_YEAR + ")\\s+-\\s+(.+)$");
    private static final Pattern TITLE_DASH_YEAR =
            Pattern.compile("^(.+?)\\s+-\\s+(" + RELEASE_YEAR + ")$");

    public Optional<ParsedAlbum> parseFlatArtistAlbum(Path folder, String collectionId) {
        String name = folder.getFileName().toString().trim();
        return parseArtistYearAlbum(name, folder, collectionId);
    }

    public Optional<ParsedAlbum> parseNestedArtistAlbum(Path artistFolder, Path albumFolder, String collectionId) {
        String name = albumFolder.getFileName().toString().trim();
        Matcher matcher = YEAR_ALBUM.matcher(name);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String title = clean(matcher.group(2));
        Integer releaseYear = ReleaseYears.parse(matcher.group(1));
        return Optional.of(new ParsedAlbum(
                Names.chicagoStyle(clean(artistFolder.getFileName().toString())),
                title,
                releaseYear,
                TitleSortNames.create(title, releaseYear),
                albumFolder,
                collectionId));
    }

    public ParsedAlbum parseTitleAlbum(Path folder, String collectionId) {
        String name = clean(folder.getFileName().toString());

        Matcher artistYear = TITLE_FINAL_ARTIST_YEAR.matcher(name);
        if (artistYear.matches()) {
            String title = clean(artistYear.group(1));
            Integer releaseYear = ReleaseYears.parse(clean(artistYear.group(3)));
            return new ParsedAlbum(
                    Names.chicagoStyle(clean(artistYear.group(2))),
                    title,
                    releaseYear,
                    TitleSortNames.create(title, releaseYear),
                    folder,
                    collectionId);
        }

        Matcher finalYear = TITLE_FINAL_YEAR.matcher(name);
        if (finalYear.matches()) {
            String title = clean(finalYear.group(1));
            Integer releaseYear = ReleaseYears.parse(clean(finalYear.group(2)));
            return new ParsedAlbum(
                    null,
                    title,
                    releaseYear,
                    TitleSortNames.create(title, releaseYear),
                    folder,
                    collectionId);
        }

        Matcher dashYearSubtitle = TITLE_DASH_YEAR_SUBTITLE.matcher(name);
        if (dashYearSubtitle.matches()) {
            String baseTitle = clean(dashYearSubtitle.group(1));
            Integer releaseYear = ReleaseYears.parse(clean(dashYearSubtitle.group(2)));
            String subtitle = clean(dashYearSubtitle.group(3));
            String title = baseTitle + " - " + subtitle;
            return new ParsedAlbum(
                    null,
                    title,
                    releaseYear,
                    TitleSortNames.create(baseTitle, releaseYear, subtitle),
                    folder,
                    collectionId);
        }

        Matcher dashYear = TITLE_DASH_YEAR.matcher(name);
        if (dashYear.matches()) {
            String title = clean(dashYear.group(1));
            Integer releaseYear = ReleaseYears.parse(clean(dashYear.group(2)));
            return new ParsedAlbum(
                    null,
                    title,
                    releaseYear,
                    TitleSortNames.create(title, releaseYear),
                    folder,
                    collectionId);
        }

        return new ParsedAlbum(
                null,
                name,
                null,
                TitleSortNames.create(name, (Integer) null),
                folder,
                collectionId);
    }

    private Optional<ParsedAlbum> parseArtistYearAlbum(String name, Path folder, String collectionId) {
        Matcher matcher = ARTIST_YEAR_ALBUM.matcher(name);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String title = clean(matcher.group(3));
        Integer releaseYear = ReleaseYears.parse(matcher.group(2));
        return Optional.of(new ParsedAlbum(
                Names.chicagoStyle(clean(matcher.group(1))),
                title,
                releaseYear,
                TitleSortNames.create(title, releaseYear),
                folder,
                collectionId));
    }

    private static String clean(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

}
