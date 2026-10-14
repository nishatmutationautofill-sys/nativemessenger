package com.webmessenger.app

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

class App : Application() { val client by lazy { ChatClient(this) } }

class MainActivity : ComponentActivity() {
    private val client get() = (application as App).client
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF075E54))) {
                val signed by client.signedIn.collectAsState()
                if (signed) ChatScreen(client) else LoginScreen(client)
            }
        }
    }
    override fun onStart() { super.onStart(); client.ensureConnected() } // stay logged in
}

@Composable
fun LoginScreen(c: ChatClient) {
    var id by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val err by c.error.collectAsState()
    val conn by c.conn.collectAsState()
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Web Messenger", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(id, { id = it.trim() }, label = { Text("Machine ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(8) }, label = { Text("৮ সংখ্যার pairing code") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
        if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(16.dp))
        Button({ c.login(id, code) }, enabled = id.length >= 6 && code.length == 8 && conn != Conn.CONNECTING, modifier = Modifier.fillMaxWidth()) {
            Text(if (conn == Conn.CONNECTING) "সংযোগ হচ্ছে…" else "নিরাপদে সংযুক্ত করুন")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(c: ChatClient) {
    val msgs by c.messages.collectAsState()
    val conn by c.conn.collectAsState()
    var sel by remember { mutableStateOf<Msg?>(null) }
    var quote by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.lastIndex) }
    BackHandler(sel != null) { sel = null }
  val green = TopAppBarDefaults.topAppBarColors(
    containerColor = Color(0xFF075E54),
    titleContentColor = Color.White,
    navigationIconContentColor = Color.White,
    actionIconContentColor = Color.White
)

    Scaffold(modifier = Modifier.imePadding(), topBar = {
        val s = sel
        if (s == null) {
            TopAppBar(title = {
                Column { Text("Support"); Text(when (conn) { Conn.ONLINE -> "Online"; Conn.CONNECTING -> "সংযোগ হচ্ছে…"; else -> "অফলাইন — পুনরায় সংযোগ হচ্ছে" }, fontSize = 11.sp) }
            }, actions = { TextButton({ c.logout() }) { Text("লগআউট", color = Color.White) } }, colors = green)
        } else {
            // WhatsApp-style action bar replaces the header while a message is selected
            TopAppBar(title = {}, colors = green,
                navigationIcon = { IconButton({ sel = null }) { Icon(Icons.Default.Close, "Cancel") } },
                actions = {
                    IconButton({ quote = s.text.take(100); sel = null }) { Icon(Icons.AutoMirrored.Filled.Reply, "Reply") }
                    IconButton({ clip.setText(AnnotatedString(s.text)); Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show(); sel = null }) { Icon(Icons.Default.ContentCopy, "Copy") }
                    IconButton({
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "⤼ Forwarded\n" + s.text), "Forward"))
                        sel = null
                    }) { Icon(Icons.Default.Share, "Forward") }
                    if (s.mine) IconButton({ c.delete(s.id); sel = null }) { Icon(Icons.Default.Delete, "Delete") }
                })
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().background(Color(0xFFECE5DD))) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(msgs, key = { it.cid + it.id }) { m ->
                    val picked = sel?.id == m.id
                    Row(Modifier.fillMaxWidth().background(if (picked) Color(0x330084FF) else Color.Transparent)
                        .clickable { sel = if (picked) null else m }.padding(vertical = 2.dp),
                        horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
                        Surface(shape = RoundedCornerShape(12.dp), color = if (m.mine) Color(0xFFDCF8C6) else Color.White, modifier = Modifier.widthIn(max = 300.dp)) {
                            Column(Modifier.padding(8.dp)) {
                                val q = Regex("^↪ (.*)\\n—\\n([\\s\\S]*)$").find(m.text)
                                if (q != null) {
                                    Text(q.groupValues[1], fontSize = 12.sp, color = Color.Gray, maxLines = 2,
                                        modifier = Modifier.background(Color(0x14000000)).padding(4.dp))
                                    Text(q.groupValues[2])
                                } else Text(m.text)
                                Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.ts)) + if (m.pending) " ⏳" else "",
                                    fontSize = 10.sp, color = Color.Gray, modifier = Modifier.align(Alignment.End))
                            }
                        }
                    }
                }
            }
            quote?.let {
                Row(Modifier.fillMaxWidth().background(Color(0x1A06CF9C)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("↪ " + it.replace('\n', ' '), maxLines = 1, modifier = Modifier.weight(1f), fontSize = 13.sp)
                    IconButton({ quote = null }) { Icon(Icons.Default.Close, "Cancel reply") }
                }
            }
            Row(Modifier.fillMaxWidth().background(Color.White).navigationBarsPadding().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, placeholder = { Text("Type a message…") }, modifier = Modifier.weight(1f), maxLines = 4,
                    shape = RoundedCornerShape(24.dp))
                IconButton({
                    if (input.isNotBlank()) { c.send(if (quote != null) "↪ $quote\n—\n$input" else input); input = ""; quote = null }
                }) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color(0xFF075E54)) }
            }
        }
    }
}
