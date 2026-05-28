package org.kroky.musiclib.resource;

import java.net.URI;
import java.util.List;

import org.jboss.logging.Logger;
import org.kroky.musiclib.model.Artist;
import org.kroky.musiclib.model.CollectionFolderCandidate;
import org.kroky.musiclib.model.MusicCollection;
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

    @PUT
    @Path("/{id}")
    public MusicCollection update(@PathParam("id") String id, MusicCollectionRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BadRequestException("name is required");
        }
        LOG.infof("Update collection request id=%s name='%s'", id, request.name());
        return collections.update(id, request.name().trim())
                .orElseThrow(NotFoundException::new);
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        LOG.infof("Delete collection request id=%s", id);
        collections.delete(id);
        return Response.noContent().build();
    }

    public record MusicCollectionRequest(String name, String relativePath) {
    }
}
