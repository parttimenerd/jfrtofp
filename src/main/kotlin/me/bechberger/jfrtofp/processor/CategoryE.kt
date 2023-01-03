package me.bechberger.jfrtofp.processor

import me.bechberger.jfrtofp.types.Category

enum class CategoryE(
    val displayName: String,
    val color: String,
    val subcategories: MutableList<String> = mutableListOf()
) {
    OTHER("Other", "grey", mutableListOf("Profiling", "Waiting")), JAVA(
        "Java",
        "blue",
        mutableListOf("Other", "Interpreted", "Compiled", "Native", "Inlined")
    ),
    NON_PROJECT_JAVA(
        "Java (non-project)",
        "darkgray",
        mutableListOf("Other", "Interpreted", "Compiled", "Native", "Inlined")
    ),
    GC("GC", "orange", mutableListOf("Other")), CPP("Native", "red", mutableListOf("Other")),

    // JFR related categories
    JFR("Flight Recorder", "lightgrey"), JAVA_APPLICATION(
        "Java Application",
        "red"
    ),
    JAVA_APPLICATION_STATS(
        "Java Application, Statistics",
        "grey"
    ),
    JVM_CLASSLOADING("Java Virtual Machine, Class Loading", "brown"), JVM_CODE_CACHE(
        "Java Virtual Machine, Code Cache",
        "lightbrown"
    ),
    JVM_COMPILATION_OPT(
        "Java Virtual Machine, Compiler, Optimization",
        "lightblue"
    ),
    JVM_COMPILATION("Java Virtual Machine, Compiler", "lightblue"), JVM_DIAGNOSTICS(
        "Java Virtual Machine, Diagnostics",
        "lightgrey"
    ),
    JVM_FLAG("Java Virtual Machine, Flag", "lightgrey"), JVM_GC_COLLECTOR(
        "Java Virtual Machine, GC, Collector",
        "orange"
    ),
    JVM_GC_CONF(
        "Java Virtual Machine, GC, Configuration",
        "lightgrey"
    ),
    JVM_GC_DETAILED("Java Virtual Machine, GC, Detailed", "lightorange"), JVM_GC_HEAP(
        "Java Virtual Machine, GC, Heap",
        "lightorange"
    ),
    JVM_GC_METASPACE("Java Virtual Machine, GC, Metaspace", "lightorange"), // add another category for errors (OOM)
    JVM_GC_PHASES(
        "Java Virtual Machine, GC, Phases",
        "lightorange"
    ),
    JVM_GC_REFERENCE(
        "Java Virtual Machine, GC, Reference",
        "lightorange"
    ),
    JVM_INTERNAL("Java Virtual Machine, Internal", "lightgrey"), JVM_PROFILING(
        "Java Virtual Machine, Profiling",
        "lightgrey"
    ),
    JVM_RUNTIME_MODULES(
        "Java Virtual Machine, Runtime, Modules",
        "lightgrey"
    ),
    JVM_RUNTIME_SAFEPOINT(
        "Java Virtual Machine, Runtime, Safepoint",
        "yellow"
    ),
    JVM_RUNTIME_TABLES(
        "Java Virtual Machine, Runtime, Tables",
        "lightgrey"
    ),
    JVM_RUNTIME("Java Virtual Machine, Runtime", "green"), JVM(
        "Java Virtual Machine",
        "lightgrey"
    ),
    OS_MEMORY("Operating System, Memory", "lightgrey"), OS_NETWORK(
        "Operating System, Network",
        "lightgrey"
    ),
    OS_PROCESS("Operating System, Processor", "lightgrey"), OS("Operating System", "lightgrey"),
    MISC("Misc", "lightgrey", mutableListOf("Other"));

    val index: Int = ordinal

    fun sub(sub: String) = subcategories.indexOf(sub).let {
        index to if (it == -1) {
            subcategories.add(sub)
            subcategories.size - 1
        } else it
    }

    fun toCategory() = Category(displayName, color, subcategories)

    companion object {
        fun toCategoryList() = values().map { it.toCategory() }

        internal val map by lazy {
            values().associateBy { it.displayName }
        }

        fun fromName(displayName: String) = map[displayName] ?: OTHER

        val MISC_OTHER = MISC.sub("Other")
    }
}
