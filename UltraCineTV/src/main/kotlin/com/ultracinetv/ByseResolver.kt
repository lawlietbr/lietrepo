package com.ultracinetv

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class ByseResolver {
    private fun origin(url: String) = URI(url).let { "${it.scheme}://${it.authority}" }
    private fun decode(value: String) = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP)

    private suspend fun solve(nonce: String, difficulty: Int): Int = withContext(Dispatchers.Default) {
        require(difficulty in 0..20) { "Unsupported Byse challenge difficulty" }
        val started = System.nanoTime()
        var solution = 0
        while (!ByseProtocol.verify(nonce, solution, difficulty)) {
            solution++
            if (solution % 256 == 0) {
                currentCoroutineContext().ensureActive()
                require(System.nanoTime() - started < 90_000_000_000L) { "Byse challenge timed out" }
                yield()
            }
        }
        solution
    }

    suspend fun resolve(url: String, parent: String, label: String, subtitle: (SubtitleFile) -> Unit,
                        callback: (ExtractorLink) -> Unit): Boolean {
        val base = origin(url)
        val code = ByseProtocol.code(url)
        val details = JSONObject(app.get("$base/api/videos/$code/embed/details").text)
        val frame = details.getString("embed_frame_url")
        require(URI(frame).scheme == "https")
        val frameBase = origin(frame)
        val headers = mapOf("Content-Type" to "application/json", "Referer" to frame,
            "X-Embed-Origin" to URI(parent).host, "X-Embed-Referer" to parent, "X-Embed-Parent" to url)
        val challenge = JSONObject(app.post("$base/api/videos/$code/embed/captcha", json = emptyMap<String, String>(), headers = headers).text)
        val nonce = challenge.getString("pow_nonce")
        val difficulty = challenge.getInt("pow_difficulty")
        val solution = solve(nonce, difficulty)
        val verifyBody = mapOf("pow_token" to challenge.getString("pow_token"), "solution" to solution.toString())
        val verified = JSONObject(app.post("$base/api/videos/$code/embed/captcha/verify", json = verifyBody, headers = headers).text)
        require(verified.optString("status") == "ok") { "Byse challenge rejected" }
        val playback = JSONObject(app.post("$frameBase/api/videos/$code/embed/playback",
            json = mapOf("fingerprint" to emptyMap<String, String>()), headers = headers + ("X-Captcha-Token" to verified.getString("token"))).text)
            .getJSONObject("playback")
        require(playback.getString("algorithm") == "AES-256-GCM")
        val parts = playback.getJSONArray("key_parts")
        val version = playback.optString("version").toIntOrNull()
        val key = ByseProtocol.keyIndexes(version, parts.length()).flatMap { decode(parts.getString(it)).toList() }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, decode(playback.getString("iv"))))
        val plain = JSONObject(String(cipher.doFinal(decode(playback.getString("payload"))), Charsets.UTF_8))
        val sources = plain.getJSONArray("sources")
        var emitted = 0
        for (i in 0 until sources.length()) {
            val source = sources.getJSONObject(i)
            val stream = source.getString("url")
            require(URI(stream).scheme == "https")
            val mediaHeaders = mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "$frameBase/")
            val manifest = app.get(stream, headers = mediaHeaders).text
            require(manifest.trimStart().startsWith("#EXTM3U")) { "Byse did not return HLS" }
            val height = ByseProtocol.masterQuality(manifest).takeIf { it > 0 }
                ?: source.optInt("height", Qualities.Unknown.value)
            callback(newExtractorLink("UltraCineTV", "Byse • $label • Auto", stream, ExtractorLinkType.M3U8) {
                quality = height; referer = "$frameBase/"; this.headers = mediaHeaders
            })
            emitted++
        }
        val tracks = plain.optJSONArray("tracks")
        if (tracks != null) for (i in 0 until tracks.length()) {
            val track = tracks.getJSONObject(i)
            subtitle(SubtitleFile(track.optString("language", "Legenda"), track.getString("url")))
        }
        return emitted > 0
    }
}
