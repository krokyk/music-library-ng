package org.kroky.musiclib.resource;

import java.util.List;

import org.jboss.logging.Logger;
import org.kroky.musiclib.model.MusicCollection;
import org.kroky.musiclib.repository.MusicCollectionRepository;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/api/collections")
public class MusicCollectionResource {

    private static final Logger LOG = Logger.getLogger(MusicCollectionResource.class);

    @Inject
    MusicCollectionRepository collections;

    @GET
    public List<MusicCollection> list() {
        LOG.info("Listing music collections");
        return collections.list();
    }
}
