package org.kroky.musiclib.resource;

import java.util.List;

import org.jboss.logging.Logger;
import org.kroky.musiclib.model.Artist;
import org.kroky.musiclib.repository.ArtistRepository;

import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

@Path("/api/artists")
public class ArtistResource {

    private static final Logger LOG = Logger.getLogger(ArtistResource.class);

    @Inject
    ArtistRepository artists;

    @GET
    public List<Artist> list(@QueryParam("search") String search, @QueryParam("collectionId") String collectionId) {
        LOG.infof("Listing artists search='%s' collectionId=%s", search, collectionId);
        return artists.list(search, collectionId);
    }

    @GET
    @Path("/{id}")
    public Artist find(@PathParam("id") long id, @QueryParam("collectionId") String collectionId) {
        LOG.infof("Loading artist id=%d collectionId=%s", id, collectionId);
        return artists.find(id, collectionId).orElseThrow(NotFoundException::new);
    }

    @PUT
    @Path("/{id}")
    public Artist update(@PathParam("id") long id, ArtistRequest request) {
        LOG.infof("Update artist request id=%d name='%s'", id, request.name());
        return artists.update(id, request.name(), request.sortName(), request.countryOverride(),
                request.activeOverride())
                .orElseThrow(NotFoundException::new);
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") long id) {
        LOG.infof("Delete artist request id=%d", id);
        artists.delete(id);
        return Response.noContent().build();
    }

    public record ArtistRequest(String name, String sortName, String countryOverride, Boolean activeOverride) {
    }
}
