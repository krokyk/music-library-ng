package org.kroky.musiclib.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.kroky.musiclib.model.AlbumProviderLink;
import org.kroky.musiclib.model.ProviderReleaseYearConflict;
import org.kroky.musiclib.model.ProviderReleaseYearConflictSource;
import org.kroky.musiclib.model.ProviderTitleConflict;
import org.kroky.musiclib.model.ProviderTitleConflictSource;
import org.kroky.musiclib.model.ReleaseYears;
import org.kroky.musiclib.provider.ProviderTitles;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AlbumProviderLinkRepository {

    @Inject
    DataSource dataSource;

    public List<ProviderReleaseYearConflict> listReleaseYearConflicts() {
        String sql = """
                SELECT apl.id provider_link_id, a.id album_id, ar.id artist_id, ar.name artist_name,
                       a.title album_title, a.release_year local_release_year, a.local_relative_path,
                       apl.provider_title, apl.provider_release_year, apl.provider_id, apl.provider_url
                FROM album_provider_links apl
                JOIN albums a ON a.id = apl.album_id
                JOIN album_artists aa ON aa.album_id = a.id AND aa.position = 0
                JOIN artists ar ON ar.id = aa.artist_id
                WHERE apl.release_year_resolution IS NULL
                  AND a.release_year IS NOT NULL AND apl.provider_release_year IS NOT NULL
                  AND a.release_year <> apl.provider_release_year
                ORDER BY ar.name, a.release_year, a.title, apl.provider_release_year
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            Map<String, YearAccumulator> grouped = new LinkedHashMap<>();
            while (rs.next()) {
                long albumId = rs.getLong("album_id");
                Integer providerYear = nullableInteger(rs, "provider_release_year");
                String key = albumId + ":" + providerYear;
                YearAccumulator conflict = grouped.computeIfAbsent(key, ignored -> new YearAccumulator(
                        albumId, rsLong(rs, "artist_id"), rsString(rs, "artist_name"), rsString(rs, "album_title"),
                        rsInteger(rs, "local_release_year"), rsString(rs, "local_relative_path"), new ArrayList<>()));
                conflict.sources().add(new ProviderReleaseYearConflictSource(
                        rs.getLong("provider_link_id"), rs.getString("provider_id"), rs.getString("provider_title"),
                        providerYear, rs.getString("provider_url")));
            }
            return grouped.values().stream().map(YearAccumulator::toConflict).toList();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list provider release year conflicts", e);
        }
    }

    public List<ProviderTitleConflict> listTitleConflicts() {
        String sql = """
                SELECT apl.id provider_link_id, a.id album_id, ar.id artist_id, ar.name artist_name,
                       a.title album_title, a.release_year local_release_year, a.local_relative_path,
                       apl.provider_title, apl.provider_release_year, apl.provider_id, apl.provider_url
                FROM album_provider_links apl
                JOIN albums a ON a.id = apl.album_id
                JOIN album_artists aa ON aa.album_id = a.id AND aa.position = 0
                JOIN artists ar ON ar.id = aa.artist_id
                WHERE apl.title_resolution IS NULL
                ORDER BY ar.name, a.release_year, a.title, apl.provider_title
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            Map<String, TitleAccumulator> grouped = new LinkedHashMap<>();
            while (rs.next()) {
                String localTitle = rs.getString("album_title");
                String providerTitle = rs.getString("provider_title");
                if (!ProviderTitles.titleConflict(localTitle, providerTitle)) continue;
                long albumId = rs.getLong("album_id");
                String key = albumId + ":" + ProviderTitles.clean(providerTitle).toLowerCase();
                TitleAccumulator conflict = grouped.computeIfAbsent(key, ignored -> new TitleAccumulator(
                        albumId, rsLong(rs, "artist_id"), rsString(rs, "artist_name"), localTitle,
                        rsInteger(rs, "local_release_year"), rsString(rs, "local_relative_path"), new ArrayList<>()));
                conflict.sources().add(new ProviderTitleConflictSource(
                        rs.getLong("provider_link_id"), rs.getString("provider_id"), providerTitle,
                        nullableInteger(rs, "provider_release_year"), rs.getString("provider_url")));
            }
            return grouped.values().stream().map(TitleAccumulator::toConflict).toList();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list provider title conflicts", e);
        }
    }

    public Optional<AlbumProviderLink> find(long id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT id,album_id,provider_id,provider_release_group_id,provider_title,provider_release_year,
                               provider_url,release_year_resolution,title_resolution,created_at,updated_at
                        FROM album_provider_links WHERE id=?
                        """)) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find album provider link " + id, e);
        }
    }

    public Optional<Long> findAlbumId(String providerId, String providerReleaseGroupId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT album_id FROM album_provider_links WHERE provider_id=? AND provider_release_group_id=?")) {
            statement.setString(1, providerId); statement.setString(2, providerReleaseGroupId);
            try (ResultSet rs = statement.executeQuery()) { return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty(); }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find provider album", e);
        }
    }

    public void linkAlbum(long albumId, String providerId, String groupId, String title, Integer year, String url) {
        ReleaseYears.normalize(year);
        String sql = """
                INSERT INTO album_provider_links (album_id,provider_id,provider_release_group_id,provider_title,provider_release_year,provider_url)
                VALUES (?,?,?,?,?,?)
                ON CONFLICT(provider_id,provider_release_group_id) DO UPDATE SET
                  album_id=excluded.album_id, provider_title=excluded.provider_title,
                  provider_release_year=excluded.provider_release_year, provider_url=excluded.provider_url,
                  release_year_resolution=CASE WHEN album_provider_links.album_id=excluded.album_id AND album_provider_links.provider_release_year IS excluded.provider_release_year THEN album_provider_links.release_year_resolution ELSE NULL END,
                  title_resolution=CASE WHEN album_provider_links.album_id=excluded.album_id AND album_provider_links.provider_title=excluded.provider_title THEN album_provider_links.title_resolution ELSE NULL END,
                  updated_at=CURRENT_TIMESTAMP
                """;
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, albumId); statement.setString(2, providerId); statement.setString(3, groupId);
            statement.setString(4, ProviderTitles.clean(title)); setInteger(statement, 5, year); statement.setString(6, blankToNull(url));
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to link provider album", e);
        }
    }

    public int resolveAlbumReleaseYearUsingProvider(long albumId, Integer providerYear) {
        ReleaseYears.normalize(providerYear);
        return update("UPDATE album_provider_links SET release_year_resolution=CASE WHEN provider_release_year=? THEN 'USE_PROVIDER' ELSE 'USE_OTHER_PROVIDER' END,updated_at=CURRENT_TIMESTAMP WHERE album_id=? AND release_year_resolution IS NULL AND provider_release_year IS NOT NULL",
                providerYear, albumId);
    }

    public int resolveAllReleaseYearConflictsUsingLocal(long albumId, Integer localYear) {
        ReleaseYears.normalize(localYear);
        return update("UPDATE album_provider_links SET release_year_resolution='KEEP_LOCAL',updated_at=CURRENT_TIMESTAMP WHERE album_id=? AND release_year_resolution IS NULL AND provider_release_year IS NOT NULL AND provider_release_year<>?",
                albumId, localYear);
    }

    public int resetMatchingKeepLocalReleaseYearConflicts(long albumId, Integer providerYear) {
        ReleaseYears.normalize(providerYear);
        return update("UPDATE album_provider_links SET release_year_resolution=NULL,updated_at=CURRENT_TIMESTAMP WHERE album_id=? AND release_year_resolution='KEEP_LOCAL' AND provider_release_year=?",
                albumId, providerYear);
    }

    public int resolveAlbumTitleUsingProvider(long albumId, String providerTitle) {
        String selected = ProviderTitles.clean(providerTitle);
        if (selected.isBlank()) throw new IllegalArgumentException("Provider title is blank.");
        int changed = 0;
        for (TitleTarget target : titleTargets(albumId)) {
            String resolution = ProviderTitles.sameProviderTitleText(selected, target.title())
                    ? "USE_PROVIDER" : "USE_OTHER_PROVIDER";
            changed += update("UPDATE album_provider_links SET title_resolution=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND title_resolution IS NULL",
                    resolution, target.id());
        }
        return changed;
    }

    public int resolveAlbumTitleUsingLocal(long albumId, String localTitle) {
        String selected = ProviderTitles.clean(localTitle);
        if (selected.isBlank()) throw new IllegalArgumentException("Local title is blank.");
        int changed = 0;
        for (TitleTarget target : titleTargets(albumId)) {
            changed += update("UPDATE album_provider_links SET title_resolution=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    ProviderTitles.sameTitle(selected, target.title()) ? null : "KEEP_LOCAL", target.id());
        }
        return changed;
    }

    public int resetMatchingKeepLocalTitleConflicts(long albumId, String providerTitle) {
        int changed = 0;
        for (TitleTarget target : titleTargets(albumId)) {
            if (ProviderTitles.sameProviderTitle(providerTitle, target.title())) {
                changed += update("UPDATE album_provider_links SET title_resolution=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND title_resolution='KEEP_LOCAL'", target.id());
            }
        }
        return changed;
    }

    private List<TitleTarget> titleTargets(long albumId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT id,provider_title FROM album_provider_links WHERE album_id=? AND provider_title IS NOT NULL")) {
            statement.setLong(1, albumId);
            List<TitleTarget> result = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) { while (rs.next()) result.add(new TitleTarget(rs.getLong(1), rs.getString(2))); }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list provider titles", e);
        }
    }

    private int update(String sql, Object... values) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to resolve provider conflict", e);
        }
    }

    private static AlbumProviderLink map(ResultSet rs) throws Exception {
        return new AlbumProviderLink(rs.getLong("id"), rs.getLong("album_id"), rs.getString("provider_id"),
                rs.getString("provider_release_group_id"), rs.getString("provider_title"),
                nullableInteger(rs, "provider_release_year"), rs.getString("provider_url"),
                rs.getString("release_year_resolution"), rs.getString("title_resolution"), false, false,
                rs.getString("created_at"), rs.getString("updated_at"));
    }

    private static Integer nullableInteger(ResultSet rs, String column) throws Exception { int v=rs.getInt(column); return rs.wasNull()?null:v; }
    private static void setInteger(PreparedStatement s,int i,Integer v)throws Exception{if(v==null)s.setNull(i,java.sql.Types.INTEGER);else s.setInt(i,v);}
    private static String blankToNull(String v){return v==null||v.isBlank()?null:v.trim();}
    private static long rsLong(ResultSet rs,String c){try{return rs.getLong(c);}catch(Exception e){throw new IllegalStateException(e);}}
    private static String rsString(ResultSet rs,String c){try{return rs.getString(c);}catch(Exception e){throw new IllegalStateException(e);}}
    private static Integer rsInteger(ResultSet rs,String c){try{return nullableInteger(rs,c);}catch(Exception e){throw new IllegalStateException(e);}}

    private record TitleTarget(long id,String title){}
    private record YearAccumulator(long albumId,long artistId,String artistName,String albumTitle,Integer localYear,String localPath,List<ProviderReleaseYearConflictSource> sources){
        ProviderReleaseYearConflict toConflict(){var first=sources.get(0);return new ProviderReleaseYearConflict(albumId,first.providerLinkId(),artistId,artistName,albumTitle,localYear,first.providerTitle(),first.providerReleaseYear(),first.providerId(),first.providerUrl(),localPath,List.copyOf(sources));}
    }
    private record TitleAccumulator(long albumId,long artistId,String artistName,String albumTitle,Integer localYear,String localPath,List<ProviderTitleConflictSource> sources){
        ProviderTitleConflict toConflict(){var first=sources.get(0);return new ProviderTitleConflict(albumId,first.providerLinkId(),artistId,artistName,albumTitle,localYear,first.providerTitle(),first.providerReleaseYear(),first.providerId(),first.providerUrl(),localPath,List.copyOf(sources));}
    }
}
