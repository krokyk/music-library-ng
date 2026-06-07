package org.kroky.musiclib.resource;

import java.util.List;

import org.jboss.logging.Logger;
import org.kroky.musiclib.model.ScanEvent;
import org.kroky.musiclib.model.ScanJobStatus;
import org.kroky.musiclib.model.ScanRun;
import org.kroky.musiclib.repository.ScanRunRepository;
import org.kroky.musiclib.scan.ScanJobService;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/scan")
public class ScanResource {

    private static final Logger LOG = Logger.getLogger(ScanResource.class);

    @Inject
    ScanRunRepository scanRuns;

    @Inject
    ScanJobService scanJobs;

    @POST
    @Path("/jobs")
    public ScanJobStatus startJob(@QueryParam("collectionId") String collectionId) {
        LOG.infof("Scan job requested collectionId=%s", collectionId);
        return scanJobs.start(collectionId);
    }

    @POST
    @Path("/jobs/local-albums")
    public ScanJobStatus startLocalAlbumJob(
            @QueryParam("collectionId") String collectionId,
            @QueryParam("artistId") Long artistId) {
        LOG.infof("Local album scan job requested collectionId=%s artistId=%s", collectionId, artistId);
        return scanJobs.startLocalAlbums(collectionId, artistId);
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

    @GET
    @Path("/runs")
    public List<ScanRun> runs(@QueryParam("limit") Integer limit) {
        LOG.infof("Listing scan runs limit=%s", limit);
        return scanRuns.listRecent(limit == null ? 25 : limit);
    }

    @GET
    @Path("/runs/{id}/events")
    public List<ScanEvent> events(@PathParam("id") long id) {
        LOG.infof("Listing scan events runId=%d", id);
        return scanRuns.listEvents(id);
    }

    @GET
    @Path("/runs/{id}/report")
    @Produces(MediaType.TEXT_PLAIN)
    public String report(@PathParam("id") long id) {
        LOG.infof("Loading scan report runId=%d", id);
        return scanRuns.report(id).orElseThrow(NotFoundException::new);
    }
}
