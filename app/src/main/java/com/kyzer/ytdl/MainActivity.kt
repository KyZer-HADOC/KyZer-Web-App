package com.kyzer.ytdl

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arthenica.ffmpegkit.FFmpegKit
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

// ------------------------------------------------------------------ colours
private val cBg = Color(0xFF0E0E13)
private val cCard = Color(0xFF181820)
private val cCard2 = Color(0xFF242431)
private val cAccent = Color(0xFFFF3D4A)
private val cText = Color(0xFFF4F4F7)
private val cSub = Color(0xFF9C9CAB)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(transparent),
            navigationBarStyle = SystemBarStyle.dark(transparent)
        )
        NewPipe.init(YtDownloader())
        val shared = if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = cAccent, background = cBg, surface = cCard,
                    onSurface = cText, onBackground = cText, onPrimary = Color.White
                )
            ) { App(shared) }
        }
    }
}

sealed interface Task {
    data object Idle : Task
    data class Running(val stage: String, val fraction: Float?, val detail: String, val title: String) : Task
    data class Done(val file: Engine.Saved) : Task
    data class Failed(val message: String) : Task
}

// --------------------------------------------------------------------- app
@Composable
fun App(shared: String?) {
    val ctx = LocalContext.current
    val appCtx = ctx.applicationContext
    val view = LocalView.current
    val focus = LocalFocusManager.current
    val clipboard = LocalClipboardManager.current
    val urlRegex = remember { Regex("https?://\\S+") }

    var tab by remember { mutableIntStateOf(0) }
    var url by remember { mutableStateOf(urlRegex.find(shared ?: "")?.value ?: "") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<StreamInfo?>(null) }
    var thumb by remember { mutableStateOf<Bitmap?>(null) }
    var videoOpts by remember { mutableStateOf(listOf<VideoOption>()) }
    var mp3Opts by remember { mutableStateOf(listOf<Mp3Option>()) }
    var isVideo by remember { mutableStateOf(true) }
    var selVideo by remember { mutableIntStateOf(0) }
    var selMp3 by remember { mutableIntStateOf(1) }
    var task by remember { mutableStateOf<Task>(Task.Idle) }
    var paused by remember { mutableStateOf(false) }
    var flag by remember { mutableStateOf(CancelFlag()) }
    var history by remember { mutableStateOf(HistoryStore.load(appCtx)) }

    DisposableEffect(task is Task.Running) {
        view.keepScreenOn = task is Task.Running
        onDispose { view.keepScreenOn = false }
    }

    fun search() {
        val link = urlRegex.find(url)?.value
        if (link == null) { error = "Please paste a valid YouTube link."; return }
        focus.clearFocus()
        loading = true; error = null; info = null; thumb = null
        if (task !is Task.Running) task = Task.Idle
        thread {
            try {
                val i = StreamInfo.getInfo(link)
                val vo = Engine.videoOptions(i)
                val mo = Engine.mp3Options(i)
                val bmp = try { Engine.loadThumb(i) } catch (e: Exception) { null }
                videoOpts = vo; mp3Opts = mo; thumb = bmp
                selVideo = 0; selMp3 = 1; isVideo = true
                info = i
            } catch (e: Exception) {
                error = "Couldn't load this video: ${e.message ?: "unknown error"}"
            }
            loading = false
        }
    }

    fun startDownload() {
        val i = info ?: return
        val vid = isVideo
        val vo = videoOpts.getOrNull(selVideo)
        val mo = mp3Opts.getOrNull(selMp3)
        if (vid && vo == null) return
        if (!vid && mo == null) return
        val f = CancelFlag()
        flag = f; paused = false
        task = Task.Running("Starting…", 0f, "", i.name)
        thread {
            try {
                val progress = { stage: String, fr: Float?, d: String ->
                    task = Task.Running(stage, fr, d, i.name)
                }
                val saved = if (vid) Engine.runVideo(appCtx, i, vo!!, f, progress)
                else Engine.runMp3(appCtx, i, mo!!, f, progress)
                HistoryStore.add(
                    appCtx,
                    HistoryItem(
                        i.name, saved.name, saved.uri.toString(), saved.mime,
                        if (vid) "VIDEO" else "MP3",
                        if (vid) "${vo!!.label} · ${vo.container.uppercase()}" else "${mo!!.kbps} kbps MP3",
                        saved.size, System.currentTimeMillis()
                    )
                )
                history = HistoryStore.load(appCtx)
                task = Task.Done(saved)
            } catch (e: Exception) {
                task = if (f.cancelled) Task.Idle else Task.Failed(e.message ?: "Download failed")
            }
        }
    }

    fun open(uri: String, mime: String) {
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Toast.makeText(ctx, "Can't open this file (moved or deleted?)", Toast.LENGTH_SHORT).show()
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startDownload()
        else Toast.makeText(ctx, "Storage permission is needed to save files", Toast.LENGTH_LONG).show()
    }

    fun requestAndStart() {
        if (Build.VERSION.SDK_INT < 29 &&
            ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else startDownload()
    }

    LaunchedEffect(Unit) { if (shared != null && urlRegex.containsMatchIn(shared)) search() }

    Surface(color = cBg, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Header()
            Tabs(tab, history.size) { tab = it }
            Spacer(Modifier.height(12.dp))

            Box(Modifier.weight(1f)) {
                if (tab == 0) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp)
                    ) {
                        OutlinedTextField(
                            value = url, onValueChange = { url = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("Paste YouTube link…", color = cSub) },
                            shape = RoundedCornerShape(16.dp),
                            trailingIcon = {
                                TextButton(onClick = {
                                    clipboard.getText()?.text?.let { url = it }
                                }) { Text("Paste", color = cAccent, fontWeight = FontWeight.Bold) }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { search() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = cAccent, unfocusedBorderColor = cCard2,
                                focusedContainerColor = cCard, unfocusedContainerColor = cCard,
                                cursorColor = cAccent
                            )
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { search() }, enabled = !loading,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = cAccent, contentColor = Color.White,
                                disabledContainerColor = cCard2, disabledContentColor = cSub
                            )
                        ) {
                            if (loading) CircularProgressIndicator(
                                Modifier.size(22.dp), color = cAccent, strokeWidth = 2.5.dp
                            ) else Text("🔍  Get video", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }

                        error?.let {
                            Spacer(Modifier.height(12.dp))
                            Text(it, color = cAccent, fontSize = 14.sp)
                        }

                        val i = info
                        if (i == null) {
                            if (!loading && error == null) {
                                Spacer(Modifier.height(28.dp))
                                Text(
                                    "Paste a link above, or use Share → KyZer YouBe from the YouTube app.",
                                    color = cSub, fontSize = 14.sp
                                )
                            }
                        } else {
                            Spacer(Modifier.height(18.dp))
                            VideoCard(i, thumb)
                            Spacer(Modifier.height(18.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FormatTab("🎬  MP4 · Video", isVideo, Modifier.weight(1f)) { isVideo = true }
                                FormatTab("🎵  MP3 · Audio", !isVideo, Modifier.weight(1f)) { isVideo = false }
                            }
                            Spacer(Modifier.height(16.dp))
                            Text("Choose quality", color = cText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(Modifier.height(10.dp))

                            if (isVideo) {
                                if (videoOpts.isEmpty()) Text("No video qualities available.", color = cSub)
                                videoOpts.forEachIndexed { idx, o ->
                                    QualityRow(
                                        o.label, o.badge, o.detail,
                                        Engine.fmtSize(o.sizeBytes).let { if (it.isEmpty()) "" else "~ $it" },
                                        selVideo == idx
                                    ) { selVideo = idx }
                                    Spacer(Modifier.height(8.dp))
                                }
                            } else {
                                mp3Opts.forEachIndexed { idx, o ->
                                    val badge = when (o.kbps) { 320 -> "Best"; 128 -> "Standard"; 64 -> "Small"; else -> "" }
                                    QualityRow(
                                        "${o.kbps} kbps", badge, "MP3",
                                        Engine.fmtSize(o.sizeBytes).let { if (it.isEmpty()) "" else "~ $it" },
                                        selMp3 == idx
                                    ) { selMp3 = idx }
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                } else {
                    HistoryTab(
                        history,
                        onOpen = { open(it.uri, it.mime) },
                        onRemove = { HistoryStore.remove(appCtx, it); history = HistoryStore.load(appCtx) },
                        onClear = { HistoryStore.clear(appCtx); history = emptyList() }
                    )
                }
            }

            val running = task is Task.Running
            if ((tab == 0 && info != null) || running || (tab == 0 && task !is Task.Idle)) {
                val label = if (isVideo) {
                    videoOpts.getOrNull(selVideo)?.let { "⬇  Download MP4 · ${it.label}" } ?: "No MP4 available"
                } else {
                    mp3Opts.getOrNull(selMp3)?.let { "⬇  Download MP3 · ${it.kbps} kbps" } ?: "No audio available"
                }
                val can = info != null && (if (isVideo) videoOpts.isNotEmpty() else mp3Opts.isNotEmpty())
                BottomBar(
                    task = task, paused = paused, canDownload = can, downloadLabel = label,
                    onDownload = { requestAndStart() },
                    onPause = { paused = !paused; flag.paused = paused },
                    onCancel = {
                        flag.cancelled = true; flag.paused = false; paused = false
                        FFmpegKit.cancel()
                    },
                    onOpen = { open(it.uri.toString(), it.mime) },
                    onReset = { task = Task.Idle }
                )
            }
        }
    }
}

// ------------------------------------------------------------ small pieces
@Composable
private fun Header() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(cAccent),
            contentAlignment = Alignment.Center
        ) { Text("▶", color = Color.White, fontSize = 18.sp) }
        Spacer(Modifier.width(12.dp))
        Column {
            Row {
                Text("KyZer ", color = cText, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Text("YouBe", color = cAccent, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            }
            Text("Videos & music, your way", color = cSub, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Tabs(tab: Int, count: Int, onTab: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(14.dp)).background(cCard).padding(4.dp)
    ) {
        TabPill("⬇  Download", tab == 0, Modifier.weight(1f)) { onTab(0) }
        TabPill("🕘  History ($count)", tab == 1, Modifier.weight(1f)) { onTab(1) }
    }
}

@Composable
private fun TabPill(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(44.dp).clip(RoundedCornerShape(11.dp))
            .background(if (selected) cAccent else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text, color = if (selected) Color.White else cSub,
            fontWeight = FontWeight.SemiBold, fontSize = 14.sp
        )
    }
}

@Composable
private fun FormatTab(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(54.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) cAccent.copy(alpha = 0.16f) else cCard)
            .border(1.5.dp, if (selected) cAccent else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text, color = if (selected) cText else cSub,
            fontWeight = FontWeight.Bold, fontSize = 15.sp
        )
    }
}

@Composable
private fun VideoCard(i: StreamInfo, thumb: Bitmap?) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cCard)) {
        Box {
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(), contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16 / 9f)
                )
            } else {
                Box(Modifier.fillMaxWidth().aspectRatio(16 / 9f).background(cCard2))
            }
            if (i.duration > 0) {
                Text(
                    fmtDuration(i.duration), color = Color.White, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                        .clip(RoundedCornerShape(6.dp)).background(Color(0xCC000000))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
        }
        Column(Modifier.padding(14.dp)) {
            Text(
                i.name, color = cText, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(i.uploaderName ?: "", color = cSub, fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
private fun QualityRow(
    title: String, badge: String, detail: String, size: String,
    selected: Boolean, onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) cAccent.copy(alpha = 0.14f) else cCard)
            .border(1.5.dp, if (selected) cAccent else Color.Transparent, shape)
            .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = cText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (badge.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        badge, color = cAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .background(cAccent.copy(alpha = 0.16f))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }
            Text(detail, color = cSub, fontSize = 13.sp)
        }
        if (size.isNotEmpty()) {
            Text(size, color = cSub, fontSize = 13.sp)
            Spacer(Modifier.width(12.dp))
        }
        Box(
            Modifier.size(22.dp).clip(CircleShape)
                .border(2.dp, if (selected) cAccent else cSub, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) Box(Modifier.size(12.dp).clip(CircleShape).background(cAccent))
        }
    }
}

