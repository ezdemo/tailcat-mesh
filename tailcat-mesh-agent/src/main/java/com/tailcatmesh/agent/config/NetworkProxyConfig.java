package com.tailcatmesh.agent.config;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Optional local proxy shared by the Agent's control plane and Tailcat runtime. */
public record NetworkProxyConfig(Type type, String host, int port) {

    public NetworkProxyConfig {
        type = Objects.requireNonNull(type, "type");
        host = normalizeHost(host);
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("proxy port must be between 1 and 65535");
        }
    }

    public static NetworkProxyConfig of(String type, String host, int port) {
        return new NetworkProxyConfig(Type.parse(type), host, port);
    }

    /** Returns a selector suitable for Java HttpClient HTTP and WebSocket requests. */
    public ProxySelector proxySelector() {
        Proxy.Type proxyType = type == Type.HTTP ? Proxy.Type.HTTP : Proxy.Type.SOCKS;
        InetSocketAddress address = InetSocketAddress.createUnresolved(host, port);
        Proxy proxy = new Proxy(proxyType, address);
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return List.of(proxy);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress socketAddress, IOException exception) {
                // HttpClient reports the connection failure to its request future.
            }
        };
    }

    /** Environment variables understood by the Go-based Tailcat executable. */
    public Map<String, String> environment() {
        String endpoint = endpoint();
        return Map.of(
                "HTTP_PROXY", endpoint,
                "HTTPS_PROXY", endpoint,
                "ALL_PROXY", endpoint,
                "http_proxy", endpoint,
                "https_proxy", endpoint,
                "all_proxy", endpoint
        );
    }

    public String endpoint() {
        String scheme = type == Type.HTTP ? "http" : "socks5";
        String formattedHost = host.indexOf(':') >= 0 && !host.startsWith("[")
                ? "[" + host + "]" : host;
        return scheme + "://" + formattedHost + ":" + port;
    }

    private static String normalizeHost(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.isBlank()
                || normalized.indexOf('/') >= 0
                || normalized.indexOf('\\') >= 0
                || normalized.indexOf('@') >= 0
                || normalized.chars().anyMatch(character -> Character.isWhitespace(character)
                || Character.isISOControl(character))) {
            throw new IllegalArgumentException("proxy host is invalid");
        }
        return normalized;
    }

    public enum Type {
        HTTP,
        SOCKS5;

        public static Type parse(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "http" -> HTTP;
                case "socks5", "socks" -> SOCKS5;
                default -> throw new IllegalArgumentException("proxy type must be http or socks5");
            };
        }
    }
}
