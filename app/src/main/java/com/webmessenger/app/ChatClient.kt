package com.webmessenger.app

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class Msg(val id: String, val cid: String, val mine: Boolean, val text: String, val ts: Long, val pending: Boolean = false)
enum class Conn { OFFLINE, CONNECTING, ONLINE }

/** Same protocol as the web app: Firestore -> serverUrl -> wss://…/ws, pair_hello / pair_resume. */
class ChatClient(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("wm", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val firestore = "https://firestore.googleapis.com/v1/projects/nishatmutationautofill-3bde4/databases/(default)/documents/config/chat"

    @Volatile private var gen = 0
    @Volatile private var retry = 0
    private var ws: WebSocket? = null
    private var base = ""
    private var uid = ""
    private var uploadKey = ""

    val messages = MutableStateFlow<List<Msg>>(emptyList())
    val conn = MutableStateFlow(Conn.OFFLINE)
    val error = MutableStateFlow("")
    /** True when a saved session exists (stay logged in) or the server accepted us. */
    val signedIn = MutableStateFlow(prefs.getString("token", null) != null)

    private fun savedId() = prefs.getString("id", null)
    private fun savedToken() = prefs.getString("token", null)

    fun login(id: String, code: String) { error.value = ""; start(id, code, null) }

    /** Call on every app start / return to foreground: silently resumes the saved session. */
    fun ensureConnected() {
        val id = savedId(); val tk = savedToken()
        if (id != null && tk != null && conn.value == Conn.OFFLINE) start(id, null, tk)
    }

    fun logout() {
        gen++
        try { ws?.send(JSONObject().put("t", "pair_logout").put("id", uid).put("token", savedToken() ?: "").toString()) } catch (_: Exception) {}
        ws?.close(1000, "logout"); ws = null
        prefs.edit().clear().apply()
        messages.value = emptyList(); conn.value = Conn.OFFLINE; signedIn.value = false
    }

    fun send(text: String): Boolean {
        val t = text.trim().take(2000); if (t.isEmpty()) return false
        val cid = UUID.randomUUID().toString()
        messages.update { it + Msg("c_$cid", cid, true, t, System.currentTimeMillis(), true) }
        return ws?.send(JSONObject().put("t", "msg").put("cid", cid).put("text", t).toString()) == true
    }

    fun delete(mid: String) {
        if (mid.startsWith("c_") || uploadKey.isEmpty()) { messages.update { l -> l.filter { it.id != mid } }; return }
        scope.launch {
            try {
                val r = Request.Builder().url("$base/delete-msg").delete()
                    .header("X-Chat-Uid", uid).header("X-Upload-Key", uploadKey).header("X-Msg-Id", mid).build()
                http.newCall(r).execute().use { if (it.isSuccessful) messages.update { l -> l.filter { m -> m.id != mid } } }
            } catch (_: Exception) {}
        }
    }

    private fun start(id: String, code: String?, token: String?) {
        val g = ++gen
        ws?.close(1000, null); ws = null
        conn.value = Conn.CONNECTING
        scope.launch {
            try {
                if (g != gen) return@launch
                base = fetchBase()
                val req = Request.Builder().url(base.replaceFirst("https:", "wss:") + "/ws").build()
                ws = http.newWebSocket(req, object : WebSocketListener() {
                    override fun onOpen(w: WebSocket, r: Response) {
                        if (g != gen) return
                        val o = JSONObject().put("t", if (token != null) "pair_resume" else "pair_hello").put("id", id)
                        if (token != null) o.put("token", token) else o.put("code", code)
                        w.send(o.toString())
                    }
                    override fun onMessage(w: WebSocket, text: String) { if (g == gen) handle(JSONObject(text), id, token != null) }
                    override fun onClosed(w: WebSocket, c: Int, r: String) { if (g == gen) dropped(g) }
                    override fun onFailure(w: WebSocket, t: Throwable, r: Response?) { if (g == gen) dropped(g) }
                })
            } catch (e: Exception) { if (g == gen) dropped(g) }
        }
    }

    /** Connection lost: keep the saved session and retry quietly (never asks for a new code). */
    private fun dropped(g: Int) {
        conn.value = Conn.OFFLINE
        val id = savedId(); val tk = savedToken()
        if (id == null || tk == null) { if (!signedIn.value) error.value = "সংযোগ ব্যর্থ। আবার চেষ্টা করুন।"; return }
        scope.launch {
            delay(minOf(1000L shl minOf(retry++, 4), 15000L))
            if (g == gen) start(id, null, tk)
        }
    }

    private fun fetchBase(): String {
        http.newCall(Request.Builder().url(firestore).build()).execute().use { r ->
            val u = JSONObject(r.body!!.string()).getJSONObject("fields").getJSONObject("serverUrl").getString("stringValue")
            require(u.startsWith("https://")); return u.trimEnd('/')
        }
    }

    private fun handle(m: JSONObject, id: String, usedToken: Boolean) {
        when (m.optString("t")) {
            "welcome" -> {
                uid = m.optString("uid", m.optString("userId", id)); uploadKey = m.optString("uploadKey")
                val tk = m.optString("sessionToken")
                if (tk.isNotEmpty()) prefs.edit().putString("id", id).putString("token", tk).apply()
                retry = 0; error.value = ""; signedIn.value = true; conn.value = Conn.ONLINE
                val h = m.optJSONArray("history")
                messages.value = (0 until (h?.length() ?: 0)).map { toMsg(h!!.getJSONObject(it)) }
            }
            "msg" -> { val x = toMsg(m, false); messages.update { l -> if (l.any { it.id == x.id }) l else l + x } }
            "ack" -> messages.update { l -> l.map { if (it.cid == m.optString("cid")) it.copy(id = m.optString("mid"), pending = false) else it } }
            "deleted" -> messages.update { l -> l.filter { it.id != m.optString("mid") } }
            "pair_error" -> {
                val e = m.optString("error")
                error.value = when (e) {
                    "invalid_or_expired_code" -> "কোড ভুল, মেয়াদ শেষ, অথবা আগেই ব্যবহার করা হয়েছে।"
                    "rate_limited" -> "অনেকবার চেষ্টা হয়েছে। পরে আবার চেষ্টা করুন।"
                    "unknown_machine" -> "Machine ID পাওয়া যায়নি।"
                    "banned" -> "এই অ্যাকাউন্টে চ্যাট বন্ধ।"
                    else -> "লগইন ব্যর্থ: $e"
                }
                if (usedToken && e != "rate_limited") { prefs.edit().clear().apply(); signedIn.value = false }
                gen++; ws?.close(1000, null); ws = null; conn.value = Conn.OFFLINE
            }
            "banned" -> { gen++; ws?.close(1000, null); conn.value = Conn.OFFLINE; error.value = "Chat access disabled" }
        }
    }

    private fun toMsg(o: JSONObject, history: Boolean = true): Msg {
        val file = o.optJSONObject("file")
        var text = o.optString("text")
        if (text.isBlank() && file != null) text = "📎 " + file.optString("name", "file")
        val mid = o.optString("mid", o.optString("id"))
        return Msg(mid, mid, o.optString("from") == "user", text, o.optLong("ts", System.currentTimeMillis()))
    }
}
