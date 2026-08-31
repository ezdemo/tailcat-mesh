package com.tailcatmesh.agent.virtual;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Expands the configured tun2socks argument template into a no-shell command. */
public final class Tun2SocksCommandFactory {

    public List<String> build(Tun2SocksConfig config) {
        Objects.requireNonNull(config, "config");
        if (config.argumentTemplate().isEmpty()) {
            throw new IllegalArgumentException(
                    "tun2socks argumentTemplate is required; configure the selected binary explicitly");
        }
        String proxy = "socks5://" + config.proxy().host() + ":" + config.proxy().port();
        List<String> command = new ArrayList<>();
        command.add(config.binary().toString());
        for (String template : config.argumentTemplate()) {
            String expanded = template
                    .replace("${tun}", config.interfaceName())
                    .replace("${proxy}", proxy)
                    .replace("${proxy-host}", config.proxy().host())
                    .replace("${proxy-port}", Integer.toString(config.proxy().port()));
            if (expanded.contains("${")) {
                throw new IllegalArgumentException("unknown tun2socks argument placeholder: " + template);
            }
            command.add(expanded);
        }
        return List.copyOf(command);
    }
}
