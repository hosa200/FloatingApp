package com.nusuk.helper

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiService(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var localQaBank: JSONArray? = null

    init {
        loadLocalBank()
    }

    private fun loadLocalBank() {
        try {
            val jsonStr = context.assets.open("qa_bank.json").bufferedReader().use { it.readText() }
            localQaBank = JSONArray(jsonStr)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun normalize(text: String): String {
        return text.replace(Regex("[\\u064B-\\u065F\\u0670\\u0640«»\"'؟?!.,،:؛()\\[\\]\\-]"), " ")
            .replace(Regex("[أإآ]"), "ا")
            .replace("ة", "ه")
            .replace("ى", "ي")
            .replace("ؤ", "و")
            .replace("ئ", "ي")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun matchLocalAnswer(question: String, options: List<String>): String? {
        val bank = localQaBank ?: return null
        val normQ = normalize(question)

        for (i in 0 until bank.length()) {
            val rule = bank.optJSONObject(i) ?: continue
            val qList = rule.optJSONArray("q") ?: continue
            val aList = rule.optJSONArray("a") ?: continue
            val mode = rule.optString("mode", "all")

            val qKeywords = (0 until qList.length()).map { normalize(qList.getString(it)) }
            val qMatched = if (mode == "any") {
                qKeywords.any { normQ.contains(it) }
            } else {
                qKeywords.all { normQ.contains(it) }
            }

            if (qMatched) {
                val aKeywords = (0 until aList.length()).map { normalize(aList.getString(it)) }
                for (opt in options) {
                    val normOpt = normalize(opt)
                    for (ak in aKeywords) {
                        if (normOpt.contains(ak) || ak.contains(normOpt)) {
                            return opt
                        }
                    }
                }
            }
        }
        return null
    }

    suspend fun askGemini(apiKey: String, question: String, options: List<String>): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null

        val prompt = """
            أنت خبير في المسابقات الإسلامية ومعلومات الحج والعمرة والسيرة النبوية.
            السؤال: $question
            الخيارات:
            ${options.mapIndexed { idx, o -> "${idx + 1}. $o" }.joinToString("\n")}

            اختر الإجابة الصحيحة فقط من الخيارات الأربعة أعلاه بدقة 100%. اكتب نص الخيار المطابق تماماً دون أي مقدمات أو شرح إضافي.
        """.trimIndent()

        val jsonBody = JSONObject().apply {
            val contents = JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    })
                })
            }
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.0)
                put("maxOutputTokens", 64)
            })
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = jsonBody.toString().toRequestBody(mediaType)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val resStr = response.body?.string() ?: return@withContext null
                val resJson = JSONObject(resStr)
                val candidates = resJson.optJSONArray("candidates") ?: return@withContext null
                if (candidates.length() == 0) return@withContext null
                val first = candidates.getJSONObject(0)
                val content = first.optJSONObject("content") ?: return@withContext null
                val parts = content.optJSONArray("parts") ?: return@withContext null
                if (parts.length() == 0) return@withContext null
                val text = parts.getJSONObject(0).optString("text", "").trim()

                // Find closest match among options
                val matched = options.find { normalize(text).contains(normalize(it)) || normalize(it).contains(normalize(text)) }
                return@withContext matched ?: text
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }
}
