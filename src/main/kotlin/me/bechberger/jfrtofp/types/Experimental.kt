package me.bechberger.jfrtofp.types

/** marks parts that are not yet merged into the upstream firefox profiler */
@Target(AnnotationTarget.FIELD, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Experimental
