package com.tailcatmesh.server.web;

import com.tailcatmesh.server.auth.AdminPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** Smoke coverage for the server-rendered admin surface and session guard. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminWebControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticatedAdminRequestRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/admin/overview"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?redirect=%2Fadmin%2Foverview"));
    }

    @Test
    void allAdminPagesRenderWithTheServerSession() throws Exception {
        MockHttpSession session = authenticatedSession();
        page("/admin/overview", "admin/overview", session);
        page("/admin/devices", "admin/devices", session);
        page("/admin/networks", "admin/networks", session);
        page("/admin/services", "admin/services", session);
        page("/admin/forwards", "admin/forwards", session);
        page("/admin/connections", "admin/connections", session);
        page("/admin/tokens", "admin/tokens", session);
    }

    private void page(String path, String viewName, MockHttpSession session) throws Exception {
        mockMvc.perform(get(path).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name(viewName))
                .andExpect(content().string(containsString("Tailcat Mesh")));
    }

    private MockHttpSession authenticatedSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AdminWebSession.PRINCIPAL_ATTRIBUTE,
                new AdminPrincipal(UUID.randomUUID(), "admin", "ADMIN"));
        return session;
    }
}
