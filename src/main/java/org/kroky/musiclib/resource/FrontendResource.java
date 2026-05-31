package org.kroky.musiclib.resource;

import java.io.InputStream;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

@Path("/")
public class FrontendResource {

    private static final String INDEX_HTML = "META-INF/resources/index.html";

    @GET
    @Path("{path:.*}")
    @Produces(MediaType.TEXT_HTML)
    public Response frontend(@Context UriInfo uriInfo) {
        String path = uriInfo.getPath();
        if (!isFrontendRoute(path)) {
            throw new NotFoundException();
        }

        InputStream index = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(INDEX_HTML);
        if (index == null) {
            throw new NotFoundException();
        }

        return Response.ok(index)
                .type(MediaType.TEXT_HTML_TYPE)
                .header("Cache-Control", "no-cache")
                .build();
    }

    private static boolean isFrontendRoute(String path) {
        if (path == null || path.isBlank()) {
            return true;
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        return !normalized.startsWith("api/")
                && !normalized.equals("api")
                && !normalized.startsWith("q/")
                && !normalized.equals("q")
                && !normalized.contains(".");
    }
}
