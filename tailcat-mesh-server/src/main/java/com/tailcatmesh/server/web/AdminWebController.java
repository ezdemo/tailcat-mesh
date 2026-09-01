package com.tailcatmesh.server.web;

import com.tailcatmesh.server.auth.AdminPrincipal;
import com.tailcatmesh.server.auth.AdminSessionService;
import com.tailcatmesh.server.common.ControlPlaneException;
import com.tailcatmesh.server.device.DeviceService;
import com.tailcatmesh.server.device.DeviceStatus;
import com.tailcatmesh.server.device.DeviceView;
import com.tailcatmesh.server.enrollment.EnrollmentService;
import com.tailcatmesh.server.forward.ForwardService;
import com.tailcatmesh.server.forward.ForwardView;
import com.tailcatmesh.server.mesh.MeshNetworkService;
import com.tailcatmesh.server.mesh.MeshNetworkView;
import com.tailcatmesh.server.peer.PeerService;
import com.tailcatmesh.server.peer.PeerStatusView;
import com.tailcatmesh.server.service.ServiceService;
import com.tailcatmesh.server.service.ServiceView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** MVC boundary for the same administrator capabilities exposed by the REST API. */
@Controller
public final class AdminWebController {

    private final AdminSessionService sessionService;
    private final DeviceService deviceService;
    private final EnrollmentService enrollmentService;
    private final MeshNetworkService meshNetworkService;
    private final ServiceService serviceService;
    private final ForwardService forwardService;
    private final PeerService peerService;

    public AdminWebController(AdminSessionService sessionService,
                              DeviceService deviceService,
                              EnrollmentService enrollmentService,
                              MeshNetworkService meshNetworkService,
                              ServiceService serviceService,
                              ForwardService forwardService,
                              PeerService peerService) {
        this.sessionService = sessionService;
        this.deviceService = deviceService;
        this.enrollmentService = enrollmentService;
        this.meshNetworkService = meshNetworkService;
        this.serviceService = serviceService;
        this.forwardService = forwardService;
        this.peerService = peerService;
    }

    @GetMapping({"/", "/admin"})
    public String home() {
        return "redirect:/admin/overview";
    }

    @GetMapping("/login")
    public String loginPage(
            @RequestParam(name = "redirect", defaultValue = "/admin/overview") String redirect,
            HttpServletRequest request,
            Model model) {
        if (AdminWebSession.principal(request) != null) {
            return "redirect:" + safeRedirect(redirect);
        }
        model.addAttribute("redirect", safeRedirect(redirect));
        return "login";
    }

    @PostMapping("/login")
    public String login(
            @RequestParam(defaultValue = "") String username,
            @RequestParam(defaultValue = "") String password,
            @RequestParam(name = "redirect", defaultValue = "/admin/overview") String redirect,
            HttpServletRequest request,
            RedirectAttributes attributes) {
        try {
            AdminSessionService.LoginResult result = sessionService.login(username, password);
            AdminWebSession.establish(request, result, sessionService);
            return "redirect:" + safeRedirect(redirect);
        } catch (ControlPlaneException exception) {
            attributes.addFlashAttribute("loginError", "用户名或密码不正确，请重试。");
            return "redirect:/login?redirect=" + encodedRedirect(redirect);
        }
    }

    @PostMapping("/logout")
    public String logout(HttpServletRequest request) {
        AdminWebSession.clear(request, sessionService);
        return "redirect:/login?loggedOut=true";
    }

