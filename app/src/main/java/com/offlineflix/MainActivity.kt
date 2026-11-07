package com.offlineflix

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.ImageLoader
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private val Bg = Color(0xFF0B0B0F)
private val Red = Color(0xFFE50914)
private val Gray = Color(0xFF2A2A33)
private val Muted = Color(0xFFB0B0B8)
private val Palettes = listOf(
    listOf(Color(0xFF7F1D1D), Color(0xFF1F1013)),
    listOf(Color(0xFF1E3A8A), Color(0xFF0F172A)),
    listOf(Color(0xFF14532D), Color(0xFF0B1F14)),
    listOf(Color(0xFF581C87), Color(0xFF1A0B2E)),
    listOf(Color(0xFF92400E), Color(0xFF241205)),
    listOf(Color(0xFF134E4A), Color(0xFF071F1E))
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scheduleAuto()
        val loader = ImageLoader.Builder(this).respectCacheHeaders(false).build()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Red)) {
                Surface(Modifier.fillMaxSize(), color = Bg) { App(loader) }
            }
        }
    }

    private fun scheduleAuto() {
        val cons = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresStorageNotLow(true)
            .build()
        val wm = WorkManager.getInstance(this)
        wm.enqueueUniquePeriodicWork(
            "auto_dl", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AutoDownloadWorker>(1, TimeUnit.HOURS)
                .setConstraints(cons).build()
        )
        wm.enqueueUniqueWork(
            "auto_dl_now", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AutoDownloadWorker>().setConstraints(cons).build()
        )
    }
}

@Composable
fun App(loader: ImageLoader) {
    val ctx = LocalContext.current
    var movies by remember { mutableStateOf(listOf<Movie>()) }
    var status by remember { mutableStateOf("লোড হচ্ছে...") }
    var infos by remember { mutableStateOf(mapOf<String, DlInfo>()) }
    var selected by remember { mutableStateOf<Movie?>(null) }

    LaunchedEffect(Unit) {
        val cache = Store.cacheFile(ctx)
        val cached = withContext(Dispatchers.IO) {
            runCatching { ArchiveApi.parse(cache.readText()) }.getOrNull()
        }
        if (cached != null) movies = cached

        val freshText = withContext(Dispatchers.IO) {
            runCatching { ArchiveApi.fetchList() }.getOrNull()
        }
        val fresh = freshText?.let { t -> runCatching { ArchiveApi.parse(t) }.getOrNull() }
        if (fresh != null && fresh.isNotEmpty()) {
            withContext(Dispatchers.IO) { runCatching { cache.writeText(freshText) } }
            movies = fresh
            status = "অনলাইন: ${fresh.size}টি মুভি"
        } else {
            status = if (movies.isEmpty()) "ইন্টারনেট নেই" else "অফলাইন: সংরক্ষিত তালিকা"
        }
    }

    LaunchedEffect(movies) {
        while (true) {
            infos = withContext(Dispatchers.IO) {
                movies.associate { it.id to Downloads.info(ctx, it.id) }
            }
            delay(2000)
        }
    }

    BackHandler(enabled = selected != null) { selected = null }

    val sel = selected
    if (sel != null) {
        Detail(sel, infos[sel.id] ?: DlInfo(DlState.NONE, 0), loader) { selected = null }
    } else {
        Home(movies, status, infos, loader) { selected = it }
    }
}

@Composable
fun Home(
    movies: List<Movie>,
    status: String,
    infos: Map<String, DlInfo>,
    loader: ImageLoader,
    onOpen: (Movie) -> Unit
) {
    val ctx = LocalContext.current
    var auto by remember { mutableStateOf(Store.autoOn(ctx)) }
    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    val doneCount = infos.values.count { it.state == DlState.DONE }
    val shown = movies.filter {
        (tab == 0 || infos[it.id]?.state == DlState.DONE) &&
            it.title.contains(query, ignoreCase = true)
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "OFFLINEFLIX", color = Red, fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f)
            )
            Text("অটো ডাউনলোড", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.width(6.dp))
            Switch(checked = auto, onCheckedChange = { auto = it; Store.setAuto(ctx, it) })
        }
        Text(status, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("মুভি খুঁজুন") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Red,
                unfocusedBorderColor = Color(0xFF444450),
                cursorColor = Red
            )
        )
        Row {
            TabBtn("সব (${movies.size})", tab == 0) { tab = 0 }
            TabBtn("ডাউনলোড ($doneCount)", tab == 1) { tab = 1 }
        }

        if (shown.isEmpty()) {
            Text(
                if (tab == 1) "এখনো কোনো মুভি ডাউনলোড হয়নি" else "কিছু পাওয়া যায়নি",
                color = Muted, modifier = Modifier.padding(top = 24.dp).fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(shown, key = { it.id }) { m ->
                MovieCard(m, infos[m.id], loader) { onOpen(m) }
            }
        }
    }
}