@Composable
private fun HistoryTab(
    items: List<HistoryItem>,
    onOpen: (HistoryItem) -> Unit,
    onRemove: (HistoryItem) -> Unit,
    onClear: () -> Unit
) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No downloads yet", color = cSub, fontSize = 15.sp)
        }
        return
    }
    val fmt = remember { SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()) }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClear) { Text("Clear all", color = cAccent) }
            }
        }
        items(items) { h ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cCard).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(cCard2),
                    contentAlignment = Alignment.Center
                ) { Text(if (h.kind == "MP3") "🎵" else "🎬", fontSize = 22.sp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).clickable { onOpen(h) }) {
                    Text(
                        h.title, color = cText, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${h.quality} · ${Engine.fmtSize(h.sizeBytes)} · ${fmt.format(Date(h.time))}",
                        color = cSub, fontSize = 12.sp
                    )
                }
                TextButton(onClick = { onRemove(h) }) { Text("✕", color = cSub, fontSize = 16.sp) }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun BottomBar(
    task: Task, paused: Boolean, canDownload: Boolean, downloadLabel: String,
    onDownload: () -> Unit, onPause: () -> Unit, onCancel: () -> Unit,
    onOpen: (Engine.Saved) -> Unit, onReset: () -> Unit
) {
    val btnShape = RoundedCornerShape(16.dp)
    val accentColors = ButtonDefaults.buttonColors(
        containerColor = cAccent, contentColor = Color.White,
        disabledContainerColor = cCard2, disabledContentColor = cSub
    )
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(cCard).padding(20.dp)
    ) {
        when (task) {
            is Task.Idle -> Button(
                onClick = onDownload, enabled = canDownload, colors = accentColors, shape = btnShape,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text(downloadLabel, fontSize = 16.sp, fontWeight = FontWeight.Bold) }

            is Task.Running -> {
                val f = task.fraction
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (paused) "⏸  Paused" else task.stage,
                            color = cText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                        )
                        Text(task.title, color = cSub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (f != null) Text(
                        "${(f * 100).toInt()}%", color = cAccent,
                        fontWeight = FontWeight.ExtraBold, fontSize = 22.sp
                    )
                }
                Spacer(Modifier.height(12.dp))
                val barMod = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))
                if (f != null) LinearProgressIndicator(
                    progress = { f }, modifier = barMod, color = cAccent, trackColor = cCard2
                ) else LinearProgressIndicator(modifier = barMod, color = cAccent, trackColor = cCard2)
                Spacer(Modifier.height(8.dp))
                Text(task.detail, color = cSub, fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (task.stage.startsWith("Downloading")) {
                        Button(
                            onClick = onPause, shape = btnShape,
                            colors = ButtonDefaults.buttonColors(containerColor = cCard2, contentColor = cText),
                            modifier = Modifier.weight(1f).height(50.dp)
                        ) { Text(if (paused) "▶  Resume" else "⏸  Pause", fontWeight = FontWeight.Bold) }
                    }
                    Button(
                        onClick = onCancel, shape = btnShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF3A1D22), contentColor = cAccent
                        ),
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) { Text("✕  Cancel", fontWeight = FontWeight.Bold) }
                }
            }

            is Task.Done -> {
                Text("✓  Saved to Downloads/KyZer YouBe", color = cText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text(task.file.name, color = cSub, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { onOpen(task.file) }, colors = accentColors, shape = btnShape,
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) { Text("▶  Open", fontWeight = FontWeight.Bold) }
                    Button(
                        onClick = onReset, shape = btnShape,
                        colors = ButtonDefaults.buttonColors(containerColor = cCard2, contentColor = cText),
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) { Text("Another one", fontWeight = FontWeight.Bold) }
                }
            }

            is Task.Failed -> {
                Text("⚠  ${task.message}", color = cAccent, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onDownload, colors = accentColors, shape = btnShape,
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) { Text("Try again", fontWeight = FontWeight.Bold) }
                    Button(
                        onClick = onReset, shape = btnShape,
                        colors = ButtonDefaults.buttonColors(containerColor = cCard2, contentColor = cText),
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) { Text("Dismiss", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

private fun fmtDuration(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}
