package com.Cinegato

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

@CloudstreamPlugin
class CinegatoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Cinegato())
    }
}

class Cinegato : MainAPI() {
    override var mainUrl = "https://www.stuartartsupply.com"
    override var name = "Cinegato"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Cartoon)
    override val usesWebView = false

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/assistir-filme" to "Filmes",
        "$mainUrl/Genero-do-filme/desenho-animado" to "Desenhos Animados",
        "$mainUrl/Genero-do-filme/acao" to "Ação",
        "$mainUrl/Genero-do-filme/comedia" to "Comédia",
        "$mainUrl/Genero-do-filme/drama" to "Drama",
        "$mainUrl/Genero-do-filme/terror" to "Terror",
        "$mainUrl/Genero-do-filme/suspense" to "Suspense",
        "$mainUrl/Genero-do-filme/ficcao-cientifica" to "Ficção Científica",
        "$mainUrl/Genero-do-filme/romance" to "Romance",
        "$mainUrl/Genero-do-filme/documentario" to "Documentário"
    )

    private fun defaultHeaders(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page > 1) "$base/page/$page" else base

        val doc = try {
            app.get(url, headers = defaultHeaders()).document
        } catch (e: Exception) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            throw e
        }

        val isCartoon = request.name.contains("Desenho", ignoreCase = true)
        val items = doc.select("a.cin2-film-card-a[href^='/filme/']")
            .mapNotNull { it.toSearchResult(isCartoon) }
            .distinctBy { it.url }

        val hasNext = doc.select("a[href*='/page/${page + 1}']").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext)
    }

    private fun Element.toSearchResult(isCartoon: Boolean = false): SearchResponse? {
        val rawHref = attr("href").trim()
        if (!rawHref.startsWith("/filme/")) return null

        val href = fixUrl(rawHref)
        val img = selectFirst("img")
        val title = attr("title").trim()
            .ifBlank { img?.attr("alt")?.trim().orEmpty() }
            .ifBlank { selectFirst("h3, h2, p, .title")?.text()?.trim().orEmpty() }
        if (title.isBlank()) return null

        val poster = img?.let {
            val src = it.attr("data-src").ifBlank { it.attr("src") }
            if (src.isNotBlank()) fixUrl(src) else null
        }

        return newMovieSearchResponse(title, href, if (isCartoon) TvType.Cartoon else TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val doc = app.get("$mainUrl/pesquisar/$encoded", headers = defaultHeaders()).document

        return doc.select("a.cin2-film-card-a[href^='/filme/']")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val resp = app.get(url, headers = defaultHeaders())
        val html = resp.text
        val doc = resp.document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?.ifBlank { doc.title().substringBefore(" - ").substringBefore("|").trim() }
            ?: "Filme"

        val poster = Regex("""https://img\d*\.fumdx\.com/image/[^"'\s<>\\]+""")
            .find(html)?.value
            ?.replace("\\u002F", "/")

        val plot = doc.selectFirst("meta[name='description']")?.attr("content")?.trim()
        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(doc.select(".cin2-detail-info, .info, h1 + div").text())
            ?.groupValues?.get(1)?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = try {
            app.get(data, headers = defaultHeaders()).text
        } catch (_: Exception) {
            return false
        }

        val unescaped = html
            .replace("&amp;", "&")
            .replace("\\u002F", "/")
            .replace("\\/", "/")

        val m3u8Urls = Regex("""https?://[^"'\s<>\\]+\.m3u8[^"'\s<>\\]*""")
            .findAll(unescaped)
            .map { it.value.trim() }
            .distinct()
            .toList()

        if (m3u8Urls.isEmpty()) return false

        m3u8Urls.forEachIndexed { idx, streamUrl ->
            val label = if (m3u8Urls.size > 1) "$name - HLS ${idx + 1}" else "$name - HD"
            callback(
                newExtractorLink(
                    source = label,
                    name = label,
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "$mainUrl/"
                    )
                }
            )
        }
        return true
    }
}
