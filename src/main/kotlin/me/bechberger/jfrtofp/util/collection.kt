package me.bechberger.jfrtofp.util

class HashedList<T>(val array: List<T>, val start: Int = 0, val end: Int = array.size) {
    override fun hashCode(): Int {
        var hash = 1
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

fun <T> List<T>.listOfPrefixHashes(): List<Int> {
    val result = mutableListOf<Int>()
    var hash = 1
    for (i in 0 until size) {
        hash = hash * 31 + this[i].hashCode()
        result.add(hash)
    }
    return result
}

/** hashed list with copy free prefix operation */
class HashedList2<T>(
    private val array: List<T>,
    private val end: Int = array.size,
    private val hashes: List<Int> = array.listOfPrefixHashes()
) {
    override fun hashCode(): Int {
        return hashes[end - 1]
    }

    override fun equals(other: Any?): Boolean {
        if (other !is HashedList2<*>) {
            return false
        }
        if (other.end != end) {
            return false
        }
        for (i in 0 until end) {
            if (array[i] != other.array[i]) {
                return false
            }
        }
        return true
    }

    val size: Int
        get() = end

    val first: T
        get() = array[0]

    val last: T
        get() = array[end - 1]

    operator fun get(index: Int): T {
        return array[index]
    }

    fun prefix() = HashedList2(array, end - 1, hashes)
}
