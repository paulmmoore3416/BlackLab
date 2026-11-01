package com.blacklab.app.brainstorm

import android.graphics.Bitmap
import com.blacklab.app.AppContainer
import com.blacklab.app.core.Keys
import com.blacklab.app.core.Profile
import com.blacklab.app.core.Time
import com.blacklab.app.data.CommentEntity
import com.blacklab.app.data.ContentStore
import com.blacklab.app.data.ShareLinkEntity
import com.blacklab.app.link.LinkService
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Live Share: a private link to one page or note that anyone on the same Wi-Fi can open in a browser —
 * no account, nothing uploaded to a server. Links are 128-bit random, scoped to a single item, expire,
 * and can be revoked. Guests can view, comment, or (notes) tick and add checklist items.
 */
object LiveShare {
    data class Response(val code: Int, val type: String, val body: ByteArray)

    private val rnd = SecureRandom()
    private val posts = ConcurrentHashMap<String, MutableList<Long>>()
    const val MAX_COMMENTS = 300

    fun newToken(): String = ByteArray(16).also(rnd::nextBytes).joinToString("") { "%02x".format(it) }

    fun serverReady(app: AppContainer): Boolean = app.settings.current.let { it.linkEnabled && it.linkLan } && app.link.running.value

    fun enableServer(app: AppContainer) {
        app.settings.update { putBoolean(Keys.LINK_ENABLED, true); putBoolean(Keys.LINK_LAN, true) }
        LinkService.sync(app.context)
    }

    fun url(app: AppContainer, token: String): String? = app.link.lanAddress()?.let { "http://$it:${app.settings.current.linkPort}/s/$token" }

    suspend fun create(app: AppContainer, kind: String, refId: Long, title: String, permission: String, hours: Int): ShareLinkEntity {
        val s = ShareLinkEntity(newToken(), kind, refId, title.ifBlank { if (kind == "PAGE") "Page" else "Note" }, permission, expiresAt = Time.now() + hours * 3_600_000L)
        app.db.brainstorm().putShare(s)
        return s
    }

    suspend fun revoke(app: AppContainer, s: ShareLinkEntity) = app.db.brainstorm().putShare(s.copy(revoked = true))

    fun qr(text: String, size: Int = 640): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    // ---------------------------------------------------------------- HTTP (called by LinkServer before pairing checks)

    private fun json(code: Int, o: Any) = Response(code, "application/json; charset=utf-8", o.toString().toByteArray())
    private fun err(code: Int, m: String) = json(code, JSONObject().put("error", m))

    private fun rateOk(token: String): Boolean {
        val now = System.currentTimeMillis()
        val l = posts.getOrPut(token) { mutableListOf() }
        synchronized(l) { l.removeAll { now - it > 60_000 }; if (l.size >= 20) return false; l += now }
        return true
    }

