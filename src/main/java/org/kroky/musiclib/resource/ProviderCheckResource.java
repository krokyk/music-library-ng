package org.kroky.musiclib.resource;

import org.kroky.musiclib.model.ProviderCheckJobStatus;
import org.kroky.musiclib.provider.ProviderCheckJobService;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;

@Path("/api/provider-checks")
public class ProviderCheckResource {

    @Inject
    ProviderCheckJobService jobs;

    @POST
    @Path("/jobs/artist/{artistId}")
    public ProviderCheckJobStatus startArtistJob(
            @PathParam("artistId") long artistId,
            @QueryParam("collectionId") String collectionId) {
        return jobs.startArtist(artistId, collectionId);
    }

    @POST
    @Path("/jobs/collection/{collectionId}")
    public ProviderCheckJobStatus startCollectionJob(@PathParam("collectionId") String collectionId) {
        return jobs.startCollection(collectionId);
    }

    @GET
    @Path("/jobs/current")
    public ProviderCheckJobStatus currentJob() {
        return jobs.current();
    }

}
