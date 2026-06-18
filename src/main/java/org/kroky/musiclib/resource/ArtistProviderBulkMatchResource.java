package org.kroky.musiclib.resource;

import org.jboss.logging.Logger;
import org.kroky.musiclib.model.ArtistProviderBulkMatchRequest;
import org.kroky.musiclib.model.ArtistProviderBulkMatchResult;
import org.kroky.musiclib.provider.ArtistProviderBulkMatchService;

import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

@Path("/api/provider-matches/musicbrainz/artists")
public class ArtistProviderBulkMatchResource {

    private static final Logger LOG = Logger.getLogger(ArtistProviderBulkMatchResource.class);

    @Inject
    ArtistProviderBulkMatchService bulkMatches;

    @POST
    public ArtistProviderBulkMatchResult match(ArtistProviderBulkMatchRequest request) {
        int requested = request == null || request.artistIds() == null ? 0 : request.artistIds().size();
        LOG.infof("Bulk MusicBrainz artist match request artists=%d", requested);
        return bulkMatches.matchMusicBrainzArtists(request == null ? null : request.artistIds());
    }
}
