package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlinx.coroutines.delay

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Configurações do sniffer
    private val sniffingEnabled = true
    private val debugNetwork = true

    // Padrões para detectar URLs de vídeo
    private val videoPatterns = listOf(
        Regex("""\.(mp4|m4v|mkv|avi|mov|flv|wmv|webm|3gp|ts|m4s)($|\?)""", RegexOption.IGNORE_CASE),
        Regex("""\.m3u8($|\?)""", RegexOption.IGNORE_CASE),
        Regex("""\.mpd($|\?)""", RegexOption.IGNORE_CASE),
        Regex("""manifest\.m3u8""", RegexOption.IGNORE_CASE),
        Regex("""master\.m3u8""", RegexOption.IGNORE_CASE),
        Regex("""video.*\.m3u8""", RegexOption.IGNORE_CASE),
        Regex("""/video/|/stream/|/media/|/hls/|/dash/""", RegexOption.IGNORE_CASE),
        Regex("""\.googlevideo\.com/""", RegexOption.IGNORE_CASE),
        Regex("""cdn\d*\.|stream\d*\.|vod\d*\.|video\d*\.""", RegexOption.IGNORE_CASE)
    )

    // Padrões para filtrar URLs não-desejadas
    private val filterPatterns = listOf(
        "analytics",
        "google-analytics",
        "doubleclick",
        "googlesyndication",
        "adservice",
        "facebook.com/tr",
        "googletagmanager",
        "googletagservices",
        "tracking",
        "pixel",
        "beacon",
        "ads",
        "adx",
        "banner",
        "logo",
        "icon",
        "thumbnail",
        "poster",
        "placeholder",
        "sprite",
        "preview",
        "teaser",
        "tracker",
        "stat"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/filmes" to "Filmes",
        "$mainUrl/series" to "Séries",
        "$mainUrl/lancamentos" to "Lançamentos",
        "$mainUrl/em-alta" to "Em Alta",
        "$mainUrl/lista" to "Lista"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("SuperFlix: getMainPage - Iniciando, page=$page, request=${request.name}")
        val url = request.data + if (page > 1) "?page=$page" else ""
        
        val document = app.get(url, headers = getDefaultHeaders()).document
        val home = mutableListOf<SearchResponse>()

        document.select("div.recs-grid a.rec-card, .movie-card, article, .item, .card").forEach { element ->
            element.toSearchResult()?.let { home.add(it) }
        }

        if (home.isEmpty()) {
            document.select("a[href*='/filme/'], a[href*='/serie/'], a[href*='/assistir/']").forEach { link ->
                val href = link.attr("href")
                if (href.isNotBlank() && !href.contains("#")) {
                    val title = extractTitleFromElement(link, href)
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

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        
        val document = app.get(searchUrl, headers = getDefaultHeaders()).document
        val results = mutableListOf<SearchResponse>()

        document.select("div.recs-grid a.rec-card, a[href*='/filme/'], a[href*='/serie/']").forEach { element ->
            element.toSearchResult()?.let { results.add(it) }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        println("SuperFlix: load - Carregando: $url")
        
        val document = app.get(url, headers = getDefaultHeaders()).document
        val html = document.html()

        val jsonLd = extractJsonLd(html)
        val titleElement = document.selectFirst("h1, .title, .movie-title, .serie-title")
        val scrapedTitle = titleElement?.text()
        
        val title = jsonLd.title ?: scrapedTitle ?: return null
        val year = jsonLd.year ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        
        val poster = extractPoster(document, jsonLd)
        val plot = extractPlot(document, jsonLd)
        val tags = jsonLd.genres ?: document.select("a.chip, .chip, .genre").map { it.text() }
        val actors = jsonLd.actors?.map { Actor(it, "") } ?: emptyList()
        val director = jsonLd.director?.firstOrNull()
        
        val isSerie = url.contains("/serie/") || jsonLd.type == "TVSeries" || document.selectFirst(".season-list, .episode-list") != null

        return if (isSerie) {
            println("SuperFlix: load - Processando como SÉRIE")
            val episodes = extractEpisodes(document, url)
            println("SuperFlix: load - ${episodes.size} episódios encontrados")

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                if (actors.isNotEmpty()) addActors(actors)
            }
        } else {
            println("SuperFlix: load - Processando como FILME")
            val playerUrl = findPlayerUrl(document)
            println("SuperFlix: load - URL do player: ${playerUrl ?: "Não encontrada"}")

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
        println("SuperFlix: loadLinks - Iniciando para: $data")
        
        // Primeiro, tentar o método padrão com extractors
        if (data.contains("filemoon.") || data.contains("fembed.") || data.contains("ico3c.")) {
            println("SuperFlix: loadLinks - Usando extractor Filemoon")
            try {
                com.lagradost.cloudstream3.extractors.Filemoon().getUrl(
                    url = data,
                    referer = "$mainUrl/",
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
                return true
            } catch (e: Exception) {
                println("SuperFlix: loadLinks - Filemoon falhou: ${e.message}")
            }
        }

        // Tentar loadExtractor genérico
        if (loadExtractor(data, subtitleCallback, callback)) {
            return true
        }

        // Se não funcionar, tentar sniffing de rede
        if (sniffingEnabled) {
            println("SuperFlix: loadLinks - Iniciando network sniffing")
            val videoUrls = sniffVideoUrls(data)
            
            if (videoUrls.isNotEmpty()) {
                println("SuperFlix: loadLinks - ${videoUrls.size} URL(s) de vídeo encontrada(s) via sniffing")
                videoUrls.forEach { videoUrl ->
                    val quality = detectQualityFromUrl(videoUrl)
                    val source = detectSourceFromUrl(videoUrl)
                    
                    // CORREÇÃO AQUI: Usando newExtractorLink corretamente
                    val extractorLink = newExtractorLink(
                        source = this.name,
                        name = source,
                        url = videoUrl,
                        referer = data,
                        quality = quality,
                        isM3u8 = videoUrl.contains(".m3u8")
                    ) {}
                    
                    callback(extractorLink)
                    println("SuperFlix: loadLinks - Link adicionado: $source - Qualidade: $quality")
                }
                return true
            }
        }

        println("SuperFlix: loadLinks - Nenhum método funcionou")
        return false
    }

    // ========== MÉTODOS DE NETWORK SNIFFING ==========
    
    private suspend fun sniffVideoUrls(pageUrl: String): List<String> {
        val videoUrls = mutableSet<String>()
        
        try {
            println("SuperFlix: sniffVideoUrls - Iniciando sniffing em: $pageUrl")
            
            // Método 1: Analisar HTML da página
            val document = app.get(pageUrl, headers = getDefaultHeaders()).document
            extractVideoUrlsFromHtml(document, videoUrls)
            
            // Método 2: Analisar scripts JavaScript
            extractVideoUrlsFromScripts(document, videoUrls)
            
            // Método 3: Analisar iframes
            extractVideoUrlsFromIframes(document, videoUrls)
            
            // Método 4: Analisar dados JSON
            extractVideoUrlsFromJsonData(document, videoUrls)
            
        } catch (e: Exception) {
            println("SuperFlix: sniffVideoUrls - Erro: ${e.message}")
            e.printStackTrace()
        }
        
        // Filtrar e retornar URLs únicas
        return videoUrls.filter { isVideoUrl(it) && !shouldFilterUrl(it) }.toList()
    }
    
    private fun extractVideoUrlsFromHtml(document: Element, urlSet: MutableSet<String>) {
        // Extrair tags <video>
        document.select("video").forEach { video ->
            video.attr("src")?.let { src ->
                if (isVideoUrl(src)) urlSet.add(fixUrl(src))
            }
            
            video.select("source").forEach { source ->
                source.attr("src")?.let { src ->
                    if (isVideoUrl(src)) urlSet.add(fixUrl(src))
                }
            }
            
            video.attr("data-src")?.let { dataSrc ->
                if (isVideoUrl(dataSrc)) urlSet.add(fixUrl(dataSrc))
            }
        }
        
        // Extrair atributos data-*
        document.select("[data-src], [data-url], [data-file], [data-video], [data-source]").forEach { element ->
            val src = element.attr("data-src") ?: 
                     element.attr("data-url") ?: 
                     element.attr("data-file") ?: 
                     element.attr("data-video") ?: 
                     element.attr("data-source")
            if (isVideoUrl(src)) urlSet.add(fixUrl(src))
        }
        
        // Extrair links com extensões de vídeo
        document.select("a[href]").forEach { link ->
            val href = link.attr("href")
            if (isVideoUrl(href)) urlSet.add(fixUrl(href))
        }
    }
    
    private fun extractVideoUrlsFromScripts(document: Element, urlSet: MutableSet<String>) {
        document.select("script").forEach { script ->
            val scriptContent = script.html()
            
            // Buscar por variáveis JavaScript com URLs de vídeo
            val jsPatterns = listOf(
                Regex("""var\s+\w+\s*=\s*["']([^"']+\.(?:mp4|m3u8|mpd)[^"']*)["']"""),
                Regex("""let\s+\w+\s*=\s*["']([^"']+\.(?:mp4|m3u8|mpd)[^"']*)["']"""),
                Regex("""const\s+\w+\s*=\s*["']([^"']+\.(mp4|m3u8|mpd)[^"']*)["']"""),
                Regex("""\.setup\s*\({[^}]*["']file["']\s*:\s*["']([^"']+)["']"""),
                Regex("""player\.load\(["']([^"']+)["']\)"""),
                Regex("""hls\.loadSource\(["']([^"']+)["']\)"""),
                Regex("""dashPlayer\.load\(["']([^"']+)["']\)"""),
                Regex("""sources\s*:\s*\[([^\]]+)\]""")
            )
            
            jsPatterns.forEach { pattern ->
                pattern.findAll(scriptContent).forEach { match ->
                    val url = match.groupValues.getOrNull(1) ?: return@forEach
                    if (isVideoUrl(url)) {
                        urlSet.add(fixUrl(url))
                    }
                }
            }
        }
    }
    
    private fun extractVideoUrlsFromIframes(document: Element, urlSet: MutableSet<String>) {
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.startsWith("about:")) {
                // Adicionar URL do iframe (pode conter player)
                if (isVideoUrl(src) || src.contains("player") || src.contains("embed")) {
                    urlSet.add(fixUrl(src))
                }
            }
        }
    }
    
    private fun extractVideoUrlsFromJsonData(document: Element, urlSet: MutableSet<String>) {
        // Buscar por dados JSON-LD
        document.select("script[type='application/ld+json']").forEach { script ->
            try {
                val jsonText = script.html()
                
                // Buscar URLs em JSON
                val jsonPatterns = listOf(
                    Regex(""""contentUrl"\s*:\s*"([^"]+)""""),
                    Regex(""""embedUrl"\s*:\s*"([^"]+)""""),
                    Regex(""""url"\s*:\s*"([^"]+)""""),
                    Regex(""""src"\s*:\s*"([^"]+)""""),
                    Regex(""""video"\s*:\s*\{[^}]*"contentUrl"\s*:\s*"([^"]+)""""),
                    Regex(""""associatedMedia"\s*:\s*\[[^\]]*"contentUrl"\s*:\s*"([^"]+)"""")
                )
                
                jsonPatterns.forEach { pattern ->
                    pattern.findAll(jsonText).forEach { match ->
                        val url = match.groupValues.getOrNull(1) ?: return@forEach
                        if (isVideoUrl(url)) {
                            urlSet.add(fixUrl(url))
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignorar erros de parsing
            }
        }
    }
    
    // ========== MÉTODOS AUXILIARES ==========
    
    private fun isVideoUrl(url: String): Boolean {
        if (url.isBlank() || url.length < 10) return false
        
        // Verificar padrões de vídeo
        val isVideo = videoPatterns.any { it.containsMatchIn(url) }
        
        // Verificar se parece ser uma URL válida
        val isValidUrl = url.startsWith("http") && url.contains("://")
        
        return isVideo && isValidUrl
    }
    
    private fun shouldFilterUrl(url: String): Boolean {
        return filterPatterns.any { url.contains(it, ignoreCase = true) }
    }
    
    private fun detectQualityFromUrl(url: String): Int {
        // CORREÇÃO AQUI: Usando valores numéricos diretamente
        return when {
            url.contains("1080") || url.contains("fullhd", ignoreCase = true) -> 1080
            url.contains("720") || url.contains("hd", ignoreCase = true) -> 720
            url.contains("480") || url.contains("sd", ignoreCase = true) -> 480
            url.contains("360") -> 360
            url.contains("240") -> 240
            else -> 0  // Qualidade desconhecida
        }
    }
    
    private fun detectSourceFromUrl(url: String): String {
        return when {
            url.contains(".m3u8") -> "HLS Stream"
            url.contains(".mpd") -> "MPEG-DASH"
            url.contains("googlevideo") -> "Google Video"
            url.contains("youtube") -> "YouTube"
            url.contains("vimeo") -> "Vimeo"
            url.contains("filemoon") -> "Filemoon"
            url.contains("fembed") -> "Fembed"
            else -> "Direct Stream"
        }
    }
    
    private fun getDefaultHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Accept-Encoding" to "gzip, deflate, br",
            "DNT" to "1",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1",
            "Cache-Control" to "max-age=0"
        )
    }
    
    // Métodos auxiliares existentes (mantidos do seu código original)
    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = selectFirst(".rec-title, .movie-title, h2, h3, .title, .name")
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
            ?: selectFirst(".rec-meta, .movie-year, .year, .date")?.text()?.let {
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
    
    private fun extractTitleFromElement(element: Element, href: String): String {
        val imgAlt = element.selectFirst("img")?.attr("alt") ?: ""
        val titleElement = element.selectFirst(".rec-title, .title, h2, h3, h4, .name")
        val elementTitle = titleElement?.text() ?: ""
        
        return if (imgAlt.isNotBlank()) imgAlt
        else if (elementTitle.isNotBlank()) elementTitle
        else href.substringAfterLast("/").replace("-", " ").replace(Regex("\\d{4}$"), "").trim()
    }
    
    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        // Procurar por botões de play
        val playButton = document.selectFirst("button.bd-play[data-url], .play-button[data-url], [data-url*='http']")
        if (playButton != null) {
            return playButton.attr("data-url")
        }
        
        // Procurar por iframes
        val iframe = document.selectFirst("iframe[src*='fembed'], iframe[src*='filemoon'], iframe[src*='player'], iframe[src*='embed']")
        if (iframe != null) {
            return iframe.attr("src")
        }
        
        // Procurar por links de vídeo
        val videoLinks = document.select("a[href*='.m3u8'], a[href*='.mp4'], a[href*='watch'], a[href*='player']")
        return videoLinks.firstOrNull()?.attr("href")
    }
    
    private fun extractEpisodes(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        val buttons = document.select("button.bd-play[data-url], .episode-button[data-url], [data-url][data-ep]")
        buttons.forEach { button ->
            val videoUrl = button.attr("data-url")
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
                newEpisode(videoUrl) {
                    this.name = episodeTitle
                    this.season = season
                    this.episode = episodeNum
                }
            )
        }
        
        return episodes
    }
    
    private fun extractPoster(document: Element, jsonLd: JsonLdInfo): String? {
        val ogImage = document.selectFirst("meta[property='og:image']")?.attr("content")
        val posterElement = document.selectFirst(".poster img, .movie-poster img, img[src*='poster']")
        
        return jsonLd.posterUrl?.replace("/w500/", "/original/")
            ?: ogImage?.let { fixUrl(it) }?.replace("/w500/", "/original/")
            ?: posterElement?.attr("src")?.let { fixUrl(it) }
    }
    
    private fun extractPlot(document: Element, jsonLd: JsonLdInfo): String? {
        val metaDescription = document.selectFirst("meta[name='description']")?.attr("content")
        val synopsis = document.selectFirst(".syn, .description, .plot, .sinopse")?.text()
        
        return jsonLd.description ?: metaDescription ?: synopsis
    }
    
    private fun extractJsonLd(html: String): JsonLdInfo {
        val pattern = Regex("<script type=\"application/ld\\+json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
        
        pattern.findAll(html).forEach { match ->
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
                // Continuar para o próximo JSON-LD
            }
        }
        
        return JsonLdInfo()
    }
    
    data class JsonLdInfo(
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
}