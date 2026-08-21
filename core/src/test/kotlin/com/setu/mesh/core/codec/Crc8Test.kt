package com.setu.mesh.core.codec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class Crc8Test {

    @Test
    fun `known vector 123456789 is 0xF4`() {
        val data = "123456789".toByteArray(Charsets.US_ASCII)
        val crc = Crc8.compute(data)
        assertEquals(0xF4, crc)
    }

    @Test
    fun `empty input is 0x00`() {
        val crc = Crc8.compute(ByteArray(0))
        assertEquals(0x00, crc)
    }

    @Test
    fun `single bit flip changes output`() {
        val data1 = "123456789".toByteArray(Charsets.US_ASCII)
        val data2 = data1.copyOf()
        // flip a bit
        data2[0] = (data2[0].toInt() xor 0x01).toByte()

        val crc1 = Crc8.compute(data1)
        val crc2 = Crc8.compute(data2)
        
        assertNotEquals(crc1, crc2)
    }
}
