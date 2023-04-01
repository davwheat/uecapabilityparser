package it.smartphonecombo.uecapabilityparser.extension

import com.soywiz.kmem.finsert
import com.soywiz.kmem.isEven
import kotlin.jvm.Throws

internal fun String.indexOf(regex: Regex): Int = regex.find(this)?.range?.first ?: -1

internal fun String.repeat(n: Int, separator: String) =
    plus(separator).repeat(n).dropLast(separator.length)

@Throws(IllegalArgumentException::class)
internal fun String.decodeHex(): ByteArray {
    return ByteArray(length / 2) {
        val i = it * 2
        this[i + 1].digitToInt(16).finsert(this[i].digitToInt(16), 4).toByte()
    }
}

internal fun String.preformatHex(): String {
    return filterNot(Char::isWhitespace).replace(",", "").replace("0x", "").let {
        if (it.length.isEven) it else "${it}0"
    }
}
