package me.bechberger.jfrtofp

import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordedThread
import jdk.jfr.consumer.RecordingFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.Spliterator
import java.util.Spliterators
import java.util.stream.StreamSupport

/**
 * Wraps {@see RecordingFile} to trade memory for speed depending on the size of the JFR file,
 * by rereading the JFR file if the JFR file size exceeds a certain threshold ({@see REREAD_MIN_SIZE}).
 */
class MyRecordingFile(val jfrFile: Path, reread: Boolean? = null) {

    val mReread = reread ?: (Files.size(jfrFile) >= REREAD_MIN_SIZE)
    val cache = if (mReread) RecordingFile.readAllEvents(jfrFile) else null

    fun stream() = cache?.stream() ?: RecordingFile(jfrFile).use { file ->
        StreamSupport.stream(
            Spliterators.spliteratorUnknownSize(
                object : Iterator<RecordedEvent> {
                    override fun hasNext() = file.hasMoreEvents()
                    override fun next(): RecordedEvent {
                        if (file.hasMoreEvents()) {
                            return file.readEvent()
                        }
                        throw NoSuchElementException()
                    }
                },
                Spliterator.ORDERED
            ),
            false
        )
    }

    fun streamForThread(thread: RecordedThread) = streamForThread(thread.id)

    fun streamForThread(threadId: Long) = stream().filter { it.realThreadId == threadId }

    fun eventsForThread(thread: RecordedThread) = eventsForThread(thread.id)

    fun eventsForThread(threadId: Long) = cache?.filter {
        it.realThreadId == threadId
    } ?: RecordingFile(jfrFile).use { file ->
        val list: MutableList<RecordedEvent> = ArrayList()
        while (file.hasMoreEvents()) {
            val event = file.readEvent()
            if (event.realThreadId == threadId) {
                list.add(event)
            }
        }
        list
    }

    fun eventsForProcessThread() = eventsForThread(PROCESS_THREAD_ID)

    /** sorted by their appearance */
    fun threads() = cache?.map { it.thread }?.distinct() ?: RecordingFile(jfrFile).use { file ->
        val list: MutableList<RecordedThread> = ArrayList()
        val threads: MutableSet<Long> = HashSet()
        while (file.hasMoreEvents()) {
            val thread = file.readEvent().sampledThread
            if (threads.add(thread.id)) {
                list.add(thread)
            }
        }
        list
    }

    companion object {
        const val REREAD_MIN_SIZE = 10_000_000
    }
}

private val RecordedEvent.isSampledThreadCorrectProperty
    get() = eventType.name == "jdk.NativeMethodSample" || eventType.name == "jdk.ExecutionSample"

val RecordedEvent.sampledThread: RecordedThread
    get() = sampledThreadOrNull!!

val RecordedEvent.sampledThreadOrNull: RecordedThread?
    get() = if (isSampledThreadCorrectProperty) getThread("sampledThread") else thread

fun List<RecordedEvent>.groupByType() =
    groupBy { if (it.eventType.name == "jdk.NativeMethodSample") "jdk.ExecutionSample" else it.eventType.name }

val RecordedEvent.realThread: RecordedThread?
    get() = thread ?: sampledThreadOrNull

/** -1 if not thread present */
val RecordedEvent.realThreadId: Long
    get() = realThread?.id ?: PROCESS_THREAD_ID

/** the real thread id associated with events without any thread, like jdk.EnvironmentVariable */
const val PROCESS_THREAD_ID = -1L
