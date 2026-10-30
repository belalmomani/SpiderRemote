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
import com.spider.remote.core.ProtocolStatus
import com.spider.remote.data.SettingsStore
import com.spider.remote.probe.SsdpProbe
import com.spider.remote.probe.TcpProbe
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme { Surface(Modifier.fillMaxSize()) { ProbeScreen() } }
            }
        }
    }
}

@Composable
fun ProbeScreen() {
    val ctx = LocalContext.current
    val store = remember { SettingsStore(ctx) }
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(store.host) }
    var ports by remember { mutableStateOf(store.ports) }
    var lines by remember { mutableStateOf(listOf<String>()) }
    var running by remember { mutableStateOf(false) }
    val profile = ProfileRegistry.all.first()

    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Spider Remote", style = MaterialTheme.typography.headlineSmall)
        Text("الموديل: ${profile.displayName}")
        Text(
            if (profile.status == ProtocolStatus.UNVERIFIED)
                "حالة البروتوكول: غير مثبت — لا توجد أزرار تحكم حتى يثبت الاختبار."
            else "حالة البروتوكول: مثبت"
        )
        HorizontalDivider()
        Text("أداة فحص الاتصال (قراءة فقط، لا ترسل أي بيانات)", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = host, onValueChange = { host = it }, singleLine = true,
            label = { Text("عنوان IP للرسيفر (مثال 192.168.8.14)") }, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = ports, onValueChange = { ports = it }, singleLine = true,
            label = { Text("المنافذ (مفصولة بفاصلة، حتى 20)") }, modifier = Modifier.fillMaxWidth()
        )
        Button(
            enabled = !running,
            onClick = {
                val h = host.trim()
                val list = TcpProbe.parsePorts(ports)
                when {
                    !TcpProbe.isPrivateIPv4(h) -> lines = listOf("العنوان يجب أن يكون IP محلياً (192.168.x.x أو 10.x.x.x أو 172.16–31.x.x)")
                    list.isEmpty() -> lines = listOf("أدخل منفذاً صحيحاً واحداً على الأقل")
                    else -> {
                        store.host = h; store.ports = ports
                        running = true; lines = listOf("جارٍ الفحص...")
                        scope.launch {
                            val out = list.map { TcpProbe.check(h, it) }
                            lines = out.map { "المنفذ ${it.port}: ${if (it.open) "مفتوح" else "مغلق/لا استجابة"}" } +
                                if (out.none { it.open }) listOf("لا منافذ مفتوحة من القائمة. هذا لا يثبت غياب كل المنافذ.") else emptyList()
                            running = false
                        }
                    }
                }
            }
        ) { Text("فحص الاتصال") }
        OutlinedButton(
            enabled = !running,
            onClick = {
                running = true; lines = listOf("جارٍ الاستماع 3 ثوانٍ...")
                scope.launch {
                    val byIp = SsdpProbe.search().groupBy { it.from }
                    lines = if (byIp.isEmpty()) listOf("لم يرد أي جهاز على SSDP. هذا لا يثبت غياب خدمات أخرى.")
                    else byIp.map { (ip, rs) ->
                        (if (ip == host.trim()) "★ $ip (العنوان المدخل)\n" else "$ip\n") + SsdpProbe.summarize(rs)
                    }
                    running = false
                }
            }
        ) { Text("اكتشاف الأجهزة على الشبكة (SSDP)") }
        lines.forEach { Text(it) }
    }
}
