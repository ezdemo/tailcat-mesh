package com.tailcatmesh.agent.virtual;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Builds platform-native route commands without invoking a shell. */
public final class OsRouteCommandFactory {

    private final HostPlatform platform;

    public OsRouteCommandFactory(HostPlatform platform) {
        this.platform = Objects.requireNonNull(platform, "platform");
        if (platform == HostPlatform.UNSUPPORTED) {
            throw new IllegalArgumentException("M7 OS routes are supported only on Windows and Linux");
        }
    }

    public List<String> add(OsRoute route) {
        return build(route, false);
    }

    public List<String> delete(OsRoute route) {
        return build(route, true);
    }

    private List<String> build(OsRoute route, boolean delete) {
        Objects.requireNonNull(route, "route");
        if (route.cidr().isDefaultRoute()) {
            throw new IllegalArgumentException("M7 must not add or delete a default route");
        }
        return platform == HostPlatform.WINDOWS
                ? windows(route, delete) : linux(route, delete);
    }

    private static List<String> windows(OsRoute route, boolean delete) {
        List<String> command = new ArrayList<>(List.of(
                "netsh", "interface", "ipv4", delete ? "delete" : "add", "route",
                "prefix=" + route.networkCidr(),
                "interface=" + (route.interfaceIndex() == null
                        ? route.interfaceName() : route.interfaceIndex())));
        if (route.nextHop() != null && !route.nextHop().isBlank()) {
            command.add("nexthop=" + route.nextHop());
        }
        command.add("store=active");
        return List.copyOf(command);
    }

    private static List<String> linux(OsRoute route, boolean delete) {
        if (delete) {
            return List.of("ip", "route", "del", route.networkCidr(), "dev", route.interfaceName());
        }
        return List.of("ip", "route", "replace", route.networkCidr(), "dev", route.interfaceName());
    }
}
