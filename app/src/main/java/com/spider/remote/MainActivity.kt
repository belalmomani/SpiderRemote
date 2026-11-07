@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.spider.remote

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.spider.remote.core.ProfileRegistry
import com.spider.remote.core.RemoteKey
import com.spider.remote.data.SettingsStore
import com.spider.remote.dlna.Dlna
import com.spider.remote.dlna.DlnaTransport
import com.spider.remote.dlna.Renderer
import com.spider.remote.probe.SsdpProbe
import com.spider.remote.probe.TcpProbe
import com.spider.remote.stream.FileServer
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme { Surface(Modifier.fillMaxSize()) { RootScreen() } }
            }
        }
    }
}

private fun Result<Unit>.msg() = fold({ "تم" }, { "فشل: ${it.message}" })

/** الجذر: يختار بين الشاشة المبسطة للمستخدم وشاشة الأدوات المتقدمة، دون خلطهما. */
@Composable
fun RootScreen() {
    var advanced by remember { mutableStateOf(false) }
    if (advanced) {
        AdvancedScreen(onBack = { advanced = false })
    } else {
        UserScreen(onOpenAdvanced = { advanced = true })
    }
}

/** الشاشة التي يراها المستخدم: اكتشاف تلقائي ثم أزرار الجهاز المدعومة فقط. */
@Composable
fun UserScreen(onOpenAdvanced: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var renderers by remember { mutableStateOf(listOf<Renderer>()) }
    var selected by remember { mutableStateOf<Renderer?>(null) }
    var statusMsg by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf<FileServer?>(null) }

    fun search() {
        scope.launch {
            busy = true; searched = true; statusMsg = "جارٍ البحث عن جهازك..."
            renderers = Dlna.discover()
            if (renderers.size == 1) selected = renderers.first()
            statusMsg = if (renderers.isEmpty()) "لم يُعثر على جهاز. تأكد أن الرسيفر والهاتف على نفس شبكة Wi-Fi." else ""
            busy = false
        }
    }

    LaunchedEffect(Unit) { search() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spider Remote") },
                actions = {
                    IconButton(onClick = onOpenAdvanced) {
                        Icon(Icons.Filled.Settings, contentDescription = "خيارات متقدمة")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (renderers.size > 1 || (searched && selected == null)) {
                Text("اختر جهازك", style = MaterialTheme.typography.titleMedium)
                renderers.forEach { r ->
                    OutlinedButton(onClick = { selected = r }, modifier = Modifier.fillMaxWidth()) {
                        Text(r.name)
                    }
                }
            }

            val sel = selected
            if (sel == null) {
                if (statusMsg.isNotBlank()) Text(statusMsg)
                Button(enabled = !busy, onClick = { search() }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "جارٍ البحث..." else "ابحث عن جهازي")
                }
            } else {
                val t = remember(sel) { DlnaTransport(sel) }
                var level by remember(sel) { mutableStateOf(t.level.toFloat()) }
                var resultLine by remember(sel) { mutableStateOf("") }
                val hasVolume = sel.renderingUrl != null
                val hasTransport = sel.avUrl != null

                Text(sel.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("الموديل المرجعي: ${ProfileRegistry.all.first().displayName}", style = MaterialTheme.typography.bodySmall)

                if (!hasVolume && !hasTransport) {
                    Text("هذا الجهاز لا يعرض أي وظيفة تحكم مدعومة حالياً.")
                }

                if (hasVolume) {
                    ElevatedCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("الصوت", style = MaterialTheme.typography.titleMedium)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                IconButtonVol("−") {
                                    scope.launch { resultLine = t.send(RemoteKey.VOL_DOWN).msg(); level = t.level.toFloat() }
                                }
                                Slider(
                                    value = level, onValueChange = { level = it },
                                    onValueChangeFinished = { scope.launch { resultLine = t.setLevel(level.toInt()).msg() } },
                                    valueRange = 0f..100f, modifier = Modifier.weight(1f)
                                )
                                IconButtonVol("+") {
                                    scope.launch { resultLine = t.send(RemoteKey.VOL_UP).msg(); level = t.level.toFloat() }
                                }
                            }
                            OutlinedButton(onClick = {
                                scope.launch { resultLine = t.send(RemoteKey.MUTE).msg(); level = t.level.toFloat() }
                            }, modifier = Modifier.fillMaxWidth()) { Text("كتم / إلغاء الكتم") }
                        }
                    }
                }

                if (hasTransport) {
                    ElevatedCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("تشغيل الوسائط", style = MaterialTheme.typography.titleMedium)
                            Text("يعمل فقط على ما يُبث إلى الجهاز، لا على قنوات الرسيفر.", style = MaterialTheme.typography.bodySmall)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(modifier = Modifier.weight(1f), onClick = {
                                    scope.launch { resultLine = t.avAction("Play").msg() }
                                }) { Text("تشغيل") }
                                Button(modifier = Modifier.weight(1f), onClick = {
                                    scope.launch { resultLine = t.avAction("Pause").msg() }
                                }) { Text("إيقاف مؤقت") }
                                Button(modifier = Modifier.weight(1f), onClick = {
                                    scope.launch { resultLine = t.avAction("Stop").msg() }
                                }) { Text("إيقاف") }
                            }
                            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                                if (uri != null) {
                                    val bind = FileServer.localAddressFor(sel.ip)
                                    if (bind == null) { resultLine = "تعذّر تحديد عنوان الهاتف على نفس شبكة الرسيفر"; return@rememberLauncherForActivityResult }
                                    server?.stop()
                                    val fs = FileServer(ctx, uri, sel.ip, bind)
                                    val url = fs.start()
                                    server = fs
                                    scope.launch { resultLine = t.playUri(url, fs.mime, "Spider Remote").msg() }
                                }
                            }
                            OutlinedButton(onClick = { picker.launch(arrayOf("video/*", "audio/*", "image/*")) }, modifier = Modifier.fillMaxWidth()) {
                                Text("بث ملف من الهاتف (تجريبي)")
                            }
                        }
                    }
                }

                if (resultLine.isNotBlank()) Text(resultLine, style = MaterialTheme.typography.bodySmall)

                TextButton(onClick = { selected = null; renderers = emptyList(); search() }) {
                    Text("تغيير الجهاز")
                }
            }
        }
    }
}

