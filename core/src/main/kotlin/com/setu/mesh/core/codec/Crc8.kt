package com.setu.mesh.core.codec

/**
 * CRC-8/ATM: polynomial 0x07, init 0x00, no reflection, no final XOR.
 *
 * One byte of integrity is all the beacon can afford. It is not a security control — an
 * attacker recomputes it trivially — it exists to reject the garbled advertisements that a
 * noisy 2.4 GHz band produces in a crowd, before a corrupt payload reaches routing.
 */
object Crc8 {

    fun compute(data: ByteArray, from: Int = 0, until: Int = data.size): Int {
        var crc = 0
        for (i in from until until) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x80) != 0) {
                    ((crc shl 1) xor POLYNOMIAL) and 0xFF
                } else {
                    (crc shl 1) and 0xFF
                }
            }
        }
        return crc
    }

    private const val POLYNOMIAL = 0x07
}
