package org.kroky.musiclib.resource;

import java.util.List;

import org.jboss.logging.Logger;
import org.kroky.musiclib.model.Artist;
import org.kroky.musiclib.model.MusicCollection;
import org.kroky.musiclib.repository.ArtistRepository;
import org.kroky.musiclib.repository.MusicCollectionRepository;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

@Path("/api/collections")
public class MusicCollectionResource {

    private static final Logger LOG = Logger.getLogger(MusicCollectionResource.class);

    @Inject
    MusicCollectionRepository collections;

    @Inject
    ArtistRepository artists;

    @GET
    public List<MusicCollection> list() {
        LOG.info("Listing music collections");
        return collections.list();
    }

    @GET
    @Path("/{id}/artists")
    public List<Artist> artists(@PathParam("id") String id) {
        LOG.infof("Listing artists for collection %s", id);
        return artists.list(null, id);
    }
}
