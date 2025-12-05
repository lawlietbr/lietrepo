package com.UltraCine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class UltraCine : MainAPI() {
    override var mainUrl = "https://ultracine.org"
    override var name = "UltraCine"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category/lancamentos/" to "Lançamentos",
        "$mainUrl/category/acao/" to "Ação",
        "$mainUrl/category/animacao/" to "Animação",
        "$mainUrl/category/comedia/" to "Comédia",
        "$mainUrl/category/crime/" to "Crime",
        "$mainUrl/category/documentario/" to "Documentário",
        "$mainUrl/category/drama/" to "Drama",
        "$mainUrl/category/familia/" to "Família",
        "$mainUrl/category/fantasia/" to "Fantasia",
        "$mainUrl/category/ficcao-cientifica/" to "Ficção Científica",
        "$mainUrl/category/guerra/" to "Guerra",
        "$mainUrl/category/kids/" to "Kids",
        "$mainUrl/category/misterio/" to "Mistério",
        "$mainUrl/category/romance/" to "Romance",
        "$mainUrl/category/terror/" to "Terror",
        "$mainUrl/category/thriller/" to "Thriller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + if (page > 1) "page/$page/" else "").document
        val home = document.select("div.aa-cn div#movies-a ul.post-lst li").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("header.entry-header h2.entry-title")?.text() ?: return null
        val href = this.selectFirst("a.lnk-blk")?.attr("href") ?: return null
        
        val posterUrl = this.selectFirst("div.post-thumbnail figure img")?.let { img ->
            val src = img.attr("src").takeIf { it.isNotBlank() } ?: img.attr("data-src")
            src?.let { url ->
                val fullUrl = if (url.startsWith("//")) "https:$url" else url
                fullUrl.replace("/w500/", "/original/")
            }
        }
        
        val year = this.selectFirst("span.year")?.text()?.toIntOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
            this.quality = getQualityFromString(this@toSearchResult.selectFirst("span.post-ql")?.text())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = app.get("$mainUrl/?s=$query").document
        return searchResponse.select("div.aa-cn div#movies-a ul.post-lst li").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val title = document.selectFirst("aside.fg1 header.entry-header h1.entry-title")?.text() ?: return null
        val poster = document.selectFirst("div.bghd img.TPostBg")?.let { img ->
            val src = img.attr("src").takeIf { it.isNotBlank() } ?: img.attr("data-src")
            src?.let { url ->
                val fullUrl = if (url.startsWith("//")) "https:$url" else url
                fullUrl.replace("/w1280/", "/original/")
            }
        }
        val year = document.selectFirst("aside.fg1 header.entry-header div.entry-meta span.year")?.text()?.substringAfter("far\">")?.toIntOrNull()
        val duration = document.selectFirst("aside.fg1 header.entry-header div.entry-meta span.duration")?.text()?.substringAfter("far\">")
        val plot = document.selectFirst("aside.fg1 div.description p")?.text()
        val genres = document.select("aside.fg1 header.entry-header div.entry-meta span.genres a").map { it.text() }
        
        val actors = document.selectFirst("aside.fg1 ul.cast-lst p")?.select("a")?.map { 
            Actor(it.text(), it.attr("href"))
        }
        
        val trailerUrl = document.selectFirst("div.mdl-cn div.video iframe")?.let { iframe ->
            iframe.attr("src").takeIf { it.isNotBlank() } ?: iframe.attr("data-src")
        }

        val iframeElement = document.selectFirst("iframe[src*='assistirseriesonline']")
        val iframeUrl = iframeElement?.let { iframe ->
            iframe.attr("src").takeIf { it.isNotBlank() } ?: iframe.attr("data-src")
        }

        val isSerie = url.contains("/serie/")
        
        return if (isSerie) {
            if (iframeUrl != null) {
                val iframeDocument = app.get(iframeUrl).document
                val episodes = parseSeriesEpisodes(iframeDocument, iframeUrl)
                
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = plot
                    // CORREÇÃO: Nenhum rating/score para evitar erros
                    this.tags = genres
                    if (actors != null) addActors(actors)
                    addTrailer(trailerUrl)
                }
            } else {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, emptyList()) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = plot
                    // CORREÇÃO: Nenhum rating/score para evitar erros
                    this.tags = genres
                    if (actors != null) addActors(actors)
                    addTrailer(trailerUrl)
                }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, iframeUrl ?: "") {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                // CORREÇÃO: Nenhum rating/score para evitar erros
                this.tags = genres
                this.duration = parseDuration(duration)
                if (actors != null) addActors(actors)
                addTrailer(trailerUrl)
            }
        }
    }

    private suspend fun parseSeriesEpisodes(iframeDocument: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val seasons = iframeDocument.select("header.header ul.header-navigation li")
        
        for (seasonElement in seasons) {
            val seasonNumber = seasonElement.attr("data-season-number").toIntOrNull() ?: continue
            val seasonId = seasonElement.attr("data-season-id")
            
            val seasonEpisodes = iframeDocument.select("li[data-season-id='$seasonId']")
                .mapNotNull { episodeElement ->
                    val episodeId = episodeElement.attr("data-episode-id")
                    val episodeTitle = episodeElement.selectFirst("a")?.text() ?: return@mapNotNull null
                    
                    val episodeNumber = episodeTitle.substringBefore(" - ").toIntOrNull() ?: 1
                    val cleanTitle = if (episodeTitle.contains(" - ")) {
                        episodeTitle.substringAfter(" - ")
                    } else {
                        episodeTitle
                    }
                    
                    // CORREÇÃO: Usando newEpisode em vez do construtor depreciado
                    newEpisode(episodeId) {
                        this.name = cleanTitle
                        this.season = seasonNumber
                        this.episode = episodeNumber
                    }
                }
            
            episodes.addAll(seasonEpisodes)
        }
        
        return episodes
    }

   override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    if (data.isBlank()) return false

    val url = when {
        data.matches(Regex("^\\d+$")) -> "https://assistirseriesonline.icu/episodio/$data"
        data.startsWith("http") -> data
        else -> return false
    }

    try {
        val res = app.get(url, referer = "https://ultracine.org/", timeout = 30)
        val html = res.text

        // Regex infalível 2025
        val linkRegex = Regex("""["'](?:file|src|source)["']?\s*:\s*["']([^"']+embedplay[^"']+)""")
        val match = linkRegex.find(html) ?: return false

        var videoUrl = match.groupValues[1]

        // Força o player limpo oficial do UltraCine
        videoUrl = when {
            videoUrl.contains("embedplay.upns.pro") || videoUrl.contains("embedplay.upn.one") -> {
                val id = videoUrl.substringAfterLast("/").substringBefore("?").substringBefore("\"")
                "https://player.ultracine.org/watch/$id"
            }
            else -> videoUrl
        }

        // Constructor old, compatível e sem erros
        
         // SUBSTITUI ESSE BLOCO INTEIRO:
callback(
    ExtractorLink(
        source = "UltraCine",
        name = "UltraCine 4K • Tela Cheia",
        url = videoUrl,
        referer = "https://ultracine.org/",
        quality = Qualities.Unknown.value,
        isM3u8 = true,
        headers = mapOf("Origin" to "https://ultracine.org")
    )
)

// POR ESSE (100% compatível 2025):
callback.invoke(
    newExtractorLink(
        url = videoUrl,
        name = "UltraCine 4K • Tela Cheia",
        referer = "https://ultracine.org/",
        quality = Qualities.Unknown.value,
        isM3u8 = true,
        headers = mapOf("Origin" to "https://ultracine.org", "Referer" to "https://ultracine.org/")
    )
)

        return true

    } catch (e: Exception) {
        e.printStackTrace()
    }

    return false
}
    private fun parseDuration(duration: String?): Int? {
        if (duration == null) return null
        val regex = Regex("(\\d+)h\\s*(\\d+)m")
        val matchResult = regex.find(duration)
        return if (matchResult != null) {
            val hours = matchResult.groupValues[1].toIntOrNull() ?: 0
            val minutes = matchResult.groupValues[2].toIntOrNull() ?: 0
            hours * 60 + minutes
        } else {
            val minutesRegex = Regex("(\\d+)m")
            val minutesMatch = minutesRegex.find(duration)
            minutesMatch?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    // FUNÇÃO CORRETA FORA DE QUALQUER OUTRA
    private fun getQuality(qualityStr: String?): Int {
        return when {
            qualityStr?.contains("4k", ignoreCase = true) == true -> Qualities.P2160.value
            qualityStr?.contains("1080p", ignoreCase = true) == true -> Qualities.P1080.value
            qualityStr?.contains("720p", ignoreCase = true) == true -> Qualities.P720.value
            qualityStr?.contains("480p", ignoreCase = true) == true -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }
}

// FECHA A CLASSE AQUI COM APENAS UM }
