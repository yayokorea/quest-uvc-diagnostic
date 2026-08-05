package com.yayo.questuvc

object UvcDescriptorParser {
    private const val CS_INTERFACE = 0x24
    private const val VIDEO_CLASS = 0x0e
    private const val VC_SUBCLASS = 1
    private const val VS_SUBCLASS = 2

    fun parse(raw: ByteArray): UvcTopology {
        val warnings = mutableListOf<String>(); val alternates = mutableListOf<AlternateSetting>()
        val vc = mutableSetOf<Int>(); val vs = mutableSetOf<Int>(); val modes = mutableListOf<UvcStreamMode>()
        var uvcVersion: Int? = null; var current: MutableAlt? = null; var currentFormat = VideoFormat.UNKNOWN
        var currentFormatIndex = 0; var currentVs = -1; var offset = 0
        while (offset + 2 <= raw.size) {
            val len = raw.u8(offset); val type = raw.u8(offset + 1)
            if (len < 2 || offset + len > raw.size) { warnings += "Invalid descriptor at $offset (length=$len)"; break }
            when (type) {
                4 -> {
                    current?.let { alternates += it.freeze() }
                    if (len >= 9) {
                        current = MutableAlt(raw.u8(offset+2), raw.u8(offset+3), raw.u8(offset+5), raw.u8(offset+6), raw.u8(offset+7))
                        if (current!!.clazz == VIDEO_CLASS && current!!.subclass == VC_SUBCLASS) vc += current!!.number
                        if (current!!.clazz == VIDEO_CLASS && current!!.subclass == VS_SUBCLASS) { vs += current!!.number; currentVs = current!!.number }
                    }
                }
                5 -> if (len >= 7) { val w=raw.u16(offset+4); val effective=(w and 0x7ff)*(1+((w shr 11) and 3)); current?.endpoints?.add(EndpointInfo(raw.u8(offset+2), raw.u8(offset+3), effective, raw.u8(offset+6))) }
                CS_INTERFACE -> if (len >= 3 && current?.clazz == VIDEO_CLASS) {
                    val subtype = raw.u8(offset+2)
                    if (current!!.subclass == VC_SUBCLASS && subtype == 1 && len >= 5) uvcVersion = raw.u16(offset+3)
                    if (current!!.subclass == VS_SUBCLASS) when (subtype) {
                        4 -> { currentFormat = VideoFormat.UNCOMPRESSED; currentFormatIndex = raw.u8(offset+3); if (len >= 21 && isYuy2(raw, offset+5)) currentFormat = VideoFormat.YUY2 }
                        6 -> { currentFormat = VideoFormat.MJPEG; currentFormatIndex = raw.u8(offset+3) }
                        5, 7 -> parseFrame(raw, offset, len, currentFormatIndex, currentFormat, currentVs, warnings)?.let(modes::add)
                    }
                }
            }
            offset += len
        }
        current?.let { alternates += it.freeze() }
        val enriched = modes.flatMap { mode ->
            val candidates = alternates.filter { it.interfaceNumber == mode.interfaceNumber }.flatMap { alt -> alt.endpoints.filter { it.directionIn && (it.transferKind == TransferKind.BULK || it.transferKind == TransferKind.ISOCHRONOUS) }.map { alt to it } }
            if (candidates.isEmpty()) listOf(mode) else candidates.distinctBy { it.first.alternate to it.second.address }.map { mode.copy(endpointAddress=it.second.address, alternateSetting=it.first.alternate, transferKind=it.second.transferKind, maxPacketSize=it.second.maxPacketSize) }
        }
        if (vc.isEmpty()) warnings += "VideoControl interface not found"
        if (vs.isEmpty()) warnings += "VideoStreaming interface not found"
        return UvcTopology(uvcVersion, vc.toList(), vs.toList(), alternates, enriched, warnings)
    }

    private fun parseFrame(b: ByteArray, o: Int, len: Int, fi: Int, format: VideoFormat, vs: Int, warnings: MutableList<String>): UvcStreamMode? {
        if (len < 26) { warnings += "Short frame descriptor at $o"; return null }
        val frameIndex=b.u8(o+3); val width=b.u16(o+5); val height=b.u16(o+7); val intervalType=b.u8(o+25)
        val intervals = mutableListOf<Long>()
        if (intervalType == 0 && len >= 38) {
            val min=b.u32(o+26); val max=b.u32(o+30); val step=b.u32(o+34)
            if (step > 0) { var value=min; while (value <= max && intervals.size < 256) { intervals += value; value += step } }
        } else for (i in 0 until intervalType) if (26+i*4+4 <= len) intervals += b.u32(o+26+i*4)
        if (intervals.isEmpty() && len >= 25) intervals += b.u32(o+21)
        return UvcStreamMode(fi, frameIndex, format, width, height, intervals.filter { it > 0 }, vs)
    }
    private fun isYuy2(b: ByteArray, o: Int) = o + 4 <= b.size && b[o].toInt().toChar()=='Y' && b[o+1].toInt().toChar()=='U' && b[o+2].toInt().toChar()=='Y' && b[o+3].toInt().toChar()=='2'
    private class MutableAlt(val number:Int,val alternate:Int,val clazz:Int,val subclass:Int,val protocol:Int) { val endpoints=mutableListOf<EndpointInfo>(); fun freeze()=AlternateSetting(number,alternate,clazz,subclass,protocol,endpoints.toList()) }
    private fun ByteArray.u8(i:Int)=this[i].toInt() and 0xff
    private fun ByteArray.u16(i:Int)=u8(i) or (u8(i+1) shl 8)
    private fun ByteArray.u32(i:Int)=((u8(i).toLong()) or (u8(i+1).toLong() shl 8) or (u8(i+2).toLong() shl 16) or (u8(i+3).toLong() shl 24)) and 0xffffffffL
}
