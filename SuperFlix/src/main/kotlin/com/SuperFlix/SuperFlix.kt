package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element
import java.net.URLEncoder

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/filmes" to "Filmes",
        "$mainUrl/series" to "Séries",
        "$mainUrl/lancamentos" to "Lançamentos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "?page=$page" else ""
        val document = app.get(url).document
        
        val home = mutableListOf<SearchResponse>()
        
        document.select("div.recs-grid a.rec-card, .movie-card, article, .item").forEach { element ->
            element.toSearchResult()?.let { home.add(it) }
        }
        
        if (home.isEmpty()) {
            document.select("a[href*='/filme/'], a[href*='/serie/']").forEach { link ->
                val href = link.attr("href")
                if (href.isNotBlank() && !href.contains("#")) {
                    val title = link.selectFirst("img")?.attr("alt")
                        ?: link.selectFirst(".rec-title, .title, h2, h3")?.text()
                        ?: href.substringAfterLast("/").replace("-", " ").replace(Regex("\\d{4}$"), "").trim()
                    
                    if (title.isNotBlank()) {
                        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                        val poster = link.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                        val isSerie = href.contains("/serie/")
                        
                        val searchResponse = if (isSerie) {
                            newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                                this.posterUrl = poster
                                this.year = year
                            }
                        } else {
                            newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                                this.posterUrl = poster
                                this.year = year
                            }
                        }
                        
                        home.add(searchResponse)
                    }
                }
            }
        }
        
        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".rec-title, .movie-title, h2, h3, .title")?.text()
            ?: selectFirst("img")?.attr("alt")
            ?: return null
            
        val href = attr("href") ?: selectFirst("a")?.attr("href") ?: return null
        
        val poster = selectFirst("img")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }
            ?: selectFirst("img")?.attr("data-src")?.let { fixUrl(it) }
        
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: selectFirst(".rec-meta, .movie-year, .year")?.text()?.let { 
                Regex("\\b(\\d{4})\\b").find(it)?.groupValues?.get(1)?.toIntOrNull() 
            }
        
        val isSerie = href.contains("/serie/")
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        return if (isSerie) {
            newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encodedQuery").document
        
        val results = mutableListOf<SearchResponse>()
        
        document.select("div.recs-grid a.rec-card, a[href*='/filme/'], a[href*='/serie/']").forEach { element ->
            element.toSearchResult()?.let { results.add(it) }
        }
        
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val html = document.html()

        val jsonLd = extractJsonLd(html)
        
        val title = jsonLd.title ?: document.selectFirst("h1, .title")?.text() ?: return null
        val year = jsonLd.year ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        
        val poster = jsonLd.posterUrl?.replace("/w500/", "/original/")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?.replace("/w500/", "/original/")
        
        val plot = jsonLd.description ?: document.selectFirst("meta[name='description']")?.attr("content")
            ?: document.selectFirst(".syn, .description")?.text()
        
        val tags = jsonLd.genres ?: document.select("a.chip, .chip").map { it.text() }
        
        val actors = jsonLd.actors?.map { Actor(it, "") } ?: emptyList()
        
        val director = jsonLd.director?.firstOrNull()
        
        val isSerie = url.contains("/serie/") || jsonLd.type == "TVSeries"
        
        return if (isSerie) {
            val episodes = extractEpisodesFromPage(document, url)
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                addActors(actors)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                addActors(actors)
            }
        }
    }

    private fun extractEpisodesFromPage(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        document.select("button.bd-play[data-url], button[data-url]").forEach { button ->
            val dataUrl = button.attr("data-url")
            val season = button.attr("data-season").toIntOrNull() ?: 1
            val episodeNum = button.attr("data-ep").toIntOrNull() ?: 1
            
            episodes.add(
                newEpisode(dataUrl) {
                    this.name = "Episódio $episodeNum"
                    this.season = season
                    this.episode = episodeNum
                }
            )
        }
        
        return episodes
    }

    private data class JsonLdInfo(
        val title: String? = null,
        val year: Int? = null,
        val posterUrl: String? = null,
        val description: String? = null,
        val genres: List<String>? = null,
        val director: List<String>? = null,
        val actors: List<String>? = null,
        val tmdbId: String? = null,
        val type: String? = null
    )

    private fun extractJsonLd(html: String): JsonLdInfo {
        val pattern = Regex("<script type=\"application/ld\\+json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
        val matches = pattern.findAll(html)
        
        matches.forEach { match ->
            try {
                val json = match.groupValues[1].trim()
                if (json.contains("\"@type\":\"Movie\"") || json.contains("\"@type\":\"TVSeries\"")) {
                    
                    val title = Regex("\"name\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    val image = Regex("\"image\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    val description = Regex("\"description\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    
                    val genresMatch = Regex("\"genre\":\\s*\\[([^\\]]+)\\]").find(json)
                    val genres = genresMatch?.groupValues?.get(1)
                        ?.split(",")
                        ?.map { it.trim().trim('"', '\'') }
                        ?.filter { it.isNotBlank() }
                    
                    val actorsMatch = Regex("\"actor\":\\s*\\[([^\\]]+)\\]").find(json)
                    val actors = actorsMatch?.groupValues?.get(1)
                        ?.split("},")
                        ?.mapNotNull { actor ->
                            Regex("\"name\":\"([^\"]+)\"").find(actor)?.groupValues?.get(1)
                        }
                    
                    val directorMatch = Regex("\"director\":\\s*\\[([^\\]]+)\\]").find(json)
                    val director = directorMatch?.groupValues?.get(1)
                        ?.split("},")
                        ?.mapNotNull { dir ->
                            Regex("\"name\":\"([^\"]+)\"").find(dir)?.groupValues?.get(1)
                        }
                    
                    val sameAsMatch = Regex("\"sameAs\":\\s*\\[([^\\]]+)\\]").find(json)
                    val tmdbId = sameAsMatch?.groupValues?.get(1)
                        ?.split(",")
                        ?.find { it.contains("themoviedb.org") }
                        ?.substringAfterLast("/")
                        ?.trim(' ', '"', '\'')
                    
                    val type = if (json.contains("\"@type\":\"Movie\"")) "Movie" else "TVSeries"
                    
                    return JsonLdInfo(
                        title = title,
                        year = null,
                        posterUrl = image,
                        description = description,
                        genres = genres,
                        director = director,
                        actors = actors,
                        tmdbId = tmdbId,
                        type = type
                    )
                }
            } catch (e: Exception) {
                // Continua
            }
        }
        
        return JsonLdInfo()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        
        return try {
            // 🔥 SE JÁ FOR LINK .m3u8 DIRETO
            if (data.contains(".m3u8") && data.contains("rcr22")) {
                val quality = extractQualityFromM3u8(data)
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name (${quality}p)",
                        url = data,
                        referer = mainUrl,
                        quality = quality,
                        isM3u8 = true
                    )
                )
                return true
            }
            
            // 🔥 SE FOR DATA-URL (botão play)
            if (data.contains("fembed.sx")) {
                // Tentar extrair vídeo da página do Fembed
                return extractFromFembedPage(data, callback)
            }
            
            // 🔥 SE FOR URL DO SUPERFLIX
            extractDirectVideoLinks(data, callback)
            
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    // 🔥 EXTRAIR VÍDEOS DE PÁGINA DO FEMBED
    private suspend fun extractFromFembedPage(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val res = app.get(url, referer = mainUrl, timeout = 30)
            val html = res.text
            
            // Procurar links .m3u8 em scripts do Fembed
            val patterns = listOf(
                Regex("""sources\s*:\s*\[([^\]]+)\]"""),
                Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""")
            )
            
            for (pattern in patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    var m3u8Url = if (match.groupValues.size > 1) match.groupValues[1] else match.value
                    
                    // Limpar URL se for JSON array
                    if (m3u8Url.contains("[")) {
                        val urlMatch = Regex("""https?://[^"',\s]+\.m3u8[^"',\s]*""").find(m3u8Url)
                        m3u8Url = urlMatch?.value ?: continue
                    }
                    
                    if (m3u8Url.isNotBlank()) {
                        val quality = extractQualityFromM3u8(m3u8Url)
                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = "$name (${quality}p)",
                                url = m3u8Url,
                                referer = mainUrl,
                                quality = quality,
                                isM3u8 = true
                            )
                        )
                        return true
                    }
                }
            }
            
            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    // 🔥 EXTRAIR LINKS DIRETOS DA PÁGINA
    private suspend fun extractDirectVideoLinks(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val res = app.get(url, referer = mainUrl, timeout = 30)
            val html = res.text
            
            // 🔥 PADRÃO 1: Links .m3u8 do servidor rcr22
            val m3u8Patterns = listOf(
                Regex("""(https?://[^"'\s]+rcr22[^"'\s]+\.m3u8[^"'\s]*)"""),
                Regex("""(https?://[^"'\s]+/hls2/[^"'\s]+\.m3u8[^"'\s]*)"""),
                Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""src\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""(https?://be[0-9]+\.rcr22\.[^"'\s]+\.m3u8[^"'\s]*)""")
            )
            
            for (pattern in m3u8Patterns) {
                val matches = pattern.findAll(html)
                for (match in matches) {
                    val m3u8Url = if (match.groupValues.size > 1) match.groupValues[1] else match.value
                    if (m3u8Url.isNotBlank()) {
                        val quality = extractQualityFromM3u8(m3u8Url)
                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = "$name (${quality}p)",
                                url = m3u8Url,
                                referer = mainUrl,
                                quality = quality,
                                isM3u8 = true
                            )
                        )
                        return true
                    }
                }
            }
            
            // 🔥 PADRÃO 2: JW Player config
            val jwPlayerPattern = Regex("""jwplayer\(["'][^"']+["']\)\.setup\(({[^}]+})\)""")
            val jwMatch = jwPlayerPattern.find(html)
            if (jwMatch != null) {
                val playerConfig = jwMatch.groupValues[1]
                val fileMatch = Regex("""file\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(playerConfig)
                val videoUrl = fileMatch?.groupValues?.get(1)
                
                if (videoUrl != null && videoUrl.contains(".m3u8")) {
                    val quality = extractQualityFromM3u8(videoUrl)
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "$name (${quality}p)",
                            url = videoUrl,
                            referer = mainUrl,
                            quality = quality,
                            isM3u8 = true
                        )
                    )
                    return true
                }
            }
            
            // 🔥 PADRÃO 3: Scripts com variáveis
            val scriptPatterns = listOf(
                Regex("""var\s+videoUrl\s*=\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""var\s+src\s*=\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""hlsUrl\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")
            )
            
            for (pattern in scriptPatterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val videoUrl = match.groupValues[1]
                    val quality = extractQualityFromM3u8(videoUrl)
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "$name (${quality}p)",
                            url = videoUrl,
                            referer = mainUrl,
                            quality = quality,
                            isM3u8 = true
                        )
                    )
                    return true
                }
            }
            
            false
            
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    // 🔥 FUNÇÃO PARA EXTRAIR QUALIDADE
    private fun extractQualityFromM3u8(url: String): Int {
        return when {
            url.contains("/2160/") || url.contains("2160p") -> 2160
            url.contains("/1080/") || url.contains("1080p") -> 1080
            url.contains("/720/") || url.contains("720p") -> 720
            url.contains("/480/") || url.contains("480p") -> 480
            url.contains("/360/") || url.contains("360p") -> 360
            else -> Qualities.Unknown.value
        }
    }
}