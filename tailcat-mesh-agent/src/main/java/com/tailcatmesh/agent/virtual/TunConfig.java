package com.tailcatmesh.agent.virtual;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Platform-neutral configuration for the one Agent-owned M7 TUN interface. */
public record TunConfig(
        String interfaceName,
        UUID adapterGuid,
        List<Ipv4Cidr> localAddresses,
        Path wintunDll
) {

    public TunConfig {
        interfaceName = validateInterfaceName(interfaceName);
        localAddresses = localAddresses == null ? List.of() : List.copyOf(localAddresses);
        Set<String> addresses = new HashSet<>();
        for (Ipv4Cidr address : localAddresses) {
            Objects.requireNonNull(address, "localAddresses contains null");
            if (address.isDefaultRoute()) {
                throw new IllegalArgumentException("a TUN local address must not use prefix /0");
            }
            if (!addresses.add(address.value())) {
                throw new IllegalArgumentException("duplicate TUN local address: " + address.value());
            }
        }
        if (wintunDll != null) {
            wintunDll = wintunDll.toAbsolutePath().normalize();
        }
    }

    public static TunConfig windows(String interfaceName, UUID adapterGuid,
                                    List<Ipv4Cidr> localAddresses, Path wintunDll) {
        return new TunConfig(interfaceName, Objects.requireNonNull(adapterGuid, "adapterGuid"),
                localAddresses, wintunDll);
    }

    public static TunConfig linux(String interfaceName, List<Ipv4Cidr> localAddresses) {
        return new TunConfig(interfaceName, null, localAddresses, null);
    }

    private static String validateInterfaceName(String value) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0
                || value.indexOf('\t') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("interfaceName must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException("interfaceName is too long");
        }
        return normalized;
    }
}
