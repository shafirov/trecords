package trecords.util

import java.math.*
import java.security.*

object Random {
    private val rnd = ThreadLocal.withInitial {
        SecureRandom()
    }
    private val len = 10
    private val radix = 36

    fun nextUID(): UID {
        return UID(BigInteger(128, rnd.get()).toString(radix).take(len))
    }

    fun nextBytes(len: Int): ByteArray {
        return ByteArray(len).apply { rnd.get().nextBytes(this) }
    }

    fun nextLong(): Long {
        val l = rnd.get().nextLong()
        return if (l < 0) -l else l
    }
}