    @GetMapping("/admin/overview")
    public String overview(HttpServletRequest request, Model model) {
        prepare(model, request, "overview");
        List<DeviceView> devices = deviceService.list();
        List<EnrollmentService.EnrollmentTokenView> tokens = enrollmentService.listTokens();
        List<ServiceView> services = serviceService.list();
        List<ForwardView> forwards = forwardService.list();
        long activeTokens = tokens.stream().filter(token -> token.enabled()
                && token.expiresAt() != null && token.expiresAt().isAfter(Instant.now())).count();
        DashboardStats stats = new DashboardStats(
                devices.size(),
                devices.stream().filter(device -> device.status() == DeviceStatus.ONLINE).count(),
                devices.stream().filter(device -> device.status() == DeviceStatus.PENDING).count(),
                activeTokens,
                services.stream().filter(ServiceView::enabled).count(),
                forwards.stream().filter(forward -> forward.enabled()
                        && "READY".equals(forward.status())).count());
        model.addAttribute("devices", devices);
        model.addAttribute("recentDevices", devices.stream()
                .sorted(Comparator.comparing(DeviceView::updatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(5)
                .toList());
        model.addAttribute("stats", stats);
        return "admin/overview";
    }

    @GetMapping({"/admin/devices", "/admin/devices/{id}"})
    public String devices(
            @PathVariable(name = "id", required = false) UUID selectedId,
            @RequestParam(name = "q", defaultValue = "") String query,
            @RequestParam(name = "status", defaultValue = "ALL") String status,
            HttpServletRequest request,
            Model model) {
        prepare(model, request, "devices");
        List<DeviceView> allDevices = deviceService.list();
        DeviceStatus selectedStatus = parseDeviceStatus(status);
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase();
        List<DeviceView> devices = allDevices.stream()
                .filter(device -> selectedStatus == null || device.status() == selectedStatus)
                .filter(device -> normalizedQuery.isBlank() || List.of(
                                device.name(), device.hostname(), device.os(), device.arch(),
                                device.id().toString())
                        .stream().anyMatch(value -> value != null
                                && value.toLowerCase().contains(normalizedQuery)))
                .toList();
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        statusCounts.put("ALL", (long) allDevices.size());
        for (DeviceStatus deviceStatus : DeviceStatus.values()) {
            statusCounts.put(deviceStatus.name(), allDevices.stream()
                    .filter(device -> device.status() == deviceStatus).count());
        }
        model.addAttribute("devices", devices);
        model.addAttribute("deviceCount", allDevices.size());
        model.addAttribute("query", query == null ? "" : query);
        model.addAttribute("statusFilter", selectedStatus == null ? "ALL" : selectedStatus.name());
        model.addAttribute("statusCounts", statusCounts);
        if (selectedId != null) {
            model.addAttribute("selectedDevice", deviceService.get(selectedId));
        }
        return "admin/devices";
    }

    @PostMapping("/admin/devices/{id}/approve")
    public String approveDevice(@PathVariable UUID id, RedirectAttributes attributes) {
        DeviceView device = deviceService.approve(id);
        attributes.addFlashAttribute("success", device.name() + " 已通过审批。");
        return "redirect:/admin/devices/" + id;
    }

    @PostMapping("/admin/devices/{id}/disable")
    public String disableDevice(@PathVariable UUID id, RedirectAttributes attributes) {
        DeviceView device = deviceService.disable(id);
        attributes.addFlashAttribute("success", device.name() + " 已禁用。");
        return "redirect:/admin/devices/" + id;
    }

    @GetMapping("/admin/networks")
    public String networks(HttpServletRequest request, Model model) {
        prepare(model, request, "networks");
        List<MeshNetworkView> networks = meshNetworkService.list();
        List<DeviceView> devices = deviceService.list();
        Map<UUID, List<DeviceView>> availableDevices = new HashMap<>();
        for (MeshNetworkView network : networks) {
            List<UUID> memberIds = network.members().stream()
                    .filter(member -> member.enabled())
                    .map(member -> member.deviceId())
                    .toList();
            availableDevices.put(network.id(), devices.stream()
                    .filter(device -> device.status() == DeviceStatus.ONLINE
                            || device.status() == DeviceStatus.OFFLINE)
                    .filter(device -> !memberIds.contains(device.id()))
                    .toList());
        }
        model.addAttribute("networks", networks);
        model.addAttribute("devices", devices);
        model.addAttribute("availableDevices", availableDevices);
        return "admin/networks";
    }

    @PostMapping("/admin/networks/create")
    public String createNetwork(@RequestParam(defaultValue = "") String name,
                                @RequestParam(defaultValue = "") String cidr,
                                RedirectAttributes attributes) {
        meshNetworkService.create(new MeshNetworkService.NetworkRequest(name, blankToNull(cidr)));
        attributes.addFlashAttribute("success", "Virtual Network 已创建。");
        return "redirect:/admin/networks";
    }

    @PostMapping("/admin/networks/{id}/update")
    public String updateNetwork(@PathVariable UUID id,
                                @RequestParam(defaultValue = "") String name,
                                @RequestParam(defaultValue = "") String cidr,
                                @RequestParam(defaultValue = "false") boolean enabled,
                                RedirectAttributes attributes) {
        meshNetworkService.update(id, new MeshNetworkService.NetworkUpdateRequest(
                name, cidr, enabled));
        attributes.addFlashAttribute("success", "Network 配置已更新。");
        return "redirect:/admin/networks";
    }

    @PostMapping("/admin/networks/{id}/toggle")
    public String toggleNetwork(@PathVariable UUID id, RedirectAttributes attributes) {
        MeshNetworkView network = meshNetworkService.get(id);
        meshNetworkService.update(id, new MeshNetworkService.NetworkUpdateRequest(
                network.name(), network.cidr(), !network.enabled()));
        attributes.addFlashAttribute("success", network.enabled() ? "Network 已停用。" : "Network 已启用。");
        return "redirect:/admin/networks";
    }

    @PostMapping("/admin/networks/{id}/delete")
    public String deleteNetwork(@PathVariable UUID id, RedirectAttributes attributes) {
        meshNetworkService.delete(id);
        attributes.addFlashAttribute("success", "Network 已删除。");
        return "redirect:/admin/networks";
    }

    @PostMapping("/admin/networks/{id}/members/add")
    public String addNetworkMember(@PathVariable UUID id,
                                   @RequestParam(defaultValue = "") String deviceId,
                                   @RequestParam(defaultValue = "") String virtualIpv4,
                                   RedirectAttributes attributes) {
        meshNetworkService.addMember(id, new MeshNetworkService.MemberRequest(
                parseUuid(deviceId, "deviceId"), blankToNull(virtualIpv4)));
        attributes.addFlashAttribute("success", "设备已加入 Network。");
        return "redirect:/admin/networks";
    }

    @PostMapping("/admin/networks/{networkId}/members/{deviceId}/remove")
    public String removeNetworkMember(@PathVariable UUID networkId,
                                      @PathVariable UUID deviceId,
                                      RedirectAttributes attributes) {
        meshNetworkService.removeMember(networkId, deviceId);
        attributes.addFlashAttribute("success", "设备已从 Network 移除。");
        return "redirect:/admin/networks";
    }

    @GetMapping("/admin/services")
    public String services(
            @RequestParam(name = "create", defaultValue = "false") boolean create,
            @RequestParam(name = "edit", required = false) String edit,
            HttpServletRequest request,
            Model model) {
        prepare(model, request, "services");
        List<DeviceView> devices = deviceService.list();
        List<ServiceView> services = serviceService.list();
        ServiceView editing = blankToNull(edit) == null ? null
                : serviceService.get(parseUuid(edit, "service id"));
        ServiceForm form = editing == null
                ? new ServiceForm(firstUsableDevice(devices), "", "", 80, true)
                : new ServiceForm(editing.deviceId(), editing.name(), editing.targetHost(),
                editing.targetPort(), editing.enabled());
        model.addAttribute("services", services);
        model.addAttribute("devices", devices);
        model.addAttribute("deviceNames", deviceNames(devices));
        model.addAttribute("serviceForm", form);
        model.addAttribute("serviceFormOpen", create || editing != null);
        model.addAttribute("serviceFormAction", editing == null
                ? "/admin/services/create" : "/admin/services/" + editing.id() + "/update");
        model.addAttribute("serviceFormTitle", editing == null ? "发布 TCP 服务" : "编辑服务");
        model.addAttribute("lastLoadedAt", Instant.now());
        return "admin/services";
    }

    @PostMapping("/admin/services/create")
    public String createService(@RequestParam(defaultValue = "") String deviceId,
                                @RequestParam(defaultValue = "") String name,
                                @RequestParam(defaultValue = "") String targetHost,
                                @RequestParam(defaultValue = "") String targetPort,
                                @RequestParam(defaultValue = "false") boolean enabled,
                                RedirectAttributes attributes) {
        serviceService.create(new ServiceService.ServiceRequest(
                parseUuid(deviceId, "deviceId"), name, "TCP", targetHost, parsePort(targetPort), enabled));
        attributes.addFlashAttribute("success", "服务已创建，Agent 将在同步后启动 bridge。");
        return "redirect:/admin/services";
    }

    @PostMapping("/admin/services/{id}/update")
    public String updateService(@PathVariable UUID id,
                                @RequestParam(defaultValue = "") String deviceId,
                                @RequestParam(defaultValue = "") String name,
                                @RequestParam(defaultValue = "") String targetHost,
                                @RequestParam(defaultValue = "") String targetPort,
                                @RequestParam(defaultValue = "false") boolean enabled,
                                RedirectAttributes attributes) {
        serviceService.update(id, new ServiceService.ServiceRequest(
                parseUuid(deviceId, "deviceId"), name, "TCP", targetHost, parsePort(targetPort), enabled));
        attributes.addFlashAttribute("success", "服务配置已更新。");
        return "redirect:/admin/services";
    }

    @PostMapping("/admin/services/{id}/delete")
    public String deleteService(@PathVariable UUID id, RedirectAttributes attributes) {
        serviceService.delete(id);
        attributes.addFlashAttribute("success", "服务已删除。");
        return "redirect:/admin/services";
    }

    @GetMapping("/admin/forwards")
    public String forwards(
            @RequestParam(name = "create", defaultValue = "false") boolean create,
            @RequestParam(name = "edit", required = false) String edit,
            HttpServletRequest request,
            Model model) {
        prepare(model, request, "forwards");
        List<DeviceView> devices = deviceService.list();
        List<ServiceView> services = serviceService.list();
        List<ForwardView> forwards = forwardService.list();
        ForwardView editing = blankToNull(edit) == null ? null
                : forwardService.get(parseUuid(edit, "forward id"));
        UUID sourceDeviceId = editing == null ? firstUsableDevice(devices) : editing.sourceDeviceId();
        ForwardForm form = editing == null
                ? new ForwardForm(sourceDeviceId, null, "", 18080, true)
                : new ForwardForm(editing.sourceDeviceId(), editing.remoteServiceId(), editing.name(),
                editing.localBindPort(), editing.enabled());
        model.addAttribute("forwards", forwards);
        model.addAttribute("devices", devices);
        model.addAttribute("services", services);
        model.addAttribute("deviceNames", deviceNames(devices));
        model.addAttribute("formDevices", devices.stream()
                .filter(device -> device.status() != DeviceStatus.DISABLED
                        || device.id().equals(form.sourceDeviceId()))
                .toList());
        model.addAttribute("remoteServices", remoteServices(form.sourceDeviceId(), services, devices,
                form.remoteServiceId()));
        model.addAttribute("forwardForm", form);
        model.addAttribute("forwardFormOpen", create || editing != null);
        model.addAttribute("forwardFormAction", editing == null
                ? "/admin/forwards/create" : "/admin/forwards/" + editing.id() + "/update");
        model.addAttribute("forwardFormTitle", editing == null ? "新建本地转发" : "编辑本地转发");
        model.addAttribute("lastLoadedAt", Instant.now());
        return "admin/forwards";
    }

    @PostMapping("/admin/forwards/create")
    public String createForward(@RequestParam(defaultValue = "") String sourceDeviceId,
                                @RequestParam(defaultValue = "") String remoteServiceId,
                                @RequestParam(defaultValue = "") String name,
                                @RequestParam(defaultValue = "") String localBindPort,
                                @RequestParam(defaultValue = "false") boolean enabled,
                                RedirectAttributes attributes) {
        forwardService.create(new ForwardService.ForwardRequest(
                parseUuid(sourceDeviceId, "sourceDeviceId"),
                parseUuid(remoteServiceId, "remoteServiceId"),
                name, "127.0.0.1", parsePort(localBindPort), enabled));
        attributes.addFlashAttribute("success", "转发已创建，Agent 将在同步后监听本地端口。");
        return "redirect:/admin/forwards";
    }

    @PostMapping("/admin/forwards/{id}/update")
    public String updateForward(@PathVariable UUID id,
                                @RequestParam(defaultValue = "") String sourceDeviceId,
                                @RequestParam(defaultValue = "") String remoteServiceId,
                                @RequestParam(defaultValue = "") String name,
                                @RequestParam(defaultValue = "") String localBindPort,
                                @RequestParam(defaultValue = "false") boolean enabled,
                                RedirectAttributes attributes) {
        forwardService.update(id, new ForwardService.ForwardRequest(
                parseUuid(sourceDeviceId, "sourceDeviceId"),
                parseUuid(remoteServiceId, "remoteServiceId"),
                name, "127.0.0.1", parsePort(localBindPort), enabled));
        attributes.addFlashAttribute("success", "转发配置已更新。");
        return "redirect:/admin/forwards";
    }

    @PostMapping("/admin/forwards/{id}/delete")
    public String deleteForward(@PathVariable UUID id, RedirectAttributes attributes) {
        forwardService.delete(id);
        attributes.addFlashAttribute("success", "转发已删除。");
        return "redirect:/admin/forwards";
    }

    @GetMapping("/admin/connections")
    public String connections(HttpServletRequest request, Model model) {
        prepare(model, request, "connections");
        List<PeerStatusView> connections = peerService.list();
        model.addAttribute("connections", connections);
        model.addAttribute("connectionSummary", new ConnectionSummary(
                connections.stream().filter(connection -> connection.status().name().equals("ONLINE")).count(),
                connections.stream().filter(connection -> "DIRECT".equals(connection.pathType())).count(),
                connections.stream().filter(connection -> "DERP".equals(connection.pathType())).count(),
                connections.stream().filter(connection -> !connection.status().name().equals("ONLINE")).count()));
        model.addAttribute("lastLoadedAt", Instant.now());
        model.addAttribute("autoRefresh", 30_000);
        return "admin/connections";
    }

    @GetMapping("/admin/tokens")
    public String tokens(
            @RequestParam(name = "create", defaultValue = "false") boolean create,
            HttpServletRequest request,
            Model model) {
        prepare(model, request, "tokens");
        List<EnrollmentService.EnrollmentTokenView> tokens = enrollmentService.listTokens();
        List<MeshNetworkView> networks = meshNetworkService.list();
        model.addAttribute("tokens", tokens);
        model.addAttribute("networks", networks);
        model.addAttribute("networkNames", networks.stream().collect(Collectors.toMap(
                MeshNetworkView::id, MeshNetworkView::name)));
        model.addAttribute("activeTokenCount", tokens.stream().filter(token -> token.enabled()
                && token.expiresAt() != null && token.expiresAt().isAfter(Instant.now())).count());
        model.addAttribute("tokenFormOpen", create);
        model.addAttribute("tokenMaxUses", 1);
        model.addAttribute("tokenExpiresInHours", 24);
        return "admin/tokens";
    }

    @PostMapping("/admin/tokens/create")
    public String createToken(@RequestParam(defaultValue = "") String networkId,
                              @RequestParam(defaultValue = "1") String maxUses,
                              @RequestParam(defaultValue = "24") String expiresInHours,
                              RedirectAttributes attributes) {
        EnrollmentService.EnrollmentTokenCreated created = enrollmentService.createToken(
                new EnrollmentService.CreateEnrollmentTokenRequest(
                        nullableUuid(networkId), parseInteger(maxUses, "maxUses"),
                        parseInteger(expiresInHours, "expiresInHours")));
        attributes.addFlashAttribute("createdToken", created);
        attributes.addFlashAttribute("success", "加入凭证已创建；完整凭证只会显示这一次。");
        return "redirect:/admin/tokens";
    }

    @PostMapping("/admin/tokens/{id}/disable")
    public String disableToken(@PathVariable UUID id, RedirectAttributes attributes) {
        enrollmentService.disableToken(id);
        attributes.addFlashAttribute("success", "加入凭证已禁用。");
        return "redirect:/admin/tokens";
    }

    @ExceptionHandler(ControlPlaneException.class)
    public String handleControlPlane(ControlPlaneException exception,
                                     HttpServletRequest request,
                                     RedirectAttributes attributes) {
        attributes.addFlashAttribute("error", exception.getMessage() + "（" + exception.code() + "）");
        return "redirect:" + pageFor(request.getRequestURI());
    }

    private void prepare(Model model, HttpServletRequest request, String page) {
        AdminPrincipal principal = AdminWebSession.principal(request);
        model.addAttribute("currentPage", page);
        model.addAttribute("currentUser", principal == null ? "admin" : principal.username());
        model.addAttribute("autoRefresh", 0);
    }

    private static String pageFor(String uri) {
        if (uri.contains("/devices")) {
            return "/admin/devices";
        }
        if (uri.contains("/networks")) {
            return "/admin/networks";
        }
        if (uri.contains("/services")) {
            return "/admin/services";
        }
        if (uri.contains("/forwards")) {
            return "/admin/forwards";
        }
        if (uri.contains("/connections")) {
            return "/admin/connections";
        }
        if (uri.contains("/tokens")) {
            return "/admin/tokens";
        }
        return "/admin/overview";
    }

    private static String safeRedirect(String redirect) {
        if (redirect != null && (redirect.equals("/admin") || redirect.startsWith("/admin/"))) {
            return redirect;
        }
        return "/admin/overview";
    }

    private static String encodedRedirect(String redirect) {
        return URLEncoder.encode(safeRedirect(redirect), StandardCharsets.UTF_8);
    }

    private static DeviceStatus parseDeviceStatus(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return null;
        }
        try {
            return DeviceStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static UUID nullableUuid(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : parseUuid(normalized, "id");
    }

    private static UUID parseUuid(String value, String field) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            throw badRequest(field + " is required");
        }
        try {
            return UUID.fromString(normalized);
        } catch (IllegalArgumentException exception) {
            throw badRequest(field + " is invalid");
        }
    }

    private static int parsePort(String value) {
        return parseInteger(value, "port");
    }

    private static int parseInteger(String value, String field) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException exception) {
            throw badRequest(field + " must be a number");
        }
    }

    private static ControlPlaneException badRequest(String message) {
        return new ControlPlaneException("TM-CTRL-400", org.springframework.http.HttpStatus.BAD_REQUEST, message);
    }

    private static UUID firstUsableDevice(List<DeviceView> devices) {
        return devices.stream()
                .filter(device -> device.status() != DeviceStatus.DISABLED)
                .map(DeviceView::id)
                .findFirst()
                .orElse(null);
    }

    private static Map<UUID, String> deviceNames(List<DeviceView> devices) {
        Map<UUID, String> names = new HashMap<>();
        devices.forEach(device -> names.put(device.id(), device.name()));
        return names;
    }

    private static List<ServiceView> remoteServices(UUID sourceDeviceId,
                                                     List<ServiceView> services,
                                                     List<DeviceView> devices,
                                                     UUID selectedServiceId) {
        Map<UUID, DeviceView> deviceById = devices.stream().collect(Collectors.toMap(
                DeviceView::id, device -> device));
        DeviceView source = sourceDeviceId == null ? null : deviceById.get(sourceDeviceId);
        return services.stream().filter(service -> {
            DeviceView remote = deviceById.get(service.deviceId());
            boolean eligible = source != null && remote != null
                    && remote.status() != DeviceStatus.DISABLED
                    && remote.networkId().equals(source.networkId())
                    && !remote.id().equals(source.id());
            return eligible || (selectedServiceId != null && selectedServiceId.equals(service.id()));
        }).toList();
    }

    public record DashboardStats(long total, long online, long pending,
                                 long activeTokens, long services, long activeForwards) {
        public int onlineRate() {
            return total == 0 ? 0 : (int) Math.round(online * 100.0 / total);
        }
    }

    public record ConnectionSummary(long online, long direct, long derp, long unhealthy) {
    }

    public record ServiceForm(UUID deviceId, String name, String targetHost,
                              int targetPort, boolean enabled) {
    }

    public record ForwardForm(UUID sourceDeviceId, UUID remoteServiceId,
                              String name, int localBindPort, boolean enabled) {
    }
}
