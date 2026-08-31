package com.tailcatmesh.server.peer;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Admin Connections endpoint for Peer Direct/DERP path state. */
@RestController
@RequestMapping("/api/v1/connections")
public final class PeerController {

    private final PeerService peerService;

    public PeerController(PeerService peerService) {
        this.peerService = peerService;
    }

    @GetMapping
    public List<PeerStatusView> list() {
        return peerService.list();
    }
}
