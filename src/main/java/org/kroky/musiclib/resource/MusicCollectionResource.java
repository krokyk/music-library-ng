package org.kroky.musiclib.resource;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.jboss.logging.Logger;
import org.kroky.musiclib.db.ArtistNames;
import org.kroky.musiclib.model.Artist;
import org.kroky.musiclib.model.CollectionTitleItem;
import org.kroky.musiclib.model.CollectionType;
import org.kroky.musiclib.model.CollectionFolderCandidate;
import org.kroky.musiclib.model.MusicCollection;
import org.kroky.musiclib.model.ParserType;
import org.kroky.musiclib.repository.AlbumRepository;
import org.kroky.musiclib.repository.ArtistRepository;
import org.kroky.musiclib.repository.CollectionTitleItemRepository;
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
    CollectionTitleItemRepository titleItems;

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
    @Path("/{id}/titles")
    public List<CollectionTitleItem> titles(@PathParam("id") String id) {
        LOG.infof("Listing title items for collection %s", id);
        return titleItems.list(id);
    }

    @PUT
    @Path("/{id}/titles/{titleItemId}")
    public CollectionTitleItem updateTitle(
            @PathParam("id") String id,
            @PathParam("titleItemId") long titleItemId,
            TitleItemRequest request) {
        if (request == null || request.title() == null || request.title().isBlank()) {
            throw new BadRequestException("title is required");
        }
        LOG.infof("Update title item request collection=%s id=%d title='%s'",
                id, titleItemId, request.title());
        try {
            CollectionTitleItem updated = titleItems.updateManual(
                    id,
                    titleItemId,
                    request.title().trim(),
                    request.artistName(),
                    request.releaseDate(),
                    request.sortName()).orElseThrow(NotFoundException::new);
            populateArtistAlbum(id, updated);
            return updated;
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
    }

    private void populateArtistAlbum(String collectionId, CollectionTitleItem item) {
        List<Long> artistIds = new ArrayList<>();
        for (String artistName : ArtistNames.splitList(item.artistName())) {
            var artist = artists.upsertByName(artistName);
            artists.assignToCollection(artist.id(), collectionId);
            artistIds.add(artist.id());
        }
        if (!artistIds.isEmpty()) {
            albums.upsertScanned(artistIds, item.title(), item.releaseDate(), item.relativePath(), collectionId);
        }
    }

    @DELETE
    @Path("/{id}/titles/{titleItemId}")
    public Response deleteTitleLocalPath(
            @PathParam("id") String id,
            @PathParam("titleItemId") long titleItemId) {
        LOG.infof("Delete title local path request collection=%s id=%d", id, titleItemId);
        CollectionTitleItem item = titleItems.markMissing(id, titleItemId).orElseThrow(NotFoundException::new);
        albums.markLocalPathMissing(id, item.relativePath());
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
}
