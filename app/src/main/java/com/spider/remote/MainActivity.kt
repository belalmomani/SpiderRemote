@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.spider.remote

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
                MaterialTheme {
                    Surface(Modifier.fillMaxSize(), color = Color.Transparent) { RootScreen() }
                }
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
    var renderers by remember { mutableStateOf(listOf<Renderer>()) }
    var selected by remember { mutableStateOf<Renderer?>(null) }
    var statusMsg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf<FileServer?>(null) }

    fun search() {
        scope.launch {
            busy = true; statusMsg = "جارٍ البحث عن جهازك..."
            renderers = Dlna.discover()
            if (renderers.size == 1) selected = renderers.first()
            statusMsg = if (renderers.isEmpty()) "لم يُعثر على جهاز. تأكد أن الرسيفر والهاتف على نفس شبكة Wi-Fi." else ""
            busy = false
        }
    }

    LaunchedEffect(Unit) { search() }

    Box(Modifier.fillMaxSize()) {
        // الريموت: هيكل أسود شبه معتم فوق خلفية التطبيق الشفافة
        Column(
            Modifier
                .fillMaxSize()
                .padding(vertical = 28.dp, horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                .background(Color(0xEE1A1A1A), RoundedCornerShape(28.dp))
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(36.dp)) // مكان لزري الإغلاق والإعدادات العائمين

            val sel = selected
            if (sel == null) {
                Text("Spider Remote", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (renderers.size > 1) {
                    renderers.forEach { r ->
                        OutlinedButton(onClick = { selected = r }, modifier = Modifier.fillMaxWidth()) { Text(r.name) }
                    }
                }
                if (statusMsg.isNotBlank()) Text(statusMsg, color = Color.White)
                Button(enabled = !busy, onClick = { search() }) { Text(if (busy) "جارٍ البحث..." else "ابحث عن جهازي") }
                return@Column
            }

            val t = remember(sel) { DlnaTransport(sel) }
            var level by remember(sel) { mutableStateOf(t.level.toFloat()) }
            var resultLine by remember(sel) { mutableStateOf("") }
            val hasVolume = sel.renderingUrl != null
            val hasTransport = sel.avUrl != null
            val enabled = buildSet {
                if (hasVolume) { add(RemoteKey.VOL_UP); add(RemoteKey.VOL_DOWN); add(RemoteKey.MUTE) }
            }
            fun press(key: RemoteKey) {
                scope.launch { resultLine = t.send(key).msg(); level = t.level.toFloat() }
            }

            Text(sel.name, color = Color(0xFFBBBBBB), style = MaterialTheme.typography.labelMedium)

            // الصف الأول: POWER و MUTE
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                RKey(RemoteKey.POWER, "⏻", enabled, ::press, accent = Color(0xFFE53935))
                RKey(RemoteKey.MUTE, "🔇", enabled, ::press)
            }
            RKeyRow(listOf(RemoteKey.NONE to "P.MODE", RemoteKey.NONE to "S.MODE", RemoteKey.NONE to "ASPECT"), enabled, ::press)
            RKeyRow(listOf(RemoteKey.NONE to "PC", RemoteKey.NONE to "SLEEP", RemoteKey.NONE to "AV"), enabled, ::press)
            RKeyRow(listOf(RemoteKey.TV_RADIO to "TV", RemoteKey.NONE to "AUDIO", RemoteKey.NONE to "⌨"), enabled, ::press)

            val numbers = listOf(
                RemoteKey.NUM_1 to "1", RemoteKey.NUM_2 to "2", RemoteKey.NUM_3 to "3",
                RemoteKey.NUM_4 to "4", RemoteKey.NUM_5 to "5", RemoteKey.NUM_6 to "6",
                RemoteKey.NUM_7 to "7", RemoteKey.NUM_8 to "8", RemoteKey.NUM_9 to "9"
            )
            numbers.chunked(3).forEach { RKeyRow(it, enabled, ::press) }
            RKeyRow(listOf(RemoteKey.FAV to "FAV", RemoteKey.NUM_0 to "0", RemoteKey.NONE to "↻"), enabled, ::press)

            // صليب الأسهم + OK
            Box(
                Modifier.size(190.dp).clip(CircleShape).background(Color(0xFF2A2A2A)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    RKey(RemoteKey.UP, "▲", enabled, ::press, round = true)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RKey(RemoteKey.LEFT, "◀", enabled, ::press, round = true)
                        RKey(RemoteKey.OK, "OK", enabled, ::press, round = true)
                        RKey(RemoteKey.RIGHT, "▶", enabled, ::press, round = true)
                    }
                    RKey(RemoteKey.DOWN, "▼", enabled, ::press, round = true)
                }
            }
            RKeyRow(listOf(RemoteKey.MENU to "MENU", RemoteKey.EXIT to "EXIT"), enabled, ::press)

            // CH / HOME / VOL
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RKey(RemoteKey.CH_UP, "CH ▲", enabled, ::press)
                    RKey(RemoteKey.CH_DOWN, "CH ▼", enabled, ::press)
                }
                RKey(RemoteKey.NONE, "⌂ HOME", enabled, ::press)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RKey(RemoteKey.VOL_UP, "VOL +", enabled, ::press)
                    RKey(RemoteKey.VOL_DOWN, "VOL −", enabled, ::press)
                }
            }

            RKeyRow(listOf(RemoteKey.EPG to "EPG", RemoteKey.NONE to "REC", RemoteKey.NONE to "REC.M"), enabled, ::press)
            RKeyRow(listOf(RemoteKey.NONE to "T.SHIFT", RemoteKey.NONE to "SETTING", RemoteKey.TV_RADIO to "TV/RADIO"), enabled, ::press)

            if (hasTransport) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TransportKey("⏮", false) {}
                    TransportKey("⏯", true) { scope.launch { resultLine = t.avAction("Play").msg() } }
                    TransportKey("⏭", false) {}
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TransportKey("⏪", false) {}
                    TransportKey("⏹", true) { scope.launch { resultLine = t.avAction("Stop").msg() } }
                    TransportKey("⏩", false) {}
                }
            }

            RKeyRow(listOf(RemoteKey.NONE to "SUB.T", RemoteKey.NONE to "REPEAT", RemoteKey.NONE to "LANG"), enabled, ::press)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(Color(0xFFE53935), Color(0xFF43A047), Color(0xFFFDD835), Color(0xFF1E88E5)).forEach { c ->
                    Box(Modifier.size(28.dp, 18.dp).clip(RoundedCornerShape(4.dp)).background(c.copy(alpha = 0.5f)))
                }
            }

            Slider(
                value = level, onValueChange = { level = it },
                onValueChangeFinished = { scope.launch { resultLine = t.setLevel(level.toInt()).msg() } },
                valueRange = 0f..100f, enabled = hasVolume
            )
            if (resultLine.isNotBlank()) Text(resultLine, color = Color.White, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { selected = null; renderers = emptyList(); server?.stop(); search() }) {
                Text("تغيير الجهاز", color = Color(0xFFBBBBBB))
            }
        }

        // زرا الإغلاق والإعدادات العائمان أعلى الشاشة
        val activity = ctx as? Activity
        Row(
            Modifier.align(Alignment.TopEnd).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FloatingGlyphButton("⚙") { onOpenAdvanced() }
            FloatingGlyphButton("✕") { activity?.finish() }
        }
    }
}

