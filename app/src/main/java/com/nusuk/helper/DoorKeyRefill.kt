package com.nusuk.helper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object DoorKeyRefill {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun fetchNextCode(): String? = withContext(Dispatchers.IO) {
        val url = "https://hisham-nusuk.hisham-nusuk.workers.dev/code"
        try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val code = resp.body?.string()?.trim()
                    if (!code.isNullOrBlank() && code.length >= 4) {
                        return@withContext code
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }
}
