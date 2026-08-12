package org.kroky.musiclib.resource;

import java.util.List;
import java.util.regex.Pattern;

import org.kroky.musiclib.model.ArtistProviderCandidate;
import org.kroky.musiclib.model.ArtistProviderLink;
import org.kroky.musiclib.provider.ArtistProviderMatchService;
import org.kroky.musiclib.provider.ProviderException;
import org.kroky.musiclib.provider.ProviderRegistry;
import org.kroky.musiclib.provider.ProviderUrlNormalizer;
import org.kroky.musiclib.provider.musicbrainz.MusicBrainzClient;
import org.kroky.musiclib.repository.ArtistProviderLinkRepository;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
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
    MusicBrainzClient musicBrainz;

    @Inject
    ProviderRegistry providerRegistry;

    @GET
    @Path("/provider-candidates/{providerId}")
    public List<ArtistProviderCandidate> providerCandidates(@PathParam("artistId") long artistId,
            @PathParam("providerId") String providerId) {
        try {
            return matches.searchCandidates(artistId, providerId);
        } catch (ProviderException e) {
            throw new BadRequestException(e.getMessage(), e);
        }
    }

    @PUT
    @Path("/provider")
    public ArtistProviderLink saveProvider(@PathParam("artistId") long artistId, ProviderRequest request) {
        String normalizedProviderUrl = validateProviderRequest(request);
        String providerUrl = request.providerUrl();
        String providerArtistId = request.providerArtistId();
        if (MusicBrainzClient.PROVIDER_ID.equals(request.providerId())) {
            providerUrl = musicBrainz.artistUrl(request.providerArtistId());
        } else if (providerArtistId == null || providerArtistId.isBlank()) {
            providerUrl = normalizedProviderUrl;
            providerArtistId = providerUrl;
        } else {
            providerUrl = normalizedProviderUrl;
        }
        var metadata = matches.enrichSelectedMetadata(
                request.providerId(), providerUrl, request.providerCountry(), request.providerActive());
        try {
            return providerLinks.upsertForArtist(
                    artistId,
                    request.providerId(),
                    providerArtistId,
                    request.providerArtistName(),
                    providerUrl,
                    metadata.country(),
                    request.providerDisambiguation(),
                    metadata.active(),
                    request.enabledOrDefault());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage(), e);
        }
    }

    @DELETE
    @Path("/providers/{providerId}")
    public Response clearProvider(@PathParam("artistId") long artistId, @PathParam("providerId") String providerId) {
        providerLinks.deleteByArtistAndProvider(artistId, providerId);
        return Response.noContent().build();
    }

    private String validateProviderRequest(ProviderRequest request) {
        if (request == null) {
            throw new BadRequestException("Provider request is required");
        }
        if (MusicBrainzClient.PROVIDER_ID.equals(request.providerId())) {
            if (request.providerArtistId() == null || !MBID.matcher(request.providerArtistId()).matches()) {
                throw new BadRequestException("MusicBrainz artist MBID is invalid");
            }
            return null;
        }
        if (!"spirit_of_metal".equals(request.providerId()) && !"metal_archives".equals(request.providerId())) {
            throw new BadRequestException("Unsupported provider: " + request.providerId());
        }
        String normalizedProviderUrl;
        try {
            normalizedProviderUrl = "metal_archives".equals(request.providerId())
                    ? ProviderUrlNormalizer.normalizeMetalArchives(request.providerUrl(), request.providerArtistName())
                    : ProviderUrlNormalizer.normalize(request.providerId(), request.providerUrl());
            providerRegistry.find(request.providerId(), normalizedProviderUrl);
        } catch (ProviderException | IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage(), e);
        }
        return normalizedProviderUrl;
    }

    public record ProviderRequest(
            String providerId,
            String providerArtistId,
            String providerArtistName,
            String providerUrl,
            String providerCountry,
            String providerDisambiguation,
            Boolean providerActive,
            Boolean enabled) {
        boolean enabledOrDefault() {
            return enabled == null || enabled;
        }
    }
}
