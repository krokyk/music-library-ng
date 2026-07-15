package org.kroky.musiclib.resource;

import java.util.List;

import org.kroky.musiclib.model.ArtistCountryConflict;
import org.kroky.musiclib.model.ArtistStatusConflict;
import org.kroky.musiclib.model.ProviderReleaseDateConflict;
import org.kroky.musiclib.model.ProviderTitleConflict;
import org.kroky.musiclib.repository.AlbumProviderLinkRepository;
import org.kroky.musiclib.repository.ArtistProviderLinkRepository;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/api/provider-conflicts")
public class ProviderConflictResource {

    @Inject
    AlbumProviderLinkRepository providerLinks;

    @Inject
    ArtistProviderLinkRepository artistProviderLinks;

    @GET
    @Path("/release-dates")
    public List<ProviderReleaseDateConflict> releaseDateConflicts() {
        return providerLinks.listReleaseDateConflicts();
    }

    @GET
    @Path("/titles")
    public List<ProviderTitleConflict> titleConflicts() {
        return providerLinks.listTitleConflicts();
    }

    @GET
    @Path("/artist-countries")
    public List<ArtistCountryConflict> artistCountryConflicts() {
        return artistProviderLinks.listCountryConflicts();
    }

    @GET
    @Path("/artist-statuses")
    public List<ArtistStatusConflict> artistStatusConflicts() {
        return artistProviderLinks.listStatusConflicts();
    }
}
