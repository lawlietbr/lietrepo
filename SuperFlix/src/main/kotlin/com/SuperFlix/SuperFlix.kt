package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
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
            val episodes = extractEpisodesFromButtons(document, url)

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                addActors(actors)
            }
        } else {
            // Para filmes, armazenamos a URL do iframe/button para uso posterior
            val videoData = findFembedUrl(document) ?: ""

            newMovieLoadResponse(title, url, TvType.Movie, videoData) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                addActors(actors)
            }
        }
    }

    private fun extractEpisodesFromButtons(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()

        document.select("button.bd-play[data-url]").forEach { button ->
            val fembedUrl = button.attr("data-url")
            val season = button.attr("data-season").toIntOrNull() ?: 1
            val episodeNum = button.attr("data-ep").toIntOrNull() ?: 1

            var episodeTitle = "Episódio $episodeNum"

            val parent = button.parents().find { it.hasClass("episode-item") || it.hasClass("episode") }
            parent?.let {
                val titleElement = it.selectFirst(".ep-title, .title, .name, h3, h4")
                if (titleElement != null) {
                    episodeTitle = titleElement.text().trim()
                }
            }

            episodes.add(
                newEpisode(fembedUrl) {
                    this.name = episodeTitle
                    this.season = season
                    this.episode = episodeNum
                }
            )
        }

        return episodes
    }

    private fun findFembedUrl(document: org.jsoup.nodes.Document): String? {
        // Primeiro, procurar por iframe do Fembed
        val iframe = document.selectFirst("iframe[src*='fembed']")
        if (iframe != null) {
            return iframe.attr("src")
        }

        // Procurar por botão de play com data-url
        val playButton = document.selectFirst("button.bd-play[data-url]")
        if (playButton != null) {
            return playButton.attr("data-url")
        }

        // Procurar por qualquer botão com URL do Fembed
        val anyButton = document.selectFirst("button[data-url*='fembed']")
        if (anyButton != null) {
            return anyButton.attr("data-url")
        }

        // Procurar no HTML por padrões
        val html = document.html()
        val patterns = listOf(
            Regex("""https?://[^"'\s]*fembed[^"'\s]*/e/\w+"""),
            Regex("""data-url=["'](https?://[^"']*fembed[^"']+)["']"""),
            Regex("""src\s*[:=]\s*["'](https?://[^"']*fembed[^"']+)["']""")
        )

        patterns.forEach { pattern ->
            pattern.find(html)?.let { match ->
                val url = if (match.groupValues.size > 1) match.groupValues[1] else match.value
                if (url.isNotBlank()) return url
            }
        }

        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("SuperFlix: Carregando links de: $data")
        
        // Extrair ID do vídeo (ex: 1152238 de https://fembed.sx/e/1152238)
        val videoId = data.substringAfterLast("/").substringBefore("?").trim()
        if (videoId.isEmpty()) {
            println("SuperFlix: ERRO - Não consegui extrair ID do vídeo")
            return false
        }
        
        println("SuperFlix: ID do vídeo: $videoId")
        
        return try {
            // Usar a API do Fembed para extrair links
            extractFembedLinks(videoId, subtitleCallback, callback)
        } catch (e: Exception) {
            println("SuperFlix: ERRO - ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private suspend fun extractFembedLinks(
        videoId: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("SuperFlix: Extraindo links via API do Fembed")
        
        val baseUrl = "https://fembed.sx"
        val apiUrl = "$baseUrl/api.php?s=$videoId&c="
        
        println("SuperFlix: API URL: $apiUrl")
        
        // Headers para simular navegador
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to baseUrl,
            "Origin" to baseUrl,
            "X-Requested-With" to "XMLHttpRequest"
        )
        
        // Primeiro, tentar obter o player para Dublado (DUB)
        println("SuperFlix: Tentando obter player Dublado...")
        
        val playerResponse = try {
            app.post(
                apiUrl,
                headers = headers,
                data = mapOf(
                    "action" to "getPlayer",
                    "lang" to "DUB",
                    "key" to "MA==" // base64 de "0"
                )
            )
        } catch (e: Exception) {
            println("SuperFlix: Falha no POST, tentando GET...")
            // Tentar via GET
            app.get("$apiUrl&action=getPlayer&lang=DUB&key=MA==", headers = headers)
        }
        
        if (!playerResponse.isSuccessful) {
            println("SuperFlix: Falha ao obter player: ${playerResponse.code}")
            return false
        }
        
        val playerHtml = playerResponse.text
        println("SuperFlix: Player HTML obtido (${playerHtml.length} chars)")
        
        // Analisar o HTML do player para encontrar URLs de vídeo
        val videoUrls = extractVideoUrlsFromPlayer(playerHtml)
        
        if (videoUrls.isNotEmpty()) {
            println("SuperFlix: Encontradas ${videoUrls.size} URLs de vídeo")
            
            videoUrls.forEachIndexed { index, (url, quality) ->
                println("SuperFlix: Vídeo $index -> ${quality}p: $url")
                
                // Criar ExtractorLink usando a versão mais compatível
                val extractorLink = ExtractorLink(
                    name = "$name (Dublado)",
                    source = name,
                    url = url,
                    referer = "https://fembed.sx/e/$videoId",
                    quality = quality,
                    isM3u8 = url.contains(".m3u8")
                )
                callback(extractorLink)
            }
            
            // Também tentar Legendado
            println("SuperFlix: Tentando obter links Legendados...")
            tryGetLegendas(videoId, subtitleCallback, callback)
            
            return true
        }
        
        // Se não encontrou no HTML, tentar via download
        println("SuperFlix: Nenhum vídeo encontrado, tentando via download...")
        return tryViaDownloadApi(videoId, apiUrl, headers, callback)
    }

    private fun extractVideoUrlsFromPlayer(html: String): List<Pair<String, Int>> {
        val videoUrls = mutableListOf<Pair<String, Int>>()
        
        // Padrões comuns para encontrar URLs de vídeo
        val patterns = listOf(
            Regex("""src\s*=\s*["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""file\s*:\s*["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex(""""(https?://[^"]+\.(?:mp4|m3u8)[^"]*)""""),
            Regex("""source\s+src\s*=\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in patterns) {
            val matches = pattern.findAll(html)
            matches.forEach { match ->
                var url = if (match.groupValues.size > 1) match.groupValues[1] else match.value
                url = url.trim()
                
                if (url.isNotBlank() && (url.contains(".mp4") || url.contains(".m3u8"))) {
                    // Determinar qualidade
                    val quality = when {
                        url.contains("1080") || url.contains("fullhd") -> Qualities.P1080.value
                        url.contains("720") || url.contains("hd") -> Qualities.P720.value
                        url.contains("480") || url.contains("sd") -> Qualities.P480.value
                        url.contains("360") -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                    
                    videoUrls.add(Pair(url, quality))
                }
            }
        }
        
        return videoUrls.distinctBy { it.first }
    }

    private suspend fun tryGetLegendas(
        videoId: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val baseUrl = "https://fembed.sx"
        val apiUrl = "$baseUrl/api.php?s=$videoId&c="
        
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to baseUrl
        )
        
        try {
            val legResponse = app.post(
                apiUrl,
                headers = headers,
                data = mapOf(
                    "action" to "getPlayer",
                    "lang" to "LEG",
                    "key" to "MA==" // base64 de "0"
                )
            )
            
            if (legResponse.isSuccessful) {
                val legHtml = legResponse.text
                val legVideos = extractVideoUrlsFromPlayer(legHtml)
                
                legVideos.forEach { (url, quality) ->
                    println("SuperFlix: Legendado encontrado -> ${quality}p: $url")
                    
                    val extractorLink = ExtractorLink(
                        name = "$name (Legendado)",
                        source = name,
                        url = url,
                        referer = "https://fembed.sx/e/$videoId",
                        quality = quality,
                        isM3u8 = url.contains(".m3u8")
                    )
                    callback(extractorLink)
                }
            }
        } catch (e: Exception) {
            println("SuperFlix: Não consegui obter legendados: ${e.message}")
        }
    }

    private suspend fun tryViaDownloadApi(
        videoId: String,
        apiUrl: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("SuperFlix: Tentando API de download...")
        
        try {
            // Tentar obter link de download Dublado
            val dubResponse = app.get(
                "$apiUrl&action=getDownload&key=0&lang=DUB",
                headers = headers
            )
            
            if (dubResponse.isSuccessful) {
                val json = dubResponse.parsedSafe<Map<String, Any>>()
                val downloadUrl = json?.get("url") as? String
                
                if (downloadUrl != null && downloadUrl.isNotBlank()) {
                    println("SuperFlix: Download Dublado encontrado: $downloadUrl")
                    
                    val extractorLink = ExtractorLink(
                        name = "$name (Download Dublado)",
                        source = name,
                        url = downloadUrl,
                        referer = "https://fembed.sx/e/$videoId",
                        quality = Qualities.P1080.value,
                        isM3u8 = downloadUrl.contains(".m3u8")
                    )
                    callback(extractorLink)
                    return true
                }
            }
            
            // Tentar Legendado também
            val legResponse = app.get(
                "$apiUrl&action=getDownload&key=0&lang=LEG",
                headers = headers
            )
            
            if (legResponse.isSuccessful) {
                val json = legResponse.parsedSafe<Map<String, Any>>()
                val downloadUrl = json?.get("url") as? String
                
                if (downloadUrl != null && downloadUrl.isNotBlank()) {
                    println("SuperFlix: Download Legendado encontrado: $downloadUrl")
                    
                    val extractorLink = ExtractorLink(
                        name = "$name (Download Legendado)",
                        source = name,
                        url = downloadUrl,
                        referer = "https://fembed.sx/e/$videoId",
                        quality = Qualities.P1080.value,
                        isM3u8 = downloadUrl.contains(".m3u8")
                    )
                    callback(extractorLink)
                    return true
                }
            }
            
        } catch (e: Exception) {
            println("SuperFlix: Erro na API de download: ${e.message}")
        }
        
        return false
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
}