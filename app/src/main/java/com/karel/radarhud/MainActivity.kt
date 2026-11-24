package com.karel.radarhud
import androidx.core.content.ContextCompat
import android.Manifest; import android.annotation.SuppressLint; import android.app.Activity; import android.content.Context; import android.net.Uri
import android.os.*; import android.speech.tts.TextToSpeech; import android.view.WindowManager
import androidx.activity.ComponentActivity; import androidx.activity.compose.rememberLauncherForActivityResult; import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts; import androidx.annotation.OptIn
import androidx.camera.core.*; import androidx.camera.lifecycle.ProcessCameraProvider; import androidx.camera.view.PreviewView
import androidx.compose.foundation.*; import androidx.compose.foundation.layout.*; import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*; import androidx.compose.material.icons.Icons; import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*; import androidx.compose.runtime.*; import androidx.compose.ui.*
import androidx.compose.ui.draw.clip; import androidx.compose.ui.geometry.*; import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*; import androidx.compose.ui.text.font.FontWeight; import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.location.*; import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition; import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.*; import java.io.File; import java.util.Locale; import java.util.concurrent.Executors; import kotlin.math.*

enum class AlertType { POLICIE, SECRET, RADAR }
data class ActiveAlert(val type: AlertType, val title: String, val detail: String, val until: Long)
data class RadarPt(val lat: Double, val lon: Double, val maxSpeed: Int?)

fun parseRadars(json: String): List<RadarPt> = try {
  val arr = JSONObject(json).optJSONArray("elements") ?: JSONArray()
  List(arr.length()) { i ->
    val o = arr.getJSONObject(i)
    if (o.has("lat") && o.has("lon")) RadarPt(o.getDouble("lat"), o.getDouble("lon"), o.optJSONObject("tags")?.optString("maxspeed")?.filter { it.isDigit() }?.toIntOrNull()) else null
  }.filterNotNull()
} catch (_: Exception) { emptyList() }
fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
  val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
  val dp = Math.toRadians(lat2 - lat1); val dl = Math.toRadians(lon2 - lon1)
  val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
  return 12742000.0 * atan2(sqrt(a), sqrt(1 - a))
}

fun lev1(a: String, b: String): Boolean {
  if (abs(a.length - b.length) > 1) return false
  var i = 0; var j = 0; var diff = 0
  while (i < a.length && j < b.length) {
    if (a[i] != b[j]) { if (++diff > 1) return false; if (a.length > b.length) i++ else if (b.length > a.length) j++ else { i++; j++ } }
    else { i++; j++ }
  }
  return diff + (a.length - i) + (b.length - j) <= 1
}

fun matchPlate(clean: String, targets: List<String>): Pair<String, String>? {
  for (s in targets) {
    for (len in listOf(s.length, s.length - 1, s.length + 1)) {
      if (len < 5 || len > clean.length) continue
      for (i in 0..(clean.length - len)) { val sub = clean.substring(i, i + len); if (lev1(sub, s)) return s to sub }
    }
  }
  return null
}

object Store {
  fun loadRadars(c: Context) = File(c.filesDir, "radary_cz.json").let { if (it.exists()) parseRadars(it.readText()) else emptyList() }
  fun saveRadars(c: Context, u: Uri): List<RadarPt> {
    val t = c.contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText() } ?: return emptyList()
    return parseRadars(t).also { if (it.isNotEmpty()) File(c.filesDir, "radary_cz.json").writeText(t) }
  }
  fun list(c: Context, k: String, d: Set<String>) = c.getSharedPreferences("hud", 0).getStringSet(k, d)?.sorted() ?: d.sorted()
  fun saveList(c: Context, k: String, l: List<String>) = c.getSharedPreferences("hud", 0).edit().putStringSet(k, l.toSet()).apply()
  fun mode(c: Context) = c.getSharedPreferences("hud", 0).getInt("mode", 0)
  fun saveMode(c: Context, m: Int) = c.getSharedPreferences("hud", 0).edit().putInt("mode", m).apply()
}

