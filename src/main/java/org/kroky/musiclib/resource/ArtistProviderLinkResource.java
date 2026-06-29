package org.kroky.musiclib.resource;

import java.net.URI;
import java.util.List;

import org.kroky.musiclib.model.ArtistProviderLink;
import org.kroky.musiclib.provider.ProviderUrlNormalizer;
import org.kroky.musiclib.repository.ArtistProviderLinkRepository;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
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
        String providerUrl = normalizeUrlProvider(request);
        ArtistProviderLink link = links.upsertForArtist(artistId, request.providerId(), request.providerArtistIdOrUrl(providerUrl),
                request.providerArtistName(), providerUrl,
                request.providerCountry(), request.providerDisambiguation(),
                request.providerActive(),
                request.enabledOrDefault());
        return Response.created(URI.create("/api/artists/" + artistId + "/provider-links/" + link.id()))
                .entity(link)
                .build();
    }

    @PUT
    @Path("/{linkId}")
    public ArtistProviderLink update(@PathParam("linkId") long linkId, ProviderLinkRequest request) {
        String providerUrl = normalizeUrlProvider(request);
        return links.update(linkId, request.providerId(), request.providerArtistIdOrUrl(providerUrl),
                request.providerArtistName(), providerUrl,
                request.providerCountry(), request.providerDisambiguation(),
                request.providerActive(), request.enabledOrDefault())
                .orElseThrow(NotFoundException::new);
    }

    @DELETE
    @Path("/{linkId}")
    public Response delete(@PathParam("linkId") long linkId) {
        links.delete(linkId);
        return Response.noContent().build();
    }

    private String normalizeUrlProvider(ProviderLinkRequest request) {
        if (request == null) {
            throw new BadRequestException("Provider link request is required");
        }
        if (request.providerId() == null || request.providerId().isBlank()) {
            throw new BadRequestException("Provider id is required");
        }
        try {
            return ProviderUrlNormalizer.normalize(request.providerId(), request.providerUrl());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage(), e);
        }
    }

    public record ProviderLinkRequest(String providerId, String providerArtistId, String providerArtistName,
            String providerUrl, String providerCountry,
            String providerDisambiguation, Boolean providerActive, Boolean enabled) {
        boolean enabledOrDefault() {
            return enabled == null || enabled;
        }

        String providerArtistIdOrUrl(String providerUrl) {
            return providerArtistId == null || providerArtistId.isBlank() ? providerUrl : providerArtistId;
        }
    }
}
