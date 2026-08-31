package com.tailcatmesh.agent.virtual;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Idempotent Windows/Linux route reconciler used by the Virtual LAN supervisor. */
public final class SystemOsRouteManager implements OsRouteManager {

    private final OsCommandExecutor executor;
    private final OsRouteCommandFactory commandFactory;
    private final Duration commandTimeout;
    private final Object lock = new Object();
    private final Map<RouteKey, OsRoute> appliedRoutes = new HashMap<>();

    public SystemOsRouteManager(HostPlatform platform, OsCommandExecutor executor,
                                Duration commandTimeout) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.commandFactory = new OsRouteCommandFactory(platform);
        this.commandTimeout = requirePositive(commandTimeout);
    }

    @Override
    public void reconcile(Collection<OsRoute> desiredRoutes) {
        Objects.requireNonNull(desiredRoutes, "desiredRoutes");
        Map<RouteKey, OsRoute> desired = index(desiredRoutes);
        synchronized (lock) {
            for (Map.Entry<RouteKey, OsRoute> entry : List.copyOf(appliedRoutes.entrySet())) {
                if (!desired.containsKey(entry.getKey())) {
                    deleteBestEffort(entry.getValue());
                    appliedRoutes.remove(entry.getKey());
                }
            }
            for (Map.Entry<RouteKey, OsRoute> entry : desired.entrySet()) {
                if (!appliedRoutes.containsKey(entry.getKey())) {
                    addRequired(entry.getValue());
                    appliedRoutes.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    /** Removes only the manager's currently tracked Mesh routes. */
    @Override
    public void removeAll() {
        synchronized (lock) {
            for (OsRoute route : List.copyOf(appliedRoutes.values())) {
                deleteBestEffort(route);
            }
            appliedRoutes.clear();
        }
    }

    /** Best-effort cleanup for routes recovered from persisted Agent state. */
    public void removeKnown(Collection<OsRoute> routes) {
        Objects.requireNonNull(routes, "routes");
        Map<RouteKey, OsRoute> known = index(routes);
        for (OsRoute route : known.values()) {
            deleteBestEffort(route);
        }
        synchronized (lock) {
            Set<RouteKey> keys = new HashSet<>(known.keySet());
            appliedRoutes.keySet().removeIf(keys::contains);
        }
    }

    @Override
    public List<OsRoute> snapshot() {
        synchronized (lock) {
            return appliedRoutes.values().stream()
                    .sorted(java.util.Comparator.comparing(route -> route.networkId().toString()))
                    .toList();
        }
    }

    private void addRequired(OsRoute route) {
        OsCommandExecutor.CommandResult result = executor.execute(
                commandFactory.add(route), null, Map.of(), commandTimeout);
        if (result.exitCode() != 0) {
            throw new OsCommandException("could not add Mesh route " + route.networkCidr()
                    + (result.stderr().isBlank() ? "" : ": " + result.stderr().trim()));
        }
    }

    private void deleteBestEffort(OsRoute route) {
        try {
            executor.execute(commandFactory.delete(route), null, Map.of(), commandTimeout);
        } catch (RuntimeException ignored) {
            // Cleanup is intentionally best effort during crash/shutdown paths.
        }
    }

    private static Map<RouteKey, OsRoute> index(Collection<OsRoute> routes) {
        Map<RouteKey, OsRoute> result = new HashMap<>();
        for (OsRoute route : routes) {
            Objects.requireNonNull(route, "route");
            RouteKey key = new RouteKey(route.networkId(), route.networkCidr(),
                    route.interfaceName(), route.interfaceIndex());
            if (result.put(key, route) != null) {
                throw new IllegalArgumentException("duplicate Mesh route: " + route.networkCidr());
            }
        }
        Set<String> cidrs = new HashSet<>();
        for (OsRoute route : result.values()) {
            if (!cidrs.add(route.networkCidr())) {
                throw new IllegalArgumentException("Mesh CIDRs must not be duplicated: " + route.networkCidr());
            }
        }
        return result;
    }

    private static Duration requirePositive(Duration timeout) {
        Objects.requireNonNull(timeout, "commandTimeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("commandTimeout must be positive");
        }
        return timeout;
    }

    private record RouteKey(UUID networkId, String networkCidr, String interfaceName,
                            Integer interfaceIndex) {
    }
}
