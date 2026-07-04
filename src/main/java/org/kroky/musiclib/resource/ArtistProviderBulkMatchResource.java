package org.kroky.musiclib.resource;

import org.jboss.logging.Logger;
import org.kroky.musiclib.model.ArtistProviderBulkMatchRequest;
import org.kroky.musiclib.model.ArtistProviderBulkMatchResult;
import org.kroky.musiclib.provider.ArtistProviderBulkMatchService;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

@Path("/api/provider-matches/{providerId}/artists")
public class ArtistProviderBulkMatchResource {

    private static final Logger LOG = Logger.getLogger(ArtistProviderBulkMatchResource.class);

    @Inject
    ArtistProviderBulkMatchService bulkMatches;

    @POST
    public ArtistProviderBulkMatchResult match(@PathParam("providerId") String providerId,
            ArtistProviderBulkMatchRequest request) {
        if (request == null || request.artistIds() == null) {
            throw new BadRequestException("artistIds is required");
        }
        int requested = request.artistIds().size();
        LOG.infof("Bulk provider artist match request provider=%s artists=%d", providerId, requested);
        return bulkMatches.matchProviderArtists(providerId, request.artistIds());
    }
}
