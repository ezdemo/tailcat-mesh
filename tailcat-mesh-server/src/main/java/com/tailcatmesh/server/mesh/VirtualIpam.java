package com.tailcatmesh.server.mesh;

import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Validates virtual IPv4 networks and allocates stable member addresses.
 *
 * <p>The allocator deliberately owns all virtual-IP decisions. Callers may
 * request a specific host address, but they cannot bypass CIDR membership,
 * network/broadcast reservations, or the per-network uniqueness check.</p>
 */
@Component
public final class VirtualIpam {

    public static final String DEFAULT_POOL_CIDR = "10.77.0.0/16";
    public static final int DEFAULT_NETWORK_PREFIX_LENGTH = 24;

    private static final int MIN_NETWORK_PREFIX_LENGTH = 8;
    private static final int MAX_NETWORK_PREFIX_LENGTH = 29;

    private final Supplier<List<String>> localCidrSupplier;

    public VirtualIpam() {
        this(VirtualIpam::localIpv4Cidrs);
    }

    VirtualIpam(Supplier<List<String>> localCidrSupplier) {
        this.localCidrSupplier = localCidrSupplier;
    }

    /** Canonicalizes and validates a user-provided virtual network CIDR. */
    public String canonicalizeNetworkCidr(String value) {
        Cidr cidr = parse(value);
        validateNetworkSize(cidr);
        return cidr.toString();
    }

    /**
     * Allocates the first free /24 from the default 10.77.0.0/16 pool.
     * Existing and locally-advertised CIDRs are treated as reservations.
     */
    public String allocateDefaultCidr(Collection<String> existingCidrs) {
        List<String> reserved = new ArrayList<>();
        if (existingCidrs != null) {
            reserved.addAll(existingCidrs);
        }
        reserved.addAll(localCidrSupplier.get());
        return nextDefaultCidr(reserved);
    }

    /**
     * Repository compatibility helper for pre-M7 callers that insert a
     * control-plane network without a CIDR. It does not inspect host routes;
     * service-level M7 creation uses {@link #allocateDefaultCidr(Collection)}.
     */
    public static String nextDefaultCidr(Collection<String> reservedCidrs) {
        Cidr pool = parse(DEFAULT_POOL_CIDR);
        Set<Cidr> reserved = parseAll(reservedCidrs);
        long blockSize = 1L << (32 - DEFAULT_NETWORK_PREFIX_LENGTH);
        long blockCount = 1L << (DEFAULT_NETWORK_PREFIX_LENGTH - 16);
        for (long offset = 0; offset < blockCount; offset++) {
            Cidr candidate = new Cidr(pool.network() + offset * blockSize,
                    pool.network() + (offset + 1) * blockSize - 1,
                    DEFAULT_NETWORK_PREFIX_LENGTH);
            if (reserved.stream().noneMatch(candidate::overlaps)) {
                return candidate.toString();
            }
        }
        throw new IllegalArgumentException("default virtual IPv4 pool is exhausted");
    }

    /** Fails if a candidate overlaps any existing or local IPv4 CIDR. */
    public void ensureNoOverlap(String candidateCidr, Collection<String> existingCidrs) {
        Cidr candidate = parseAndValidateNetwork(candidateCidr);
        if (existingCidrs != null) {
            for (String existing : existingCidrs) {
                if (existing == null || existing.isBlank()) {
                    continue;
                }
                if (candidate.overlaps(parse(existing))) {
                    throw new IllegalArgumentException("virtual IPv4 CIDR overlaps an existing network");
                }
            }
        }
        for (String local : localCidrSupplier.get()) {
            if (candidate.overlaps(parse(local))) {
                throw new IllegalArgumentException("virtual IPv4 CIDR overlaps a local interface network");
            }
        }
    }

    /**
     * Allocates an address in a network. Existing disabled memberships are
     * included in {@code reservedIps} so a re-created membership cannot cause
     * an old runtime to receive a different address accidentally.
     */
    public String allocateMemberIp(String networkCidr, Collection<String> reservedIps,
                                   String requestedIp) {
        Cidr network = parseAndValidateNetwork(networkCidr);
        Set<Long> reserved = parseIps(reservedIps);
        if (requestedIp != null && !requestedIp.isBlank()) {
            long address = parseIpv4(requestedIp.trim());
            validateHostAddress(network, address);
            if (!reserved.add(address)) {
                throw new IllegalArgumentException("virtual IPv4 address is already assigned");
            }
            return formatIpv4(address);
        }

        for (long address = network.network() + 2; address < network.broadcast(); address++) {
            if (!reserved.contains(address)) {
                return formatIpv4(address);
            }
        }
        throw new IllegalArgumentException("virtual IPv4 network has no available member address");
    }

    public boolean contains(String networkCidr, String ipv4) {
        Cidr network = parseAndValidateNetwork(networkCidr);
        return network.contains(parseIpv4(ipv4));
    }

