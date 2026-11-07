package com.offlineflix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
            "?q=collection%3Afeature_films+AND+mediatype%3Amovies" +
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cache = File(filesDir, "movies.json")
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { MovieList(cache) }
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

    Column(Modifier.padding(16.dp)) {
        Text("OfflineFlix", style = MaterialTheme.typography.headlineMedium)
        Text(status)
        LazyColumn {
            items(movies) { m ->
                Text(m.title, Modifier.padding(vertical = 8.dp))
            }
        }
    }
}
