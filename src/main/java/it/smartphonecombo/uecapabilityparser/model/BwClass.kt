package it.smartphonecombo.uecapabilityparser.model

/**
 * Represents a bandwidth class, it's backed by a char.
 *
 * BwClass of '0' has a special meaning, it's [NONE].
 */
@JvmInline
value class BwClass(private val bwClass: Char) : Comparable<BwClass> {
    companion object {
        val NONE = BwClass('0')

        /**
         * Return a BwClass whose value is the first character (uppercase) of [s].
         *
         * If [s] is empty or null return [NONE].
         */
        fun valueOf(s: String?): BwClass {
            if (s.isNullOrEmpty()) {
                return NONE
            }
            return BwClass(s.first().uppercaseChar())
        }

        /**
         * Return a BwClass whose value is [i] + 0x40.
         *
         * If [i] = 0 return [NONE].
         */
        fun valueOf(i: Int): BwClass {
            if (i == 0) {
                return NONE
            }
            return BwClass((i + 0x40).toChar())
        }

        /**
         * Return a BwClass whose value is [i] + 0x41
         *
         * If [i] = 6 return [NONE].
         */
        fun valueOfMtkIndex(i: Int): BwClass {
            if (i == 6) {
                return NONE
            }
            return BwClass((i + 0x41).toChar())
        }
    }

    override fun compareTo(other: BwClass): Int {
        return bwClass.compareTo(other.bwClass)
    }

    override fun toString(): String {
        return if (bwClass != NONE.bwClass) bwClass.toString() else ""
    }
}
