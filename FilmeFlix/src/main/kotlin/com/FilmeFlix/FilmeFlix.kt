package com.FilmeFlix

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLEncoder

@CloudstreamPlugin
class FilmeFlixPlugin : Plugin() {
    private var http: AbyssHttp? = null
    private var proxy: AbyssProxy? = null

    override fun load(context: Context) {
        val upstream = AbyssHttp()
        val server = AbyssProxy(upstream::fetch)
        http = upstream
        proxy = server
        registerMainAPI(FilmeFlix(server))
    }

    override fun beforeUnload() {
        proxy?.close()
        http?.close()
        proxy = null
        http = null
    }
}

class FilmeFlix internal constructor(private val proxy: AbyssProxy) : MainAPI() {
    override var mainUrl = "https://filmeflix.space"
    override var name = "FilmeFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Cartoon)
    override val usesWebView = false

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/filmes/" to "Filmes",
        "$mainUrl/series/" to "Séries",
        "$mainUrl/generos/animacao/" to "Animação",
        "$mainUrl/generos/acao/" to "Ação",
        "$mainUrl/generos/aventura/" to "Aventura",
        "$mainUrl/generos/comedia/" to "Comédia",
        "$mainUrl/generos/drama/" to "Drama",
        "$mainUrl/generos/familia/" to "Família",
        "$mainUrl/generos/fantasia/" to "Fantasia",
        "$mainUrl/generos/ficcao-cientifica/" to "Ficção Científica",
        "$mainUrl/generos/crime/" to "Crime",
        "$mainUrl/generos/documentario/" to "Documentário"
    )

    private fun defaultHeaders(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer
    )

    private fun upgradeTmdbPoster(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return fixUrl(raw.trim())
            .replace("/w92/", "/w500/")
            .replace("/w154/", "/w500/")
            .replace("/w185/", "/w500/")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page > 1) "$base/page/$page/" else "$base/"

        val doc = try {
            app.get(url, headers = defaultHeaders()).document
        } catch (e: Exception) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            throw e
        }

        val items = doc.select("article.item")
            .mapNotNull { it.toListingSearchResult() }
            .distinctBy { it.url }

        val hasNext = doc.select("a.arrow_pag[href*='/page/${page + 1}'], .pagination a[href*='/page/${page + 1}'], link[rel=next]").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext)
    }

    private fun Element.toListingSearchResult(): SearchResponse? {
        val link = selectFirst("a[href]") ?: return null
        val href = fixUrl(link.attr("href"))
        if (!href.contains("/filmes/") && !href.contains("/series/")) return null

        val img = selectFirst("img")
        val title = selectFirst("h3.title, h3, .data h3")?.text()?.trim()
            ?.ifBlank { img?.attr("alt")?.trim() }
            ?: img?.attr("alt")?.trim()
            ?: return null
        if (title.isBlank()) return null

        val poster = upgradeTmdbPoster(img?.attr("data-src")?.ifBlank { img.attr("src") })
        val isSeries = href.contains("/series/")

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
        val doc = app.get("$mainUrl/?s=$encoded", headers = defaultHeaders()).document

        return doc.select("div.result-item")
            .mapNotNull { item ->
                val link = item.selectFirst(".title a[href], a[href]") ?: return@mapNotNull null
                val href = fixUrl(link.attr("href"))
                if (!href.contains("/filmes/") && !href.contains("/series/")) return@mapNotNull null

                val img = item.selectFirst("img")
                val title = link.text().trim()
                    .ifBlank { img?.attr("alt")?.trim().orEmpty() }
                if (title.isBlank()) return@mapNotNull null

                val poster = upgradeTmdbPoster(img?.attr("data-src")?.ifBlank { img.attr("src") })
                val isSeries = href.contains("/series/")

                if (isSeries) {
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                } else {
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = poster
                    }
                }
            }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = defaultHeaders()).document

        val title = doc.selectFirst(".sheader .data h1, h1")?.text()?.trim()
            ?.ifBlank { doc.title().substringBefore(" - ").trim() }
            ?: "Título"

        val poster = upgradeTmdbPoster(
            doc.selectFirst(".sheader .poster img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
        )

        val plot = doc.selectFirst(".wp-content p, #info .wp-content, meta[name='description']")
            ?.let { it.text().ifBlank { it.attr("content") } }
            ?.trim()

        val year = doc.selectFirst(".sheader .data .date, .extra .date")?.text()
            ?.let { Regex("""\b(19\d{2}|20\d{2})\b""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val genres = doc.select(".sgeneros a").map { it.text().trim() }.filter { it.isNotBlank() }

        val isSeries = url.contains("/series/")
        if (isSeries) {
            val episodes = doc.select("ul.episodios li").mapNotNull { li ->
                val epLink = li.selectFirst(".episodiotitle a[href], a[href*='/episodios/']") ?: return@mapNotNull null
                val epUrl = fixUrl(epLink.attr("href"))
                val epTitle = epLink.text().trim()

                val numText = li.selectFirst(".numerando")?.text().orEmpty()
                val numMatch = Regex("""(\d+)\s*[-xX]\s*(\d+)""").find(numText)
                    ?: Regex("""(\d+)x(\d+)""", RegexOption.IGNORE_CASE).find(epUrl)
                val seasonNum = numMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum = numMatch?.groupValues?.get(2)?.toIntOrNull() ?: 1

                val epPoster = upgradeTmdbPoster(
                    li.selectFirst(".imagen img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                )

                newEpisode(epUrl) {
                    this.name = epTitle.ifBlank { "Episódio $epNum" }
                    this.season = seasonNum
                    this.episode = epNum
                    this.posterUrl = epPoster
                }
            }.distinctBy { it.data }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres
        }
    }

    private suspend fun extractVidmoly(
        embedUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val resp = app.get(embedUrl, headers = defaultHeaders("$mainUrl/"))
            val html = resp.text.replace("\\/", "/")
            val m3u8Urls = Regex("""https?://[^"'\s<>\\]+\.m3u8[^"'\s<>\\]*""")
                .findAll(html)
                .map { it.value.trim() }
                .distinct()
                .toList()

            if (m3u8Urls.isEmpty()) return false

            m3u8Urls.forEach { m3u8 ->
                callback(
                    newExtractorLink(
                        source = label,
                        name = label,
                        url = m3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = resp.url
                        this.headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to resp.url
                        )
                    }
                )
            }
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
        val doc = try {
            app.get(data, headers = defaultHeaders()).document
        } catch (_: Exception) {
            return false
        }

        val options = doc.select(".dooplay_player_option")
        var foundAny = false

        for (opt in options) {
            val postId = opt.attr("data-post").trim()
            val nume = opt.attr("data-nume").trim()
            val dtype = opt.attr("data-type").trim()
            if (postId.isBlank() || nume.isBlank() || dtype.isBlank()) continue
            if (nume.equals("trailer", ignoreCase = true)) continue

            val optTitle = opt.selectFirst(".title")?.text()?.trim().orEmpty()
            val label = if (optTitle.isNotBlank()) "$name - $optTitle" else "$name - Player $nume"

            try {
                val ajaxText = app.post(
                    url = "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to postId,
                        "nume" to nume,
                        "type" to dtype
                    ),
                    referer = data,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded"
                    )
                ).text

                val embedUrl = JSONObject(ajaxText).optString("embed_url")
                    .replace("\\/", "/")
                    .trim()
                if (embedUrl.isBlank() || !embedUrl.startsWith("http")) continue
                if (embedUrl.contains("abyssplayer", ignoreCase = true)) {
                    if (!isCasting && AbyssResolver(proxy).resolve(embedUrl, data, optTitle.ifBlank { "Player $nume" }, callback)) {
                        foundAny = true
                    }
                    continue
                }

                if (embedUrl.contains("vidmoly", ignoreCase = true)) {
                    if (extractVidmoly(embedUrl, label, callback)) {
                        foundAny = true
                        continue
                    }
                }

                if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                    foundAny = true
                }
            } catch (_: Exception) {}
        }

        return foundAny
    }
}
