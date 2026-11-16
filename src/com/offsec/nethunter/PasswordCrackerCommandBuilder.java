package com.offsec.nethunter;

import com.offsec.nethunter.utils.ShellExecuter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class PasswordCrackerCommandBuilder {

    public enum Engine {
        HASHCAT,
        JOHN
    }

    public enum Attack {
        DICTIONARY,
        MASK,
        HYBRID_WORDLIST_MASK,
        HYBRID_MASK_WORDLIST,
        INCREMENTAL
    }

    public static class Config {
        public Engine engine = Engine.HASHCAT;
        public Attack attack = Attack.DICTIONARY;

        public String hashFile = "";
        public String wordlist = "";
        public String ruleFile = "";
        public String mask = "";
        public String format = "";
        public String session = "nethunter";
        public String incrementMode = "ASCII";

        public int hashMode = 0;
        public int workload = 2;

        public boolean optimizedKernel = false;
        public boolean showStatus = true;
    }

    private static final Pattern SESSION_PATTERN =
            Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private PasswordCrackerCommandBuilder() {
    }

    public static String build(Config config) {

        if (config == null) {
            throw new IllegalArgumentException(
                    "Configuration cannot be null.");
        }

        if (config.engine == null || config.attack == null) {
            throw new IllegalArgumentException(
                    "Select an engine and attack mode.");
        }

        requireValue(config.hashFile, "Hash file");

        if (config.engine == Engine.HASHCAT) {
            return buildHashcat(config);
        }

        return buildJohn(config);
    }

    private static String buildHashcat(Config config) {

        if (config.attack == Attack.INCREMENTAL) {
            throw new IllegalArgumentException(
                    "Use Mask mode with increment enabled for Hashcat.");
        }

        if (config.hashMode < 0) {
            throw new IllegalArgumentException(
                    "Invalid Hashcat hash mode.");
        }

        if (config.workload < 1 || config.workload > 4) {
            throw new IllegalArgumentException(
                    "Hashcat workload must be between 1 and 4.");
        }

        List<String> args = new ArrayList<>();

        args.add("hashcat");
        args.add("-m");
        args.add(String.valueOf(config.hashMode));

        args.add("-a");
        args.add(String.valueOf(hashcatAttackNumber(config.attack)));

        args.add("-w");
        args.add(String.valueOf(config.workload));

        if (config.optimizedKernel) {
            args.add("-O");
        }

        if (config.showStatus) {
            args.add("--status");
            args.add("--status-timer=5");
        }

        if (hasValue(config.session)) {
            validateSession(config.session);
            args.add("--session");
            args.add(config.session);
        }

        if (hasValue(config.ruleFile)) {
            if (config.attack != Attack.DICTIONARY) {
                throw new IllegalArgumentException(
                        "Rule files are supported here only for dictionary attacks.");
            }

            args.add("-r");
            args.add(config.ruleFile);
        }

        args.add(config.hashFile);

        switch (config.attack) {

            case DICTIONARY:
                requireValue(config.wordlist, "Wordlist");
                args.add(config.wordlist);
                break;

            case MASK:
                requireValue(config.mask, "Mask");
                args.add(config.mask);
                break;

            case HYBRID_WORDLIST_MASK:
                requireValue(config.wordlist, "Wordlist");
                requireValue(config.mask, "Mask");
                args.add(config.wordlist);
                args.add(config.mask);
                break;

            case HYBRID_MASK_WORDLIST:
                requireValue(config.wordlist, "Wordlist");
                requireValue(config.mask, "Mask");
                args.add(config.mask);
                args.add(config.wordlist);
                break;

            default:
                throw new IllegalArgumentException(
                        "Unsupported Hashcat attack.");
        }

        return joinArguments(args);
    }

    private static String buildJohn(Config config) {

        if (config.attack == Attack.MASK ||
                config.attack == Attack.HYBRID_WORDLIST_MASK ||
                config.attack == Attack.HYBRID_MASK_WORDLIST) {

            throw new IllegalArgumentException(
                    "This John interface supports dictionary and incremental modes.");
        }

        requireValue(config.format, "John hash format");

        List<String> args = new ArrayList<>();

        args.add("john");
        args.add("--format=" + config.format);

        if (hasValue(config.session)) {
            validateSession(config.session);
            args.add("--session=" + config.session);
        }

        switch (config.attack) {

            case DICTIONARY:
                requireValue(config.wordlist, "Wordlist");
                args.add("--wordlist=" + config.wordlist);

                if (hasValue(config.ruleFile)) {

                    if (!config.ruleFile.matches("[A-Za-z0-9_-]+")) {
                        throw new IllegalArgumentException(
                                "John rules must be a named ruleset.");
                    }

                    args.add("--rules=" + config.ruleFile);
                }
                break;

            case INCREMENTAL:

                if (hasValue(config.ruleFile)) {
                    throw new IllegalArgumentException(
                            "Rules cannot be combined with incremental mode.");
                }

                String mode = hasValue(config.incrementMode)
                        ? config.incrementMode : "ASCII";

                if (!mode.matches("[A-Za-z0-9_-]+")) {
                    throw new IllegalArgumentException(
                            "Invalid incremental mode.");
                }

                args.add("--incremental=" + mode);
                break;

            default:
                throw new IllegalArgumentException(
                        "Unsupported John attack.");
        }

        args.add(config.hashFile);

        return joinArguments(args);
    }

    private static int hashcatAttackNumber(Attack attack) {

        switch (attack) {

            case DICTIONARY:
                return 0;

            case MASK:
                return 3;

            case HYBRID_WORDLIST_MASK:
                return 6;

            case HYBRID_MASK_WORDLIST:
                return 7;

            default:
                throw new IllegalArgumentException(
                        "Unsupported Hashcat attack mode.");
        }
    }

    public static String buildHashcatRestore(String session) {

        validateSession(session);

        return "hashcat --session " +
                quote(session) +
                " --restore";
    }

    public static String buildJohnRestore(String session) {

        validateSession(session);

        return "john --restore=" + quote(session);
    }

    public static String buildJohnShow(
            String hashFile,
            String format) {

        requireValue(hashFile, "Hash file");
        requireValue(format, "John hash format");

        List<String> args = new ArrayList<>();

        args.add("john");
        args.add("--format=" + format);
        args.add("--show");
        args.add(hashFile);

        return joinArguments(args);
    }

    private static String joinArguments(List<String> args) {

        StringBuilder command = new StringBuilder();

        for (String arg : args) {

            if (command.length() > 0) {
                command.append(' ');
            }

            command.append(quote(arg));
        }

        return command.toString();
    }

    private static String quote(String value) {
        return ShellExecuter.shellEscape(value);
    }

    private static void validateSession(String session) {

        if (session == null ||
                !SESSION_PATTERN.matcher(session).matches()) {

            throw new IllegalArgumentException(
                    "Session names may contain letters, numbers, " +
                    "underscores and hyphens (maximum 64 characters).");
        }
    }

    private static void requireValue(
            String value,
            String field) {

        if (!hasValue(value)) {
            throw new IllegalArgumentException(
                    field + " is required.");
        }
    }

    private static boolean hasValue(String value) {
        return value != null && !value.trim().isEmpty();
    }
}