package com.yayo.questuvc

import android.hardware.usb.UsbDevice

enum class SessionPhase { DISCONNECTED, DETECTED, PERMISSION_PENDING, OPENED, PARSED, PROBING, STREAMING, STOPPING, ERROR }
enum class TransferKind { CONTROL, ISOCHRONOUS, BULK, INTERRUPT, UNKNOWN }

data class UsbDeviceInfo(
    val deviceId: Int, val name: String, val vid: Int, val pid: Int,
    val manufacturer: String?, val product: String?, val serial: String?,
    val deviceClass: Int, val subclass: Int, val protocol: Int, val permission: Boolean
) {
    companion object { fun from(d: UsbDevice, permission: Boolean) = UsbDeviceInfo(
        d.deviceId, d.deviceName, d.vendorId, d.productId,
        runCatching { d.manufacturerName }.getOrNull(), runCatching { d.productName }.getOrNull(),
        if (permission) runCatching { d.serialNumber }.getOrNull() else null,
        d.deviceClass, d.deviceSubclass, d.deviceProtocol, permission
    ) }
}

data class EndpointInfo(val address: Int, val attributes: Int, val maxPacketSize: Int, val interval: Int) {
    val transferKind = when (attributes and 3) { 0 -> TransferKind.CONTROL; 1 -> TransferKind.ISOCHRONOUS; 2 -> TransferKind.BULK; 3 -> TransferKind.INTERRUPT; else -> TransferKind.UNKNOWN }
    val directionIn get() = address and 0x80 != 0
}
data class AlternateSetting(val interfaceNumber: Int, val alternate: Int, val interfaceClass: Int, val subclass: Int, val protocol: Int, val endpoints: List<EndpointInfo>)
enum class VideoFormat { MJPEG, YUY2, UNCOMPRESSED, UNKNOWN }
data class UvcStreamMode(
    val formatIndex: Int, val frameIndex: Int, val format: VideoFormat,
    val width: Int, val height: Int, val intervals100ns: List<Long>,
    val interfaceNumber: Int, val endpointAddress: Int = 0, val alternateSetting: Int = 0,
    val transferKind: TransferKind = TransferKind.UNKNOWN, val maxPacketSize: Int = 0
) {
    fun fps(interval: Long) = if (interval > 0) 10_000_000.0 / interval else 0.0
    val label get() = "$format ${width}×$height " + intervals100ns.joinToString("/") { "%.1f".format(fps(it)) } + " FPS · $transferKind alt $alternateSetting (${maxPacketSize}B)"
}
data class UvcTopology(val uvcVersion: Int?, val videoControlInterfaces: List<Int>, val videoStreamingInterfaces: List<Int>, val alternates: List<AlternateSetting>, val modes: List<UvcStreamMode>, val warnings: List<String>)
data class StreamStatistics(
    val receivedBytes: Long = 0, val packets: Long = 0, val frames: Long = 0,
    val corruptFrames: Long = 0, val droppedFrames: Long = 0, val usbErrors: Long = 0,
    val minFrameBytes: Int = 0, val averageFrameBytes: Double = 0.0, val maxFrameBytes: Int = 0,
    val fps: Double = 0.0, val lastError: String? = null
)
data class DiagnosticEvent(val timestampMs: Long = System.currentTimeMillis(), val level: String = "INFO", val message: String)
data class DiagnosticState(
    val phase: SessionPhase = SessionPhase.DISCONNECTED,
    val devices: List<UsbDeviceInfo> = emptyList(), val selectedDeviceId: Int? = null,
    val topology: UvcTopology? = null, val selectedMode: UvcStreamMode? = null,
    val selectedInterval: Long? = null, val probeResult: String = "Not attempted",
    val statistics: StreamStatistics = StreamStatistics(), val previewJpeg: ByteArray? = null,
    val events: List<DiagnosticEvent> = emptyList(), val busy: Boolean = false,
    val cameraPermissionRequired: Boolean = false, val cameraPermissionGranted: Boolean = false
)
