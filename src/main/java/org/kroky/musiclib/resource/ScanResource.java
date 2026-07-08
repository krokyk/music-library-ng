package org.kroky.musiclib.resource;

import org.jboss.logging.Logger;
import org.kroky.musiclib.model.ScanJobStatus;
import org.kroky.musiclib.scan.ScanJobService;

import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.GET;

@Path("/api/scan")
public class ScanResource {

    private static final Logger LOG = Logger.getLogger(ScanResource.class);

    @Inject
    ScanJobService scanJobs;

    @POST
    @Path("/jobs")
    public ScanJobStatus startJob(@QueryParam("collectionId") String collectionId) {
        LOG.infof("Scan job requested collectionId=%s", collectionId);
        return scanJobs.start(collectionId);
    }

    @GET
    @Path("/jobs/current")
    public ScanJobStatus currentJob() {
        return scanJobs.current();
    }

    @POST
    @Path("/jobs/current/cancel")
    public ScanJobStatus cancelCurrentJob() {
        LOG.info("Scan job cancel requested");
        return scanJobs.cancelCurrent();
    }

}