fun notifyUser(c: Context, mode: Int, tts: TextToSpeech?, say: String) {
  if (mode == 0) tts?.speak(say, TextToSpeech.QUEUE_FLUSH, null, "a")
  else if (mode == 1) {
    val v = if (Build.VERSION.SDK_INT >= 31) (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator else @Suppress("DEPRECATION") c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 100, 250), -1))
  }
}
@SuppressLint("MissingPermission")
@Composable
fun DriveHud(radars: List<RadarPt>, pol: List<String>, sec: List<String>, mode: Int, tts: TextToSpeech?, onMenu: () -> Unit, onExit: () -> Unit) {
  val ctx = LocalContext.current; val owner = ctx as androidx.lifecycle.LifecycleOwner
  var speed by remember { mutableIntStateOf(0) }
  var nearDist by remember { mutableDoubleStateOf(99999.0) }
  var nearLimit by remember { mutableStateOf<Int?>(null) }
  var lastOcr by remember { mutableStateOf("...") }
  var alert by remember { mutableStateOf<ActiveAlert?>(null) }
  var lastBeep by remember { mutableLongStateOf(0L) }

  DisposableEffect(Unit) {
    val w = (ctx as? Activity)?.window; w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    val client = LocationServices.getFusedLocationProviderClient(ctx)
    val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).setMinUpdateIntervalMillis(500L).build()
    val cb = object : LocationCallback() {
      override fun onLocationResult(res: LocationResult) {
        val loc = res.lastLocation ?: return
        speed = (loc.speed * 3.6f).roundToInt()
        if (radars.isNotEmpty()) {
          var bestD = 9e9; var bestL: Int? = null
          for (r in radars) { val d = haversine(loc.latitude, loc.longitude, r.lat, r.lon); if (d < bestD) { bestD = d; bestL = r.maxSpeed } }
          nearDist = bestD; nearLimit = bestL
          if (bestD < 500.0 && System.currentTimeMillis() - lastBeep > 4500L) {
            lastBeep = System.currentTimeMillis(); notifyUser(ctx, mode, tts, "Radar")
          }
        }
      }
    }
    try { client.requestLocationUpdates(req, cb, Looper.getMainLooper()) } catch (_: Exception) {}
    onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); client.removeLocationUpdates(cb) }
  }

  Box(Modifier.fillMaxSize().background(Color.Black)) {
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
      val pv = PreviewView(c); val exec = Executors.newSingleThreadExecutor()
      val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS); var lastScan = 0L
      ProcessCameraProvider.getInstance(c).addListener({
        val cp = ProcessCameraProvider.getInstance(c).get()
        val prev = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
        val ana = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also {
          it.setAnalyzer(exec) { px ->
            val now = System.currentTimeMillis()
            val img = getImg(px)
            if (img != null && now - lastScan > 250L) {
              lastScan = now
              rec.process(InputImage.fromMediaImage(img, px.imageInfo.rotationDegrees)).addOnSuccessListener { vt ->
                val clean = vt.text.uppercase().filter { ch -> ch.isLetterOrDigit() }
                if (clean.length >= 3) lastOcr = clean.take(16)
                val mp = matchPlate(clean, pol); val ms = if (mp == null) matchPlate(clean, sec) else null
                if (mp != null) {
                  alert = ActiveAlert(AlertType.POLICIE, "POLICIE", "${mp.second} ≈ ${mp.first}", now + 4000L)
                  if (now - lastBeep > 4000L) { lastBeep = now; notifyUser(ctx, mode, tts, "Policie") }
                } else if (ms != null) {
                  alert = ActiveAlert(AlertType.SECRET, "SECRET POLICIE", "${ms.second} ≈ ${ms.first}", now + 4000L)
                  if (now - lastBeep > 4000L) { lastBeep = now; notifyUser(ctx, mode, tts, "Secret policie") }
                }
              }.addOnCompleteListener { px.close() }
            } else px.close()
          }
        }
        try { cp.unbindAll(); cp.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, prev, ana) } catch (_: Exception) {}
      }, ContextCompat.getMainExecutor(c))
      pv
    })

    Box(Modifier.align(Alignment.Center).fillMaxWidth(0.72f).height(110.dp).border(2.dp, Color(0xAA00E676), RoundedCornerShape(14.dp)))

    Row(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
      IconButton(onClick = onMenu, modifier = Modifier.background(Color(0xBB10141C), CircleShape)) { Icon(Icons.Default.MoreVert, null, tint = Color.White) }
      Spacer(Modifier.width(8.dp))
      IconButton(onClick = onExit, modifier = Modifier.background(Color(0xBB10141C), CircleShape)) { Icon(Icons.Default.Close, null, tint = Color.White) }
    }

    Column(Modifier.align(Alignment.CenterStart).padding(start = 12.dp).background(Color(0xCC10141C), RoundedCornerShape(16.dp)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp)).padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Icon(Icons.Default.Speed, null, tint = if (nearDist < 500) Color(0xFFFF6D00) else Color(0xFF00E5FF), modifier = Modifier.size(32.dp))
      Text("RADAR", fontSize = 11.sp, color = Color(0xFF9AA4B2), fontWeight = FontWeight.Bold)
      Text(if (radars.isEmpty()) "-- m" else "${nearDist.roundToInt()} m", fontSize = 24.sp, fontWeight = FontWeight.Black, color = if (nearDist < 500) Color(0xFFFF9100) else Color.White)
    }

    Row(Modifier.align(Alignment.BottomCenter).padding(14.dp).background(Color(0xDD10141C), RoundedCornerShape(20.dp)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp)).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val over = nearLimit != null && speed > nearLimit!!
        Text("$speed", fontSize = 36.sp, fontWeight = FontWeight.Black, color = if (over) Color(0xFFFF3D00) else Color.White)
        Text("km/h  •  OCR: $lastOcr", fontSize = 11.sp, color = Color(0xFF9AA4B2))
      }
      Spacer(Modifier.width(24.dp))
      Box(Modifier.size(62.dp).clip(CircleShape).background(Color.White).border(6.dp, Color(0xFFD50000), CircleShape), contentAlignment = Alignment.Center) {
        Text(nearLimit?.toString() ?: "--", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color.Black)
      }
    }

    val cur = alert?.takeIf { System.currentTimeMillis() < it.until } ?: if (nearDist < 500.0) ActiveAlert(AlertType.RADAR, "RADAR", "${nearDist.roundToInt()} m", 0L) else null
    if (cur != null) {
      val bg = when (cur.type) { AlertType.POLICIE -> Color(0xDD0D47A1); AlertType.SECRET -> Color(0xDDB71C1C); AlertType.RADAR -> Color(0xDDE65100) }
      Column(Modifier.align(Alignment.TopCenter).padding(top = 74.dp).background(bg, RoundedCornerShape(20.dp)).padding(horizontal = 28.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AlertArt(cur.type)
        Text(cur.title, fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.White)
        Text(cur.detail, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
      }
    }
  }
}

