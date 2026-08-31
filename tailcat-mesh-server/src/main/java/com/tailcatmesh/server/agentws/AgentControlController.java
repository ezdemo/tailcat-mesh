package com.tailcatmesh.server.agentws;

import com.tailcatmesh.server.common.ControlPlaneException;
import com.tailcatmesh.server.device.DeviceService;
import com.tailcatmesh.server.enrollment.EnrollmentService;
import com.tailcatmesh.protocol.agent.AgentDesiredState;
import com.tailcatmesh.protocol.agent.AgentEnrollmentRequest;
import com.tailcatmesh.protocol.agent.AgentEnrollmentResponse;
import com.tailcatmesh.protocol.agent.AgentHeartbeatRequest;
import com.tailcatmesh.protocol.agent.AgentHeartbeatResponse;
import com.tailcatmesh.protocol.agent.AgentRuntimeServerRequest;
import com.tailcatmesh.protocol.agent.AgentForwardRuntimeReport;
import com.tailcatmesh.protocol.agent.AgentPeerRuntimeReport;
import com.tailcatmesh.protocol.agent.AgentServiceRuntimeReport;
import com.tailcatmesh.server.peer.PeerService;
import com.tailcatmesh.server.forward.ForwardService;
import com.tailcatmesh.server.service.ServiceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** REST control channel used by the lightweight Java Agent. */
@RestController
@RequestMapping("/api/v1/agent")
public final class AgentControlController {

    private final EnrollmentService enrollmentService;
    private final DeviceService deviceService;
    private final AgentDesiredStateService desiredStateService;
    private final ServiceService serviceService;
    private final PeerService peerService;
    private final ForwardService forwardService;

    public AgentControlController(EnrollmentService enrollmentService, DeviceService deviceService,
                                  AgentDesiredStateService desiredStateService,
                                  ServiceService serviceService,
                                  PeerService peerService,
                                  ForwardService forwardService) {
        this.enrollmentService = enrollmentService;
        this.deviceService = deviceService;
        this.desiredStateService = desiredStateService;
        this.serviceService = serviceService;
        this.peerService = peerService;
        this.forwardService = forwardService;
    }

    @PostMapping("/enroll")
    public AgentEnrollmentResponse enroll(@RequestBody AgentEnrollmentRequest request) {
        return enrollmentService.enroll(request);
    }

    @PostMapping("/heartbeat")
    public AgentHeartbeatResponse heartbeat(HttpServletRequest request,
                                            @RequestBody AgentHeartbeatRequest heartbeat) {
        AgentPrincipal principal = principal(request);
        return deviceService.heartbeat(principal.deviceId(), heartbeat);
    }

    @GetMapping("/desired-state")
    public AgentDesiredState desiredState(HttpServletRequest request) {
        AgentPrincipal principal = principal(request);
        return desiredStateService.get(principal.deviceId());
    }

    @PostMapping("/runtime/server")
    public ResponseEntity<Void> runtimeServer(HttpServletRequest request,
                                               @RequestBody AgentRuntimeServerRequest runtime) {
        deviceService.runtimeServer(principal(request).deviceId(), runtime);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/runtime/services")
    public ResponseEntity<Void> runtimeServices(HttpServletRequest request,
                                                @RequestBody AgentServiceRuntimeReport runtime) {
        serviceService.recordRuntime(principal(request).deviceId(), runtime);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/runtime/peers")
    public ResponseEntity<Void> runtimePeers(HttpServletRequest request,
                                              @RequestBody AgentPeerRuntimeReport runtime) {
        peerService.recordRuntime(principal(request).deviceId(), runtime);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/runtime/forwards")
    public ResponseEntity<Void> runtimeForwards(HttpServletRequest request,
                                                 @RequestBody AgentForwardRuntimeReport runtime) {
        forwardService.recordRuntime(principal(request).deviceId(), runtime);
        return ResponseEntity.noContent().build();
    }

    private static AgentPrincipal principal(HttpServletRequest request) {
        Object value = request.getAttribute(AgentAuthenticationFilter.PRINCIPAL_ATTRIBUTE);
        if (value instanceof AgentPrincipal principal) {
            return principal;
        }
        throw new ControlPlaneException("TM-CTRL-001", HttpStatus.UNAUTHORIZED,
                "agent authentication failed");
    }
}
