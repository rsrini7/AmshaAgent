package com.amsha.app.cli;

import java.util.Locale;
import java.util.Set;

/** Shared command and option tokens used before Quarkus has started. */
public final class CliArguments {

    public static final String COMMAND_ROOT = "amsha";
    public static final String COMMAND_MODELS = "models";
    public static final String COMMAND_SERVE = "serve";
    public static final String COMMAND_CHAT = "chat";
    public static final String COMMAND_ASK = "ask";
    public static final String COMMAND_ATTACH = "attach";
    public static final String COMMAND_LIST = "list";
    public static final String COMMAND_INSTALL = "install";
    public static final String COMMAND_VERIFY = "verify";

    public static final String OPTION_HELP_LONG = "--help";
    public static final String OPTION_HELP_SHORT = "-h";
    public static final String OPTION_VERSION_LONG = "--version";
    public static final String OPTION_VERSION_SHORT = "-v";
    public static final String OPTION_VERSION_SHORT_UPPER = "-V";

    private static final Set<String> HELP_OPTIONS = Set.of(OPTION_HELP_LONG, OPTION_HELP_SHORT);
    private static final Set<String> VERSION_OPTIONS =
            Set.of(OPTION_VERSION_LONG, OPTION_VERSION_SHORT, OPTION_VERSION_SHORT_UPPER);

    private CliArguments() {}

    public static boolean isHelpOption(String argument) {
        return argument != null && HELP_OPTIONS.contains(argument.toLowerCase(Locale.ROOT));
    }

    public static boolean isVersionOption(String argument) {
        return argument != null && VERSION_OPTIONS.contains(argument.toLowerCase(Locale.ROOT));
    }

    public static boolean isInformational(String[] args) {
        if (args == null || args.length == 0) {
            return true;
        }
        String firstArgument = args[0];
        return isHelpOption(firstArgument)
                || isVersionOption(firstArgument)
                || COMMAND_MODELS.equalsIgnoreCase(firstArgument);
    }
}
