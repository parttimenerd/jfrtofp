package me.bechberger.jfrtofp.util

import me.bechberger.jfrtofp.types.IndexIntoStringTable
import me.bechberger.jfrtofp.types.StringTable

class StringTableWrapper {
    val map = mutableMapOf<String, IndexIntoStringTable>()
    val strings = mutableListOf<String>()

    operator fun get(string: String): IndexIntoStringTable {
        return map.getOrPut(string) {
            strings.add(string)
            map.size
        }
    }

    operator fun get(index: IndexIntoStringTable): String {
        return strings[index]
    }

    fun toStringTable(): StringTable = strings

    val size: Int
        get() = strings.size
}
