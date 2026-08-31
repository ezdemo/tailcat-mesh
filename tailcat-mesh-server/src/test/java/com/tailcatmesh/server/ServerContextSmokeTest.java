package com.tailcatmesh.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ServerContextSmokeTest {

    @Test
    void contextLoadsWithFlywayEnabled() {
        // The test is intentionally a context smoke test for the M0 skeleton.
    }
}
