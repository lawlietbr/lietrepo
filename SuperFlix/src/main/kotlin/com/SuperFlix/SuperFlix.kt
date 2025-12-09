package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import java.net.URLEncoder

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val usesWebView = true // ESSENCIAL para capturar requisições de rede

    override val mainPage = mainPageOf(
        "$mainUrl/filmes" to "Filmes",
        "$mainUrl/series" to "Séries",
        "$mainUrl/lancamentos" to "Lançamentos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("SuperFlix: getMainPage - page=$page, request=${request.name}")
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
                    val imgElement = link.selectFirst("img")
                    val altTitle = imgElement?.attr("alt") ?: ""

                    val titleElement = link.selectFirst(".rec-title, .title, h2, h3")
                    val elementTitle = titleElement?.text() ?: ""

                    val title = if (altTitle.isNotBlank()) altTitle
                        else if (elementTitle.isNotBlank()) elementTitle
                        else href.substringAfterLast("/").replace("-", " ").replace(Regex("\\d{4}$"), "").trim()

                    if (title.isNotBlank()) {
                        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                        val poster = imgElement?.attr("src")?.let { fixUrl(it) }
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
        val titleElement = selectFirst(".rec-title, .movie-title, h2, h3, .title")
        val title = titleElement?.text() ?: selectFirst("img")?.attr("alt") ?: return null

        val elementHref = attr("href")
        val href = if (elementHref.isNotBlank()) elementHref else selectFirst("a")?.attr("href")
        if (href.isNullOrBlank()) return null

        val imgElement = selectFirst("img")
        val posterSrc = imgElement?.attr("src")
        val posterDataSrc = imgElement?.attr("data-src")
        val poster = if (posterSrc.isNullOrBlank()) {
            posterDataSrc?.let { fixUrl(it) }
        } else {
            fixUrl(posterSrc)
        }

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
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        val document = app.get(searchUrl).document

        val results = mutableListOf<SearchResponse>()

        document.select("div.recs-grid a.rec-card, a[href*='/filme/'], a[href*='/serie/']").forEach { element ->
            element.toSearchResult()?.let { results.add(it) }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        println("SuperFlix: load - URL: $url")
        val document = app.get(url).document

        val jsonLd = extractJsonLd(document.html())
        val titleElement = document.selectFirst("h1, .title")
        val scrapedTitle = titleElement?.text()
        val title = jsonLd.title ?: scrapedTitle ?: return null

        val year = jsonLd.year ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val ogImage = document.selectFirst("meta[property='og:image']")?.attr("content")
        val poster = jsonLd.posterUrl?.replace("/w500/", "/original/")
            ?: ogImage?.let { fixUrl(it) }?.replace("/w500/", "/original/")

        val description = document.selectFirst("meta[name='description']")?.attr("content")
        val synopsis = document.selectFirst(".syn, .description")?.text()
        val plot = jsonLd.description ?: description ?: synopsis

        val tags = jsonLd.genres ?: document.select("a.chip, .chip").map { it.text() }
        val actors = jsonLd.actors?.map { Actor(it, "") } ?: emptyList()
        val director = jsonLd.director?.firstOrNull()

        val isSerie = url.contains("/serie/") || jsonLd.type == "TVSeries"

        return if (isSerie) {
            println("SuperFlix: load - É uma série")
            val episodes = extractEpisodesFromButtons(document, url)
            println("SuperFlix: load - Episódios encontrados: ${episodes.size}")

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                if (actors.isNotEmpty()) addActors(actors)
            }
        } else {
            println("SuperFlix: load - É um filme")
            val playerUrl = findPlayerUrl(document)
            println("SuperFlix: load - Player URL encontrada: $playerUrl")

            // Passamos a URL do player para o loadLinks
            newMovieLoadResponse(title, url, TvType.Movie, playerUrl ?: url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                if (actors.isNotEmpty()) addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("SuperFlix: loadLinks - INÍCIO para: $data")

        if (data.isEmpty()) {
            println("SuperFlix: loadLinks - ERRO: URL vazia")
            return false
        }

        // Método principal: Analisar a página para encontrar URLs .m3u8
        println("SuperFlix: loadLinks - Analisando página para URLs .m3u8...")
        
        val m3u8Urls = findM3u8Urls(data)
        println("SuperFlix: loadLinks - URLs .m3u8 encontradas: ${m3u8Urls.size}")
        
        for (m3u8Url in m3u8Urls) {
            println("SuperFlix: loadLinks - URL encontrada: $m3u8Url")
            
            val quality = extractQualityFromM3u8Url(m3u8Url)
            println("SuperFlix: loadLinks - Qualidade detectada: $quality")
            
            callback(
                newExtractorLink(
                    source = this.name,
                    name = "SuperFlix HLS (${quality}p)",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    this.headers = getHeaders()
                    this.quality = quality
                }
            )
        }
        
        // Se não encontrou URLs .m3u8, tentar usar extractor padrão
        if (m3u8Urls.isEmpty()) {
            println("SuperFlix: loadLinks - Nenhuma URL .m3u8 encontrada, tentando extractor padrão...")
            return loadExtractor(data, subtitleCallback, callback)
        }
        
        val success = m3u8Urls.isNotEmpty()
        println("SuperFlix: loadLinks - FIM: ${if (success) "SUCESSO" else "FALHA"} (${m3u8Urls.size} links)")
        return success
    }

    // ========== MÉTODO PARA ENCONTRAR URLs .m3u8 ==========

    private suspend fun findM3u8Urls(url: String): List<String> {
        val m3u8Urls = mutableListOf<String>()
        
        try {
            println("SuperFlix: findM3u8Urls - Analisando: $url")
            
            // Obter HTML da página
            val document = app.get(url).document
            val html = document.html()
            
            // Padrões para URLs .m3u8 (especialmente do tipo hls2)
            val patterns = listOf(
                // Padrão exato: https://be6721.rcr72.waw04.../hls2/.../master.m3u8
                Regex("""https?://[^\s"']*?/hls2/[^\s"']*?/master\.m3u8[^\s"']*""", RegexOption.IGNORE_CASE),
                
                // Qualquer URL .m3u8 com parâmetros
                Regex("""https?://[^\s"']*?\.m3u8\?[^\s"']*""", RegexOption.IGNORE_CASE),
                
                // URLs .m3u8 simples
                Regex("""https?://[^\s"']*?\.m3u8[^\s"']*""", RegexOption.IGNORE_CASE),
                
                // URLs em atributos src, data-src, etc.
                Regex("""(?:src|data-src|data-url|data-file)\s*=\s*["'](https?://[^"']*?\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
                
                // URLs em configurações de player JavaScript
                Regex("""["'](?:file|url|source)["']\s*:\s*["'](https?://[^"']*?\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
                
                // Padrão específico para hls.js
                Regex("""\.loadSource\s*\(\s*["'](https?://[^"']*?\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
            )
            
            // Procurar usando todos os padrões
            for (pattern in patterns) {
                val matches = pattern.findAll(html)
                for (match in matches) {
                    var foundUrl = match.value
                    
                    // Se o padrão tem grupos de captura, pegar o primeiro grupo
                    if (match.groupValues.size > 1 && match.groupValues[1].startsWith("http")) {
                        foundUrl = match.groupValues[1]
                    }
                    
                    if (isValidM3u8Url(foundUrl)) {
                        println("SuperFlix: findM3u8Urls - URL encontrada via regex: $foundUrl")
                        m3u8Urls.add(foundUrl)
                    }
                }
            }
            
            // Procurar em scripts JavaScript
            document.select("script").forEach { script ->
                val scriptContent = script.html()
                if (scriptContent.contains("m3u8") || scriptContent.contains("hls") || scriptContent.contains("master.m3u8")) {
                    // Padrão específico para URLs hls2
                    val hls2Pattern = Regex("""https?://[^"'\s;]+/hls2/[^"'\s;]+/master\.m3u8[^"'\s;]*""")
                    val matches = hls2Pattern.findAll(scriptContent)
                    
                    for (match in matches) {
                        val foundUrl = match.value
                        if (isValidM3u8Url(foundUrl)) {
                            println("SuperFlix: findM3u8Urls - URL encontrada em script: $foundUrl")
                            m3u8Urls.add(foundUrl)
                        }
                    }
                }
            }
            
            // Procurar em iframes (players embutidos)
            val iframes = document.select("iframe[src]")
            println("SuperFlix: findM3u8Urls - Iframes encontrados: ${iframes.size}")
            
            for (iframe in iframes) {
                val iframeSrc = iframe.attr("src")
                if (iframeSrc.isNotBlank()) {
                    println("SuperFlix: findM3u8Urls - Analisando iframe: $iframeSrc")
                    try {
                        // Analisar o iframe
                        val iframeDoc = app.get(fixUrl(iframeSrc)).document
                        val iframeHtml = iframeDoc.html()
                        
                        // Procurar .m3u8 no iframe
                        val iframePattern = Regex("""https?://[^\s"']*?\.m3u8[^\s"']*""")
                        val matches = iframePattern.findAll(iframeHtml)
                        
                        for (match in matches) {
                            val foundUrl = match.value
                            if (isValidM3u8Url(foundUrl)) {
                                println("SuperFlix: findM3u8Urls - URL encontrada no iframe: $foundUrl")
                                m3u8Urls.add(foundUrl)
                            }
                        }
                    } catch (e: Exception) {
                        println("SuperFlix: findM3u8Urls - Erro ao analisar iframe: ${e.message}")
                    }
                }
            }
            
        } catch (e: Exception) {
            println("SuperFlix: findM3u8Urls - Erro: ${e.message}")
        }
        
        return m3u8Urls.distinct().filter { isValidM3u8Url(it) }
    }

    private fun isValidM3u8Url(url: String): Boolean {
        if (url.isBlank() || !url.startsWith("http")) return false
        
        // URLs para ignorar (análise, anúncios, etc.)
        val ignorePatterns = listOf(
            "google-analytics", "doubleclick", "facebook", "twitter",
            "instagram", "analytics", "tracking", "pixel", "beacon",
            "ads", "adserver", "banner", "sponsor", "gstatic",
            "googlesyndication", "googletagmanager", "youtube.com",
            "vimeo.com", "facebook.com/tr", "facebook.com/events"
        )
        
        if (ignorePatterns.any { url.contains(it, ignoreCase = true) }) {
            return false
        }
        
        // Deve conter .m3u8
        return url.contains(".m3u8")
    }

    private fun extractQualityFromM3u8Url(url: String): Int {
        val qualityPatterns = mapOf(
            Regex("""/360p?/|/360/|360p?\.m3u8""") to 360,
            Regex("""/480p?/|/480/|480p?\.m3u8""") to 480,
            Regex("""/720p?/|/720/|720p?\.m3u8|hd\.m3u8""") to 720,
            Regex("""/1080p?/|/1080/|1080p?\.m3u8|fullhd\.m3u8""") to 1080,
            Regex("""/2160p?/|/4k/|/uhd/|2160p?\.m3u8|4k\.m3u8""") to 2160
        )
        
        for ((pattern, quality) in qualityPatterns) {
            if (pattern.containsMatchIn(url.lowercase())) {
                return quality
            }
        }
        
        // Tentar detectar qualidade pelo padrão hls2
        if (url.contains("/hls2/")) {
            try {
                val pathSegments = url.split("/")
                for (segment in pathSegments) {
                    if (segment.contains("p") && segment.matches(Regex("""\d+p"""))) {
                        return segment.replace("p", "").toIntOrNull() ?: Qualities.Unknown.value
                    }
                }
            } catch (e: Exception) {
                // Ignorar erro
            }
        }
        
        return Qualities.Unknown.value
    }

    private fun getHeaders(): Map<String, String> {
        return mapOf(
            "accept" to "*/*",
            "accept-language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "cache-control" to "no-cache",
            "dnt" to "1",
            "origin" to mainUrl,
            "pragma" to "no-cache",
            "referer" to mainUrl,
            "sec-ch-ua" to "\"Google Chrome\";v=\"137\", \"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
            "sec-fetch-dest" to "empty",
            "sec-fetch-mode" to "cors",
            "sec-fetch-site" to "cross-site",
            "sec-gpc" to "1",
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "X-Requested-With" to "XMLHttpRequest"
        )
    }

    // ========== MÉTODOS AUXILIARES ==========

    private fun extractEpisodesFromButtons(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val buttons = document.select("button.bd-play[data-url]")

        buttons.forEach { button ->
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

    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        // Botões com data-url
        val playButton = document.selectFirst("button.bd-play[data-url]")
        if (playButton != null) {
            return playButton.attr("data-url")
        }

        // Iframes
        val iframe = document.selectFirst("iframe[src*='fembed'], iframe[src*='filemoon'], iframe[src*='player'], iframe[src*='embed']")
        if (iframe != null) {
            return iframe.attr("src")
        }

        // Links de vídeo
        val videoLink = document.selectFirst("a[href*='.m3u8'], a[href*='.mp4'], a[href*='watch']")
        return videoLink?.attr("href")
    }

    private data class JsonLdInfo(
        val title: String? = null,
        val year: Int? = null,
        val posterUrl: String? = null,
        val description: String? = null,
        val genres: List<String>? = null,
        val director: List<String>? = null,
        val actors: List<String>? = null,
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

                    val year = Regex("\"dateCreated\":\"(\\d{4})").find(json)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("\"copyrightYear\":(\\d{4})").find(json)?.groupValues?.get(1)?.toIntOrNull()

                    val type = if (json.contains("\"@type\":\"TVSeries\"")) "TVSeries" else "Movie"

                    return JsonLdInfo(
                        title = title,
                        year = year,
                        posterUrl = image,
                        description = description,
                        genres = genres,
                        director = director,
                        actors = actors,
                        type = type
                    )
                }
            } catch (e: Exception) {
                // Continuar para o próximo JSON
            }
        }

        return JsonLdInfo()
    }
}