@Composable
fun TabBtn(label: String, on: Boolean, click: () -> Unit) {
    TextButton(onClick = click) {
        Text(
            label,
            color = if (on) Red else Muted,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun MovieCard(m: Movie, info: DlInfo?, loader: ImageLoader, onClick: () -> Unit) {
    val colors = Palettes[(m.id.hashCode() and 0x7fffffff) % Palettes.size]
    val st = info?.state
    val badge = when (st) {
        DlState.DONE -> "✓"
        DlState.RUNNING -> "${info?.percent ?: 0}%"
        DlState.QUEUED -> "…"
        else -> null
    }
    Column(Modifier.clickable { onClick() }) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.verticalGradient(colors)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                m.title.take(1).uppercase(),
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 56.sp, fontWeight = FontWeight.Bold
            )
            AsyncImage(
                model = ArchiveApi.posterUrl(m.id),
                contentDescription = m.title,
                imageLoader = loader,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (badge != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Red)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(badge, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(
            m.title, color = Color.White, fontSize = 13.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
fun ActionButton(text: String, color: Color = Red, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) { Text(text) }
}

@Composable
fun Detail(m: Movie, info: DlInfo, loader: ImageLoader, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var source by remember { mutableStateOf<String?>(null) }

    val src = source
    if (src != null) {
        PlayerScreen(m.id, src) { source = null }
        return
    }

    fun start(wifi: Boolean) {
        scope.launch {
            msg = "প্রস্তুত হচ্ছে..."
            Store.setBlocked(ctx, m.id, false)
            val ok = withContext(Dispatchers.IO) {
                runCatching { Downloads.enqueue(ctx, m, wifi) }.getOrDefault(false)
            }
            msg = if (ok) "ডাউনলোড শুরু হয়েছে" else "ফাইল পাওয়া যায়নি বা ইন্টারনেট নেই"
        }
    }

    fun stream() {
        scope.launch {
            msg = "লোড হচ্ছে..."
            val vf = withContext(Dispatchers.IO) {
                runCatching { ArchiveApi.pickFile(m.id) }.getOrNull()
            }
            if (vf == null) {
                msg = "ভিডিও পাওয়া যায়নি বা ইন্টারনেট নেই"
            } else {
                msg = ""
                source = ArchiveApi.fileUrl(m.id, vf.name)
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("← পেছনে", color = Red) }
        }
        Box(
            Modifier
                .fillMaxWidth(0.6f)
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.verticalGradient(Palettes[(m.id.hashCode() and 0x7fffffff) % Palettes.size]))
        ) {
            AsyncImage(
                model = ArchiveApi.posterUrl(m.id),
                contentDescription = m.title,
                imageLoader = loader,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Text(
            m.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 14.dp)
        )

        when (info.state) {
            DlState.DONE -> {
                ActionButton("▶ প্লে করুন") {
                    source = Uri.fromFile(Downloads.localFile(ctx, m.id)).toString()
                }
                ActionButton("🗑 ডাউনলোড মুছুন", Gray) {
                    Downloads.remove(ctx, m.id)
                    Store.setBlocked(ctx, m.id, true)
                    msg = "মুছে ফেলা হয়েছে"
                }
            }
            DlState.RUNNING, DlState.QUEUED -> {
                Text("ডাউনলোড হচ্ছে ${info.percent}%", color = Color.White)
                LinearProgressIndicator(
                    progress = { info.percent / 100f },
                    color = Red,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                )
                ActionButton("বাতিল করুন", Gray) {
                    Downloads.remove(ctx, m.id)
                    Store.setBlocked(ctx, m.id, true)
                }
            }
            else -> {
                if (info.state == DlState.FAILED) {
                    Text("ডাউনলোড ব্যর্থ হয়েছে, আবার চেষ্টা করুন", color = Muted)
                }
                ActionButton("⬇ ওয়াইফাইতে ডাউনলোড") { start(true) }
                ActionButton("⬇ মোবাইল ডাটায় ডাউনলোড", Gray) { start(false) }
                ActionButton("▶ অনলাইনে দেখুন (ডাটা খরচ হবে)", Gray) { stream() }
            }
        }
        if (msg.isNotEmpty()) {
            Text(msg, color = Muted, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
fun PlayerScreen(id: String, source: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler { onClose() }

    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(source))
            val pos = Store.position(ctx, id)
            if (pos > 0) seekTo(pos)
            prepare()
            playWhenReady = true
        }
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) player.pause()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    DisposableEffect(Unit) {
        onDispose {
            val d = player.duration
            val p = player.currentPosition
            Store.setPosition(ctx, id, if (d > 0 && p > d - 10_000) 0L else p)
            player.release()
        }
    }

    AndroidView(
        factory = { c -> PlayerView(c).apply { this.player = player; useController = true } },
        modifier = Modifier.fillMaxSize().background(Color.Black)
    )
}