    public static boolean overlaps(String first, String second) {
        return parse(first).overlaps(parse(second));
    }

    private Cidr parseAndValidateNetwork(String value) {
        Cidr cidr = parse(value);
        validateNetworkSize(cidr);
        return cidr;
    }

    private static void validateNetworkSize(Cidr cidr) {
        if (cidr.prefixLength() < MIN_NETWORK_PREFIX_LENGTH
                || cidr.prefixLength() > MAX_NETWORK_PREFIX_LENGTH) {
            throw new IllegalArgumentException("virtual IPv4 CIDR prefix must be between /8 and /29");
        }
    }

    private static void validateHostAddress(Cidr network, long address) {
        if (!network.contains(address) || address <= network.network() + 1
                || address >= network.broadcast()) {
            throw new IllegalArgumentException("virtual IPv4 address is outside the usable member range");
        }
    }

    private static Set<Cidr> parseAll(Collection<String> cidrs) {
        Set<Cidr> result = new HashSet<>();
        if (cidrs == null) {
            return result;
        }
        for (String cidr : cidrs) {
            if (cidr != null && !cidr.isBlank()) {
                result.add(parse(cidr));
            }
        }
        return result;
    }

    private static Set<Long> parseIps(Collection<String> ips) {
        Set<Long> result = new HashSet<>();
        if (ips != null) {
            for (String ip : ips) {
                if (ip != null && !ip.isBlank()) {
                    result.add(parseIpv4(ip.trim()));
                }
            }
        }
        return result;
    }

    static Cidr parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("virtual IPv4 CIDR is required");
        }
        String[] parts = value.trim().split("/", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("virtual IPv4 CIDR must use address/prefix format");
        }
        int prefix;
        try {
            prefix = Integer.parseInt(parts[1]);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("virtual IPv4 CIDR prefix is invalid", exception);
        }
        if (prefix < 0 || prefix > 32) {
            throw new IllegalArgumentException("virtual IPv4 CIDR prefix is invalid");
        }
        long address = parseIpv4(parts[0]);
        long mask = prefix == 0 ? 0 : (0xffffffffL << (32 - prefix)) & 0xffffffffL;
        long network = address & mask;
        long broadcast = network | (~mask & 0xffffffffL);
        return new Cidr(network, broadcast, prefix);
    }

    static long parseIpv4(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("IPv4 address is required");
        }
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            throw new IllegalArgumentException("IPv4 address is invalid");
        }
        long result = 0;
        for (String octet : octets) {
            if (octet.isEmpty() || !octet.chars().allMatch(Character::isDigit)) {
                throw new IllegalArgumentException("IPv4 address is invalid");
            }
            int valuePart;
            try {
                valuePart = Integer.parseInt(octet);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("IPv4 address is invalid", exception);
            }
            if (valuePart < 0 || valuePart > 255) {
                throw new IllegalArgumentException("IPv4 address is invalid");
            }
            result = (result << 8) | valuePart;
        }
        return result;
    }

    static String formatIpv4(long value) {
        return ((value >>> 24) & 0xff) + "."
                + ((value >>> 16) & 0xff) + "."
                + ((value >>> 8) & 0xff) + "."
                + (value & 0xff);
    }

    private static List<String> localIpv4Cidrs() {
        List<String> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return result;
            }
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                    InetAddress address = interfaceAddress.getAddress();
                    short prefix = interfaceAddress.getNetworkPrefixLength();
                    if (address instanceof Inet4Address && prefix >= 0 && prefix <= 32) {
                        result.add(new Cidr(
                                parseIpv4(address.getHostAddress()) &
                                        (prefix == 0 ? 0 : (0xffffffffL << (32 - prefix)) & 0xffffffffL),
                                0,
                                prefix
                        ).withBroadcast().toString());
                    }
                }
            }
        } catch (SocketException ignored) {
            // If the platform cannot enumerate interfaces, the server still
            // performs the authoritative existing-Mesh overlap check.
        }
        return result;
    }

    /** Inclusive IPv4 network range represented as unsigned 32-bit values. */
    record Cidr(long network, long broadcast, int prefixLength) {

        Cidr {
            network &= 0xffffffffL;
            broadcast &= 0xffffffffL;
        }

        boolean contains(long address) {
            long normalized = address & 0xffffffffL;
            return normalized >= network && normalized <= broadcast;
        }

        boolean overlaps(Cidr other) {
            return network <= other.broadcast && other.network <= broadcast;
        }

        Cidr withBroadcast() {
            long mask = prefixLength == 0 ? 0 : (0xffffffffL << (32 - prefixLength)) & 0xffffffffL;
            return new Cidr(network, network | (~mask & 0xffffffffL), prefixLength);
        }

        @Override
        public String toString() {
            return formatIpv4(network) + "/" + prefixLength;
        }
    }
}
