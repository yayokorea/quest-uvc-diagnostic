package com.yayo.questuvc

import android.Manifest
import android.app.Application
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale

class MainViewModel(app: Application) : AndroidViewModel(app), NativeUvc.Listener {
    companion object { const val ACTION_PERMISSION = "com.yayo.questuvc.USB_PERMISSION" }
    private val manager = app.getSystemService(UsbManager::class.java)
    private val _state = MutableStateFlow(DiagnosticState())
    val state: StateFlow<DiagnosticState> = _state.asStateFlow()
    private var connection: UsbDeviceConnection? = null
    private var nativeHandle = 0L
    private var lastFrame: ByteArray? = null
    private var receiverRegistered = false
    private var pendingCameraPermissionDeviceId: Int? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.device()
            when (intent.action) {
                ACTION_PERMISSION -> if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) open(device) else fail("USB permission denied")
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> { refresh(); log("USB attached: ${device?.deviceName ?: "unknown"}") }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> { if (device?.deviceId == _state.value.selectedDeviceId) closeSession("Selected device detached"); refresh() }
            }
        }
    }

    fun register() {
        if (receiverRegistered) return
        val filter=IntentFilter().apply { addAction(ACTION_PERMISSION); addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        ContextCompat.registerReceiver(getApplication(),receiver,filter,ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered=true; refresh()
    }
    fun refresh() {
        val devices=manager.deviceList.values.map { UsbDeviceInfo.from(it, manager.hasPermission(it)) }.sortedBy { it.name }
        _state.value=_state.value.copy(devices=devices, cameraPermissionGranted=hasCameraPermission(), phase=if(devices.isEmpty()) SessionPhase.DISCONNECTED else if(_state.value.phase==SessionPhase.DISCONNECTED) SessionPhase.DETECTED else _state.value.phase)
    }
    fun select(deviceId:Int) {
        if (_state.value.selectedDeviceId != deviceId) closeSession(null)
        val d=manager.deviceList.values.firstOrNull { it.deviceId==deviceId } ?: return fail("Device disappeared")
        _state.value=_state.value.copy(selectedDeviceId=deviceId, phase=SessionPhase.DETECTED)
        if (requiresCameraPermission(d) && !hasCameraPermission()) {
            pendingCameraPermissionDeviceId=deviceId
            _state.value=_state.value.copy(phase=SessionPhase.PERMISSION_PENDING,cameraPermissionRequired=true,cameraPermissionGranted=false)
            log("Requesting Android camera permission (required for USB video devices)")
            return
        }
        openOrRequestUsbPermission(d)
    }
    fun onCameraPermissionResult(granted:Boolean) {
        val deviceId=pendingCameraPermissionDeviceId
        pendingCameraPermissionDeviceId=null
        _state.value=_state.value.copy(cameraPermissionRequired=false,cameraPermissionGranted=granted)
        if(!granted) return fail("Android camera permission denied; USB video permission cannot be granted")
        log("Android camera permission granted")
        val d=manager.deviceList.values.firstOrNull { it.deviceId==deviceId } ?: return fail("Device disappeared while requesting camera permission")
        openOrRequestUsbPermission(d)
    }
    private fun openOrRequestUsbPermission(d:UsbDevice) { if (manager.hasPermission(d)) open(d) else requestPermission(d) }
    private fun hasCameraPermission()=ContextCompat.checkSelfPermission(getApplication(),Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
    private fun requiresCameraPermission(d:UsbDevice)=d.deviceClass==UsbConstants.USB_CLASS_VIDEO || (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass==UsbConstants.USB_CLASS_VIDEO }
    private fun requestPermission(d:UsbDevice) {
        _state.value=_state.value.copy(phase=SessionPhase.PERMISSION_PENDING); log("Requesting USB permission")
        val pi=PendingIntent.getBroadcast(getApplication(),0,Intent(ACTION_PERMISSION).setPackage(getApplication<Application>().packageName),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.requestPermission(d,pi)
    }
    private fun open(d:UsbDevice) {
        closeTransport()
        val c=manager.openDevice(d) ?: return fail("UsbDeviceConnection open failed")
        val raw=c.rawDescriptors ?: ByteArray(0)
        val topology=runCatching { UvcDescriptorParser.parse(raw) }.getOrElse { c.close(); return fail("Descriptor parse failed: ${it.message}") }
        connection=c
        nativeHandle=runCatching { NativeUvc.open(c.fileDescriptor,raw,this) }.getOrElse { -1L }
        if(!isValidNativeHandle(nativeHandle)) { c.close(); connection=null; return fail("Native libusb wrap failed ($nativeHandle)") }
        val recommended=topology.modes.filter { it.format==VideoFormat.MJPEG && it.intervals100ns.isNotEmpty() }.minByOrNull { it.width.toLong()*it.height }
            ?: topology.modes.firstOrNull()
        _state.value=_state.value.copy(phase=SessionPhase.PARSED,topology=topology,selectedMode=recommended,selectedInterval=recommended?.intervals100ns?.maxOrNull(),probeResult="Not attempted")
        refresh(); log("Opened ${d.deviceName}; VC=${topology.videoControlInterfaces}, VS=${topology.videoStreamingInterfaces}, modes=${topology.modes.size}")
        topology.warnings.forEach { log(it,"WARN") }
    }
    fun chooseMode(mode:UvcStreamMode) { if(_state.value.phase==SessionPhase.STREAMING) stop(); _state.value=_state.value.copy(selectedMode=mode,selectedInterval=mode.intervals100ns.maxOrNull()) }
    fun chooseInterval(value:Long) { _state.value=_state.value.copy(selectedInterval=value) }
    fun start() {
        val mode=_state.value.selectedMode ?: return fail("No stream mode selected")
        val interval=_state.value.selectedInterval ?: return fail("No frame interval selected")
        if(!isValidNativeHandle(nativeHandle)) return fail("Device is not open")
        _state.value=_state.value.copy(phase=SessionPhase.PROBING,busy=true); log("Probe/Commit: ${mode.label}, interval=$interval")
        val result=NativeUvc.probeCommit(nativeHandle,mode.interfaceNumber,mode.formatIndex,mode.frameIndex,interval,(mode.width*mode.height*2).coerceAtLeast(64*1024),mode.maxPacketSize.coerceAtLeast(1024))
        if(!result.startsWith("OK")) { _state.value=_state.value.copy(probeResult=result,busy=false); return fail("Probe/Commit failed: $result") }
        val rc=NativeUvc.start(nativeHandle,mode.interfaceNumber,mode.alternateSetting,mode.endpointAddress,if(mode.transferKind==TransferKind.ISOCHRONOUS) 1 else 2,mode.maxPacketSize.coerceAtLeast(1024))
        if(rc<0) { _state.value=_state.value.copy(probeResult=result,busy=false); return fail("Stream start failed: $rc") }
        _state.value=_state.value.copy(phase=SessionPhase.STREAMING,probeResult=result,busy=false,statistics=StreamStatistics()); log("Streaming started (${mode.transferKind}, EP 0x${mode.endpointAddress.toString(16)})")
    }
    fun stop() { if(isValidNativeHandle(nativeHandle)) NativeUvc.stop(nativeHandle); _state.value=_state.value.copy(phase=SessionPhase.PARSED,busy=false); log("Streaming stopped") }
    override fun onStatistics(values:LongArray,fps:Double,averageFrameBytes:Double,error:String?) {
        val s=StreamStatistics(values.getOrElse(0){0},values.getOrElse(1){0},values.getOrElse(2){0},values.getOrElse(3){0},values.getOrElse(4){0},values.getOrElse(5){0},values.getOrElse(6){0}.toInt(),averageFrameBytes,values.getOrElse(7){0}.toInt(),fps,error)
        _state.value=_state.value.copy(statistics=s)
    }
    override fun onFrame(jpeg:ByteArray) { lastFrame=jpeg; _state.value=_state.value.copy(previewJpeg=jpeg) }
    fun saveFrame():Uri? {
        val bytes=lastFrame ?: return null
        val values=ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME,"quest_uvc_${System.currentTimeMillis()}.jpg"); put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg"); if(Build.VERSION.SDK_INT>=29) put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/QuestUvcDiagnostic") }
        val uri=getApplication<Application>().contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values) ?: return null
        return runCatching { getApplication<Application>().contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }; log("Frame saved: $uri"); uri }.getOrElse { getApplication<Application>().contentResolver.delete(uri,null,null); null }
    }
    fun reportUri():Uri {
        val dir=File(getApplication<Application>().cacheDir,"reports").apply { mkdirs() }; val file=File(dir,"quest-uvc-report.txt")
        file.writeText(buildReport()); return FileProvider.getUriForFile(getApplication(),"${getApplication<Application>().packageName}.files",file)
    }
    private fun buildReport():String=buildString { appendLine("Quest UVC Diagnostic report"); appendLine("Phase: ${_state.value.phase}"); appendLine("Device: ${_state.value.devices.firstOrNull { it.deviceId==_state.value.selectedDeviceId }}"); appendLine("Topology: ${_state.value.topology}"); appendLine("Mode: ${_state.value.selectedMode}"); appendLine("Statistics: ${_state.value.statistics}"); appendLine(); _state.value.events.forEach { appendLine("${it.timestampMs} ${it.level} ${it.message}") } }
    private fun closeSession(reason:String?) { if(_state.value.phase==SessionPhase.STREAMING) runCatching { NativeUvc.stop(nativeHandle) }; closeTransport(); pendingCameraPermissionDeviceId=null; _state.value=DiagnosticState(devices=_state.value.devices,events=_state.value.events,cameraPermissionGranted=hasCameraPermission()); reason?.let { log(it,"WARN") } }
    private fun closeTransport() { if(isValidNativeHandle(nativeHandle)) runCatching { NativeUvc.close(nativeHandle) }; nativeHandle=0L; connection?.close(); connection=null }
    private fun fail(message:String) { log(message,"ERROR"); _state.value=_state.value.copy(phase=SessionPhase.ERROR,busy=false) }
    private fun log(message:String,level:String="INFO") { _state.value=_state.value.copy(events=(_state.value.events+DiagnosticEvent(level=level,message=message)).takeLast(300)) }
    override fun onCleared() { if(receiverRegistered) getApplication<Application>().unregisterReceiver(receiver); closeTransport() }
    @Suppress("DEPRECATION") private fun Intent.device():UsbDevice? = if(Build.VERSION.SDK_INT>=33) getParcelableExtra(UsbManager.EXTRA_DEVICE,UsbDevice::class.java) else getParcelableExtra(UsbManager.EXTRA_DEVICE)
}