    suspend fun serve(app: AppContainer, method: String, path: String, body: String): Response {
        val parts = path.removePrefix("/s/").split('/')
        val token = parts.getOrNull(0).orEmpty()
        if (!token.matches(Regex("[0-9a-f]{32}"))) return err(404, "Not found")
        val dao = app.db.brainstorm()
        val share = dao.share(token)
        if (share == null || share.revoked) return err(404, "This link was turned off.")
        if (share.expiresAt < Time.now()) return err(410, "This link has expired.")
        val sub = parts.drop(1).joinToString("/")
        return try {
            when {
                method == "GET" && sub.isEmpty() -> { dao.putShare(share.copy(opens = share.opens + 1)); Response(200, "text/html; charset=utf-8", viewer(share).toByteArray()) }
                method == "GET" && sub == "data" -> json(200, data(app, share))
                method == "GET" && sub.startsWith("asset/") -> asset(app, share, sub.removePrefix("asset/"))
                method == "POST" && sub == "comment" -> {
                    if (share.permission == "VIEW") return err(403, "Comments are off for this link.")
                    if (!rateOk(token)) return err(429, "Slow down a little.")
                    val o = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
                    val text = o.optString("text").trim().take(2000)
                    val name = o.optString("name").trim().take(40).ifBlank { "Guest" }
                    if (text.isEmpty()) return err(400, "Empty comment")
                    val existing = if (share.kind == "PAGE") dao.pageComments(share.refId) else dao.noteComments(share.refId)
                    if (existing.size >= MAX_COMMENTS) return err(429, "Too many comments on this item.")
                    dao.insertComment(CommentEntity(pageId = share.refId.takeIf { share.kind == "PAGE" }, noteId = share.refId.takeIf { share.kind == "NOTE" }, author = name, text = text, guest = true))
                    json(200, JSONObject().put("ok", true))
                }
                method == "POST" && (sub == "check" || sub == "add") && share.kind == "NOTE" -> {
                    if (share.permission != "EDIT") return err(403, "This link is view-only.")
                    if (!rateOk(token)) return err(429, "Slow down a little.")
                    val n = app.db.notes().get(share.refId) ?: return err(404, "Note not found")
                    val o = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
                    val updated = if (sub == "check") {
                        val items = NoteOps.items(n).toMutableList()
                        val i = o.optInt("i", -1)
                        if (i !in items.indices) return err(400, "No such item")
                        items[i] = items[i].copy(done = o.optBoolean("d"))
                        n.copy(items = NoteOps.itemsJson(items))
                    } else {
                        val text = o.optString("text").trim().take(300)
                        if (text.isEmpty()) return err(400, "Empty")
                        if (n.checklist) n.copy(items = NoteOps.itemsJson(NoteOps.items(n) + NoteItem(text)))
                        else n.copy(body = (n.body.trimEnd() + "\n" + text).trim())
                    }
                    NoteOps.save(app, updated)
                    json(200, JSONObject().put("ok", true))
                }
                else -> err(404, "Not found")
            }
        } catch (e: LockedException) { err(403, "This page is in a locked section.") }
    }

    private suspend fun data(app: AppContainer, s: ShareLinkEntity): JSONObject {
        val dao = app.db.brainstorm()
        val o = JSONObject().put("kind", s.kind).put("permission", s.permission).put("expiresAt", s.expiresAt)
        val comments = if (s.kind == "PAGE") dao.pageComments(s.refId) else dao.noteComments(s.refId)
        o.put("comments", JSONArray().apply { comments.forEach { c -> put(JSONObject().put("author", c.author).put("text", c.text).put("at", c.createdAt).put("guest", c.guest).put("resolved", c.resolved)) } })
        if (s.kind == "PAGE") {
            val page = dao.page(s.refId)?.takeIf { it.deletedAt == null } ?: return o.put("gone", true)
            val json = Brainstorm.read(app, page)
            o.put("title", page.title.ifBlank { "Untitled page" }).put("updatedAt", page.updatedAt)
            o.put("html", PageHtml.static(json, { ref -> "/s/${s.token}/asset/" + java.net.URLEncoder.encode(ref.removePrefix("asset:"), "UTF-8") }, standalone = false))
        } else {
            val n = app.db.notes().get(s.refId)?.takeIf { it.trashedAt == null } ?: return o.put("gone", true)
            o.put("title", n.title).put("updatedAt", n.updatedAt).put("checklist", n.checklist).put("body", n.body)
                .put("color", NoteStyle.colors.getOrNull(n.color)?.light?.let { if (it == 0L) "#ffffff" else "#%06X".format(it and 0xFFFFFF) } ?: "#ffffff")
                .put("items", JSONArray().apply { NoteOps.items(n).forEach { put(JSONObject().put("t", it.text).put("d", it.done).put("i", it.indent)) } })
                .put("images", JSONArray().apply { NoteOps.attachments(n).filter { it.kind != "audio" }.forEachIndexed { i, _ -> put("/s/${s.token}/asset/$i") } })
                .put("labels", JSONArray(NoteOps.labels(n)))
        }
        return o
    }

