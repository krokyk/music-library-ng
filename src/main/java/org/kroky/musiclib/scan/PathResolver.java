package org.kroky.musiclib.scan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PathResolver {

    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^([a-zA-Z]):[\\\\/](.*)$");

    public Path resolve(String configuredPath) {
        Matcher matcher = WINDOWS_DRIVE.matcher(configuredPath);
        if (!matcher.matches()) {
            return Path.of(configuredPath);
        }

        String drive = matcher.group(1).toLowerCase();
        String rest = matcher.group(2).replace('\\', '/');
        Path direct = Path.of(drive + ":/" + rest);
        if (Files.exists(direct)) {
            return direct;
        }

        Path slashMount = Path.of("/" + drive, rest);
        if (Files.exists(slashMount)) {
            return slashMount;
        }

        Path mntMount = Path.of("/mnt/" + drive, rest);
        if (Files.exists(mntMount)) {
            return mntMount;
        }

        return slashMount;
    }
}
