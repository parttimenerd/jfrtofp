package me.bechberger.jfrtofp.util

class HashedList<T>(val array: List<T>, val start: Int = 0, val end: Int = array.size) {
    override fun hashCode(): Int {
        var hash = 0
        for (i in start until end) {
            hash = hash * 31 + array[i].hashCode()
        }
        return hash
    }

    override fun equals(other: Any?): Boolean {
        if (other !is HashedList<*>) {
            return false
        }
        if ((other.end - other.start) != (end - start)) {
            return false
        }
        for (i in 0 until (end - start)) {
            if (array[i + start] != other.array[i + other.start]) {
                return false
            }
        }
        return true
    }

    val size: Int
        get() = end - start

    val first: T
        get() = array[start]

    val last: T
        get() = array[end - 1]

    operator fun get(index: Int): T {
        return array[start + index]
    }
}
