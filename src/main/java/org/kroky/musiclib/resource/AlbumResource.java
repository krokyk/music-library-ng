package org.kroky.musiclib.resource;

import java.util.List;

import org.jboss.logging.Logger;
import org.kroky.musiclib.model.Album;
import org.kroky.musiclib.model.ReleaseYears;
import org.kroky.musiclib.repository.AlbumRepository;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;

@Path("/api/albums")
public class AlbumResource {

    private static final Logger LOG = Logger.getLogger(AlbumResource.class);

    @Inject
    AlbumRepository albums;

    @GET
    public List<Album> list(@QueryParam("artistId") Long artistId, @QueryParam("collectionId") String collectionId,
            @QueryParam("checked") Boolean checked, @QueryParam("hasLocalPath") Boolean hasLocalPath,
            @QueryParam("search") String search) {
        LOG.infof("Listing albums artistId=%s collectionId=%s checked=%s hasLocalPath=%s search='%s'",
                artistId, collectionId, checked, hasLocalPath, search);
        return albums.list(artistId, collectionId, checked, hasLocalPath, search);
    }

    @GET
    @Path("/{id}")
    public Album find(@PathParam("id") long id) {
        LOG.infof("Loading album id=%d", id);
        return albums.find(id).orElseThrow(NotFoundException::new);
    }

    @PUT
    @Path("/{id}")
    public Album update(@PathParam("id") long id, AlbumRequest request) {
        LOG.infof("Update album request id=%d title='%s' releaseYear='%s' checked=%s",
                id, request.title(), request.releaseYear(), request.checkedOrDefault());
        Album existing = albums.find(id).orElseThrow(NotFoundException::new);
        if (!request.checkedOrDefault() && existing.onDisk()) {
            throw new BadRequestException("Cannot uncheck album while it is still present on disk.");
        }
        return albums.update(id, request.title(), request.normalizedReleaseYear(),
                request.checkedOrDefault(), request.notes()).orElseThrow(NotFoundException::new);
    }

    @PUT
    @Path("/{id}/collection")
    public Album rehome(@PathParam("id") long id, CollectionRequest request) {
        if (request == null || request.collectionId() == null || request.collectionId().isBlank()) {
            throw new BadRequestException("collectionId is required");
        }
        LOG.infof("Reassign nonlocal album id=%d collection=%s", id, request.collectionId());
        try { return albums.reassignCollection(id, request.collectionId()); }
        catch (IllegalArgumentException e) { throw new BadRequestException(e.getMessage(), e); }
    }

    public record AlbumRequest(String title, Integer releaseYear, Boolean checked, String notes) {
        boolean checkedOrDefault() {
            return checked == null || checked;
        }

        Integer normalizedReleaseYear() {
            try {
                return ReleaseYears.normalize(releaseYear);
            } catch (IllegalArgumentException ex) {
                throw new BadRequestException(ex.getMessage(), ex);
            }
        }
    }

    public record CollectionRequest(String collectionId) { }
}
