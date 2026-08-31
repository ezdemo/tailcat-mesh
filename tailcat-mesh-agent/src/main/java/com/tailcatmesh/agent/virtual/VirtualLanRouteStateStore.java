package com.tailcatmesh.agent.virtual;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Persists only enough local ownership metadata to remove stale Mesh routes. */
public final class VirtualLanRouteStateStore {

    private final Path path;

    public VirtualLanRouteStateStore(Path path) {
        this.path = path == null ? null : path.toAbsolutePath().normalize();
    }

    public List<OsRoute> load() {
        if (path == null || !Files.isRegularFile(path)) {
            return List.of();
        }
        List<OsRoute> routes = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(path)) {
                if (line.isBlank()) {
                    continue;
                }
                String[] fields = line.split("\\t", -1);
                if (fields.length != 4 && fields.length != 5) {
                    continue;
                }
                try {
                    Integer interfaceIndex = fields[3].isBlank() ? null : Integer.valueOf(fields[3]);
                    String nextHop = fields.length == 5 && !fields[4].isBlank() ? fields[4] : null;
                    routes.add(new OsRoute(UUID.fromString(fields[0]),
                            Ipv4Cidr.parse(fields[1]), fields[2], interfaceIndex, nextHop));
                } catch (RuntimeException ignored) {
                    // Ignore a corrupt ownership line; never turn it into an arbitrary command.
                }
            }
        } catch (IOException ignored) {
            return List.of();
        }
        return routes.stream()
                .sorted(Comparator.comparing(route -> route.networkId().toString()))
                .toList();
    }

    public void save(Collection<OsRoute> routes) {
        if (path == null) {
            return;
        }
        Objects.requireNonNull(routes, "routes");
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            String content = routes.stream()
                    .sorted(Comparator.comparing(route -> route.networkId().toString()))
                    .map(route -> route.networkId() + "\t" + route.networkCidr() + "\t"
                            + route.interfaceName() + "\t"
                            + (route.interfaceIndex() == null ? "" : route.interfaceIndex()) + "\t"
                            + (route.nextHop() == null ? "" : route.nextHop()))
                    .reduce((left, right) -> left + System.lineSeparator() + right)
                    .map(value -> value + System.lineSeparator())
                    .orElse("");
            Files.writeString(temporary, content);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new TunRuntimeException("unable to persist Virtual LAN route state", exception);
        }
    }

    public void clear() {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
            Files.deleteIfExists(path.resolveSibling(path.getFileName() + ".tmp"));
        } catch (IOException exception) {
            throw new TunRuntimeException("unable to clear Virtual LAN route state", exception);
        }
    }
}
