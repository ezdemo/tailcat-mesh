package com.tailcatmesh.agent.tailcat;

import com.tailcatmesh.agent.tailcat.model.TailcatCompatibility;
import com.tailcatmesh.agent.tailcat.model.TailcatPathType;
import com.tailcatmesh.agent.tailcat.model.TailcatPingResult;
import com.tailcatmesh.agent.tailcat.model.TailcatTokenInfo;
import com.tailcatmesh.agent.tailcat.model.TailcatVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TailcatCliParserTest {

    private final TailcatCliParser parser = new TailcatCliParser();

    @Test
    void parsesAndClassifiesSupportedVersion() {
        TailcatVersion version = parser.parseVersion("v0.3.0\n");

        assertEquals(new TailcatVersion(0, 3, 0, "v0.3.0"), version);
        assertEquals(TailcatCompatibility.SUPPORTED, parser.classify(version));
        assertEquals(TailcatCompatibility.UNSUPPORTED_OLDER,
                parser.classify(new TailcatVersion(0, 2, 9, "v0.2.9")));
        assertEquals(TailcatCompatibility.UNSUPPORTED_NEWER,
                parser.classify(new TailcatVersion(0, 4, 0, "v0.4.0")));
    }

    @Test
    void parsesServerJsonListenAddress() {
        assertEquals("tcABC_def-1234567890",
                parser.parseServerListenAddress("{\"listenAddr\":\"tcABC_def-1234567890\"}\n"));
    }

    @Test
    void parsesOfficialTokenJsonFields() {
        TailcatTokenInfo info = parser.parseTokenJson("""
                {
                    "ServerPublic": "nodekey:9c8d2e6728da80a1dd37e275a82595b42d9a838610bc53f74a7670d1610f2e34",
                    "RegionID": 302
                }
                """);

        assertTrue(info.serverPublicKey().startsWith("nodekey:"));
        assertEquals(302, info.regionId());
        assertNull(info.region());
    }

    @Test
    void parsesDirectAndDerpPingLines() {
        TailcatPingResult derp = parser.parsePingOutput("pong in 42.1ms via DERP(sfo)\n");
        TailcatPingResult direct = parser.parsePingOutput("pong in 1.2ms via 203.0.113.7:41641\n");

        assertEquals(TailcatPathType.DERP, derp.pathType());
        assertEquals(42.1, derp.latencyMs());
        assertEquals("sfo", derp.derpRegion());
        assertEquals(TailcatPathType.DIRECT, direct.pathType());
        assertEquals(1.2, direct.latencyMs());
        assertEquals("203.0.113.7:41641", direct.endpoint());
    }

    @Test
    void parsesOfficialGoDurationPingUnits() {
        TailcatPingResult microseconds = parser.parsePingOutput("pong in 540µs via DERP(1)\n");
        TailcatPingResult seconds = parser.parsePingOutput("pong in 1.2s via 203.0.113.7:41641\n");

        assertEquals(TailcatPathType.DERP, microseconds.pathType());
        assertEquals(0.54, microseconds.latencyMs(), 0.0001);
        assertEquals(1_200, seconds.latencyMs(), 0.0001);
    }

    @Test
    void unexpectedPingOutputIsUnknown() {
        TailcatPingResult result = parser.parsePingOutput("unexpected output\n");

        assertEquals(TailcatPathType.UNKNOWN, result.pathType());
        assertEquals(-1, result.latencyMs());
    }

    @Test
    void parsesOfficialSocksReadinessLine() {
        TailcatCliParser.SocksListenAddress address = parser.parseSocksListenAddress(
                "2026/08/31 13:35:57 SOCKS running at socks5h://127.0.0.1:46101");

        assertEquals("127.0.0.1", address.host());
        assertEquals(46101, address.port());
    }

    @Test
    void rejectsUnexpectedSocksOutputWithStableErrorCode() {
        TailcatEngineException exception = assertThrows(
                TailcatEngineException.class,
                () -> parser.parseSocksListenAddress("SOCKS failed"));

        assertEquals("TM-AGENT-005", exception.code());
    }

    @Test
    void malformedServerJsonHasStableErrorCode() {
        TailcatEngineException exception = assertThrows(
                TailcatEngineException.class,
                () -> parser.parseServerListenAddress("not json")
        );

        assertEquals("TM-AGENT-004", exception.code());
    }
}
