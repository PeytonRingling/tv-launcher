package com.peyton.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * Tiny LAN-only upload server that runs ONLY while the "Add photos from phone" screen is open.
 * - Every URL is prefixed with a random 6-digit PIN; wrong PINs are counted and the server locks after 15.
 * - Requests from non-private (internet) addresses are refused.
 * - Uploads are decoded, downscaled and stored in app-private storage (see [MyPhotos]).
 */
/** Hooks for the "Update launcher" mode of the same server. */
interface UpdateHooks {
  /** Called on the server thread while an APK streams in. */
  fun onProgress(read: Long, total: Long)
  /** Called on the server thread once the APK is fully received; returns (accepted, message for the phone). */
  fun onApk(file: File): Pair<Boolean, String>
  /** Small JSON blob the phone page polls: {"phase":"…","message":"…","progress":0.0}. */
  fun statusJson(): String
}

class PhotoServer private constructor(
  private val ctx: Context, port: Int, val pin: String,
  private val onSaved: (batch: String?, total: Int) -> Unit, private val update: UpdateHooks? = null,
) : NanoHTTPD(port) {
  private var badPins = 0
  private val lock = Any()

  override fun serve(s: IHTTPSession): Response = try { route(s) } catch (e: Throwable) { text(Response.Status.INTERNAL_ERROR, "Error: ${e.javaClass.simpleName}") }

  private fun route(s: IHTTPSession): Response {
    if (!isLan(s.remoteIpAddress)) return text(Response.Status.FORBIDDEN, "LAN only")
    val parts = s.uri.trim('/').split('/')
    if (parts.firstOrNull() == "favicon.ico") return text(Response.Status.NOT_FOUND, "")
    if (badPins >= 15) return text(Response.Status.FORBIDDEN, "Locked. Reopen the screen on the TV for a new code.")
    if (parts.firstOrNull() != pin) {
      if (parts.firstOrNull().isNullOrEmpty()) return html(PIN_PAGE)
      badPins++
      return text(Response.Status.FORBIDDEN, "Wrong code")
    }
    if (update != null) return when {
      parts.size == 1 && s.method == Method.GET -> html(UPDATE_PAGE.replace("{{PIN}}", pin))
      parts.getOrNull(1) == "apk" && s.method == Method.POST -> receiveApk(s, update)
      parts.getOrNull(1) == "status" && s.method == Method.GET -> json(update.statusJson())
      else -> text(Response.Status.NOT_FOUND, "Not found")
    }
    return when {
      parts.size == 1 && s.method == Method.GET -> html(UPLOAD_PAGE.replace("{{PIN}}", pin))
      parts.getOrNull(1) == "upload" && s.method == Method.POST -> upload(s)
      else -> text(Response.Status.NOT_FOUND, "Not found")
    }
  }

  /** In-flight uploads and the time of the last one, so the TV can keep accepting a running batch briefly after you leave the screen. */
  val active = java.util.concurrent.atomic.AtomicInteger(0)
  @Volatile var lastActivity = 0L

  private fun upload(s: IHTTPSession): Response {
    val len = s.headers["content-length"]?.toLongOrNull() ?: return text(Response.Status.BAD_REQUEST, "Missing length")
    if (len <= 0 || len > 60L * 1024 * 1024) return text(Response.Status.BAD_REQUEST, "Too large (60 MB max)")
    active.incrementAndGet(); lastActivity = System.currentTimeMillis()
    val tmp = File.createTempFile("upload", ".bin", ctx.cacheDir)
    try {
      tmp.outputStream().buffered(256 * 1024).use { out ->
        val buf = ByteArray(128 * 1024); var left = len; val inp = s.inputStream
        while (left > 0) { val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; out.write(buf, 0, n); left -= n }
        if (left > 0) return text(Response.Status.BAD_REQUEST, "Upload interrupted")
      }
      // Parallel-safe: the phone already resized to JPEG, so this is normally just a rename (see MyPhotos.store).
      val r = MyPhotos.store(ctx, tmp)
      val f = r.file ?: return text(Response.Status.INTERNAL_ERROR, r.error ?: "Couldn't save")
      Ingest.enqueue(ctx, Photo(file = f.absolutePath))       // thumbnail + face/quality analysis in the background
      val batch = s.headers["x-batch"]
      onSaved(batch?.substringBefore(':'), batch?.substringAfter(':')?.toIntOrNull() ?: 0)
      return text(Response.Status.OK, if (r.fastPath) "ok" else "ok (converted)")
    } finally { tmp.delete(); active.decrementAndGet(); lastActivity = System.currentTimeMillis() }
  }

  @Volatile private var receiving = false

  private fun receiveApk(s: IHTTPSession, h: UpdateHooks): Response {
    val len = s.headers["content-length"]?.toLongOrNull() ?: return json(err("The upload had no size. Try again."))
    if (len <= 0 || len > 200L * 1024 * 1024) return json(err("That file is too large to be the launcher (200 MB max)."))
    synchronized(lock) { if (receiving) return json(err("Another upload is already in progress.")); receiving = true }
    val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
    val f = File(dir, "incoming.apk"); f.delete()
    try {
      f.outputStream().use { out ->
        val buf = ByteArray(128 * 1024); var read = 0L; val inp = s.inputStream; var lastReport = 0L
        while (read < len) {
          val n = inp.read(buf, 0, minOf(buf.size.toLong(), len - read).toInt()); if (n < 0) break
          out.write(buf, 0, n); read += n
          if (read - lastReport > 256 * 1024 || read == len) { lastReport = read; h.onProgress(read, len) }
        }
        if (read < len) { f.delete(); return json(err("The upload was interrupted. Try again.")) }
      }
      val (ok, msg) = h.onApk(f)
      return json("{\"ok\":$ok,\"message\":${q(msg)}}")
    } finally { synchronized(lock) { receiving = false } }
  }

  private fun err(m: String) = "{\"ok\":false,\"message\":${q(m)}}"
  private fun json(t: String) = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", t).apply { addHeader("Cache-Control", "no-store") }

  private fun text(st: Response.Status, t: String) = newFixedLengthResponse(st, "text/plain; charset=utf-8", t)
  private fun html(t: String) = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", t).apply { addHeader("Cache-Control", "no-store") }

  companion object {
    fun start(ctx: Context, onSaved: (String?, Int) -> Unit): PhotoServer? = startInternal(ctx, onSaved, null)
    fun startUpdate(ctx: Context, hooks: UpdateHooks): PhotoServer? = startInternal(ctx, { _, _ -> }, hooks)

    private fun startInternal(ctx: Context, onSaved: (String?, Int) -> Unit, hooks: UpdateHooks?): PhotoServer? {
      val pin = (SecureRandom().nextInt(900_000) + 100_000).toString()
      for (port in 8765..8775) {
        try { return PhotoServer(ctx.applicationContext, port, pin, onSaved, hooks).also { it.start(SOCKET_READ_TIMEOUT, true) } } catch (_: Exception) {}
      }
      return null
    }

    /** JSON string literal. */
    fun q(s: String) = buildString {
      append('"'); s.forEach { c -> when (c) { '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); else -> if (c < ' ') append(' ') else append(c) } }; append('"')
    }

    private fun isLan(ip: String?): Boolean = if (ip == null) false else try {
      val a = InetAddress.getByName(ip)
      a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress || (a.address.size == 16 && (a.address[0].toInt() and 0xFE) == 0xFC)
    } catch (_: Exception) { false }

    /** The TV's LAN IPv4 address (Wi-Fi or Ethernet). */
    fun lanAddress(): String? = try {
      NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .sortedBy { if (it.name.startsWith("eth")) 0 else if (it.name.startsWith("wlan")) 1 else 2 }
        .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    } catch (_: Exception) { null }

    fun qr(text: String, px: Int = 512): Bitmap {
      val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px, mapOf(EncodeHintType.MARGIN to 1))
      val pixels = IntArray(px * px) { i -> if (m[i % px, i / px]) 0xFF0B0D14.toInt() else 0xFFFFFFFF.toInt() }
      return Bitmap.createBitmap(pixels, px, px, Bitmap.Config.ARGB_8888)
    }

    private const val CSS = """
*{box-sizing:border-box}body{margin:0;font-family:system-ui,-apple-system,Roboto,sans-serif;background:#0b0d14;color:#f4f5fa;min-height:100vh;
background-image:radial-gradient(circle at 20% 0%,#2a3a6b 0,transparent 55%),radial-gradient(circle at 100% 100%,#3b2a55 0,transparent 50%)}
.w{max-width:560px;margin:0 auto;padding:28px 20px 60px}h1{font-weight:300;font-size:30px;margin:8px 0 4px}p{color:#b9bdd0;line-height:1.45;margin:6px 0 18px}
.card{background:rgba(255,255,255,.07);border:1px solid rgba(255,255,255,.14);border-radius:22px;padding:18px}
.btn{display:block;width:100%;padding:16px;border:0;border-radius:16px;background:#f4f5fa;color:#0b0d14;font-size:17px;font-weight:600;text-align:center}
.btn.alt{background:rgba(255,255,255,.12);color:#f4f5fa;margin-top:10px}input[type=file]{display:none}
.grid{display:grid;grid-template-columns:repeat(4,1fr);gap:6px;margin-top:16px}.grid div{position:relative;aspect-ratio:1;border-radius:10px;overflow:hidden;background:#1b1e2a}
.grid img{width:100%;height:100%;object-fit:cover}.grid span{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;font-size:20px;background:rgba(0,0,0,.45)}
.grid .ok span{background:transparent;align-items:flex-end;justify-content:flex-end;padding:4px;font-size:14px}.bar{height:6px;border-radius:3px;background:rgba(255,255,255,.12);margin-top:14px;overflow:hidden}
.bar i{display:block;height:100%;width:0;background:#9db8ff;transition:width .2s}#st{margin-top:10px;color:#b9bdd0;font-size:14px}
input.pin{width:100%;font-size:28px;letter-spacing:8px;text-align:center;padding:14px;border-radius:14px;border:1px solid rgba(255,255,255,.2);background:rgba(255,255,255,.06);color:#fff;margin-bottom:12px}"""

    private const val UPDATE_PAGE = """<!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1"><title>Update TV Launcher</title><style>$CSS
.ph{font-size:15px;margin-top:12px;color:#f4f5fa}.ph.err{color:#ffb4a8}.ph.ok{color:#7be0a0}</style></head>
<body><div class=w><h1>Update TV Launcher</h1><p>Pick the new <b>TV Launcher .apk</b> from Downloads or Google Drive. The TV checks it's a newer build signed with the same key, then asks you to confirm on the TV.</p>
<div class=card><label class=btn for=f>Choose .apk file</label><input id=f type=file accept="application/vnd.android.package-archive,.apk">
<div class=bar><i id=b></i></div><div class=ph id=ph>Waiting for a file.</div></div></div>
<script>
const PIN='{{PIN}}',f=document.getElementById('f'),b=document.getElementById('b'),ph=document.getElementById('ph');
let polling=null;
function show(t,cls){ph.textContent=t;ph.className='ph'+(cls?' '+cls:'');}
function poll(){clearInterval(polling);polling=setInterval(async()=>{try{const r=await fetch('/'+PIN+'/status',{cache:'no-store'});const s=await r.json();
  if(s.progress!=null)b.style.width=Math.round(s.progress*100)+'%';
  show(s.message,s.phase=='ERROR'?'err':(s.phase=='DONE'||s.phase=='CONFIRM'?'ok':''));
  if(s.phase=='ERROR'||s.phase=='DONE')clearInterval(polling);}
  catch(e){show('The TV stopped sharing. If it is showing the install prompt, confirm it on the TV.','');clearInterval(polling);}},1000);}
f.onchange=()=>{const file=f.files[0];f.value='';if(!file)return;
  if(!/\.apk$/i.test(file.name)){show('That isn\'t an .apk file.','err');return;}
  const x=new XMLHttpRequest();x.open('POST','/'+PIN+'/apk');x.setRequestHeader('Content-Type','application/octet-stream');
  x.upload.onprogress=e=>{if(e.lengthComputable){b.style.width=Math.round(e.loaded/e.total*100)+'%';show('Sending '+Math.round(e.loaded/e.total*100)+'%…');}};
  x.onload=()=>{try{const r=JSON.parse(x.responseText);show(r.message,r.ok?'ok':'err');if(r.ok)poll();}catch(e){show('Unexpected reply from the TV.','err');}};
  x.onerror=()=>show('Couldn\'t reach the TV. Same Wi-Fi?','err');
  show('Sending…');x.send(file);};
</script></body></html>"""

    private const val PIN_PAGE = """<!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1"><title>TV Launcher</title><style>$CSS</style></head>
<body><div class=w><h1>TV Launcher</h1><p>Enter the 6-digit code shown on the TV.</p><div class=card>
<input class=pin id=p inputmode=numeric maxlength=6 autofocus><button class=btn onclick="location.href='/'+document.getElementById('p').value">Continue</button></div></div></body></html>"""

    private const val UPLOAD_PAGE = """<!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1"><title>Send photos to TV</title><style>$CSS
.grid div.err span{background:rgba(120,20,20,.55)}.grid div.busy span{font-size:12px}.row{display:flex;gap:8px;margin-top:10px}.row .btn{margin:0}
#spd{color:#7b8199;font-size:12px;margin-top:4px}</style></head>
<body><div class=w><h1>Send photos to your TV</h1><p>Pick photos from your gallery or Google Photos. This phone resizes them to TV size and sends several at once, then they're saved privately on the TV for the ambient screen.</p>
<div class=card><label class=btn for=f>Choose photos</label><input id=f type=file accept="image/*" multiple>
<div class=bar><i id=b></i></div><div id=st>Nothing sent yet.</div><div id=spd></div>
<div class=row><button id=rt class="btn alt" hidden>Retry failed</button></div><div class=grid id=g></div></div></div>
<script>
const PIN='{{PIN}}',LAND=2560,PORT=2160,Q=0.85,PAR=5;
const st=document.getElementById('st'),b=document.getElementById('b'),g=document.getElementById('g'),rt=document.getElementById('rt'),spd=document.getElementById('spd'),f=document.getElementById('f');
// Target size: landscape long edge 2560 (headroom for the TV's gentle zoom), portraits 2160 tall. Never upscale.
function target(w,h){const s=Math.min(1,w>=h?LAND/w:PORT/h);return [Math.max(1,Math.round(w*s)),Math.max(1,Math.round(h*s))];}
const WSRC=`const LAND=2560,PORT=2160;function target(w,h){const s=Math.min(1,w>=h?LAND/w:PORT/h);return [Math.max(1,Math.round(w*s)),Math.max(1,Math.round(h*s))];}
self.onmessage=async e=>{const {id,file,q}=e.data;try{const bm=await createImageBitmap(file,{imageOrientation:'from-image'});const [w,h]=target(bm.width,bm.height);
const c=new OffscreenCanvas(w,h);const x=c.getContext('2d');x.imageSmoothingQuality='high';x.drawImage(bm,0,0,w,h);bm.close();
const blob=await c.convertToBlob({type:'image/jpeg',quality:q});self.postMessage({id,blob});}catch(err){self.postMessage({id,error:String(err)});}};`;
const canWorker=typeof Worker!=='undefined'&&typeof OffscreenCanvas!=='undefined'&&'convertToBlob' in OffscreenCanvas.prototype;
const workers=[],waiters=[];let nextId=1;const cbs={};
if(canWorker){try{const u=URL.createObjectURL(new Blob([WSRC],{type:'text/javascript'}));const n=Math.min(3,Math.max(2,(navigator.hardwareConcurrency||4)>>1));
  for(let i=0;i<n;i++){const w=new Worker(u);w.onmessage=e=>{const cb=cbs[e.data.id];delete cbs[e.data.id];cb&&cb(e.data);};workers.push({w,busy:false});}}catch(e){}}
function getWorker(){const w=workers.find(x=>!x.busy);if(w){w.busy=true;return Promise.resolve(w);}return new Promise(r=>waiters.push(r));}
function freeWorker(w){const n=waiters.shift();if(n)n(w);else w.busy=false;}
let mainBusy=Promise.resolve();
async function shrinkMain(file){ // fallback: one at a time on the page, yielding to keep scrolling smooth
  const run=async()=>{await new Promise(r=>setTimeout(r,0));const bm=await createImageBitmap(file,{imageOrientation:'from-image'});const [w,h]=target(bm.width,bm.height);
    const c=document.createElement('canvas');c.width=w;c.height=h;const x=c.getContext('2d');x.imageSmoothingQuality='high';x.drawImage(bm,0,0,w,h);bm.close&&bm.close();
    return await new Promise(r=>c.toBlob(z=>r(z),'image/jpeg',Q));};
  const p=mainBusy.then(run,run);mainBusy=p.catch(()=>{});return p;}
async function shrink(file){
  if(workers.length){const w=await getWorker();try{return await new Promise((res,rej)=>{const id=nextId++;cbs[id]=d=>d.error?rej(new Error(d.error)):res(d.blob);w.w.postMessage({id,file,q:Q});});}
    catch(e){return await shrinkMain(file).catch(()=>file);}finally{freeWorker(w);}}
  try{return (await shrinkMain(file))||file;}catch(e){return file;}}
let items=[],batch='',t0=0,bytes=0,running=0,queue=[];
function tile(it){const d=document.createElement('div');d.className='busy';const im=document.createElement('img');const sp=document.createElement('span');sp.textContent='Queued';
  d.append(im,sp);d.onclick=()=>{if(it.state=='fail')retry([it]);};g.prepend(d);it.el=d;it.sp=sp;it.im=im;}
function set(it,state,label){it.state=state;it.sp.textContent=label;it.el.className=state=='ok'?'ok':state=='fail'?'err':'busy';}
function stats(){const ok=items.filter(i=>i.state=='ok').length,bad=items.filter(i=>i.state=='fail').length,n=items.length;
  b.style.width=(n?Math.round((ok+bad)/n*100):0)+'%';
  st.textContent=(ok+bad<n?'Sending… ':'Done. ')+ok+' of '+n+' on your TV'+(bad?' · '+bad+' failed (tap to retry)':'');
  rt.hidden=!bad;const s=(Date.now()-t0)/1000;if(ok&&s>0)spd.textContent=(ok/s).toFixed(1)+' photos/s · '+(bytes/1048576/s).toFixed(1)+' MB/s';}
async function send(it){
  set(it,'busy','Resizing');
  if(!it.blob){it.blob=await shrink(it.file);if(it.blob&&it.blob!==it.file){it.im.src=URL.createObjectURL(it.blob);}}
  for(let a=0;a<3;a++){
    set(it,'busy',a?'Retrying…':'Sending');
    try{const r=await fetch('/'+PIN+'/upload',{method:'POST',headers:{'Content-Type':'application/octet-stream','X-Batch':batch+':'+items.length},body:it.blob});
      if(r.ok){bytes+=it.blob.size;set(it,'ok','✓');it.blob=null;return;}
      const m=await r.text();if(r.status<500||/limit|storage|photos/i.test(m)){set(it,'fail','✕');st.title=m;it.err=m;return;}}
    catch(e){}
    await new Promise(r=>setTimeout(r,700*(a+1)));}
  set(it,'fail','✕');}
function pump(){while(running<PAR&&queue.length){const it=queue.shift();running++;send(it).finally(()=>{running--;stats();pump();});}stats();}
function retry(list){list.forEach(it=>{set(it,'busy','Queued');queue.push(it);});pump();}
rt.onclick=()=>retry(items.filter(i=>i.state=='fail'));
f.onchange=()=>{const files=[...f.files];f.value='';if(!files.length)return;
  if(!running){items=[];g.innerHTML='';batch=Math.random().toString(36).slice(2,8);t0=Date.now();bytes=0;}
  files.forEach(file=>{const it={file,state:'queued'};items.push(it);tile(it);queue.push(it);});pump();};
</script></body></html>"""
  }
}
