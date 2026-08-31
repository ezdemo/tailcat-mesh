package com.tailcatmesh.agent.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Virtual-thread TCP bridge bound exclusively to the local loopback address. */
public final class TcpServiceBridge implements ServiceBridge {

    private final Map<UUID, ServiceBridgeHandle> handles = new ConcurrentHashMap<>();

    @Override
    public ServiceBridgeHandle start(ServiceRuntimeConfig config) {
        Objects.requireNonNull(config, "config");
        stop(config.serviceId());
        ServerSocket serverSocket = null;
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(config.bindHost(), config.requestedBridgePort()));
            ServiceBridgeHandle handle = new ServiceBridgeHandle(config, serverSocket);
            handles.put(config.serviceId(), handle);
            return handle;
        } catch (IOException | RuntimeException exception) {
            closeQuietly(serverSocket);
            throw new ServiceBridgeException("TM-AGENT-007",
                    "ServiceBridge could not bind its loopback port", exception);
        }
    }

    @Override
    public void stop(UUID serviceId) {
        if (serviceId == null) {
            return;
        }
        ServiceBridgeHandle handle = handles.remove(serviceId);
        if (handle != null) {
            handle.close();
        }
    }

    @Override
    public void close() {
        for (UUID serviceId : handles.keySet()) {
            stop(serviceId);
        }
    }

    private static void closeQuietly(ServerSocket socket) {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // The socket may already be closed after a bind failure.
        }
    }
}
