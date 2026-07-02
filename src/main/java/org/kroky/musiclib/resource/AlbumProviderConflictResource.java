package org.kroky.musiclib.resource;

import org.jboss.logging.Logger;
import org.kroky.musiclib.model.AlbumReleaseDateConflictPlan;
import org.kroky.musiclib.model.AlbumReleaseDateConflictResult;
import org.kroky.musiclib.provider.AlbumProviderConflictService;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

@Path("/api/albums/{albumId}/provider-links/{providerLinkId}/release-date-conflict")
public class AlbumProviderConflictResource {

    private static final Logger LOG = Logger.getLogger(AlbumProviderConflictResource.class);

    @Inject
    AlbumProviderConflictService conflicts;

    @GET
    @Path("/provider-year-plan")
    public AlbumReleaseDateConflictPlan providerYearPlan(
            @PathParam("albumId") long albumId,
            @PathParam("providerLinkId") long providerLinkId) {
        LOG.infof("Preview provider release date conflict resolution album=%d providerLink=%d",
                albumId, providerLinkId);
        return conflicts.planUseProviderReleaseDate(albumId, providerLinkId);
    }

    @POST
    @Path("/keep-local")
    public AlbumReleaseDateConflictResult keepLocal(
            @PathParam("albumId") long albumId,
            @PathParam("providerLinkId") long providerLinkId) {
        LOG.infof("Keep local release date album=%d providerLink=%d", albumId, providerLinkId);
        return conflicts.keepLocalReleaseDate(albumId, providerLinkId);
    }

    @POST
    @Path("/reset-keep-local")
    public AlbumReleaseDateConflictResult resetKeepLocal(
            @PathParam("albumId") long albumId,
            @PathParam("providerLinkId") long providerLinkId) {
        LOG.infof("Reset keep-local release date decision album=%d providerLink=%d", albumId, providerLinkId);
        return conflicts.resetKeepLocalReleaseDate(albumId, providerLinkId);
    }

    @POST
    @Path("/use-provider-year")
    public AlbumReleaseDateConflictResult useProviderYear(
            @PathParam("albumId") long albumId,
            @PathParam("providerLinkId") long providerLinkId) {
        LOG.infof("Use provider release date album=%d providerLink=%d", albumId, providerLinkId);
        return conflicts.useProviderReleaseDate(albumId, providerLinkId);
    }
}
