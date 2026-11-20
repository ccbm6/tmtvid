package com.tmt.vidso

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.*
import androidx.media3.ui.PlayerView
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.delay
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

fun videoDuration(ctx: Context, u: Uri): Long = try {
    val r = MediaMetadataRetriever()
    r.setDataSource(ctx, u)
    val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
    r.release(); d
} catch (e: Exception) { 0L }

fun saveToStore(ctx: Context, f: File, name: String, mime: String, video: Boolean): Boolean = try {
    val col = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    else MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val dir = (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC) + "/TmtVidso"
    val v = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
    }
    val u = ctx.contentResolver.insert(col, v)!!
    ctx.contentResolver.openOutputStream(u)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
    f.delete(); true
} catch (e: Exception) { false }

@Composable
fun Chips(items: List<String>, sel: Int, on: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEachIndexed { i, s -> FilterChip(selected = i == sel, onClick = { on(i) }, label = { Text(s) }) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    var uri by remember { mutableStateOf<Uri?>(null) }
    var dur by remember { mutableLongStateOf(0L) }
    var tab by remember { mutableIntStateOf(0) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        if (u != null) { uri = u; dur = videoDuration(ctx, u) }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("tmt vidso") }) }) { pad ->
        Column(
            Modifier.padding(pad).padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(onClick = {
                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            }) { Text(if (uri == null) "اختيار فيديو" else "تغيير الفيديو") }
            uri?.let { u ->
                Preview(u)
                TabRow(selectedTabIndex = tab) {
                    Tab(tab == 0, { tab = 0 }, text = { Text("تعديل") })
                    Tab(tab == 1, { tab = 1 }, text = { Text("استخراج صوت") })
                }
                if (tab == 0) EditTab(u, dur) else AudioTab(u, dur)
            }
        }
    }
}

@Composable
fun Preview(uri: Uri) {
    val ctx = LocalContext.current
    val exo = remember { ExoPlayer.Builder(ctx).build() }
    LaunchedEffect(uri) { exo.setMediaItem(MediaItem.fromUri(uri)); exo.prepare() }
    DisposableEffect(Unit) { onDispose { exo.release() } }
    AndroidView(
        factory = { c -> PlayerView(c).apply { player = exo } },
        modifier = Modifier.fillMaxWidth().height(240.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTab(uri: Uri, dur: Long) {
    val ctx = LocalContext.current
    val total = dur.toFloat().coerceAtLeast(1f)
    var range by remember(uri, dur) { mutableStateOf(0f..total) }
    var ratio by remember { mutableIntStateOf(0) }
    var rot by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(-1f) }
    var msg by remember { mutableStateOf("") }
    var tr by remember { mutableStateOf<Transformer?>(null) }
    val ratios = listOf("الأصلي" to 0f, "16:9" to 16f / 9, "9:16" to 9f / 16, "1:1" to 1f, "4:3" to 4f / 3)
    val running = progress >= 0f

    LaunchedEffect(running) {
        while (running) {
            val h = ProgressHolder()
            val t = tr
            if (t != null && t.getProgress(h) != Transformer.PROGRESS_STATE_NOT_STARTED) progress = h.progress / 100f
            delay(300)
        }
    }

    Text("القص: ${(range.start / 1000).toInt()} ث  ←  ${(range.endInclusive / 1000).toInt()} ث")
    RangeSlider(value = range, onValueChange = { range = it }, valueRange = 0f..total)
    Text("نسبة الأبعاد (Crop)")
    Chips(ratios.map { it.first }, ratio) { ratio = it }
    Text("التدوير")
    Chips(listOf("0°", "90°", "180°", "270°"), rot / 90) { rot = it * 90 }

    Button(enabled = !running, onClick = {
        progress = 0f; msg = ""
        val out = File(ctx.cacheDir, "out_${System.currentTimeMillis()}.mp4")
        val vfx = mutableListOf<Effect>()
        if (rot != 0) vfx += ScaleAndRotateTransformation.Builder().setRotationDegrees(rot.toFloat()).build()
        val ar = ratios[ratio].second
        if (ar > 0f) vfx += Presentation.createForAspectRatio(ar, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        val item = MediaItem.Builder().setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(range.start.toLong())
                    .setEndPositionMs(range.endInclusive.toLong()).build()
            ).build()
        val edited = EditedMediaItem.Builder(item).setEffects(Effects(emptyList(), vfx)).build()
        val t = Transformer.Builder(ctx).addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                Thread {
                    val ok = saveToStore(ctx, out, "tmt_${System.currentTimeMillis()}.mp4", "video/mp4", true)
                    msg = if (ok) "تم الحفظ في Movies/TmtVidso" else "تعذّر الحفظ"
                    progress = -1f
                }.start()
            }
            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                msg = "فشل التصدير: ${exportException.message}"; progress = -1f
            }
        }).build()
        tr = t
        t.start(edited, out.absolutePath)
    }) { Text("تصدير الفيديو") }

    if (running) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    if (msg.isNotEmpty()) Text(msg)
}

@Composable
fun AudioTab(uri: Uri, dur: Long) {
    val ctx = LocalContext.current
    var br by remember { mutableIntStateOf(1) }
    var enh by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(-1f) }
    var msg by remember { mutableStateOf("") }
    val rates = listOf(128, 192, 256, 320)
    val filters = listOf(
        null,
        "highpass=f=80,loudnorm=I=-16:TP=-1.5:LRA=11",
        "highpass=f=80,afftdn=nf=-25,loudnorm=I=-14:TP=-1:LRA=9"
    )
    val running = progress >= 0f

    Text("جودة MP3 (kbps)")
    Chips(rates.map { "$it" }, br) { br = it }
    Text("تحسين الصوت")
    Chips(listOf("بدون", "خفيف", "قوي"), enh) { enh = it }

    Button(enabled = !running, onClick = {
        progress = 0f; msg = ""
        val out = File(ctx.cacheDir, "audio_${System.currentTimeMillis()}.mp3")
        val args = mutableListOf("-y", "-i", FFmpegKitConfig.getSafParameterForRead(ctx, uri), "-vn")
        filters[enh]?.let { args += listOf("-af", it) }
        args += listOf("-c:a", "libmp3lame", "-b:a", "${rates[br]}k", "-ar", "48000", out.absolutePath)
        FFmpegKit.executeWithArgumentsAsync(args.toTypedArray(), { session ->
            if (ReturnCode.isSuccess(session.returnCode)) {
                val ok = saveToStore(ctx, out, "tmt_${System.currentTimeMillis()}.mp3", "audio/mpeg", false)
                msg = if (ok) "تم الحفظ في Music/TmtVidso" else "تعذّر الحفظ"
            } else msg = "فشل التحويل"
            progress = -1f
        }, null, { st ->
            if (dur > 0) progress = (st.time.toFloat() / dur).coerceIn(0f, 1f)
        })
    }) { Text("تحويل إلى MP3") }

    if (running) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    if (msg.isNotEmpty()) Text(msg)
}
