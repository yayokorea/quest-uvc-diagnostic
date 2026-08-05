package com.yayo.questuvc

import org.junit.Assert.*
import org.junit.Test

class UvcDescriptorParserTest {
    @Test fun parsesMjpegTopologyAndIsoAlternates() {
        val raw = bytes(
            18,1,0x00,0x02,0,0,0,64,0x34,0x12,0x78,0x56,0,1,1,2,3,1,
            9,2,0,0,2,1,0,0x80,50,
            9,4,0,0,0,14,1,0,0,
            13,0x24,1,0x10,0x01,0,0,0,0,0,0,0,0,
            9,4,1,0,0,14,2,0,0,
            11,0x24,6,1,1,0,1,0,0,0,0,
            30,0x24,7,1,0,0x40,0x01,0xF0,0,0,0,0,0,0,0,0,0,0,0,0,0,0x15,0x16,0x05,0,1,0x15,0x16,0x05,0,
            9,4,1,1,1,14,2,0,0,
            7,5,0x81,1,0x00,0x0C,1
        )
        val result=UvcDescriptorParser.parse(raw)
        assertEquals(listOf(0),result.videoControlInterfaces)
        assertEquals(listOf(1),result.videoStreamingInterfaces)
        assertEquals(0x0110,result.uvcVersion)
        val mode=result.modes.single()
        assertEquals(VideoFormat.MJPEG,mode.format);assertEquals(320,mode.width);assertEquals(240,mode.height)
        assertEquals(333333L,mode.intervals100ns.single());assertEquals(TransferKind.ISOCHRONOUS,mode.transferKind)
        assertEquals(2048,mode.maxPacketSize)
    }

    @Test fun malformedDescriptorBecomesWarning() {
        val result=UvcDescriptorParser.parse(byteArrayOf(20,4,1,2))
        assertTrue(result.warnings.any { it.startsWith("Invalid descriptor") })
        assertTrue(result.modes.isEmpty())
    }
    private fun bytes(vararg values:Int)=ByteArray(values.size){values[it].toByte()}
}
