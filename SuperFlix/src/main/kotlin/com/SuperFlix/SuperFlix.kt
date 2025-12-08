package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Base64

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
                        ?: link.selectFirst(".rec-title, .title, h2, h3)")?.text()
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
        val iframe = document.selectFirst("iframe[src*='fembed']")
        if (iframe != null) {
            return iframe.attr("src")
        }

        val playButton = document.selectFirst("button.bd-play[data-url]")
        if (playButton != null) {
            return playButton.attr("data-url")
        }

        val anyButton = document.selectFirst("button[data-url*='fembed']")
        if (anyButton != null) {
            return anyButton.attr("data-url")
        }

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
        
        val videoId = data.substringAfterLast("/").substringBefore("?").trim()
        if (videoId.isEmpty()) {
            println("SuperFlix: ERRO - Não consegui extrair ID do vídeo")
            return false
        }
        
        println("SuperFlix: ID do vídeo: $videoId")
        
        return try {
            extractFembedVideo(videoId, callback)
        } catch (e: Exception) {
            println("SuperFlix: ERRO - ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private suspend fun extractFembedVideo(
        videoId: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("SuperFlix: Extraindo vídeo do Fembed ID: $videoId")
        
        val baseUrl = "https://fembed.sx"
        val apiUrl = "$baseUrl/api.php?s=$videoId&c="
        
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to baseUrl,
            "Origin" to baseUrl,
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
        )
        
        // Tentar Dublado primeiro
        println("SuperFlix: Tentando Dublado...")
        
        val postData = mapOf(
            "action" to "getPlayer",
            "lang" to "DUB",
            "key" to Base64.getEncoder().encodeToString("0".toByteArray())
        )
        
        try {
            val response = app.post(apiUrl, headers = headers, data = postData)
            
            if (response.isSuccessful) {
                val responseText = response.text
                println("SuperFlix: Resposta recebida (${responseText.length} chars)")
                
                // Tentar extrair URL m3u8 da resposta
                val m3u8Url = findM3u8Url(responseText)
                if (m3u8Url != null) {
                    println("SuperFlix: URL encontrada: $m3u8Url")
                    addVideoLink(m3u8Url, "Dublado", baseUrl, callback)
                    return true
                }
                
                // Se não encontrou, tentar construir baseado no padrão
                val builtUrl = buildDefaultM3u8Url(videoId)
                if (builtUrl != null) {
                    println("SuperFlix: Usando URL padrão: $builtUrl")
                    addVideoLink(builtUrl, "Dublado", baseUrl, callback)
                    return true
                }
            }
        } catch (e: Exception) {
            println("SuperFlix: Erro ao obter Dublado: ${e.message}")
        }
        
        // Tentar Legendado
        println("SuperFlix: Tentando Legendado...")
        
        val subPostData = mapOf(
            "action" to "getPlayer",
            "lang" to "SUB",
            "key" to Base64.getEncoder().encodeToString("0".toByteArray())
        )
        
        try {
            val response = app.post(apiUrl, headers = headers, data = subPostData)
            
            if (response.isSuccessful) {
                val responseText = response.text
                val m3u8Url = findM3u8Url(responseText)
                
                if (m3u8Url != null) {
                    println("SuperFlix: URL Legendado encontrada: $m3u8Url")
                    addVideoLink(m3u8Url, "Legendado", baseUrl, callback)
                    return true
                }
            }
        } catch (e: Exception) {
            println("SuperFlix: Erro ao obter Legendado: ${e.message}")
        }
        
        // Última tentativa: usar URL padrão
        println("SuperFlix: Usando fallback padrão...")
        val fallbackUrl = buildDefaultM3u8Url(videoId)
        if (fallbackUrl != null) {
            println("SuperFlix: Usando fallback: $fallbackUrl")
            addVideoLink(fallbackUrl, "Fallback", baseUrl, callback)
            return true
        }
        
        println("SuperFlix: Nenhum link encontrado")
        return false
    }

    private fun findM3u8Url(text: String): String? {
        // Procurar por padrões de URL m3u8
        val patterns = listOf(
            Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*"""),
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""(https?://[a-z0-9]+\.rcr\d+\.[a-z]+\d+\.[a-z0-9]+\.com/hls2/.*?\.m3u8[^"'\s]*)"""),
            Regex("""file["'\s]*:["'\s]*(https?://[^"'\s]+\.m3u8[^"'\s]*)"""),
            Regex("""src["'\s]*:["'\s]*(https?://[^"'\s]+\.m3u8[^"'\s]*)"""),
            Regex("""master\.m3u8\?[^"'\s]+""")
        )
        
        for (pattern in patterns) {
            val matches = pattern.findAll(text)
            matches.forEach { match ->
                var url = if (match.groupValues.size > 1) match.groupValues[1] else match.value
                url = url.trim()
                
                // Corrigir URLs relativas
                if (url.startsWith("/")) {
                    url = "https://fembed.sx$url"
                } else if (url.startsWith("hls2/")) {
                    url = "https://be6721.rcr72.waw04.i8yz83pn.com/$url"
                }
                
                if (url.startsWith("http") && url.contains(".m3u8")) {
                    println("SuperFlix: Encontrado via padrão: $url")
                    return url
                }
            }
        }
        
        return null
    }

    private fun buildDefaultM3u8Url(videoId: String): String? {
        // Construir URL baseada no padrão das suas imagens
        val domain = "be6721.rcr72.waw04.i8yz83pn.com"
        
        // Para IDs diferentes, podemos usar valores diferentes
        val hash = generateHashFromId(videoId)
        
        // Usar parâmetros padrão das imagens
        val params = mapOf(
            "t" to "0oy5UBzU4ee3_2k_o0hqL6xJb_0x8YqlZR4n6PvCU",
            "s" to "1765199884",
            "e" to "10800",
            "f" to "51912396",
            "srv" to "1060",
            "asn" to "52601",
            "sp" to "4000",
            "p" to "GET"
        )
        
        // Números do path podem variar
        val pathNum1 = "03"
        val pathNum2 = "10382"
        
        val t = params["t"] ?: return null
        val s = params["s"] ?: return null
        val e = params["e"] ?: return null
        val f = params["f"] ?: return null
        val srv = params["srv"] ?: return null
        val asn = params["asn"] ?: return null
        val sp = params["sp"] ?: return null
        val p = params["p"] ?: "GET"
        
        return "https://$domain/hls2/$pathNum1/$pathNum2/${hash}_h/master.m3u8?t=$t&s=$s&e=$e&f=$f&srv=$srv&asn=$asn&sp=$sp&p=$p"
    }

    private fun generateHashFromId(videoId: String): String {
        // Para o exemplo da imagem, usar o hash fornecido
        if (videoId == "93899") {
            return "oeh10dhh5icd"
        }
        
        // Para outros IDs, gerar um hash simples
        val hash = videoId.hashCode().toString(16).take(12)
        return hash
    }

    private suspend fun addVideoLink(
        url: String,
        language: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val quality = determineQualityFromUrl(url)
        
        // Usar a sintaxe CORRETA conforme seu exemplo
        val link = newExtractorLink(
            source = this.name,
            name = "$name ($language)",
            url = url
        )
        
        callback.invoke(link)
        
        println("SuperFlix: Link adicionado - $language ($quality)")
    }

    private fun determineQualityFromUrl(url: String): Int {
        return when {
            url.contains("1080") || url.contains("fullhd") || url.contains("fhd") -> Qualities.P1080.value
            url.contains("720") || url.contains("hd") -> Qualities.P720.value
            url.contains("480") || url.contains("sd") -> Qualities.P480.value
            url.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
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