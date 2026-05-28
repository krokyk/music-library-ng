package org.kroky.musiclib.resource;

import org.jboss.logging.Logger;
import org.kroky.musiclib.model.UserPreference;
import org.kroky.musiclib.repository.UserPreferenceRepository;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

@Path("/api/preferences")
public class UserPreferenceResource {

    private static final Logger LOG = Logger.getLogger(UserPreferenceResource.class);

    @Inject
    UserPreferenceRepository preferences;

    @GET
    @Path("/{key}")
    public UserPreference find(@PathParam("key") String key) {
        LOG.debugf("Loading user preference key=%s", key);
        return preferences.find(key).orElseThrow(NotFoundException::new);
    }

    @PUT
    @Path("/{key}")
    public UserPreference save(@PathParam("key") String key, UserPreferenceRequest request) {
        if (request == null || request.value() == null || request.value().isBlank()) {
            throw new BadRequestException("value is required");
        }
        LOG.debugf("Save user preference request key=%s", key);
        return preferences.save(key, request.value());
    }

    public record UserPreferenceRequest(String value) {
    }
}
