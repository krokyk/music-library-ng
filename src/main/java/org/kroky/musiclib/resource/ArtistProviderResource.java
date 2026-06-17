package org.kroky.musiclib.resource;

import java.util.List;
import java.util.regex.Pattern;

import org.kroky.musiclib.model.AlbumReviewDecision;
import org.kroky.musiclib.model.ArtistProviderCandidate;
import org.kroky.musiclib.model.ArtistProviderLink;
import org.kroky.musiclib.model.ProviderRefreshResult;
import org.kroky.musiclib.provider.ArtistProviderMatchService;
import org.kroky.musiclib.provider.ArtistProviderRefreshService;
import org.kroky.musiclib.provider.ProviderException;
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
        if (providerUrl == null || providerUrl.isBlank()) {
            providerUrl = musicBrainz.artistUrl(request.providerArtistId());
        }
        return providerLinks.upsertForArtist(
                artistId,
                request.providerId(),
                request.providerArtistId(),
                request.providerArtistName(),
                providerUrl,
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

    @POST
    @Path("/provider/album-decisions")
    public ProviderRefreshResult applyAlbumDecisions(@PathParam("artistId") long artistId,
            AlbumDecisionRequest request) {
        try {
            return refreshes.applyDecisions(artistId, request == null ? List.of() : request.decisions());
        } catch (ProviderException e) {
            throw new BadRequestException(e.getMessage(), e);
        }
    }

    private static void validateProviderRequest(ProviderRequest request) {
        if (request == null) {
            throw new BadRequestException("Provider request is required");
        }
        if (!MusicBrainzClient.PROVIDER_ID.equals(request.providerId())) {
            throw new BadRequestException("Unsupported provider: " + request.providerId());
        }
        if (request.providerArtistId() == null || !MBID.matcher(request.providerArtistId()).matches()) {
            throw new BadRequestException("MusicBrainz artist MBID is invalid");
        }
    }

    public record ProviderRequest(
            String providerId,
            String providerArtistId,
            String providerArtistName,
            String providerUrl,
            Boolean enabled) {
        boolean enabledOrDefault() {
            return enabled == null || enabled;
        }
    }

    public record AlbumDecisionRequest(List<AlbumReviewDecision> decisions) {
    }
}
