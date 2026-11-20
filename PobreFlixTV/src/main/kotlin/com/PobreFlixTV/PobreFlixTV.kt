package com.PobreFlixTV

import android.content.Context
import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLEncoder

@CloudstreamPlugin
class PobreFlixTVPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PobreFlixTV())
    }
}

class PobreFlixTV : MainAPI() {
    override var mainUrl = "https://www.pobreflixtvfree.net"
    override var name = "PobreFlixTV"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Cartoon)
    override val usesWebView = false

    companion object {
        private const val PLENOFLU_URL = "https://plenoflu.com"
        private const val VAIQUECOL_URL = "https://vaiquecol.com"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/filmes-online" to "Filmes",
        "$mainUrl/series-online" to "Séries",
        "$mainUrl/lancamentos/2026" to "Lançamentos 2026",
        "$mainUrl/genero/animacao" to "Animação & Desenhos",
        "$mainUrl/genero/familia" to "Família",
        "$mainUrl/genero/acao" to "Ação",
        "$mainUrl/genero/aventura" to "Aventura",
        "$mainUrl/genero/comedia" to "Comédia",
        "$mainUrl/genero/drama" to "Drama",
        "$mainUrl/genero/fantasia" to "Fantasia",
        "$mainUrl/genero/ficcao-cientifica" to "Ficção Científica",
        "$mainUrl/genero/suspense" to "Suspense",
        "$mainUrl/genero/terror" to "Terror"
    )

    private fun defaultHeaders(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer
    )

    private fun cleanPageTitle(raw: String): String {
        return raw
            .replace(Regex("""^Assistir\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Online\s+Gr[áa]tis.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Online.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\(\d{4}\)\s*$"""), "")
            .trim()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page > 1) "$base/pagina/$page" else base

        val doc = try {
            app.get(url, headers = defaultHeaders()).document
        } catch (e: Exception) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            throw e
        }

        val items = doc.select("a.serie-card, a[href^='/filme/'], a[href^='/serie/']")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val hasNext = doc.select("a[href*='/pagina/${page + 1}']").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val rawHref = attr("href").trim()
        if (rawHref.isBlank() || rawHref.contains("/temporada-") || rawHref.contains("/episodio-")) return null
        if (!rawHref.contains("/filme/") && !rawHref.contains("/serie/")) return null

        val href = fixUrl(rawHref)
        val img = selectFirst("img")
        val title = selectFirst("h3, h2, .title, .serie-info h3")?.text()?.trim()
            ?.ifBlank { img?.attr("alt")?.trim() }
            ?: img?.attr("alt")?.trim()
            ?: text().trim()
        if (title.isBlank()) return null

        val poster = img?.let {
            val src = it.attr("data-src").ifBlank { it.attr("src") }
            if (src.isNotBlank() && !src.contains("sem-capa")) fixUrl(src) else null
        }

        val isSeries = rawHref.contains("/serie/")
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/buscar?q=$encoded", headers = defaultHeaders()).document

        return doc.select("a.serie-card, a[href^='/filme/'], a[href^='/serie/']")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val resp = app.get(url, headers = defaultHeaders())
        val html = resp.text
        val doc = resp.document

        val rawTitle = doc.selectFirst("h1")?.text()?.trim() ?: doc.title()
        val title = cleanPageTitle(rawTitle)
        val year = Regex("""\((\d{4})\)""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        val poster = doc.selectFirst("img[src*='image.tmdb.org/t/p/w342'], img[src*='image.tmdb.org/t/p/w500'], meta[property='og:image']")
            ?.let { it.attr("src").ifBlank { it.attr("content") } }
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }

        val plot = doc.selectFirst(".sinopse, .descricao, .movie-description, meta[name='description']")
            ?.let { it.text().ifBlank { it.attr("content") } }
            ?.trim()

        val isSeries = url.contains("/serie/")

        if (isSeries) {
            val epRegex = Regex("""/temporada-(\d+)/episodio-(\d+)""")
            val episodes = doc.select("a[href*='/temporada-'][href*='/episodio-']")
                .mapNotNull { a ->
                    val epHref = fixUrl(a.attr("href"))
                    val m = epRegex.find(epHref) ?: return@mapNotNull null
                    val s = m.groupValues[1].toIntOrNull() ?: 1
                    val e = m.groupValues[2].toIntOrNull() ?: 1
                    Triple(s, e, epHref)
                }
                .distinctBy { it.first to it.second }
                .sortedWith(compareBy({ it.first }, { it.second }))
                .map { (s, e, epHref) ->
                    newEpisode(epHref) {
                        this.name = "Episódio $e"
                        this.season = s
                        this.episode = e
                    }
                }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        val plenofluUrl = Regex("""https?://plenoflu\.com/movie/[^"'\s<>]+""").find(html)?.value ?: url
        return newMovieLoadResponse(title, url, TvType.Movie, plenofluUrl) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    private suspend fun extractFromVaiQueCol(
        embedUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val embedHtml = app.get(embedUrl, headers = defaultHeaders("$PLENOFLU_URL/")).text
            val hash = Regex("""\|([a-f0-9]{32})\|FirePlayer""").find(embedHtml)?.groupValues?.get(1)
                ?: Regex("""FirePlayer\(\s*["']([a-f0-9]{32})["']""").find(embedHtml)?.groupValues?.get(1)
                ?: return false

            val apiResp = app.post(
                url = "$VAIQUECOL_URL/player/index.php?data=$hash&do=getVideo",
                data = mapOf(
                    "hash" to hash,
                    "r" to "$PLENOFLU_URL/"
                ),
                referer = embedUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Origin" to VAIQUECOL_URL,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type" to "application/x-www-form-urlencoded"
                )
            ).text

            val json = JSONObject(apiResp)
            val streamUrl = json.optString("securedLink").ifBlank { json.optString("videoSource") }
                .replace("\\/", "/")
                .trim()
            if (streamUrl.isBlank()) return false

            val isHls = streamUrl.contains(".m3u8", ignoreCase = true) || json.optBoolean("hls", false)
            callback(
                newExtractorLink(
                    source = label,
                    name = label,
                    url = streamUrl,
                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$VAIQUECOL_URL/"
                    this.headers = mapOf(
                        "Referer" to "$VAIQUECOL_URL/",
                        "Origin" to VAIQUECOL_URL,
                        "User-Agent" to USER_AGENT
                    )
                }
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val plenofluUrl = if (data.contains("plenoflu.com")) {
            data
        } else {
            val pageHtml = app.get(data, headers = defaultHeaders()).text
            Regex("""https?://plenoflu\.com/(?:movie|tvshow)/[^"'\s<>]+""").find(pageHtml)?.value
                ?: return false
        }

        // 1. Tenta derivar direto a URL do VaiQueCol (funciona para filmes e episódios de séries)
        val tvMatch = Regex("""plenoflu\.com/tvshow/(\d+)/(\d+)/(\d+)""").find(plenofluUrl)
        if (tvMatch != null) {
            val tmdbId = tvMatch.groupValues[1]
            val s = tvMatch.groupValues[2]
            val e = tvMatch.groupValues[3]
            val directEmbed = "$VAIQUECOL_URL/embed2/$tmdbId-$s-$e"
            if (extractFromVaiQueCol(directEmbed, "$name - Dublado/Dual", callback)) {
                return true
            }
        }

        val movieMatch = Regex("""plenoflu\.com/movie/([^/?#]+)""").find(plenofluUrl)
        if (movieMatch != null) {
            val movieId = movieMatch.groupValues[1]
            val directEmbed = "$VAIQUECOL_URL/embed2/$movieId"
            if (extractFromVaiQueCol(directEmbed, "$name - Dublado/Dual", callback)) {
                return true
            }
        }

        // 2. Fallback via Plenoflu /api (action=getPlayer)
        return try {
            val plenoHtml = app.get(plenofluUrl, headers = defaultHeaders()).text
            val vaiQueIds = Regex("""data-id="([^"]*vaiquecol[^"]*)"""").findAll(plenoHtml)
                .map { it.groupValues[1] }
                .distinct()
                .toList()

            var found = false
            for (vid in vaiQueIds) {
                val respText = app.post(
                    url = "$PLENOFLU_URL/api",
                    data = mapOf("action" to "getPlayer", "video_id" to vid),
                    referer = plenofluUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded"
                    )
                ).text
                val b64 = JSONObject(respText).optJSONObject("data")?.optString("video_url").orEmpty()
                if (b64.isBlank()) continue
                val decodedUrl = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8).trim()
                if (decodedUrl.contains("vaiquecol.com") && extractFromVaiQueCol(decodedUrl, "$name - Dublado/Dual", callback)) {
                    found = true
                }
            }
            found
        } catch (_: Exception) {
            false
        }
    }
}
