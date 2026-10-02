package me.bechberger.jfrtofp.converter;

import java.util.ArrayList;

/** Shared prebuffer drain logic for both JFR and CJFR converters. */
final class ProcessorDrain {

    /**
     * Construct a {@link Processor} from the accumulated metadata and drain all
     * buffered events through it. After this call {@code procRef[0]} is non-null and
     * {@code prebuffer} is empty.
     */
    static void buildProcessorAndDrain(
            Processor[] procRef,
            ArrayList<PrebufferedEvent> prebuffer,
            Processor.ParsedEvent scratch,
            Runnable registerTypeInfos,
            String[] jvmVersion,
            String[] jvmArgs,
            String[] javaArgs,
            long[] startNanos,
            long[] endNanos,
            String[] cpuModel,
            int[] cpuCores,
            int[] cpuHwThreads,
            String[] osVersion,
            long[] pid) {
        Processor.JFRMetadata meta =
                new Processor.JFRMetadata(
                        jvmVersion[0],
                        jvmArgs[0],
                        javaArgs[0],
                        startNanos[0] / 1_000_000.0,
                        endNanos[0] / 1_000_000.0,
                        cpuModel[0],
                        cpuCores[0] != 0 ? cpuCores[0] : null,
                        cpuHwThreads[0] != 0 ? cpuHwThreads[0] : null,
                        osVersion[0],
                        pid[0]);
        procRef[0] = new Processor(ConverterConfig.defaults(), meta);

        registerTypeInfos.run();

        for (PrebufferedEvent e : prebuffer) {
            scratch.type = e.typeName;
            scratch.startMs = e.startMs;
            scratch.endMs = e.endMs;
            scratch.fields = e.fields;
            scratch.thread = e.thread;
            scratch.stackDepth = e.stackDepth;
            scratch.frameClassNames = e.frameClassNames;
            scratch.frameMethodNames = e.frameMethodNames;
            scratch.frameDescriptors = e.frameDescriptors;
            scratch.frameLineNumbers = e.frameLineNumbers;
            scratch.frameIsJava = e.frameIsJava;
            procRef[0].process(scratch);
        }
        prebuffer.clear();
        scratch.frameClassNames = null;
        scratch.frameMethodNames = null;
        scratch.frameDescriptors = null;
        scratch.frameLineNumbers = null;
        scratch.frameIsJava = null;
        scratch.stackDepth = 0;
    }

    private ProcessorDrain() {}
}
