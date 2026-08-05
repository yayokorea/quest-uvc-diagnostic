package com.yayo.questuvc

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm:MainViewModel by viewModels()
    override fun onCreate(savedInstanceState:Bundle?) { super.onCreate(savedInstanceState); vm.register(); setContent { QuestTheme { DiagnosticScreen(vm) } } }
}

private val DarkColors=darkColorScheme(primary=Color(0xFF62D7C8),secondary=Color(0xFFA9C7FF),surface=Color(0xFF101716),surfaceVariant=Color(0xFF263331),error=Color(0xFFFFB4AB))
@Composable
private fun QuestTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, typography = Typography(), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DiagnosticScreen(vm:MainViewModel) {
    val s by vm.state.collectAsStateWithLifecycle(); val context=androidx.compose.ui.platform.LocalContext.current
    val cameraPermissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(),vm::onCameraPermissionResult)
    val horizonUsbCameraPermissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(),vm::onHorizonUsbCameraPermissionResult)
    LaunchedEffect(s.cameraPermissionRequired) { if(s.cameraPermissionRequired) cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(s.horizonUsbCameraPermissionRequired) { if(s.horizonUsbCameraPermissionRequired) horizonUsbCameraPermissionLauncher.launch(MainViewModel.HORIZON_USB_CAMERA_PERMISSION) }
    Scaffold(topBar={ TopAppBar(title={ Text("Quest UVC Diagnostic") },actions={ TextButton(onClick={vm.refresh()}){Text("Refresh")}; TextButton(onClick={ val uri=vm.reportUri(); context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_STREAM,uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) },"Share diagnostic report")) }){Text("Share report")} }) }) { padding ->
        Row(Modifier.fillMaxSize().padding(padding).padding(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            LazyColumn(Modifier.weight(0.42f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                item { StatusCard(s) }
                item { Section("USB devices") { if(s.devices.isEmpty()) Text("No USB devices detected",color=MaterialTheme.colorScheme.error) else s.devices.forEach { d -> DeviceRow(d,d.deviceId==s.selectedDeviceId){vm.select(d.deviceId)} } } }
                item { ModeCard(s,vm) }
                item { EndpointCard(s.topology) }
            }
            LazyColumn(Modifier.weight(0.58f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                item { PreviewCard(s.previewJpeg,{ val uri=vm.saveFrame(); Toast.makeText(context,if(uri==null) "No frame to save" else "Saved to Pictures/QuestUvcDiagnostic",Toast.LENGTH_SHORT).show() }) }
                item { StatisticsCard(s.statistics) }
                item { LogCard(s.events) }
            }
        }
    }
}

@Composable private fun Section(title:String,content:@Composable ColumnScope.()->Unit) { ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) { Text(title,style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary); content() } } }
@Composable private fun StatusCard(s:DiagnosticState)=Section("Compatibility status") {
    val rows=buildList { add("Device detected" to (s.devices.isNotEmpty()).yn()); add("Android camera permission" to s.cameraPermissionGranted.granted()); if(s.horizonUsbCameraPermissionAvailable)add("Horizon USB camera" to s.horizonUsbCameraPermissionGranted.granted()); add("USB permission" to (s.devices.firstOrNull{it.deviceId==s.selectedDeviceId}?.permission==true).granted()); add("UVC interface" to (s.topology?.videoStreamingInterfaces?.isNotEmpty()==true).found()); add("Probe / Commit" to s.probeResult); add("Streaming" to if(s.phase==SessionPhase.STREAMING) "Active" else s.phase.name) }
    rows.forEach { (a,b)->KeyValue(a,b) }
}
@Composable private fun DeviceRow(d:UsbDeviceInfo,selected:Boolean,onClick:()->Unit) { Surface(onClick=onClick,color=if(selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(10.dp),modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) { Text(d.product ?: d.name,style=MaterialTheme.typography.titleSmall); Text("VID:PID %04X:%04X · ${d.manufacturer ?: "Unknown maker"}".format(d.vid,d.pid),style=MaterialTheme.typography.bodySmall); Text("Serial ${d.serial ?: "(permission required/unavailable)"} · class ${d.deviceClass}/${d.subclass}/${d.protocol}",style=MaterialTheme.typography.bodySmall) } } }
@Composable private fun ModeCard(s:DiagnosticState,vm:MainViewModel)=Section("Stream mode") {
    val modes=s.topology?.modes.orEmpty(); var expanded by remember { mutableStateOf(false) }; var fpsExpanded by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick={expanded=true},enabled=modes.isNotEmpty(),modifier=Modifier.fillMaxWidth()){Text(s.selectedMode?.label ?: "No UVC modes")}; DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) { modes.forEach { mode->DropdownMenuItem(text={Text(mode.label)},onClick={vm.chooseMode(mode);expanded=false}) } } }
    val intervals=s.selectedMode?.intervals100ns.orEmpty(); Box { OutlinedButton(onClick={fpsExpanded=true},enabled=intervals.isNotEmpty(),modifier=Modifier.fillMaxWidth()){Text(s.selectedInterval?.let{"Requested %.1f FPS".format(Locale.US,10_000_000.0/it)} ?: "No frame interval")}; DropdownMenu(expanded=fpsExpanded,onDismissRequest={fpsExpanded=false}) { intervals.forEach { v->DropdownMenuItem(text={Text("%.2f FPS".format(Locale.US,10_000_000.0/v))},onClick={vm.chooseInterval(v);fpsExpanded=false}) } } }
    s.selectedMode?.let { KeyValue("Transfer","${it.transferKind} · EP 0x${it.endpointAddress.toString(16)} · alt ${it.alternateSetting}"); KeyValue("Max packet",it.maxPacketSize.toString()) }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Button(onClick=vm::start,enabled=s.phase==SessionPhase.PARSED||s.phase==SessionPhase.ERROR){Text("Probe & start")}; OutlinedButton(onClick=vm::stop,enabled=s.phase==SessionPhase.STREAMING){Text("Stop")} }
}
@Composable private fun EndpointCard(t:UvcTopology?)=Section("UVC topology") { if(t==null) Text("Select and open a USB device") else { KeyValue("UVC version",t.uvcVersion?.let{"%x.%02x".format(it shr 8,it and 255)} ?: "Unknown"); KeyValue("VideoControl",t.videoControlInterfaces.joinToString().ifEmpty{"Not found"}); KeyValue("VideoStreaming",t.videoStreamingInterfaces.joinToString().ifEmpty{"Not found"}); t.alternates.filter{it.interfaceClass==14}.forEach { a->Text("IF ${a.interfaceNumber} alt ${a.alternate}: "+a.endpoints.joinToString { "0x${it.address.toString(16)} ${it.transferKind} ${it.maxPacketSize}B" },style=MaterialTheme.typography.bodySmall,fontFamily=FontFamily.Monospace) } } }
@Composable private fun PreviewCard(bytes:ByteArray?,save:()->Unit)=Section("MJPEG preview") { val bitmap=remember(bytes){bytes?.let{BitmapFactory.decodeByteArray(it,0,it.size)}}; Box(Modifier.fillMaxWidth().height(260.dp).background(Color.Black,RoundedCornerShape(8.dp)),contentAlignment=Alignment.Center) { if(bitmap==null) Text("Waiting for a valid JPEG frame") else Image(bitmap.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Fit) }; OutlinedButton(onClick=save,enabled=bytes!=null){Text("Save current JPEG")} }
@Composable private fun StatisticsCard(v:StreamStatistics)=Section("Live statistics") { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Metric("Received",formatBytes(v.receivedBytes));Metric("Packets","%,d".format(v.packets));Metric("Frames","%,d".format(v.frames));Metric("FPS","%.1f".format(Locale.US,v.fps))}; HorizontalDivider(); KeyValue("Frame bytes","min ${v.minFrameBytes} · avg %.0f · max ${v.maxFrameBytes}".format(Locale.US,v.averageFrameBytes)); KeyValue("Damaged / dropped","${v.corruptFrames} / ${v.droppedFrames}"); KeyValue("USB errors",v.usbErrors.toString()); v.lastError?.let{Text(it,color=MaterialTheme.colorScheme.error)} }
@Composable private fun LogCard(events:List<DiagnosticEvent>)=Section("Diagnostic log") { Column(Modifier.fillMaxWidth().heightIn(min=120.dp,max=260.dp).background(Color(0xFF08100F),RoundedCornerShape(8.dp)).padding(8.dp)) { events.takeLast(14).forEach { Text("${it.level.padEnd(5)} ${it.message}",fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall,color=if(it.level=="ERROR") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) } } }
@Composable private fun KeyValue(k:String,v:String)=Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(k,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(v,fontFamily=FontFamily.Monospace)}
@Composable private fun Metric(k:String,v:String)=Column(horizontalAlignment=Alignment.CenterHorizontally){Text(v,style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary);Text(k,style=MaterialTheme.typography.labelSmall)}
private fun Boolean.yn()=if(this)"Yes" else "No"; private fun Boolean.granted()=if(this)"Granted" else "Not granted"; private fun Boolean.found()=if(this)"Found" else "Not found"
private fun formatBytes(v:Long):String=when { v>=1_048_576->"%.1f MB".format(Locale.US,v/1_048_576.0);v>=1024->"%.1f KB".format(Locale.US,v/1024.0);else->"$v B" }
