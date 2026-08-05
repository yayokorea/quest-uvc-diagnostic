package com.yayo.questuvc

object NativeUvc {
    init { System.loadLibrary("quest_uvc") }
    interface Listener { fun onStatistics(values: LongArray, fps: Double, averageFrameBytes: Double, error: String?); fun onFrame(jpeg: ByteArray) }
    @JvmStatic external fun open(fd: Int, rawDescriptors: ByteArray, listener: Listener): Long
    @JvmStatic external fun probeCommit(handle: Long, interfaceNumber: Int, formatIndex: Int, frameIndex: Int, interval100ns: Long, maxFrameBytes: Int, maxPayloadBytes: Int): String
    @JvmStatic external fun start(handle: Long, interfaceNumber: Int, alternate: Int, endpointAddress: Int, transferType: Int, packetSize: Int): Int
    @JvmStatic external fun stop(handle: Long)
    @JvmStatic external fun close(handle: Long)
}
