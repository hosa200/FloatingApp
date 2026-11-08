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
import java.util.regex.Pattern

class GeminiService(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
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
        return text.replace(Regex("[\\u064B-\\u065F\\u0670\\u0640«»\"'؟?!.,،:؛()\\[\\]\\-*]"), " ")
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
            أنت خبير إسلامي وتاريخي ومحكم مسابقات ديني متخصص في السيرة والقرآن والفقه وتاريخ مكة والمدينة.
            السؤال: $question
            الخيارات المتاحة:
            ${options.mapIndexed { idx, o -> "${idx + 1}. $o" }.joinToString("\n")}

            المطلوب: حدد الخيار الصحيح بنسبة 100%.
            اكتب رقم الخيار أولاً ثم نصه بالضبط، مثلاً: 2. مريم
        """.trimIndent()

        // Try gemini-1.5-flash first, then fallback to gemini-2.0-flash
        val models = listOf("gemini-1.5-flash", "gemini-2.0-flash", "gemini-1.5-pro")

        for (model in models) {
            val res = callModel(apiKey, model, prompt, options)
            if (res != null) {
                return@withContext res
            }
        }

        return@withContext null
    }

    private fun callModel(apiKey: String, model: String, prompt: String, options: List<String>): String? {
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
                put("maxOutputTokens", 128)
            })
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = jsonBody.toString().toRequestBody(mediaType)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val resStr = response.body?.string() ?: return null
                val resJson = JSONObject(resStr)
                val candidates = resJson.optJSONArray("candidates") ?: return null
                if (candidates.length() == 0) return null
                val first = candidates.getJSONObject(0)
                val content = first.optJSONObject("content") ?: return null
                val parts = content.optJSONArray("parts") ?: return null
                if (parts.length() == 0) return null
                val rawText = parts.getJSONObject(0).optString("text", "").trim()
                val normText = normalize(rawText)

                // 1. Try detecting option index number (1, 2, 3, 4) at the start
                val matcher = Pattern.compile("^[\\D]*([1-4])").matcher(rawText)
                if (matcher.find()) {
                    val idx = matcher.group(1)?.toIntOrNull()
                    if (idx != null && idx in 1..options.size) {
                        return options[idx - 1]
                    }
                }

                // 2. Try exact/fuzzy matching text among options
                for (opt in options) {
                    val normOpt = normalize(opt)
                    if (normText.contains(normOpt) || normOpt.contains(normText)) {
                        return opt
                    }
                }

                // 3. Match individual significant words
                var bestOpt: String? = null
                var maxMatches = 0
                for (opt in options) {
                    val words = normalize(opt).split(" ").filter { it.length >= 3 }
                    val matchedWords = words.count { normText.contains(it) }
                    if (matchedWords > maxMatches) {
                        maxMatches = matchedWords
                        bestOpt = opt
                    }
                }
                if (maxMatches >= 1) {
                    return bestOpt
                }

                return rawText
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
