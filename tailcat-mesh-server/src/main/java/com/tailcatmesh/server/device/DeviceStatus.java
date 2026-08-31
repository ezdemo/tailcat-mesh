package com.tailcatmesh.server.device;

/** Control-plane lifecycle state of a registered Agent device. */
public enum DeviceStatus {
    PENDING,
    ONLINE,
    OFFLINE,
    DISABLED
}
