package com.nusuk.helper

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class DoorKeyRefill(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val usedCodes = mutableSetOf<String>()

    init {
        val prefs = context.getSharedPreferences("nusuk_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getStringSet("used_codes", emptySet()) ?: emptySet()
        usedCodes.addAll(saved)
    }

    private fun markUsed(code: String) {
        usedCodes.add(code)
        val prefs = context.getSharedPreferences("nusuk_prefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("used_codes", usedCodes).apply()
    }

    suspend fun getNextCode(): String? = withContext(Dispatchers.IO) {
        // 1. Try Live Cloudflare Worker
        try {
            val url = "https://hisham-nusuk.hisham-nusuk.workers.dev/api/state"
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JSONObject(body)
                    val pins = json.optJSONArray("pins")
                    if (pins != null) {
                        for (i in 0 until pins.length()) {
                            val c = pins.getJSONObject(i).optString("code")
                            if (c.isNotBlank() && c !in usedCodes) {
                                markUsed(c)
                                return@withContext c
                            }
                        }
                    }
                    val added = json.optJSONArray("added")
                    if (added != null) {
                        for (i in 0 until added.length()) {
                            val c = added.getString(i)
                            if (c.isNotBlank() && c !in usedCodes) {
                                markUsed(c)
                                return@withContext c
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Fallback to bundled offline codes pool
        try {
            val jsonStr = context.assets.open("codes.json").bufferedReader().use { it.readText() }
            val json = JSONObject(jsonStr)
            val codesArr = json.optJSONArray("codes")
            if (codesArr != null) {
                for (i in 0 until codesArr.length()) {
                    val c = codesArr.getString(i)
                    if (c.isNotBlank() && c !in usedCodes) {
                        markUsed(c)
                        return@withContext c
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return@withContext null
    }
}
