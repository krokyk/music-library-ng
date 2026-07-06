package org.kroky.musiclib.resource;

import java.util.List;

import org.kroky.musiclib.model.ProviderReleaseDateConflict;
import org.kroky.musiclib.model.ProviderTitleConflict;
import org.kroky.musiclib.repository.AlbumProviderLinkRepository;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/api/provider-conflicts")
public class ProviderConflictResource {

    @Inject
    AlbumProviderLinkRepository providerLinks;

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
}
