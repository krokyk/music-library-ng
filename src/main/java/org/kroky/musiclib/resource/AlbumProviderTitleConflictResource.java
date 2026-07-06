package org.kroky.musiclib.resource;

import org.jboss.logging.Logger;
import org.kroky.musiclib.model.AlbumTitleConflictPlan;
import org.kroky.musiclib.model.AlbumTitleConflictResult;
import org.kroky.musiclib.provider.AlbumProviderConflictService;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

@Path("/api/albums/{albumId}/provider-links/{providerLinkId}/title-conflict")
public class AlbumProviderTitleConflictResource {

    private static final Logger LOG = Logger.getLogger(AlbumProviderTitleConflictResource.class);

    @Inject
    AlbumProviderConflictService conflicts;

    @GET
    @Path("/provider-title-plan")
    public AlbumTitleConflictPlan providerTitlePlan(
            @PathParam("albumId") long albumId,
            @PathParam("providerLinkId") long providerLinkId) {
        LOG.infof("Preview provider title conflict resolution album=%d providerLink=%d", albumId, providerLinkId);
        return conflicts.planUseProviderTitle(albumId, providerLinkId);
    }

    @POST
    @Path("/keep-local")
    public AlbumTitleConflictResult keepLocal(
            @PathParam("albumId") long albumId,
            @PathParam("providerLinkId") long providerLinkId) {
        LOG.infof("Keep local title album=%d providerLink=%d", albumId, providerLinkId);
        return conflicts.keepLocalTitle(albumId, providerLinkId);
    }

    @POST
    @Path("/reset-keep-local")
    public AlbumTitleConflictResult resetKeepLocal(
            @PathParam("albumId") long albumId,
            @PathParam("providerLinkId") long providerLinkId) {
        LOG.infof("Reset keep-local title decision album=%d providerLink=%d", albumId, providerLinkId);
        return conflicts.resetKeepLocalTitle(albumId, providerLinkId);
    }

    @POST
    @Path("/use-provider-title")
    public AlbumTitleConflictResult useProviderTitle(
            @PathParam("albumId") long albumId,
            @PathParam("providerLinkId") long providerLinkId) {
        LOG.infof("Use provider title album=%d providerLink=%d", albumId, providerLinkId);
        return conflicts.useProviderTitle(albumId, providerLinkId);
    }
}
