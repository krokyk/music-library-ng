package org.kroky.musiclib.resource;

import java.net.URI;
import java.util.List;

import org.jboss.logging.Logger;
import org.kroky.musiclib.model.Artist;
import org.kroky.musiclib.repository.ArtistRepository;

import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
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
    public Artist find(@PathParam("id") long id) {
        LOG.infof("Loading artist id=%d", id);
        return artists.find(id).orElseThrow(NotFoundException::new);
    }

    @POST
    public Response create(ArtistRequest request) {
        LOG.infof("Create artist request name='%s'", request.name());
        Artist artist = artists.create(request.name(), request.sortName(), request.notes(), request.collectionIds());
        return Response.created(URI.create("/api/artists/" + artist.id())).entity(artist).build();
    }

    @PUT
    @Path("/{id}")
    public Artist update(@PathParam("id") long id, ArtistRequest request) {
        LOG.infof("Update artist request id=%d name='%s'", id, request.name());
        return artists.update(id, request.name(), request.sortName(), request.notes(), request.collectionIds())
                .orElseThrow(NotFoundException::new);
    }

    @PUT
    @Path("/{id}/collections")
    public Artist updateCollections(@PathParam("id") long id, CollectionMembershipRequest request) {
        LOG.infof("Update artist collections request id=%d collections=%s", id, request.collectionIds());
        return artists.setCollections(id, request.collectionIds()).orElseThrow(NotFoundException::new);
    }

    @DELETE
    @Path("/{id}/collections/{collectionId}")
    public Response removeFromCollection(@PathParam("id") long id, @PathParam("collectionId") String collectionId) {
        LOG.infof("Remove artist from collection request id=%d collectionId=%s", id, collectionId);
        artists.find(id).orElseThrow(NotFoundException::new);
        artists.removeFromCollection(id, collectionId);
        return Response.noContent().build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") long id) {
        LOG.infof("Delete artist request id=%d", id);
        artists.delete(id);
        return Response.noContent().build();
    }

    public record ArtistRequest(String name, String sortName, String notes, List<String> collectionIds) {
    }

    public record CollectionMembershipRequest(List<String> collectionIds) {
    }
}
