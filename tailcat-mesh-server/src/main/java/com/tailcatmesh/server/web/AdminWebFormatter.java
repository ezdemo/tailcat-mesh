package com.tailcatmesh.server.web;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Presentation-only labels and formatting used by Thymeleaf templates. */
@Component("adminWeb")
public final class AdminWebFormatter {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern(
            "yyyy-MM-dd HH:mm", Locale.ROOT);

    private final ZoneId zoneId = ZoneId.systemDefault();

    public String date(Instant value) {
        return value == null ? "—" : DATE_TIME.withZone(zoneId).format(value);
    }

    public String relative(Instant value) {
        if (value == null) {
            return "从未上报";
        }
        long seconds = Duration.between(value, Instant.now()).getSeconds();
        if (seconds < 10) {
            return "刚刚";
        }
        if (seconds < 60) {
            return seconds + " 秒前";
        }
        long minutes = Math.round(seconds / 60.0);
        if (minutes < 60) {
            return minutes + " 分钟前";
        }
        long hours = Math.round(minutes / 60.0);
        if (hours < 24) {
            return hours + " 小时前";
        }
        return Math.round(hours / 24.0) + " 天前";
    }

    public String shorten(Object value) {
        return shorten(value, 10, 6);
    }

    public String shorten(Object value, int start, int end) {
        if (value == null) {
            return "—";
        }
        String text = String.valueOf(value);
        return text.length() <= start + end + 3
                ? text
                : text.substring(0, start) + "..." + text.substring(text.length() - end);
    }

    public boolean tokenActive(Instant expiresAt, boolean enabled) {
        return enabled && expiresAt != null && expiresAt.isAfter(Instant.now());
    }

    public int usagePercent(int used, int max) {
        if (max <= 0) {
            return 0;
        }
        return Math.min(100, Math.max(0, (int) Math.round(used * 100.0 / max)));
    }

    public String deviceStatus(Object status) {
        return switch (text(status)) {
            case "PENDING" -> "待审批";
            case "ONLINE" -> "在线";
            case "OFFLINE" -> "离线";
            case "DISABLED" -> "已禁用";
            default -> "未知";
        };
    }

    public String deviceClass(Object status) {
        return switch (text(status)) {
            case "PENDING" -> "badge-warning";
            case "ONLINE" -> "badge-success";
            case "DISABLED" -> "badge-danger";
            default -> "badge-muted";
        };
    }

    public String serviceStatus(Object status) {
        return switch (text(status)) {
            case "STARTING" -> "启动中";
            case "READY" -> "就绪";
            case "FAILED" -> "失败";
            case "STOPPED" -> "已停止";
            default -> "未知";
        };
    }

    public String serviceClass(Object status) {
        return switch (text(status)) {
            case "STARTING" -> "badge-warning";
            case "READY" -> "badge-success";
            case "FAILED" -> "badge-danger";
            default -> "badge-muted";
        };
    }

    public String forwardStatus(Object status) {
        return switch (text(status)) {
            case "STARTING" -> "启动中";
            case "READY" -> "就绪";
            case "ERROR" -> "错误";
            case "STOPPED" -> "已停止";
            default -> "未知";
        };
    }

    public String forwardClass(Object status) {
        return switch (text(status)) {
            case "STARTING" -> "badge-warning";
            case "READY" -> "badge-success";
            case "ERROR" -> "badge-danger";
            default -> "badge-muted";
        };
    }

    public String connectionStatus(Object status) {
        return switch (text(status)) {
            case "ONLINE" -> "在线";
            case "DEGRADED" -> "降级";
            case "OFFLINE" -> "离线";
            case "STOPPED" -> "已停止";
            default -> "未知";
        };
    }

    public String connectionClass(Object status) {
        return switch (text(status)) {
            case "ONLINE" -> "badge-success";
            case "DEGRADED" -> "badge-warning";
            case "OFFLINE", "STOPPED" -> "badge-danger";
            default -> "badge-muted";
        };
    }

    public String pathLabel(Object pathType, Object derpRegion) {
        String path = text(pathType);
        if ("DIRECT".equals(path)) {
            return "Direct";
        }
        if ("DERP".equals(path)) {
            String region = text(derpRegion);
            return region.isBlank() ? "DERP" : "DERP · " + region;
        }
        return switch (path) {
            case "OFFLINE" -> "Offline";
            default -> "Unknown";
        };
    }

    public String pathClass(Object pathType) {
        return switch (text(pathType)) {
            case "DIRECT" -> "path-direct";
            case "DERP" -> "path-derp";
            default -> "path-muted";
        };
    }

    public String latency(Double value) {
        if (value == null || value < 0 || !Double.isFinite(value)) {
            return "—";
        }
        return value < 10
                ? String.format(Locale.ROOT, "%.1f ms", value)
                : String.format(Locale.ROOT, "%.0f ms", value);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).toUpperCase(Locale.ROOT);
    }
}