@Composable
private fun IconButtonVol(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)) {
        Text(label, style = MaterialTheme.typography.titleLarge)
    }
}

/** شاشة منفصلة لأدوات الفحص والتطوير؛ لا يراها المستخدم العادي. */
@Composable
fun AdvancedScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("خيارات متقدمة") },
                navigationIcon = { TextButton(onClick = onBack) { Text("رجوع") } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("هذه الأدوات للتطوير والتشخيص فقط، ولا تظهر للمستخدم العادي.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            ProbeSection()
        }
    }
}

@Composable
fun ProbeSection() {
    val ctx = LocalContext.current
    val store = remember { SettingsStore(ctx) }
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(store.host) }
    var ports by remember { mutableStateOf(store.ports) }
    var lines by remember { mutableStateOf(listOf<String>()) }
    var running by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("أدوات الفحص (قراءة فقط)", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = host, onValueChange = { host = it }, singleLine = true,
            label = { Text("عنوان IP (مثال 192.168.8.52)") }, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = ports, onValueChange = { ports = it }, singleLine = true,
            label = { Text("المنافذ (مفصولة بفاصلة، حتى 20)") }, modifier = Modifier.fillMaxWidth()
        )
        Button(enabled = !running, onClick = {
            val h = host.trim()
            val list = TcpProbe.parsePorts(ports)
            when {
                !TcpProbe.isPrivateIPv4(h) -> lines = listOf("العنوان يجب أن يكون IP محلياً")
                list.isEmpty() -> lines = listOf("أدخل منفذاً صحيحاً واحداً على الأقل")
                else -> {
                    store.host = h; store.ports = ports
                    running = true; lines = listOf("جارٍ الفحص...")
                    scope.launch {
                        val out = list.map { TcpProbe.check(h, it) }
                        lines = out.map { "المنفذ ${it.port}: ${if (it.open) "مفتوح" else "مغلق/لا استجابة"}" }
                        running = false
                    }
                }
            }
        }) { Text("فحص الاتصال") }
        OutlinedButton(enabled = !running, onClick = {
            running = true; lines = listOf("جارٍ الاستماع 3 ثوانٍ...")
            scope.launch {
                val byIp = SsdpProbe.search().groupBy { it.from }
                lines = if (byIp.isEmpty()) listOf("لم يرد أي جهاز على SSDP.")
                else byIp.map { (ip, rs) ->
                    (if (ip == host.trim()) "★ $ip\n" else "$ip\n") + SsdpProbe.summarize(rs)
                }
                running = false
            }
        }) { Text("اكتشاف الأجهزة (SSDP)") }
        lines.forEach { Text(it) }
    }
}
