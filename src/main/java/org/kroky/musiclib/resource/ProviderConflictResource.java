package org.kroky.musiclib.resource;

import java.util.List;

import org.jboss.logging.Logger;
import org.kroky.musiclib.model.ArtistCountryConflict;
import org.kroky.musiclib.model.ArtistStatusConflict;
import org.kroky.musiclib.model.ProviderReleaseYearConflict;
import org.kroky.musiclib.model.ProviderTitleConflict;
import org.kroky.musiclib.provider.AlbumProviderConflictService;
import org.kroky.musiclib.provider.AlbumProviderConflictService.ProviderConflictSnapshot;
import org.kroky.musiclib.provider.AlbumProviderConflictService.ResolutionRequest;
import org.kroky.musiclib.provider.AlbumProviderConflictService.ResolutionResult;
import org.kroky.musiclib.repository.AlbumProviderLinkRepository;
import org.kroky.musiclib.repository.ArtistProviderLinkRepository;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

@Path("/api/provider-conflicts")
public class ProviderConflictResource {

    private static final Logger LOG = Logger.getLogger(ProviderConflictResource.class);

    @Inject AlbumProviderLinkRepository providerLinks;
    @Inject ArtistProviderLinkRepository artistProviderLinks;
    @Inject AlbumProviderConflictService conflicts;

    @GET @Path("/release-years")
    public List<ProviderReleaseYearConflict> releaseYearConflicts() { return providerLinks.listReleaseYearConflicts(); }

    @GET @Path("/titles")
    public List<ProviderTitleConflict> titleConflicts() { return providerLinks.listTitleConflicts(); }

    @GET @Path("/artist-countries")
    public List<ArtistCountryConflict> artistCountryConflicts() { return artistProviderLinks.listCountryConflicts(); }

    @GET @Path("/artist-statuses")
    public List<ArtistStatusConflict> artistStatusConflicts() { return artistProviderLinks.listStatusConflicts(); }

    @POST @Path("/artists/{artistId}/open")
    public ProviderConflictSnapshot open(@PathParam("artistId") long artistId) {
        LOG.infof("Open provider conflicts artist=%d and clear stale local path evidence", artistId);
        return conflicts.open(artistId);
    }

    @POST @Path("/resolve")
    public ResolutionResult resolve(ResolutionRequest request) {
        LOG.infof("Resolve provider conflict kind=%s action=%s artist=%d album=%s providerLink=%s",
                request == null ? null : request.kind(), request == null ? null : request.action(),
                request == null ? 0 : request.artistId(), request == null ? null : request.albumId(),
                request == null ? null : request.providerLinkId());
        return conflicts.resolve(request);
    }
}
