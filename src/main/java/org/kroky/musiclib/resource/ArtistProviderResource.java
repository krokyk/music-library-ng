package org.kroky.musiclib.resource;

import java.util.List;
import java.util.regex.Pattern;

import org.kroky.musiclib.model.ArtistProviderCandidate;
import org.kroky.musiclib.model.ArtistProviderLink;
import org.kroky.musiclib.model.ProviderRefreshResult;
import org.kroky.musiclib.provider.ArtistProviderMatchService;
import org.kroky.musiclib.provider.ArtistProviderRefreshService;
import org.kroky.musiclib.provider.ProviderException;
import org.kroky.musiclib.provider.ProviderRegistry;
import org.kroky.musiclib.provider.musicbrainz.MusicBrainzClient;
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

@Path("/api/artists/{artistId}")
public class ArtistProviderResource {

    private static final Pattern MBID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    @Inject
    ArtistProviderLinkRepository providerLinks;

    @Inject
    ArtistProviderMatchService matches;

    @Inject
    ArtistProviderRefreshService refreshes;

    @Inject
    MusicBrainzClient musicBrainz;

    @Inject
    ProviderRegistry providerRegistry;

    @GET
    @Path("/provider")
    public ArtistProviderLink provider(@PathParam("artistId") long artistId) {
        return providerLinks.findByArtist(artistId).orElseThrow(NotFoundException::new);
    }

    @GET
    @Path("/provider-candidates/musicbrainz")
    public List<ArtistProviderCandidate> musicBrainzCandidates(@PathParam("artistId") long artistId) {
        try {
            return matches.searchMusicBrainzCandidates(artistId);
        } catch (ProviderException e) {
            throw new BadRequestException(e.getMessage(), e);
        }
    }

    @PUT
    @Path("/provider")
    public ArtistProviderLink saveProvider(@PathParam("artistId") long artistId, ProviderRequest request) {
        validateProviderRequest(request);
        String providerUrl = request.providerUrl();
        String providerArtistId = request.providerArtistId();
        if (MusicBrainzClient.PROVIDER_ID.equals(request.providerId())) {
            providerUrl = musicBrainz.artistUrl(request.providerArtistId());
        } else if (providerArtistId == null || providerArtistId.isBlank()) {
            providerArtistId = providerUrl;
        }
        return providerLinks.upsertForArtist(
                artistId,
                request.providerId(),
                providerArtistId,
                request.providerArtistName(),
                providerUrl,
                request.providerArtistType(),
                request.providerArtistCountry(),
                request.providerArtistDisambiguation(),
                request.providerArtistActive(),
                request.enabledOrDefault());
    }

    @DELETE
    @Path("/provider")
    public Response clearProvider(@PathParam("artistId") long artistId) {
        providerLinks.deleteByArtist(artistId);
        return Response.noContent().build();
    }

    @POST
    @Path("/provider/refresh")
    public ProviderRefreshResult refreshProvider(@PathParam("artistId") long artistId) {
        try {
            return refreshes.refreshArtist(artistId);
        } catch (ProviderException e) {
            throw new BadRequestException(e.getMessage(), e);
        }
    }

    private void validateProviderRequest(ProviderRequest request) {
        if (request == null) {
            throw new BadRequestException("Provider request is required");
        }
        if (MusicBrainzClient.PROVIDER_ID.equals(request.providerId())) {
            if (request.providerArtistId() == null || !MBID.matcher(request.providerArtistId()).matches()) {
                throw new BadRequestException("MusicBrainz artist MBID is invalid");
            }
            return;
        }
        if (!"spirit_of_metal".equals(request.providerId()) && !"metal_archives".equals(request.providerId())) {
            throw new BadRequestException("Unsupported provider: " + request.providerId());
        }
        if (request.providerUrl() == null || request.providerUrl().isBlank()) {
            throw new BadRequestException("Provider URL is required");
        }
        try {
            providerRegistry.find(request.providerId(), request.providerUrl());
        } catch (ProviderException e) {
            throw new BadRequestException(e.getMessage(), e);
        }
    }

    public record ProviderRequest(
            String providerId,
            String providerArtistId,
            String providerArtistName,
            String providerUrl,
            String providerArtistType,
            String providerArtistCountry,
            String providerArtistDisambiguation,
            Boolean providerArtistActive,
            Boolean enabled) {
        boolean enabledOrDefault() {
            return enabled == null || enabled;
        }
    }
}
