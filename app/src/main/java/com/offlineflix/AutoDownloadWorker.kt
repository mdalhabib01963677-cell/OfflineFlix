package com.offlineflix

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AutoDownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = applicationContext
        if (!Store.autoOn(c)) return@withContext Result.success()
        if (c.filesDir.usableSpace < 2_000_000_000L) return@withContext Result.success()
        try {
            val text = ArchiveApi.fetchList()
            val movies = ArchiveApi.parse(text)
            if (movies.isNotEmpty()) Store.cacheFile(c).writeText(text)

            val states = movies.associate { it.id to Downloads.info(c, it.id).state }
            var active = states.values.count { it == DlState.RUNNING || it == DlState.QUEUED }
            for (m in movies) {
                if (active >= 2) break
                if (states[m.id] == DlState.NONE && !Store.blocked(c, m.id)) {
                    if (Downloads.enqueue(c, m, true)) active++
                }
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
