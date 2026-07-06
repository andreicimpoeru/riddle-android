package com.riddle.diary

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OracleClient(
    private val apiKey: String,
    private val apiBase: String,
    private val model: String,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    /**
     * Two-step oracle: (1) OCR the handwriting image, (2) reply as Tom from the text.
     */
    suspend fun ask(pngBytes: ByteArray): Result<String> = withContext(Dispatchers.IO) {
        try {
            val transcription = transcribe(pngBytes).getOrElse { return@withContext Result.failure(it) }
            Log.i(TAG, "OCR: ${transcription.take(200)}${if (transcription.length > 200) "…" else ""}")

            if (transcription.isBlank() || transcription.equals("[unreadable]", ignoreCase = true)) {
                return@withContext Result.success("I couldn't read the handwriting.")
            }
            reply(transcription)
        } catch (e: Exception) {
            Log.e(TAG, "oracle failed", e)
            Result.failure(e)
        }
    }

    private fun transcribe(pngBytes: ByteArray): Result<String> {
        val messages = JSONArray().apply {
            put(message("system", OCR_SYSTEM))
            put(visionUserMessage(OCR_USER_PROMPT, pngBytes))
        }
        return complete(messages, maxTokens = OCR_MAX_TOKENS, label = "OCR")
    }

    private fun reply(transcription: String): Result<String> {
        val messages = JSONArray().apply {
            put(message("system", PERSONA))
            put(message("user", transcription))
        }
        return complete(messages, maxTokens = REPLY_MAX_TOKENS, label = "reply")
    }

    private fun complete(messages: JSONArray, maxTokens: Int, label: String): Result<String> {
        val streamed = chat(messages, stream = true, maxTokens = maxTokens)
        if (streamed.isSuccess && !streamed.getOrNull().isNullOrBlank()) {
            return streamed
        }
        val buffered = chat(messages, stream = false, maxTokens = maxTokens)
        if (buffered.isFailure) {
            Log.e(TAG, "$label failed: ${buffered.exceptionOrNull()?.message}")
        }
        return buffered
    }

    private fun chat(messages: JSONArray, stream: Boolean, maxTokens: Int): Result<String> {
        return try {
            val body = JSONObject().apply {
                put("model", model)
                put("stream", stream)
                put("max_tokens", maxTokens)
                put("messages", messages)
                // Reasoning models (Gemma 4, etc.) otherwise spend the entire
                // budget on reasoning_content and return a truncated visible reply.
                put("reasoning_effort", "none")
            }
            client.newCall(buildRequest(body)).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(
                        IllegalStateException("HTTP ${response.code}: ${response.body?.string()}"),
                    )
                }
                val raw = response.body?.string().orEmpty()
                val text = if (stream) parseSse(raw).ifBlank { parseJson(raw) } else parseJson(raw)
                if (text.isBlank()) {
                    Result.failure(IllegalStateException("empty response"))
                } else {
                    Result.success(text.trim())
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildRequest(body: JSONObject): Request {
        val builder = Request.Builder()
            .url("${apiBase.trimEnd('/')}/chat/completions")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (apiKey.isNotBlank()) {
            builder.addHeader("Authorization", "Bearer $apiKey")
        }
        return builder.build()
    }

    private fun message(role: String, text: String): JSONObject =
        JSONObject().apply {
            put("role", role)
            put("content", text)
        }

    private fun visionUserMessage(text: String, pngBytes: ByteArray): JSONObject {
        val image = Base64.encodeToString(pngBytes, Base64.NO_WRAP)
        return JSONObject().apply {
            put("role", "user")
            put(
                "content",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("type", "text")
                            put("text", text)
                        },
                    )
                    put(
                        JSONObject().apply {
                            put("type", "image_url")
                            put(
                                "image_url",
                                JSONObject().apply {
                                    put("url", "data:image/png;base64,$image")
                                },
                            )
                        },
                    )
                },
            )
        }
    }

    private fun parseSse(raw: String): String {
        val acc = StringBuilder()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) return@forEach
            val data = trimmed.removePrefix("data:").trim()
            if (data == "[DONE]") return@forEach
            try {
                val json = JSONObject(data)
                val delta = json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("delta")
                    ?.optString("content")
                    .orEmpty()
                acc.append(delta)
            } catch (_: Exception) {
            }
        }
        return acc.toString().trim()
    }

    private fun parseJson(raw: String): String {
        if (raw.isBlank()) return ""
        return try {
            val json = JSONObject(raw)
            val choice = json.optJSONArray("choices")?.optJSONObject(0)
            val finish = choice?.optString("finish_reason").orEmpty()
            if (finish == "length") {
                Log.w(TAG, "model reply truncated (finish_reason=length); raise max_tokens or disable reasoning")
            }
            choice
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
                .trim()
        } catch (_: Exception) {
            ""
        }
    }

    companion object {
        private const val TAG = "OracleClient"

        // Reasoning models must not burn tokens on hidden chain-of-thought.
        private const val REPLY_MAX_TOKENS = 2048
        private const val OCR_MAX_TOKENS = 1024

        private const val OCR_SYSTEM =
            "You are a handwriting transcription engine. Your only job is to read handwritten text " +
                "from images and output the exact words you see. Do not reason or plan aloud — " +
                "output only the final transcription.\n\n" +
                "Rules:\n" +
                "- The image shows black ink handwriting on a white page.\n" +
                "- Read every line, top to bottom, left to right.\n" +
                "- Output ONLY the transcribed text — no commentary, no quotes, no markdown, no preamble.\n" +
                "- Preserve the original language (do not translate).\n" +
                "- Include names, numbers, punctuation, and line breaks where natural.\n" +
                "- If a word is illegible, write [illegible] for that word only.\n" +
                "- If the page is blank or unreadable, output exactly: [unreadable]"

        private const val OCR_USER_PROMPT =
            "Transcribe all handwritten text in this image."

        private const val PERSONA =
            "You are a helpful assistant. Reply concisely and directly to the user's message. " +
                "Use the same language as the user. Do not use internal reasoning or planning — " +
                "write the complete final reply immediately."
    }
}