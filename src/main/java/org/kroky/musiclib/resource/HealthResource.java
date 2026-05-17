package org.kroky.musiclib.resource;

import java.time.Instant;
import java.util.Map;

import org.jboss.logging.Logger;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/api/health")
public class HealthResource {

    private static final Logger LOG = Logger.getLogger(HealthResource.class);

    @GET
    public Map<String, Object> health() {
        LOG.debug("Health check requested");
        return Map.of(
                "status", "ok",
                "app", "music-library-ng",
                "time", Instant.now().toString());
    }
}
