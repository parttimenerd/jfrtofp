package me.bechberger.jfrtofp.processor;

import me.bechberger.jfrtofp.converter.ConverterConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Mutable configuration object used by jfrtofp-server and jfrplugin.
 * Delegates to ConverterConfig for the actual conversion.
 */
public class Config {

    // Constants matching old Kotlin Config companions
    public static final List<String> DEFAULT_NON_PROJECT_PACKAGE_PREFIXES =
            List.of(ConverterConfig.DEFAULT_NON_PROJECT_PREFIXES);
    public static final Set<String> DEFAULT_IGNORED_EVENTS =
            ConverterConfig.DEFAULT_IGNORED_EVENTS;
    public static final int DEFAULT_INITIAL_VISIBLE_THREADS = 10;
    public static final int DEFAULT_INITIAL_SELECTED_THREADS = 10;
    public static final int DEFAULT_MIN_ITEMS_PER_THREAD = 3;

    private List<String> nonProjectPackagePrefixes = new ArrayList<>(DEFAULT_NON_PROJECT_PACKAGE_PREFIXES);
    private boolean enableMarkers = true;
    private int initialVisibleThreads = DEFAULT_INITIAL_VISIBLE_THREADS;
    private int initialSelectedThreads = DEFAULT_INITIAL_SELECTED_THREADS;
    private boolean includeGCThreads = false;
    private boolean includeInitialSystemProperty = false;
    private boolean includeInitialEnvironmentVariables = false;
    private boolean includeSystemProcesses = false;
    private Set<String> ignoredEvents = new HashSet<>(DEFAULT_IGNORED_EVENTS);
    private int minRequiredItemsPerThread = DEFAULT_MIN_ITEMS_PER_THREAD;
    private String sourceUrl = null;

    public Config() {}

    public Config(
            List<String> nonProjectPackagePrefixes,
            boolean enableMarkers,
            int initialVisibleThreads,
            int initialSelectedThreads,
            boolean includeGCThreads,
            boolean includeInitialSystemProperty,
            boolean includeInitialEnvironmentVariables,
            boolean includeSystemProcesses,
            Set<String> ignoredEvents,
            int minRequiredItemsPerThread) {
        this.nonProjectPackagePrefixes = new ArrayList<>(nonProjectPackagePrefixes);
        this.enableMarkers = enableMarkers;
        this.initialVisibleThreads = initialVisibleThreads;
        this.initialSelectedThreads = initialSelectedThreads;
        this.includeGCThreads = includeGCThreads;
        this.includeInitialSystemProperty = includeInitialSystemProperty;
        this.includeInitialEnvironmentVariables = includeInitialEnvironmentVariables;
        this.includeSystemProcesses = includeSystemProcesses;
        this.ignoredEvents = new HashSet<>(ignoredEvents);
        this.minRequiredItemsPerThread = minRequiredItemsPerThread;
    }

    public void setSourceUrl(String url) { this.sourceUrl = url; }
    public String getSourceUrl() { return sourceUrl; }

    public void setNonProjectPackagePrefixes(List<String> v) { this.nonProjectPackagePrefixes = new ArrayList<>(v); }
    public List<String> getNonProjectPackagePrefixes() { return nonProjectPackagePrefixes; }

    public void setEnableMarkers(boolean v) { this.enableMarkers = v; }
    public boolean isEnableMarkers() { return enableMarkers; }

    public void setInitialVisibleThreads(int v) { this.initialVisibleThreads = v; }
    public int getInitialVisibleThreads() { return initialVisibleThreads; }

    public void setInitialSelectedThreads(int v) { this.initialSelectedThreads = v; }
    public int getInitialSelectedThreads() { return initialSelectedThreads; }

    public void setIncludeGCThreads(boolean v) { this.includeGCThreads = v; }
    public boolean isIncludeGCThreads() { return includeGCThreads; }

    public void setIgnoredEvents(Set<String> v) { this.ignoredEvents = new HashSet<>(v); }
    public Set<String> getIgnoredEvents() { return ignoredEvents; }

    public void setMinRequiredItemsPerThread(int v) { this.minRequiredItemsPerThread = v; }
    public int getMinRequiredItemsPerThread() { return minRequiredItemsPerThread; }

    public void setIncludeInitialSystemProperty(boolean v) { this.includeInitialSystemProperty = v; }
    public boolean isIncludeInitialSystemProperty() { return includeInitialSystemProperty; }

    public void setIncludeInitialEnvironmentVariables(boolean v) { this.includeInitialEnvironmentVariables = v; }
    public boolean isIncludeInitialEnvironmentVariables() { return includeInitialEnvironmentVariables; }

    public void setIncludeSystemProcesses(boolean v) { this.includeSystemProcesses = v; }
    public boolean isIncludeSystemProcesses() { return includeSystemProcesses; }

    /** Convert to immutable ConverterConfig for use by the converter. */
    public ConverterConfig toConverterConfig() {
        Set<String> eff = new HashSet<>(ignoredEvents);
        if (!includeInitialSystemProperty)        eff.add("jdk.InitialSystemProperty");
        if (!includeInitialEnvironmentVariables)  eff.add("jdk.InitialEnvironmentVariable");
        if (!includeSystemProcesses)              eff.add("jdk.SystemProcess");
        return ConverterConfig.defaults()
                .withNonProjectPackagePrefixes(nonProjectPackagePrefixes.toArray(new String[0]))
                .withEnableMarkers(enableMarkers)
                .withInitialVisibleThreads(initialVisibleThreads)
                .withInitialSelectedThreads(initialSelectedThreads)
                .withIncludeGCThreads(includeGCThreads)
                .withIgnoredEvents(eff)
                .withMinRequiredItemsPerThread(minRequiredItemsPerThread)
                .withSourceUrl(sourceUrl);
    }
}
