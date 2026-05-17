package org.kroky.musiclib.resource;

import java.util.List;

import org.jboss.logging.Logger;
import org.kroky.musiclib.model.ScanEvent;
import org.kroky.musiclib.model.ScanRun;
import org.kroky.musiclib.model.ScanSummary;
import org.kroky.musiclib.repository.ScanRunRepository;
import org.kroky.musiclib.scan.ScanService;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;

@Path("/api/scan")
public class ScanResource {

    private static final Logger LOG = Logger.getLogger(ScanResource.class);

    @Inject
    ScanService scanService;

    @Inject
    ScanRunRepository scanRuns;

    @POST
    public List<ScanSummary> scan(@QueryParam("sourceId") String sourceId) {
        LOG.warnf("Scan requested sourceId=%s", sourceId);
        if (sourceId == null || sourceId.isBlank()) {
            return scanService.scanAllEnabled();
        }
        return List.of(scanService.scan(sourceId));
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
}
