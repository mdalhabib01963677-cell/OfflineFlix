package com.offlineflix

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Movie(val id: String, val title: String)
data class VideoFile(val name: String, val size: Long)
enum class DlState { NONE, QUEUED, RUNNING, DONE, FAILED }
data class DlInfo(val state: DlState, val percent: Int)

object ArchiveApi {
    private const val LIST_URL = "https://archive.org/advancedsearch.php" +
        "?q=%28collection%3Asilent_films+OR+collection%3AFilm_Noir" +
        "+OR+collection%3Aanimationandcartoons+OR+collection%3Aclassic_tv" +
        "+OR+collection%3Aprelinger%29" +
        "+AND+mediatype%3Amovies" +
        "+AND+NOT+title%3A%28sex+OR+nudist+OR+molester+OR+nazi+OR+madness+OR+porn+OR+erotic+OR+nude%29" +
        "&fl%5B%5D=identifier&fl%5B%5D=title" +
        "&sort%5B%5D=downloads+desc&rows=120&output=json"

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "OfflineFlix/2.0")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    fun fetchList(): String = get(LIST_URL)

    fun parse(text: String): List<Movie> {
        val docs = JSONObject(text).getJSONObject("response").getJSONArray("docs")
        return (0 until docs.length()).map { i ->
            val d = docs.getJSONObject(i)
            val id = d.getString("identifier")
            Movie(id, d.optString("title", id))
        }
    }

    fun pickFile(id: String): VideoFile? {
        val files = JSONObject(get("https://archive.org/metadata/$id")).optJSONArray("files")
            ?: return null
        val list = ArrayList<VideoFile>()
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val name = f.optString("name")
            val lower = name.lowercase()
            if (lower.endsWith(".mp4") || lower.endsWith(".m4v")) {
                val size = f.optString("size", "0").toLongOrNull() ?: 0L
                list.add(VideoFile(name, size))
            }
        }
        val good = list.filter { it.size in 3_000_000L..1_500_000_000L }
        return good.minByOrNull { it.size } ?: list.firstOrNull { it.size > 0 }
    }

    fun fileUrl(id: String, name: String): String =
        "https://archive.org/download/$id/" +
            name.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    fun posterUrl(id: String): String = "https://archive.org/services/img/$id"
}

object Store {
    private fun p(c: Context) = c.getSharedPreferences("offlineflix", Context.MODE_PRIVATE)

    fun cacheFile(c: Context) = File(c.filesDir, "movies3.json")

    fun downloadId(c: Context, id: String): Long = p(c).getLong("dl_$id", -1L)
    fun setDownloadId(c: Context, id: String, v: Long) = p(c).edit().putLong("dl_$id", v).apply()

    fun done(c: Context, id: String): Boolean = p(c).getBoolean("done_$id", false)
    fun setDone(c: Context, id: String, v: Boolean) = p(c).edit().putBoolean("done_$id", v).apply()

    fun blocked(c: Context, id: String): Boolean = p(c).getBoolean("blocked_$id", false)
    fun setBlocked(c: Context, id: String, v: Boolean) = p(c).edit().putBoolean("blocked_$id", v).apply()

    fun position(c: Context, id: String): Long = p(c).getLong("pos_$id", 0L)
    fun setPosition(c: Context, id: String, v: Long) = p(c).edit().putLong("pos_$id", v).apply()

    fun autoOn(c: Context): Boolean = p(c).getBoolean("auto", true)
    fun setAuto(c: Context, v: Boolean) = p(c).edit().putBoolean("auto", v).apply()
}

object Downloads {
    fun localFile(c: Context, id: String): File {
        val dir = c.getExternalFilesDir("movies") ?: File(c.filesDir, "movies")
        dir.mkdirs()
        return File(dir, "$id.mp4")
    }

    fun enqueue(c: Context, m: Movie, wifiOnly: Boolean): Boolean {
        val vf = ArchiveApi.pickFile(m.id) ?: return false
        val dest = localFile(c, m.id)
        if (dest.exists()) dest.delete()
        val types = if (wifiOnly) DownloadManager.Request.NETWORK_WIFI
        else DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
        val req = DownloadManager.Request(Uri.parse(ArchiveApi.fileUrl(m.id, vf.name)))
            .setTitle(m.title)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedNetworkTypes(types)
            .setDestinationUri(Uri.fromFile(dest))
        val dm = c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val did = dm.enqueue(req)
        Store.setDownloadId(c, m.id, did)
        Store.setDone(c, m.id, false)
        return true
    }

    fun info(c: Context, id: String): DlInfo {
        val did = Store.downloadId(c, id)
        if (did < 0) return DlInfo(DlState.NONE, 0)
        val dm = c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cur = dm.query(DownloadManager.Query().setFilterById(did))
        return cur.use { q ->
            if (!q.moveToFirst()) {
                if (Store.done(c, id) && localFile(c, id).exists()) DlInfo(DlState.DONE, 100)
                else DlInfo(DlState.NONE, 0)
            } else {
                val st = q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val got = q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val pct = if (total > 0) (got * 100 / total).toInt() else 0
                when (st) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        Store.setDone(c, id, true)
                        DlInfo(DlState.DONE, 100)
                    }
                    DownloadManager.STATUS_FAILED -> DlInfo(DlState.FAILED, 0)
                    DownloadManager.STATUS_RUNNING -> DlInfo(DlState.RUNNING, pct)
                    else -> DlInfo(DlState.QUEUED, pct)
                }
            }
        }
    }

    fun remove(c: Context, id: String) {
        val did = Store.downloadId(c, id)
        if (did >= 0) {
            val dm = c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.remove(did)
        }
        localFile(c, id).delete()
        Store.setDownloadId(c, id, -1L)
        Store.setDone(c, id, false)
    }
}
