package me.bechberger.jfrtofp.converter;

import java.util.HashSet;
import java.util.Set;

/**
 * Port of config.ts. All converter knobs grouped on one immutable struct.
 * Default values mirror the TypeScript defaults exactly so the Profile output
 * shape stays identical.
 */
public final class ConverterConfig {

    public static final String[] DEFAULT_NON_PROJECT_PREFIXES = {
        "java.", "javax.", "kotlin.", "jdk.",
        "com.google.", "org.apache.", "org.spring.",
        "sun.", "scala.",
    };

    public static final Set<String> DEFAULT_IGNORED_EVENTS = Set.of(
        "jdk.ActiveSetting",
        "jdk.ActiveRecording",
        "jdk.BooleanFlag",
        "jdk.IntFlag",
        "jdk.DoubleFlag",
        "jdk.LongFlag",
        "jdk.NativeLibrary",
        "jdk.StringFlag",
        "jdk.UnsignedIntFlag",
        "jdk.UnsignedLongFlag",
        "jdk.InitialSystemProperty",
        "jdk.InitialEnvironmentVariable",
        "jdk.SystemProcess",
        "jdk.ModuleExport",
        "jdk.ModuleRequire"
    );

    /** High-volume GC/metaspace detail events filtered by default; opt in with includeNoisyEvents=true. */
    public static final Set<String> DEFAULT_NOISY_EVENTS = Set.of(
        "jdk.ThreadDump",
        "jdk.MetaspaceChunkFreeListSummary",
        "jdk.MetaspaceSummary",
        "jdk.MetaspaceGCThreshold",
        "jdk.GCPhasePauseLevel1",
        "jdk.GCPhasePauseLevel2",
        "jdk.GCPhasePauseLevel3",
        "jdk.GCPhasePauseLevel4",
        "jdk.GCPhaseConcurrent",
        "jdk.GCPhaseConcurrentLevel1",
        "jdk.GCPhaseParallel",
        "jdk.G1AdaptiveIHOP",
        "jdk.G1BasicIHOP",
        "jdk.G1MMU",
        "jdk.G1HeapSummary",
        // jdk.GCHeapSummary is intentionally excluded: sole source for memory counter tracks
        "jdk.G1EvacuationOldStatistics",
        "jdk.G1EvacuationYoungStatistics",
        "jdk.GCReferenceStatistics",
        "jdk.TenuringDistribution",
        "jdk.EvacuationInformation",
        "jdk.PromoteObjectInNewPLAB",
        "jdk.PromoteObjectOutsidePLAB",
        "jdk.GCCPUTime",
        "jdk.ObjectAllocationInNewTLAB",
        "jdk.G1HeapRegionTypeChange",
        "jdk.G1HeapRegionInformation",
        "jdk.ObjectCountAfterGC",
        "jdk.ZStatisticsCounter",
        "jdk.ZStatisticsSampler",
        "jdk.ZThreadPhase",
        "jdk.ZUnmap",
        "jdk.ZRelocationSet",
        "jdk.ZRelocationSetGroup",
        "jdk.ZAllocationStall"
    );

    public final String[] nonProjectPackagePrefixes;
    public final int maxExecutionSamplesPerThread;
    public final int maxMiscSamplesPerThread;
    public final String sourceUrl;             // nullable
    public final boolean enableMarkers;
    public final boolean enableAllocations;
    public final int maxThreads;
    public final boolean includeGCThreads;
    public final int minRequiredItemsPerThread;
    public final int initialVisibleThreads;
    public final int initialSelectedThreads;
    public final boolean selectProcessTrackInitially;
    public final boolean useNonProjectCategory;
    public final boolean omitEventThreadProperty;
    public final Set<String> ignoredEvents;
    public final boolean includeNoisyEvents;

    private ConverterConfig(
            String[] nonProjectPackagePrefixes,
            int maxExecutionSamplesPerThread,
            int maxMiscSamplesPerThread,
            String sourceUrl,
            boolean enableMarkers,
            boolean enableAllocations,
            int maxThreads,
            boolean includeGCThreads,
            int minRequiredItemsPerThread,
            int initialVisibleThreads,
            int initialSelectedThreads,
            boolean selectProcessTrackInitially,
            boolean useNonProjectCategory,
            boolean omitEventThreadProperty,
            Set<String> ignoredEvents,
            boolean includeNoisyEvents) {
        this.nonProjectPackagePrefixes = nonProjectPackagePrefixes;
        this.maxExecutionSamplesPerThread = maxExecutionSamplesPerThread;
        this.maxMiscSamplesPerThread = maxMiscSamplesPerThread;
        this.sourceUrl = sourceUrl;
        this.enableMarkers = enableMarkers;
        this.enableAllocations = enableAllocations;
        this.maxThreads = maxThreads;
        this.includeGCThreads = includeGCThreads;
        this.minRequiredItemsPerThread = minRequiredItemsPerThread;
        this.initialVisibleThreads = initialVisibleThreads;
        this.initialSelectedThreads = initialSelectedThreads;
        this.selectProcessTrackInitially = selectProcessTrackInitially;
        this.useNonProjectCategory = useNonProjectCategory;
        this.omitEventThreadProperty = omitEventThreadProperty;
        this.ignoredEvents = ignoredEvents;
        this.includeNoisyEvents = includeNoisyEvents;
    }

    public static ConverterConfig defaults() {
        return new ConverterConfig(
            DEFAULT_NON_PROJECT_PREFIXES,
            -1,
            -1,
            null,
            true,
            true,
            Integer.MAX_VALUE,
            false,
            3,
            10,
            10,
            true,
            true,
            true,
            DEFAULT_IGNORED_EVENTS,
            false
        );
    }

    public ConverterConfig withSourceUrl(String url) {
        return new ConverterConfig(
            nonProjectPackagePrefixes, maxExecutionSamplesPerThread, maxMiscSamplesPerThread,
            url, enableMarkers, enableAllocations, maxThreads, includeGCThreads,
            minRequiredItemsPerThread, initialVisibleThreads, initialSelectedThreads,
            selectProcessTrackInitially, useNonProjectCategory, omitEventThreadProperty,
            ignoredEvents, includeNoisyEvents);
    }

    /** Equivalent of the TS regex `jdk\.ExecutionSample|jdk\.NativeMethodSample|jdk\.CPUTimeSample` */
    public boolean isExecutionSample(String eventType) {
        return "jdk.ExecutionSample".equals(eventType)
            || "jdk.NativeMethodSample".equals(eventType)
            || "jdk.CPUTimeSample".equals(eventType);
    }

    public boolean isNonProjectPackage(String packageName) {
        for (String p : nonProjectPackagePrefixes) {
            if (packageName.startsWith(p)) return true;
        }
        return false;
    }
}
