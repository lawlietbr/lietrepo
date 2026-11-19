package com.PobreFlix.extractor

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject

object PobreFlixExtractor {

    // Player do PobreFlix (novo): POST form em /wp-json/api/v1/player(|-resolve)
    // com "type=movie|episode&tmdb=<id>&season=&episode=". Hosts confirmados na
    // integração de terceiros ativa (nuvio-fontes-ptbr-plugin, nov/2026).
    private val HOSTS = listOf(
        "https://pobreflix.best",
        "https://pobreflix.boutique",
        "https://pobreflix.cab",
        "https://pobreflix.garden",
        "https://pobreflix.monster",
        "https://redeflixapi.click"
    )

    private val HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
        "Content-Type" to "application/x-www-form-urlencoded",
        "Referer" to "https://pobreflix.best/",
        "Origin" to "https://pobreflix.best"
    )

    private fun buildBody(tmdbId: Int, type: String, season: Int, episode: Int): Map<String, String> {
        return if (type == "filme") {
            mapOf("type" to "movie", "tmdb" to "$tmdbId", "season" to "", "episode" to "")
        } else {
            mapOf("type" to "episode", "tmdb" to "$tmdbId", "season" to "$season", "episode" to "$episode")
        }
    }

    suspend fun getStreams(
        tmdbId: Int,
        type: String,
        season: Int,
        episode: Int
    ): List<ExtractorLink> {
        val body = buildBody(tmdbId, type, season, episode)
        val found = mutableListOf<ExtractorLink>()
        val seen = mutableSetOf<String>()

        for (host in HOSTS) {
            for (path in listOf("/wp-json/api/v1/player-resolve", "/wp-json/api/v1/player")) {
                try {
                    val response = app.post(
                        url = "$host$path",
                        headers = HEADERS,
                        data = body
                    )
                    if (response.code != 200) continue
                    val text = response.text
                    if (text.isBlank() || !text.trimStart().startsWith("{")) continue
                    val json = JSONObject(text)
                    if (json.optBoolean("success", true) && json.has("message") && !json.has("url")) continue
                    collectLinks(json, seen, found)
                    if (found.isNotEmpty()) return found
                } catch (e: Exception) {
                    continue
                }
            }
        }
        return found
    }

    private fun collectLinks(node: Any?, seen: MutableSet<String>, out: MutableList<ExtractorLink>) {
        when (node) {
            is JSONObject -> {
                var label = node.optString("label").ifBlank { null }
                    ?: node.optString("name").ifBlank { null }
                    ?: node.optString("audio").ifBlank { null }
                    ?: node.optString("language").ifBlank { null }
                    ?: node.optString("quality").ifBlank { null }
                val keys = listOf("url", "videoUrl", "video", "file", "link", "src")
                for (key in keys) {
                    val value = node.optString(key)
                    if (value.startsWith("http") && seen.add(value)) {
                        addLink(value, label, out)
                    }
                }
                val keysIt = node.keys()
                while (keysIt.hasNext()) {
                    collectLinks(node.opt(keysIt.next()), seen, out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectLinks(node.opt(i), seen, out)
                }
            }
            is String -> {
                if (node.startsWith("http") &&
                    (node.contains(".m3u8") || node.contains(".mp4")) &&
                    seen.add(node)
                ) {
                    addLink(node, null, out)
                }
            }
        }
    }

    private fun addLink(url: String, label: String?, out: MutableList<ExtractorLink>) {
        val quality = Regex("(\\d{3,4})p?").find(label ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 0
        out.add(
            newExtractorLink(
                source = "PobreFlix",
                name = "PobreFlix" + (label?.let { " ($it)" } ?: ""),
                url = url,
                type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.quality = quality
                this.referer = "https://pobreflix.best/"
            }
        )
    }
}
