package org.kroky.musiclib.resource;

import java.util.List;

import org.kroky.musiclib.model.ProviderCheckEvent;
import org.kroky.musiclib.model.ProviderCheckRun;
import org.kroky.musiclib.model.ProviderCheckSummary;
import org.kroky.musiclib.provider.ProviderCheckService;
import org.kroky.musiclib.repository.ProviderCheckRunRepository;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;

@Path("/api/provider-checks")
public class ProviderCheckResource {

    @Inject
    ProviderCheckService checks;

    @Inject
    ProviderCheckRunRepository runs;

    @POST
    @Path("/artist/{artistId}")
    public ProviderCheckSummary checkArtist(@PathParam("artistId") long artistId) {
        return checks.checkArtist(artistId);
    }

    @POST
    @Path("/collection/{collectionId}")
    public ProviderCheckSummary checkCollection(@PathParam("collectionId") String collectionId) {
        return checks.checkCollection(collectionId);
    }

    @POST
    @Path("/provider-link/{linkId}")
    public ProviderCheckSummary checkProviderLink(@PathParam("linkId") long linkId) {
        return checks.checkLink(linkId);
    }

    @POST
    @Path("/all")
    public ProviderCheckSummary checkAll() {
        return checks.checkAll();
    }

    @GET
    @Path("/runs")
    public List<ProviderCheckRun> runs(@QueryParam("limit") Integer limit) {
        return runs.listRecent(limit == null ? 25 : limit);
    }

    @GET
    @Path("/runs/{id}/events")
    public List<ProviderCheckEvent> events(@PathParam("id") long id) {
        return runs.listEvents(id);
    }
}
