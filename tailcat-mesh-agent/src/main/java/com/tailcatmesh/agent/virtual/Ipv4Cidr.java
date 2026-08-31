package com.tailcatmesh.agent.virtual;

/** Strict decimal IPv4 address/prefix value used by the M7 OS boundary. */
public record Ipv4Cidr(String address, int prefixLength) {

    public Ipv4Cidr {
        address = canonicalAddress(address);
        if (prefixLength < 0 || prefixLength > 32) {
            throw new IllegalArgumentException("IPv4 prefix length must be between 0 and 32");
        }
    }

    public static Ipv4Cidr parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("IPv4 CIDR is required");
        }
        String trimmed = value.trim();
        int separator = trimmed.indexOf('/');
        if (separator <= 0 || separator == trimmed.length() - 1
                || separator != trimmed.lastIndexOf('/')) {
            throw new IllegalArgumentException("IPv4 CIDR must have the form address/prefix");
        }
        int prefix;
        try {
            prefix = Integer.parseInt(trimmed.substring(separator + 1));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("IPv4 CIDR prefix is invalid", exception);
        }
        return new Ipv4Cidr(trimmed.substring(0, separator), prefix);
    }

    public String value() {
        return address + "/" + prefixLength;
    }

    public String networkAddress() {
        long value = toLong(address);
        long mask = prefixLength == 0 ? 0 : (0xffff_ffffL << (32 - prefixLength)) & 0xffff_ffffL;
        return fromLong(value & mask);
    }

    public String networkValue() {
        return networkAddress() + "/" + prefixLength;
    }

    public String netmask() {
        long mask = prefixLength == 0 ? 0 : (0xffff_ffffL << (32 - prefixLength)) & 0xffff_ffffL;
        return fromLong(mask);
    }

    public boolean isDefaultRoute() {
        return prefixLength == 0;
    }

    private static String canonicalAddress(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("IPv4 address is required");
        }
        String[] parts = value.trim().split("\\.", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("IPv4 address must contain four decimal octets");
        }
        StringBuilder canonical = new StringBuilder();
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            if (part.isEmpty() || (part.length() > 1 && part.startsWith("0"))) {
                throw new IllegalArgumentException("IPv4 address contains an invalid octet");
            }
            int octet;
            try {
                octet = Integer.parseInt(part);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("IPv4 address contains an invalid octet", exception);
            }
            if (octet < 0 || octet > 255) {
                throw new IllegalArgumentException("IPv4 address contains an invalid octet");
            }
            if (index > 0) {
                canonical.append('.');
            }
            canonical.append(octet);
        }
        return canonical.toString();
    }

    private static long toLong(String value) {
        long result = 0;
        for (String part : value.split("\\.")) {
            result = (result << 8) | Integer.parseInt(part);
        }
        return result;
    }

    private static String fromLong(long value) {
        return ((value >>> 24) & 0xff) + "."
                + ((value >>> 16) & 0xff) + "."
                + ((value >>> 8) & 0xff) + "."
                + (value & 0xff);
    }
}
