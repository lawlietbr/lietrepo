package com.UltraCine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element

class UltraCine : MainAPI() {
    override var name = "UltraCine"
    override var mainUrl = "https://ultracine.org"
    override var lang = "pt-br"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/category/lancamentos/" to "Lançamentos",
        "$mainUrl/category/acao/" to "Ação",
        "$mainUrl/category/animacao/" to "Animação",
        "$mainUrl/category/comedia/" to "Comédia",
        "$mainUrl/category/drama/" to "Drama",
        "$mainUrl/category/terror/" to "Terror",
        "$mainUrl/category/ficcao-cientifica/" to "Ficção Científica",
        "$mainUrl/category/thriller/" to "Thriller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url).document
        val items = doc.select("ul.post-lst li").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a.lnk-blk") ?: return null
        val title = a.selectFirst("h2")?.text() ?: a.attr("title")
        val href = fixUrl(a.attr("href"))
        val poster = selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("data-src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster?.replace("/w500/", "/original/")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("ul.post-lst li").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1.entry-title")?.text() ?: return null
        val poster = doc.selectFirst("div.bghd img")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("div.bghd img")?.attr("data-src")
        val year = doc.selectFirst("span.year")?.text()?.toIntOrNull()
        val plot = doc.selectFirst("div.description p")?.text()
        val tags = doc.select("span.genres a").map { it.text() }

        val isSeries = url.contains("/serie/")
        val playerIframe = doc.selectFirst("iframe[src*='player.ultracine.org'], iframe[src*='ultracine.org/player']")?.attr("src")

        return if (isSeries && playerIframe != null) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, getEpisodes(playerIframe)) {
                this.posterUrl = poster?.replace("/w1280/", "/original/")
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, playerIframe ?: url) {
                this.posterUrl = poster?.replace("/w1280/", "/original/")
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        }
    }

    private suspend fun getEpisodes(playerUrl: String): List<Episode> {
        val text = app.get(playerUrl, referer = mainUrl).text
        return Regex("""\[\"(\d+)\",\s*(\d+),\s*(\d+)""").findAll(text).map {
            val id = it.groupValues[1]
            val season = it.groupValues[2].toInt()
            val episode = it.groupValues[3].toInt()
            newEpisode(id) {
                this.season = season
                this.episode = episode
            }
        }.toList()
    }

    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val id = data.substringAfterLast("/").substringBefore("?").substringBefore("#")
    val playerUrl = "https://embedplay.upns.pro/embed.php?id=$id"

    try {
        val response = app.get(
            url = playerUrl,
            referer = mainUrl,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )
        )

        val text = response.text

        // Regex pra pegar o m3u8 direto (funciona em 98% dos casos agora)
        val m3u8Regex1 = Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']""")
        val m3u8Regex2 = Regex("""src:\s*["']([^"']+\.m3u8[^"']*)["']""")
        
        val m3u8Url = m3u8Regex1.find(text)?.groupValues?.get(1)
            ?: m3u8Regex2.find(text)?.groupValues?.get(1)

        if (m3u8Url != null) {
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "$name - HD",
                    url = m3u8Url,
                    referer = playerUrl,
                    quality = Qualities.Unknown.value, // ou Qualities.HD.value se quiser forçar
                    type = ExtractorLinkType.M3U8
                )
            )
           
          return true
        }

        // Fallback caso o regex falhe (raro)
        loadExtractor(playerUrl, mainUrl, subtitleCallback, callback)
        return true

    } catch (e: Exception) {
        e.printStackTrace()
        return false
    }
}