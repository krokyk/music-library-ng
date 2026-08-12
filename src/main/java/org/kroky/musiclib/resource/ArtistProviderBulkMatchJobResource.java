package org.kroky.musiclib.resource;

import org.jboss.logging.Logger;
import org.kroky.musiclib.model.ArtistProviderBulkMatchJobStatus;
import org.kroky.musiclib.model.ArtistProviderBulkMatchRequest;
import org.kroky.musiclib.provider.ArtistProviderBulkMatchJobService;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

@Path("/api/provider-match-jobs")
public class ArtistProviderBulkMatchJobResource {

    private static final Logger LOG = Logger.getLogger(ArtistProviderBulkMatchJobResource.class);

    @Inject
    ArtistProviderBulkMatchJobService jobs;

    @POST
    @Path("/{providerId}")
    public ArtistProviderBulkMatchJobStatus start(@PathParam("providerId") String providerId,
            ArtistProviderBulkMatchRequest request) {
        if (request == null || request.artistIds() == null) {
            throw new BadRequestException("artistIds is required");
        }
        LOG.infof("Bulk provider artist match job requested provider=%s artists=%d",
                providerId, request.artistIds().size());
        return jobs.start(providerId, request.artistIds());
    }

    @GET
    @Path("/current")
    public ArtistProviderBulkMatchJobStatus current() {
        return jobs.current();
    }

    @POST
    @Path("/current/cancel")
    public ArtistProviderBulkMatchJobStatus cancel() {
        LOG.info("Bulk provider artist match job cancel requested");
        return jobs.cancelCurrent();
    }
}
