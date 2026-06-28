package org.kroky.musiclib.resource;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.jboss.logging.Logger;
import org.kroky.musiclib.db.ArtistNames;
import org.kroky.musiclib.model.Album;
import org.kroky.musiclib.model.Artist;
import org.kroky.musiclib.model.CollectionMetadata;
import org.kroky.musiclib.model.CollectionType;
import org.kroky.musiclib.model.CollectionFolderCandidate;
import org.kroky.musiclib.model.MusicCollection;
import org.kroky.musiclib.model.ParserType;
import org.kroky.musiclib.model.ReleaseDates;
import org.kroky.musiclib.repository.AlbumRepository;
import org.kroky.musiclib.repository.ArtistRepository;
import org.kroky.musiclib.repository.MusicCollectionRepository;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

@Path("/api/collections")
public class MusicCollectionResource {

    private static final Logger LOG = Logger.getLogger(MusicCollectionResource.class);

    @Inject
    MusicCollectionRepository collections;

    @Inject
    ArtistRepository artists;

    @Inject
    AlbumRepository albums;

    @GET
    public List<MusicCollection> list() {
        LOG.info("Listing music collections");
        return collections.list();
    }

    @GET
    @Path("/candidates")
    public List<CollectionFolderCandidate> candidates() {
        LOG.info("Listing collection folder candidates");
        return collections.listFolderCandidates();
    }

    @POST
    public Response create(MusicCollectionRequest request) {
        if (request == null || request.relativePath() == null || request.relativePath().isBlank()) {
            throw new BadRequestException("relativePath is required");
        }
        LOG.infof("Create collection request relativePath=%s", request.relativePath());
        MusicCollection collection = collections.createFromFolder(request.relativePath());
        return Response.created(URI.create("/api/collections/" + collection.id())).entity(collection).build();
    }

    @GET
    @Path("/{id}/artists")
    public List<Artist> artists(@PathParam("id") String id) {
        LOG.infof("Listing artists for collection %s", id);
        return artists.list(null, id);
    }

    @GET
    @Path("/{id}/metadata")
    public CollectionMetadata metadata(@PathParam("id") String id) {
        LOG.debugf("Loading metadata for collection %s", id);
        return collections.metadata(id).orElseThrow(NotFoundException::new);
    }

    @GET
    @Path("/{id}/titles")
    public List<Album> titles(@PathParam("id") String id) {
        LOG.infof("Listing title albums for collection %s", id);
        return albums.list(null, id, null, null, null);
    }

    @POST
    @Path("/{id}/albums")
    public List<Album> addAlbums(@PathParam("id") String id, AlbumMembershipRequest request) {
        collections.find(id).orElseThrow(NotFoundException::new);
        if (request == null || request.albumIds() == null || request.albumIds().isEmpty()) {
            throw new BadRequestException("albumIds is required");
        }
        LOG.infof("Add albums to collection request collection=%s albumIds=%s", id, request.albumIds());
        List<Album> updated = new ArrayList<>();
        for (Long albumId : request.albumIds()) {
            if (albumId == null) {
                continue;
            }
            albums.find(albumId).orElseThrow(NotFoundException::new);
            albums.assignToCollection(albumId, id);
            updated.add(albums.find(albumId).orElseThrow(NotFoundException::new));
        }
        return updated;
    }

    @POST
    @Path("/{id}/titles")
    public Album createTitle(@PathParam("id") String id, TitleItemRequest request) {
        if (request == null || request.title() == null || request.title().isBlank()) {
            throw new BadRequestException("title is required");
        }
        LOG.infof("Create title album request collection=%s title='%s'", id, request.title());
        return albums.upsertManual(
                contributorArtistIds(request.artistName()),
                request.title().trim(),
                normalizeReleaseDate(request.releaseDate()),
                request.sortName(),
                true,
                id);
    }

    @PUT
    @Path("/{id}/titles/{titleItemId}")
    public Album updateTitle(
            @PathParam("id") String id,
            @PathParam("titleItemId") long titleItemId,
            TitleItemRequest request) {
        if (request == null || request.title() == null || request.title().isBlank()) {
            throw new BadRequestException("title is required");
        }
        LOG.infof("Update title item request collection=%s id=%d title='%s'",
                id, titleItemId, request.title());
        try {
            Album updated = albums.updateTitleMetadata(
                    titleItemId,
                    contributorArtistIds(request.artistName()),
                    request.title().trim(),
                    normalizeReleaseDate(request.releaseDate()),
                    request.sortName()).orElseThrow(NotFoundException::new);
            albums.assignToCollection(updated.id(), id);
            return updated;
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
    }

    private List<Long> contributorArtistIds(String artistNameValue) {
        List<Long> artistIds = new ArrayList<>();
        for (String artistName : ArtistNames.splitList(artistNameValue)) {
            var artist = artists.upsertByName(artistName);
            artistIds.add(artist.id());
        }
        return artistIds;
    }

    @DELETE
    @Path("/{id}/titles/{titleItemId}")
    public Response deleteTitleLocalPath(
            @PathParam("id") String id,
            @PathParam("titleItemId") long titleItemId) {
        LOG.infof("Delete title local path request collection=%s id=%d", id, titleItemId);
        albums.find(titleItemId).orElseThrow(NotFoundException::new);
        albums.removeLocalPaths(id, titleItemId);
        return Response.noContent().build();
    }

    @PUT
    @Path("/{id}")
    public MusicCollection update(@PathParam("id") String id, MusicCollectionRequest request) {
        if (request == null || !request.hasUpdate()) {
            throw new BadRequestException("name, type, or parser is required");
        }
        LOG.infof("Update collection request id=%s name='%s' type=%s parser=%s",
                id, request.name(), request.type(), request.parser());
        return collections.update(
                id,
                request.name() == null ? null : request.name().trim(),
                parseCollectionType(request.type()),
                parseParserType(request.parser()))
                .orElseThrow(NotFoundException::new);
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        LOG.infof("Delete collection request id=%s", id);
        collections.delete(id);
        return Response.noContent().build();
    }

    private static CollectionType parseCollectionType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return CollectionType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown collection type: " + value);
        }
    }

    private static ParserType parseParserType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ParserType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown parser: " + value);
        }
    }

    private static String normalizeReleaseDate(String releaseDate) {
        return ReleaseDates.normalize(releaseDate);
    }

    public record MusicCollectionRequest(String name, String relativePath, String type, String parser) {
        boolean hasUpdate() {
            return (name != null && !name.isBlank())
                    || (type != null && !type.isBlank())
                    || (parser != null && !parser.isBlank());
        }
    }

    public record TitleItemRequest(
            String title,
            String artistName,
            String releaseDate,
            String sortName) {
    }

    public record AlbumMembershipRequest(List<Long> albumIds) {
    }
}
