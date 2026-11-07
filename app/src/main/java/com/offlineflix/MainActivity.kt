package com.offlineflix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Movie(val id: String, val title: String)

object ArchiveApi {
    fun fetchRaw(): String {
        val url = "https://archive.org/advancedsearch.php" +
            "?q=%28collection%3Asilent_films+OR+collection%3AFilm_Noir" +
            "+OR+collection%3Aanimationandcartoons+OR+collection%3Aclassic_tv%29" +
            "+AND+mediatype%3Amovies" +
            "+AND+NOT+title%3A%28sex+OR+nudist+OR+molester+OR+nazi+OR+madness%29" +
            "&fl%5B%5D=identifier&fl%5B%5D=title" +
            "&sort%5B%5D=downloads+desc&rows=50&output=json"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    fun parse(text: String): List<Movie> {
        val docs = JSONObject(text).getJSONObject("response").getJSONArray("docs")
        return (0 until docs.length()).map { i ->
            val d = docs.getJSONObject(i)
            val id = d.getString("identifier")
            Movie(id, d.optString("title", id))
        }
    }
}

private val Bg = Color(0xFF0B0B0F)
private val Red = Color(0xFFE50914)
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
        val cache = File(filesDir, "movies2.json")
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = Bg) { MovieList(cache) }
            }
        }
    }
}

@Composable
fun MovieList(cache: File) {
    var movies by remember { mutableStateOf(listOf<Movie>()) }
    var status by remember { mutableStateOf("লোড হচ্ছে...") }

    LaunchedEffect(Unit) {
        val cached = withContext(Dispatchers.IO) {
            runCatching { ArchiveApi.parse(cache.readText()) }.getOrNull()
        }
        if (cached != null) movies = cached

        val fresh = withContext(Dispatchers.IO) {
            runCatching {
                val t = ArchiveApi.fetchRaw()
                val l = ArchiveApi.parse(t)
                cache.writeText(t)
                l
            }.getOrNull()
        }
        if (fresh != null) {
            movies = fresh
            status = "অনলাইন: ${fresh.size}টি মুভি"
        } else {
            status = if (movies.isEmpty()) "ইন্টারনেট নেই" else "অফলাইন: সংরক্ষিত তালিকা"
        }
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 12.dp)) {
        Text(
            "OFFLINEFLIX",
            color = Red,
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(top = 14.dp)
        )
        Text(
            status,
            color = Color(0xFFB0B0B8),
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(movies) { m -> MovieCard(m) }
        }
    }
}

@Composable
fun MovieCard(m: Movie) {
    val colors = Palettes[(m.id.hashCode() and 0x7fffffff) % Palettes.size]
    Column {
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
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            m.title,
            color = Color.White,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}
