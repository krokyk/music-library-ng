package org.kroky.musiclib.resource;

import java.net.URI;
import java.util.List;

import org.kroky.musiclib.model.ArtistProviderLink;
import org.kroky.musiclib.repository.ArtistProviderLinkRepository;

import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

@Path("/api/artists/{artistId}/provider-links")
public class ArtistProviderLinkResource {

    @Inject
    ArtistProviderLinkRepository links;

    @GET
    public List<ArtistProviderLink> list(@PathParam("artistId") long artistId) {
        return links.listByArtist(artistId);
    }

    @POST
    public Response create(@PathParam("artistId") long artistId, ProviderLinkRequest request) {
        ArtistProviderLink link = links.create(artistId, request.providerId(), request.providerUrl(),
                request.enabledOrDefault());
        return Response.created(URI.create("/api/artists/" + artistId + "/provider-links/" + link.id()))
                .entity(link)
                .build();
    }

    @PUT
    @Path("/{linkId}")
    public ArtistProviderLink update(@PathParam("linkId") long linkId, ProviderLinkRequest request) {
        return links.update(linkId, request.providerId(), request.providerUrl(), request.enabledOrDefault())
                .orElseThrow(NotFoundException::new);
    }

    @DELETE
    @Path("/{linkId}")
    public Response delete(@PathParam("linkId") long linkId) {
        links.delete(linkId);
        return Response.noContent().build();
    }

    public record ProviderLinkRequest(String providerId, String providerUrl, Boolean enabled) {
        boolean enabledOrDefault() {
            return enabled == null || enabled;
        }
    }
}
