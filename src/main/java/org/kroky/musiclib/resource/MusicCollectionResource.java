package org.kroky.musiclib.resource;

import java.net.URI;
import java.util.List;

import org.jboss.logging.Logger;
import org.kroky.musiclib.model.Album;
import org.kroky.musiclib.model.Artist;
import org.kroky.musiclib.model.CollectionMetadata;
import org.kroky.musiclib.model.CollectionType;
import org.kroky.musiclib.model.CollectionFolderCandidate;
import org.kroky.musiclib.model.MusicCollection;
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
        MusicCollection collection;
        try { collection = collections.createFromFolder(request.relativePath()); }
        catch (IllegalArgumentException e) { throw new BadRequestException(e.getMessage(), e); }
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

    @PUT
    @Path("/{id}")
    public MusicCollection update(@PathParam("id") String id, MusicCollectionRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BadRequestException("name is required");
        }
        if (request.relativePath() != null) {
            throw new BadRequestException("Collection path is immutable");
        }
        CollectionType type = parseCollectionType(request.type());
        LOG.infof("Update collection id=%s name='%s' type=%s", id, request.name(), type);
        try {
            return collections.update(id, request.name().trim(), type).orElseThrow(NotFoundException::new);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage(), e);
        }
    }

    @GET
    @Path("/{id}/delete-preview")
    public MusicCollectionRepository.DeletePreview deletePreview(@PathParam("id") String id) {
        try { return collections.deletePreview(id); }
        catch (IllegalArgumentException e) { throw new NotFoundException(e.getMessage()); }
    }

    @DELETE
    @Path("/{id}")
    public MusicCollectionRepository.DeleteResult delete(@PathParam("id") String id) {
        LOG.infof("Delete collection request id=%s", id);
        return collections.delete(id);
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

    public record MusicCollectionRequest(String name, String relativePath, String type) {
    }

}
