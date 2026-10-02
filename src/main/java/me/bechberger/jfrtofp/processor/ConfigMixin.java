package me.bechberger.jfrtofp.processor;

import picocli.CommandLine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * Parses CLI-style arguments into a {@link Config}.
 * Exposes a Kotlin-compatible {@code Companion} static nested class so that
 * callers written against the old Kotlin API ({@code ConfigMixin.Companion.parseConfig(...)})
 * continue to compile without changes.
 */
public final class ConfigMixin {

    @CommandLine.Option(names = {"-n", "--non-project"}, description = "Non-project package prefixes")
    private List<String> nonProjectPackagePrefixes = new ArrayList<>(Config.DEFAULT_NON_PROJECT_PACKAGE_PREFIXES);

    @CommandLine.Option(names = {"--source-url"}, description = "Source URL for Firefox Profiler")
    private String sourceUrl = null;

    @CommandLine.Option(names = {"--include-noisy-events"}, description = "Include high-volume GC/metaspace detail events")
    private boolean includeNoisyEvents = false;

    @CommandLine.Option(names = {"--exclude-event"}, description = "Exclude a specific event type (repeatable)")
    private List<String> extraIgnoredEvents = new ArrayList<>();

    @CommandLine.Option(names = {"--include-gc-threads"}, description = "Include GC threads")
    private boolean includeGCThreads = false;

    @CommandLine.Option(names = {"--min-items"}, description = "Minimum items per thread")
    private int minRequiredItemsPerThread = Config.DEFAULT_MIN_ITEMS_PER_THREAD;

    private Config toConfig() {
        Config cfg = new Config();
        cfg.setNonProjectPackagePrefixes(nonProjectPackagePrefixes);
        cfg.setSourceUrl(sourceUrl);
        cfg.setIncludeGCThreads(includeGCThreads);
        cfg.setMinRequiredItemsPerThread(minRequiredItemsPerThread);
        if (!extraIgnoredEvents.isEmpty()) {
            java.util.Set<String> eff = new HashSet<>(cfg.getIgnoredEvents());
            eff.addAll(extraIgnoredEvents);
            cfg.setIgnoredEvents(eff);
        }
        return cfg;
    }

    /** Mimics the Kotlin companion object so Java callers can use {@code ConfigMixin.Companion.parseConfig(...)}. */
    public static final class Companion {

        private Companion() {}

        public static Config parseConfig(String[] args) {
            if (args == null || args.length == 0) {
                return new Config();
            }
            ConfigMixin mixin = new ConfigMixin();
            new CommandLine(mixin).parseArgs(args);
            return mixin.toConfig();
        }

        public static Config parseConfig(String args) {
            if (args == null || args.isBlank()) {
                return new Config();
            }
            // Simple shell-style tokenizer: split on whitespace, respecting double-quoted tokens.
            List<String> tokens = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean inQuote = false;
            for (int i = 0; i < args.length(); i++) {
                char c = args.charAt(i);
                if (c == '"') {
                    inQuote = !inQuote;
                } else if (Character.isWhitespace(c) && !inQuote) {
                    if (cur.length() > 0) {
                        tokens.add(cur.toString());
                        cur.setLength(0);
                    }
                } else {
                    cur.append(c);
                }
            }
            if (cur.length() > 0) tokens.add(cur.toString());
            return parseConfig(tokens.toArray(new String[0]));
        }
    }

    /** Singleton companion instance (mirrors Kotlin's {@code ConfigMixin.Companion} field). */
    public static final Companion Companion = new Companion();
}
