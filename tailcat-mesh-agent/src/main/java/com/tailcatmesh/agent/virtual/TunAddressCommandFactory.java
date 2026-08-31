package com.tailcatmesh.agent.virtual;

import java.util.List;

/** Builds address-only commands for the platform TUN adapters. */
final class TunAddressCommandFactory {

    private TunAddressCommandFactory() {
    }

    static List<String> windowsSetAddress(String interfaceName, Ipv4Cidr address) {
        return List.of(
                "netsh", "interface", "ipv4", "set", "address",
                "name=" + interfaceName,
                "source=static",
                "address=" + address.address(),
                "mask=" + address.netmask());
    }

    static List<String> windowsAddAddress(String interfaceName, Ipv4Cidr address) {
        return List.of(
                "netsh", "interface", "ipv4", "add", "address",
                "name=" + interfaceName,
                "address=" + address.address(),
                "mask=" + address.netmask(),
                "store=active");
    }

    static List<String> windowsDeleteAddress(String interfaceName, Ipv4Cidr address) {
        return List.of(
                "netsh", "interface", "ipv4", "delete", "address",
                "name=" + interfaceName,
                "address=" + address.address(),
                "store=active");
    }
}
