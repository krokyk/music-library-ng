package org.kroky.musiclib.resource;

import java.util.List;

import org.jboss.logging.Logger;
import org.kroky.musiclib.model.MusicSource;
import org.kroky.musiclib.repository.SourceRepository;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/api/sources")
public class SourceResource {

    private static final Logger LOG = Logger.getLogger(SourceResource.class);

    @Inject
    SourceRepository sources;

    @GET
    public List<MusicSource> list() {
        LOG.info("Listing music sources");
        return sources.list();
    }
}