@Composable
private fun FloatingGlyphButton(glyph: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Color(0x99000000))
            .then(Modifier),
        contentAlignment = Alignment.Center
    ) {
        TextButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
            Text(glyph, color = Color.White)
        }
    }
}

@Composable
private fun TransportKey(glyph: String, supported: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape)
            .background(if (supported) Color(0xFF333333) else Color(0xFF222222)),
        contentAlignment = Alignment.Center
    ) {
        TextButton(onClick = onClick, enabled = supported, modifier = Modifier.fillMaxSize()) {
            Text(glyph, color = if (supported) Color.White else Color(0xFF555555))
        }
    }
}

@Composable
private fun RKeyRow(items: List<Pair<RemoteKey, String>>, enabledKeys: Set<RemoteKey>, onKey: (RemoteKey) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (key, label) ->
            Box(Modifier.weight(1f)) { RKey(key, label, enabledKeys, onKey, fill = true) }
        }
    }
}

@Composable
private fun RKey(
    key: RemoteKey, label: String, enabledKeys: Set<RemoteKey>, onKey: (RemoteKey) -> Unit,
    fill: Boolean = false, round: Boolean = false, accent: Color? = null
) {
    val supported = key != RemoteKey.NONE && key in enabledKeys
    val bg = when {
        accent != null && supported -> accent
        supported -> Color(0xFF3A3A3A)
        else -> Color(0xFF242424)
    }
    val textColor = if (supported) Color.White else Color(0xFF5A5A5A)
    val shape = if (round) CircleShape else RoundedCornerShape(10.dp)
    val sizeMod = if (round) Modifier.size(56.dp) else if (fill) Modifier.fillMaxWidth().height(44.dp) else Modifier.size(56.dp, 44.dp)
    Box(sizeMod.clip(shape).background(bg), contentAlignment = Alignment.Center) {
        TextButton(onClick = { if (supported) onKey(key) }, enabled = supported, modifier = Modifier.fillMaxSize()) {
            Text(label, color = textColor, style = MaterialTheme.typography.labelMedium)
        }
    }
}
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
