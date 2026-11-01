package com.spider.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
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
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme { Surface(Modifier.fillMaxSize()) { AppScreen() } }
            }
        }
    }
}

private fun Result<Unit>.msg() = fold({ "تم" }, { "فشل: ${it.message}" })

@Composable
fun AppScreen() {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var renderers by remember { mutableStateOf(listOf<Renderer>()) }
    var selected by remember { mutableStateOf<Renderer?>(null) }
    var log by remember { mutableStateOf(listOf<String>()) }
    val profile = ProfileRegistry.all.first()

    fun run(label: String, block: suspend () -> String) {
        scope.launch {
            busy = true
            log = listOf("$label ...")
            log = listOf("$label: " + block())
            busy = false
        }
    }

    suspend fun status(t: DlnaTransport): String {
        val v = t.getVolume().fold({ "$it" }, { "؟ (${it.message})" })
        val m = t.getMute().fold({ if (it) "مكتوم" else "غير مكتوم" }, { "؟ (${it.message})" })
        return "الصوت: $v — $m"
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Spider Remote", style = MaterialTheme.typography.headlineSmall)
        Text("الموديل المرجعي: ${profile.displayName}")
        Text("المتاح فعلياً عبر Wi-Fi لهذا الموديل: الصوت والكتم (DLNA). أزرار MENU وOK والقنوات غير معروضة من الجهاز.")
        HorizontalDivider()

        Button(enabled = !busy, onClick = {
            run("البحث") {
                renderers = Dlna.discover()
                if (renderers.isEmpty()) "لم يُعثر على أجهزة عرض" else "عُثر على ${renderers.size} جهاز"
            }
        }) { Text("ابحث عن أجهزة العرض (DLNA)") }

        renderers.forEach { r ->
            OutlinedButton(onClick = { selected = r; log = listOf("تم اختيار ${r.name}") }, modifier = Modifier.fillMaxWidth()) {
                Text("${r.name} — ${r.ip}")
            }
        }

        val sel = selected
        if (sel != null) {
            val t = remember(sel) { DlnaTransport(sel) }
            Text("المحدد: ${sel.name} (${sel.ip})", style = MaterialTheme.typography.titleMedium)
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { run("الحالة") { status(t) } }) {
                Text("قراءة الصوت والكتم (قراءة فقط)")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                    run("خفض الصوت") { t.send(RemoteKey.VOL_DOWN).msg() + " — " + status(t) }
                }) { Text("صوت −") }
                Button(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                    run("رفع الصوت") { t.send(RemoteKey.VOL_UP).msg() + " — " + status(t) }
                }) { Text("صوت +") }
            }
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                run("كتم") { t.send(RemoteKey.MUTE).msg() + " — " + status(t) }
            }) { Text("كتم / إلغاء الكتم") }
        }
        log.forEach { Text(it) }

        HorizontalDivider()
        ProbeSection()
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
