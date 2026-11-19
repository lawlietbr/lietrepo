package com.PobreFlix.extractor

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject

/**
 * Player real do PobreFlix (nov/2026). O site embute o player do RedeFlixApi/FSHD
 * (https://fshd.link) — fluxo verificado ao vivo:
 *
 *   GET  https://fshd.link/filme/<tmdb>            (ou /serie/<tmdb>/<temp>/<ep>)
 *   POST https://fshd.link/api/options             {content_id, content_type: 2}  (so serie)
 *        -> {"options":[{"ID":162759,"type":1}]}
 *   POST https://fshd.link/api/players             {content_info, content_type, video_id}
 *        -> {"message":"Player Obtido com Sucesso!",
 *            "video_url":"https://fshd.link/video/<token-cifrado>"}
 *   GET  video_url -> wrapper 278B com window.location.href = "<embed do servidor>"
 *
 * Os embeds caem em hosts proprios (abyssplayer.com, 112234152.xyz, dood.li...).
 * Hosts conhecidos sao resolvidos pelos extractors nativos do app; os demais
 * passam pelo fallback generico de pagina (midia direta, scripts internos e
 * blobs atob).
 */
object PobreFlixExtractor {

    private const val FSHD = "https://fshd.link"

    private val HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
        "Referer" to "$FSHD/"
    )

    private val JSON_HEADERS = HEADERS + mapOf(
        "Content-Type" to "application/json",
        "X-Requested-With" to "XMLHttpRequest",
        "Origin" to FSHD
    )

    private val MEDIA_REGEX = Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)[^\s"'<>\\]*""")
    private val ATOB_REGEX = Regex("""atob\(["']([A-Za-z0-9+/=]{24,})["']\)""")

    private data class Server(val id: String, val lang: String, val name: String)

    suspend fun getStreams(
        tmdbId: Int,
        type: String,
        season: Int,
        episode: Int
    ): List<ExtractorLink> {
        val found = mutableListOf<ExtractorLink>()
        val seen = mutableSetOf<String>()
        try {
            val isMovie = type.equals("filme", true)
            val pageUrl = if (isMovie) {
                "$FSHD/filme/$tmdbId"
            } else {
                "$FSHD/serie/$tmdbId/$season/$episode"
            }
            val html = app.get(pageUrl, headers = HEADERS).text

            val contentInfo: String
            val contentType: String
            val servers = mutableListOf<Server>()

            if (isMovie) {
                contentInfo = Regex("CONTENT_INFO\\s*=\\s*'(\\d+)'").find(html)
                    ?.groupValues?.get(1) ?: return emptyList()
                contentType = Regex("CONTENT_TYPE\\s*=\\s*'(\\d+)'").find(html)
                    ?.groupValues?.get(1) ?: "1"
                Regex("class=\"server-selector\" data-id=\"(\\d+)\" data-lang=\"(\\d+)\"[\\s\\S]*?<b>([^<]+)</b>")
                    .findAll(html).forEach {
                        servers.add(Server(it.groupValues[1], it.groupValues[2], it.groupValues[3].trim()))
                    }
            } else {
                // lista de episodios embutida: div.episodeSelector[data-season=N]
                // com div.episodeOption[data-contentid] em ordem de episodio
                val block = Regex("episodeSelector[^>]*data-season=\"$season\"[\\s\\S]*?(?=episodeSelector|</body>)")
                    .find(html)?.value ?: html
                val ids = Regex("episodeOption[^>]*data-contentid=\"(\\d+)\"")
                    .findAll(block).map { it.groupValues[1] }.toList()
                val idx = episode - 1
                if (idx !in ids.indices) return emptyList()
                contentInfo = ids[idx]
                contentType = "2"
                val optResp = app.post(
                    "$FSHD/api/options",
                    headers = JSON_HEADERS,
                    json = mapOf("content_id" to contentInfo, "content_type" to 2)
                ).text
                val arr = JSONObject(optResp).optJSONArray("options") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    servers.add(Server(o.optLong("ID").toString(), contentType, "Servidor ${i + 1}"))
                }
            }

            for (srv in servers) {
                try {
                    val resp = app.post(
                        "$FSHD/api/players",
                        headers = JSON_HEADERS,
                        json = mapOf(
                            "content_info" to contentInfo,
                            "content_type" to contentType,
                            "video_id" to srv.id
                        )
                    ).text
                    val videoUrl = JSONObject(resp).optString("video_url")
                    if (videoUrl.isBlank()) continue
                    val wrapper = app.get(videoUrl, headers = HEADERS).text
                    val embed = Regex("window\\.location\\.href\\s*=\\s*\"([^\"]+)\"")
                        .find(wrapper)?.groupValues?.get(1) ?: continue
                    if (!seen.add(embed)) continue

                    val label = langLabel(srv.lang, srv.name)
                    val ok = loadExtractor(embed, FSHD, {}, { link -> found.add(link) })
                    if (!ok) {
                        tryGenericPage(embed, label, seen, found)
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        return found
    }

    private fun langLabel(lang: String, name: String): String {
        val l = when (lang) {
            "1" -> "Dublado"
            "2" -> "Legendado"
            else -> name
        }
        return if (l == name) name else "$name $l"
    }

    /**
     * Fallback para embeds sem extractor nativo: procura midia direta na pagina,
     * em scripts internos (ex.: scripts.php?v=) e em blobs atob("...").
     */
    private suspend fun tryGenericPage(
        url: String,
        label: String,
        seen: MutableSet<String>,
        found: MutableList<ExtractorLink>
    ) {
        try {
            val page = app.get(url, headers = HEADERS).text
            val urls = mutableListOf<String>()
            collectMedia(page, urls)

            val subScripts = Regex("""src=["']([^"']*(?:scripts\.php|/player/)[^"']*)["']""")
                .findAll(page).map { it.groupValues[1] }.distinct().take(3)
            for (s in subScripts) {
                val su = if (s.startsWith("http")) s else "${url.substringBefore("/", "https://x")}$s"
                try {
                    val sub = app.get(su, headers = HEADERS).text
                    collectMedia(sub, urls)
                } catch (_: Exception) {
                }
            }

            for (u in urls.distinct()) {
                if (!seen.add(u)) continue
                found.add(
                    newExtractorLink(
                        source = "PobreFlix",
                        name = "PobreFlix $label",
                        url = u,
                        type = if (u.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = 0
                        this.referer = url
                        this.headers = HEADERS
                    }
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun collectMedia(text: String, out: MutableList<String>) {
        MEDIA_REGEX.findAll(text).forEach { out.add(it.value) }
        ATOB_REGEX.findAll(text).forEach {
            try {
                val dec = String(Base64.decode(it.groupValues[1], Base64.DEFAULT))
                MEDIA_REGEX.findAll(dec).forEach { m -> out.add(m.value) }
            } catch (_: Exception) {
            }
        }
    }
}
