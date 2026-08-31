package com.tailcatmesh.agent.command;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/** Parsed, side-effect-free command-line options for the user-facing Agent. */
public record AgentCliOptions(
        Command command,
        Path configPath,
        URI serverUrl,
        String enrollmentToken,
        Path tailcatBinary,
        Path dataDir,
        boolean once,
        boolean help
) {
    public enum Command {
        CONNECT,
        RUN,
        VERSION,
        HELP
    }

    public static AgentCliOptions parse(String[] args) {
        List<String> arguments = args == null ? List.of() : List.of(args);
        if (arguments.isEmpty()) {
            return new AgentCliOptions(Command.HELP, Path.of("agent.yml"), null, null, null, null, false, true);
        }
        int index = 0;
        String first = arguments.get(index);
        Command command;
        if ("--version".equals(first)) {
            return new AgentCliOptions(Command.VERSION, Path.of("agent.yml"), null, null, null, null, false, false);
        } else if ("--help".equals(first) || "-h".equals(first)) {
            return new AgentCliOptions(Command.HELP, Path.of("agent.yml"), null, null, null, null, false, true);
        } else if (first.startsWith("--")) {
            command = Command.CONNECT;
        } else {
            command = command(first);
            index++;
        }
        Path config = Path.of("agent.yml");
        URI server = null;
        String token = null;
        Path binary = null;
        Path dataDir = null;
        boolean once = false;
        boolean help = command == Command.HELP;

        while (index < arguments.size()) {
            String argument = arguments.get(index++);
            if ("--help".equals(argument) || "-h".equals(argument)) {
                help = true;
                continue;
            }
            if ("--once".equals(argument)) {
                once = true;
                continue;
            }
            ParsedOption parsed = splitOption(argument);
            String value = parsed.inlineValue();
            if (value == null) {
                if (index >= arguments.size()) {
                    throw new IllegalArgumentException(parsed.name() + " requires a value");
                }
                value = arguments.get(index++);
            }
            if (value.isBlank()) {
                throw new IllegalArgumentException(parsed.name() + " must not be blank");
            }
            switch (parsed.name()) {
                case "--config" -> config = Path.of(value);
                case "--server" -> server = parseUri(value);
                case "--token" -> token = value;
                case "--tailcat-binary" -> binary = Path.of(value);
                case "--data-dir" -> dataDir = Path.of(value);
                default -> throw new IllegalArgumentException("unknown option: " + parsed.name());
            }
        }
        return new AgentCliOptions(command, config, server, token, binary, dataDir, once, help);
    }

    private static Command command(String value) {
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "connect" -> Command.CONNECT;
            case "run", "start" -> Command.RUN;
            case "version" -> Command.VERSION;
            case "help" -> Command.HELP;
            default -> throw new IllegalArgumentException("unknown command: " + value);
        };
    }

    private static ParsedOption splitOption(String argument) {
        int separator = argument.indexOf('=');
        String name = separator < 0 ? argument : argument.substring(0, separator);
        if (!name.startsWith("--")) {
            throw new IllegalArgumentException("options must start with --: " + argument);
        }
        String inline = separator < 0 ? null : argument.substring(separator + 1);
        return new ParsedOption(name, inline);
    }

    private static URI parseUri(String value) {
        try {
            return URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("--server is not a valid URI", exception);
        }
    }

    private record ParsedOption(String name, String inlineValue) {
    }
}
