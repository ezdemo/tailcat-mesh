package com.tailcatmesh.agent.service;

import java.util.UUID;

/** Local TCP bridge boundary for a Service published by this Agent. */
public interface ServiceBridge extends AutoCloseable {

    ServiceBridgeHandle start(ServiceRuntimeConfig config);

    void stop(UUID serviceId);

    @Override
    void close();
}