    private suspend fun asset(app: AppContainer, s: ShareLinkEntity, name: String): Response {
        if (s.kind == "PAGE") {
            val page = app.db.brainstorm().page(s.refId) ?: return err(404, "Not found")
            val ref = java.net.URLDecoder.decode(name, "UTF-8")
            val bytes = Brainstorm.assetBytes(app, page, ref, Brainstorm.keyFor(app, page.sectionId)) ?: return err(404, "Not found")
            return Response(200, Brainstorm.mime(ref), bytes)
        }
        val n = app.db.notes().get(s.refId) ?: return err(404, "Not found")
        val a = NoteOps.attachments(n).filter { it.kind != "audio" }.getOrNull(name.toIntOrNull() ?: -1) ?: return err(404, "Not found")
        val f = app.storage.resolve(a.path)
        val root = Brainstorm.root(app, Profile.from(n.profile)).canonicalPath
        if (!f.isFile || !f.canonicalPath.startsWith(root + File.separator)) return err(404, "Not found")
        return Response(200, Brainstorm.mime(f.name), f.readBytes())
    }

    private fun viewer(s: ShareLinkEntity): String = """<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex"><title>${ContentStore.escape(s.title)} · BlackLab Live Share</title>
<style>
 body{margin:0;font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;background:#16171b;color:#e8e8ec}
 header{position:sticky;top:0;z-index:5;background:#1f2026;border-bottom:1px solid #2c2d35;padding:10px 16px;display:flex;align-items:center;gap:10px}
 header b{font-size:16px;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap} .live{font-size:12px;color:#7c5cff;display:flex;align-items:center;gap:6px}
 .live:before{content:'';width:8px;height:8px;border-radius:50%;background:#2bd9a8;box-shadow:0 0 0 3px rgba(43,217,168,.2)}
 main{padding:16px;max-width:980px;margin:0 auto} .wrap{overflow:auto;border-radius:8px;background:#e9ebef;padding:8px}
 .note{border-radius:14px;padding:16px;color:#1b1b1f;max-width:560px} .note h2{margin:0 0 8px;font-size:18px} .note p{white-space:pre-wrap;margin:0}
 .row{display:flex;gap:10px;align-items:flex-start;padding:4px 0} .row.done span{text-decoration:line-through;color:#777} .row input{width:18px;height:18px;margin-top:2px}
 .imgs img{max-width:100%;border-radius:10px;margin-top:10px} .labels span{display:inline-block;font-size:12px;background:rgba(0,0,0,.08);border-radius:10px;padding:2px 8px;margin:8px 6px 0 0}
 section.c{margin-top:22px;max-width:560px} .cm{background:#1f2026;border:1px solid #2c2d35;border-radius:12px;padding:10px 12px;margin:8px 0} .cm .a{font-size:12px;color:#9aa0a6;margin-bottom:4px}
 .cm.res{opacity:.55} textarea,input[type=text]{width:100%;box-sizing:border-box;background:#111216;color:#e8e8ec;border:1px solid #33343c;border-radius:10px;padding:10px;font:inherit;margin-top:6px}
 button{background:#7c5cff;color:#fff;border:0;border-radius:20px;padding:9px 18px;font:inherit;font-weight:600;margin-top:8px} .muted{color:#9aa0a6;font-size:13px}
 .add{display:flex;gap:8px} .add input{flex:1} .add button{margin-top:6px}
 $PAGE_CSS
</style></head><body>
<header><b id="t">${ContentStore.escape(s.title)}</b><span class="live">Live</span></header>
<main><div id="content" class="muted">Loading…</div>
<section class="c"><h3>Comments</h3><div id="comments" class="muted">No comments yet.</div>
<div id="form" style="display:none"><input type="text" id="name" placeholder="Your name" maxlength="40"><textarea id="text" rows="3" placeholder="Add a comment" maxlength="2000"></textarea><button id="send">Comment</button></div>
</section><p class="muted" id="foot"></p></main>
<script>
(function(){
 var base=location.pathname.replace(/\/$/,''), last='', perm='VIEW';
 var nameEl=document.getElementById('name'); try{nameEl.value=localStorage.getItem('blname')||'';}catch(e){}
 function el(tag,cls,text){var e=document.createElement(tag);if(cls)e.className=cls;if(text!=null)e.textContent=text;return e;}
 function post(p,o){return fetch(base+'/'+p,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(o)}).then(function(r){return r.json();}).then(function(j){if(j.error)alert(j.error);load(true);});}
 function render(d){
  perm=d.permission; document.getElementById('t').textContent=d.title||'Shared item';
  var c=document.getElementById('content'); c.className='';
  if(d.gone){c.textContent='This item is no longer available.';return;}
  if(d.kind==='PAGE'){c.innerHTML='<div class="wrap">'+d.html+'</div>';}
  else{
   c.innerHTML=''; var n=el('div','note'); n.style.background=d.color||'#fff';
   if(d.title)n.appendChild(el('h2',null,d.title));
   if(d.checklist){(d.items||[]).forEach(function(it,i){var r=el('label','row'+(it.d?' done':''));r.style.paddingLeft=(it.i*22)+'px';var cb=document.createElement('input');cb.type='checkbox';cb.checked=!!it.d;cb.disabled=perm!=='EDIT';cb.onchange=function(){post('check',{i:i,d:cb.checked});};r.appendChild(cb);r.appendChild(el('span',null,it.t));n.appendChild(r);});}
   else n.appendChild(el('p',null,d.body||''));
   if(perm==='EDIT'){var a=el('div','add');var inp=document.createElement('input');inp.type='text';inp.placeholder=d.checklist?'Add an item':'Add a line';inp.maxLength=300;var b=el('button',null,'Add');b.onclick=function(){if(inp.value.trim()){post('add',{text:inp.value});inp.value='';}};a.appendChild(inp);a.appendChild(b);n.appendChild(a);}
   var im=el('div','imgs');(d.images||[]).forEach(function(u){var i=document.createElement('img');i.src=u;im.appendChild(i);});n.appendChild(im);
   if(d.labels&&d.labels.length){var l=el('div','labels');d.labels.forEach(function(x){l.appendChild(el('span',null,x));});n.appendChild(l);}
   c.appendChild(n);
  }
  var cs=document.getElementById('comments'); cs.innerHTML='';
  if(!d.comments.length){cs.className='muted';cs.textContent='No comments yet.';}
  else{cs.className='';d.comments.forEach(function(x){var b=el('div','cm'+(x.resolved?' res':''));b.appendChild(el('div','a',x.author+' · '+new Date(x.at).toLocaleString()+(x.resolved?' · resolved':'')));b.appendChild(el('div',null,x.text));cs.appendChild(b);});}
  document.getElementById('form').style.display=perm==='VIEW'?'none':'block';
  document.getElementById('foot').textContent='Shared from BlackLab over your local network · link expires '+new Date(d.expiresAt).toLocaleString();
 }
 function load(force){if(document.hidden&&!force)return;fetch(base+'/data',{cache:'no-store'}).then(function(r){return r.json();}).then(function(d){if(d.error){document.getElementById('content').textContent=d.error;return;}var s=JSON.stringify(d);if(s!==last){last=s;render(d);}}).catch(function(){});}
 document.getElementById('send').onclick=function(){var t=document.getElementById('text');if(!t.value.trim())return;try{localStorage.setItem('blname',nameEl.value);}catch(e){}post('comment',{name:nameEl.value,text:t.value});t.value='';};
 load(true); setInterval(load,4000); document.addEventListener('visibilitychange',function(){load();});
})();
</script></body></html>"""

    private val PAGE_CSS = PageHtml.STATIC_CSS.replace("body {", ".wrap-body {")
}
