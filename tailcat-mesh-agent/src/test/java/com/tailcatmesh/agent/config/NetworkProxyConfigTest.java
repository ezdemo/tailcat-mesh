package com.tailcatmesh.agent.config;

import org.junit.jupiter.api.Test;

import java.net.Proxy;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkProxyConfigTest {

    @Test
    void createsHttpProxySelectorAndProcessEnvironment() {
        NetworkProxyConfig proxy = NetworkProxyConfig.of("http", "127.0.0.1", 7890);

        Proxy selected = proxy.proxySelector()
                .select(URI.create("https://mesh.example.test/api"))
                .get(0);

        assertEquals(Proxy.Type.HTTP, selected.type());
        assertEquals("http://127.0.0.1:7890", proxy.environment().get("HTTPS_PROXY"));
        assertEquals("http://127.0.0.1:7890", proxy.environment().get("ALL_PROXY"));
    }

    @Test
    void createsSocks5ProxyWithIpv6EndpointFormatting() {
        NetworkProxyConfig proxy = NetworkProxyConfig.of("socks5", "[::1]", 1080);

        Proxy selected = proxy.proxySelector()
                .select(URI.create("https://mesh.example.test/api"))
                .get(0);

        assertEquals(Proxy.Type.SOCKS, selected.type());
        assertEquals("socks5://[::1]:1080", proxy.endpoint());
        assertTrue(proxy.environment().containsKey("ALL_PROXY"));
    }
}
