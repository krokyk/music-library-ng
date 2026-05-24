package org.kroky.musiclib.resource;

import java.net.URI;
import java.util.List;

import org.jboss.logging.Logger;
import org.kroky.musiclib.model.Album;
import org.kroky.musiclib.repository.AlbumRepository;

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

@Path("/api/albums")
public class AlbumResource {

    private static final Logger LOG = Logger.getLogger(AlbumResource.class);

    @Inject
    AlbumRepository albums;

    @GET
    public List<Album> list(@QueryParam("artistId") Long artistId, @QueryParam("checked") Boolean checked,
            @QueryParam("hasLocalPath") Boolean hasLocalPath, @QueryParam("search") String search) {
        LOG.infof("Listing albums artistId=%s checked=%s hasLocalPath=%s search='%s'",
                artistId, checked, hasLocalPath, search);
        return albums.list(artistId, checked, hasLocalPath, search);
    }

    @GET
    @Path("/{id}")
    public Album find(@PathParam("id") long id) {
        LOG.infof("Loading album id=%d", id);
        return albums.find(id).orElseThrow(NotFoundException::new);
    }

    @POST
    public Response create(AlbumRequest request) {
        LOG.infof("Create album request artistId=%d title='%s' year=%s checked=%s",
                request.artistId(), request.title(), request.releaseYear(), request.checkedOrDefault());
        Album album = albums.create(request.artistId(), request.title(), request.releaseYear(),
                request.releaseDate(), request.checkedOrDefault(), request.notes());
        return Response.created(URI.create("/api/albums/" + album.id())).entity(album).build();
    }

    @PUT
    @Path("/{id}")
    public Album update(@PathParam("id") long id, AlbumRequest request) {
        LOG.infof("Update album request id=%d title='%s' year=%s checked=%s",
                id, request.title(), request.releaseYear(), request.checkedOrDefault());
        return albums.update(id, request.title(), request.releaseYear(), request.releaseDate(),
                request.checkedOrDefault(), request.notes()).orElseThrow(NotFoundException::new);
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") long id) {
        LOG.warnf("Delete album request id=%d", id);
        albums.delete(id);
        return Response.noContent().build();
    }

    public record AlbumRequest(long artistId, String title, Integer releaseYear, String releaseDate, Boolean checked,
            String notes) {
        boolean checkedOrDefault() {
            return checked == null || checked;
        }
    }
}