@Composable
fun AlertArt(t: AlertType) {
  Canvas(Modifier.size(120.dp, 64.dp)) {
    if (t == AlertType.RADAR) {
      drawRect(Color.LightGray, Offset(size.width * 0.45f, size.height * 0.55f), Size(size.width * 0.1f, size.height * 0.45f))
      drawRoundRect(Color(0xFF212121), Offset(size.width * 0.3f, size.height * 0.1f), Size(size.width * 0.4f, size.height * 0.5f), CornerRadius(12f, 12f))
      drawCircle(Color.Cyan, 12f, Offset(size.width * 0.5f, size.height * 0.3f))
    } else {
      val body = if (t == AlertType.POLICIE) Color.White else Color(0xFF1A1A1A)
      drawRoundRect(body, Offset(16f, size.height * 0.45f), Size(size.width - 32f, size.height * 0.35f), CornerRadius(14f, 14f))
      drawRoundRect(body, Offset(size.width * 0.25f, size.height * 0.22f), Size(size.width * 0.5f, size.height * 0.28f), CornerRadius(12f, 12f))
      if (t == AlertType.POLICIE) {
        drawRect(Color(0xFF2979FF), Offset(size.width * 0.4f, size.height * 0.12f), Size(size.width * 0.1f, 8f))
        drawRect(Color(0xFFFF1744), Offset(size.width * 0.5f, size.height * 0.12f), Size(size.width * 0.1f, 8f))
      } else {
        drawCircle(Color(0xFF2979FF), 6f, Offset(size.width * 0.36f, size.height * 0.62f))
        drawCircle(Color(0xFFFF1744), 6f, Offset(size.width * 0.64f, size.height * 0.62f))
      }
      drawCircle(Color.Black, 11f, Offset(size.width * 0.28f, size.height * 0.82f))
      drawCircle(Color.Black, 11f, Offset(size.width * 0.72f, size.height * 0.82f))
    }
  }
}

@Composable
fun MenuSheet(rCount: Int, pol: List<String>, sec: List<String>, mode: Int, onPick: () -> Unit, onMode: (Int) -> Unit, onPol: (List<String>) -> Unit, onSec: (List<String>) -> Unit, onClose: () -> Unit) {
  var tab by remember { mutableIntStateOf(0) }
  var txt by remember { mutableStateOf("") }
  AlertDialog(onDismissRequest = onClose, confirmButton = { TextButton(onClick = onClose) { Text("Hotovo") } },
    title = { Text("Nastavení HUD") },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Režim upozornění:", fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          listOf("Hlas", "Vibrace", "Obrazovka").forEachIndexed { idx, lbl ->
            FilterChip(selected = mode == idx, onClick = { onMode(idx) }, label = { Text(lbl, fontSize = 12.sp) })
          }
        }
        Spacer(Modifier.height(10.dp))
        Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text("Nahrát radary ($rCount načteno)") }
        Spacer(Modifier.height(12.dp))
        Text("Přidat SPZ:", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Policie") })
          FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Secret Policie") })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          OutlinedTextField(value = txt, onValueChange = { txt = it.uppercase() }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("Např. 5M89012") })
          IconButton(onClick = {
            val clean = txt.filter { it.isLetterOrDigit() }
            if (clean.length >= 4) { if (tab == 0) onPol(pol + clean) else onSec(sec + clean); txt = "" }
          }) { Icon(Icons.Default.Add, null) }
        }
        val curList = if (tab == 0) pol else sec
        curList.forEach { item ->
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(item, fontWeight = FontWeight.Bold)
            IconButton(onClick = { if (tab == 0) onPol(pol - item) else onSec(sec - item) }) { Icon(Icons.Default.Delete, null) }
          }
        }
      }
    }
  )
}
@androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class]) fun getImg(px: ImageProxy) = px.image
