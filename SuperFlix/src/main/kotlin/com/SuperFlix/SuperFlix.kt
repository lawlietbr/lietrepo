package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink.Companion.newExtractorLink // <-- ESSENCIAL: Corrige os erros de compilação
import com.lagradost.cloudstream3.utils.AppUtils.base64Encode // Necessário para resolveWrapperPlayer
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

    // --------------------------------------------------------------------------------
    // FUNÇÕES INICIAIS (getMainPage, toSearchResult, search) - MANTIDAS
    // --------------------------------------------------------------------------------

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
    
    // --------------------------------------------------------------------------------
    // FUNÇÃO LOAD E AUXILIARES
    // --------------------------------------------------------------------------------

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
            val episodes = extractEpisodesFromButtons(document)

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                addActors(actors)
            }
        } else {
            // Para filmes, armazenamos a URL do iframe/button (Player Wrapper ou Fembed direto)
            val videoData = findFembedUrlInPage(document) ?: ""

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

    private fun extractEpisodesFromButtons(document: org.jsoup.nodes.Document): List<Episode> {
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

    private fun findFembedUrlInPage(document: org.jsoup.nodes.Document): String? {
        // 1. Iframes (Fembed direto)
        val iframe = document.selectFirst("iframe[src*='fembed']")
        if (iframe != null) {
            return iframe.attr("src")
        }

        // 2. Botão de play com data-url (Player Wrapper do SuperFlix ou Fembed)
        val playButton = document.selectFirst("button.bd-play[data-url]")
        if (playButton != null) {
            return playButton.attr("data-url")
        }

        // 3. Procurar por qualquer botão com URL do Fembed ou do Player Wrapper
        val anyButton = document.selectFirst("button[data-url*='fembed'], button[data-url*='player.php']")
        if (anyButton != null) {
            return anyButton.attr("data-url")
        }
        
        // 4. Procurar no HTML por padrões
        val html = document.html()
        val patterns = listOf(
            Regex("""https?://[^"'\s]*fembed[^"'\s]*/e/[a-zA-Z0-9]+"""), // Fembed URL
            Regex("""(https?://[^"'\s]+/player[^"'\s]*\?s=\d+[^"'\s]*)""") // Player Wrapper URL
        )

        patterns.forEach { pattern ->
            pattern.find(html)?.let { match ->
                val url = if (match.groupValues.size > 1) match.groupValues[1] else match.value
                if (url.isNotBlank()) return url
            }
        }

        return null
    }
    
    // --------------------------------------------------------------------------------
    // FUNÇÕES CRUCIAIS PARA EXTRAÇÃO (Imitando o Web Caster)
    // --------------------------------------------------------------------------------

    private fun extractQualityFromUrl(url: String): Int {
        return when {
            url.contains("/2160/") || url.contains("2160p") -> Qualities.P2160.value
            url.contains("/1080/") || url.contains("1080p") -> Qualities.P1080.value
            url.contains("/720/") || url.contains("720p") -> Qualities.P720.value
            url.contains("/480/") || url.contains("480p") -> Qualities.P480.value
            url.contains("/360/") || url.contains("360p") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    // Função para resolver o Player Wrapper do SuperFlix e encontrar a URL do Fembed
    private suspend fun resolveWrapperPlayer(playerUrl: String, referer: String): String? {
        return try {
            val res = app.get(playerUrl, referer = referer)
            val html = res.text
            
            val apiMatch = Regex("""const\s+api\s*=\s*["']([^"']+)["']""").find(html)
            
            val dataMatch = Regex("""data\s*=\s*\{\s*DUB:\s*(\[[^\]]+\])""").find(html)
            val key = Regex(""""k"\s*:\s*(\d+)""").find(dataMatch?.groupValues?.get(1) ?: "")?.groupValues?.get(1) ?: "0"
            
            val apiPath = apiMatch?.groupValues?.get(1)?.replace("\\/", "/") ?: return null
            val apiFullUrl = fixUrl(apiPath)
            
            val keyB64 = base64Encode(key) 
            
            val postBody = mapOf(
                "action" to "getPlayer",
                "lang" to "DUB", 
                "key" to keyB64
            )
            
            val postRes = app.post(
                apiFullUrl, 
                data = postBody, 
                referer = playerUrl,
                headers = mapOf("User-Agent" to USER_AGENT) 
            )
            
            val fembedIframeMatch = Regex("""iframe[^>]+src=["'](https?://[^"']+fembed[^"']+)["']""").find(postRes.text)
            
            fembedIframeMatch?.groupValues?.get(1)
            
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // Função para extrair o link .m3u8 do Fembed (Imita o Web Caster/Player)
    private suspend fun manualFembedExtractor(
        fembedUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // 1. Extrair o ID do Fembed
            val fembedId = Regex("""/(?:e|v)/([a-zA-Z0-9]+)/""")
                .find(fembedUrl)?.groupValues?.get(1)
                ?: return false 

            val apiSourceUrl = "https://fembed.sx/api/source/$fembedId"
            
            // 2. Headers e Body para o POST Request (IMTA O WEB CASTER/PLAYER)
            val headers = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01", 
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to fembedUrl, // CRUCIAL para liberar o link
            )
            
            val postBody = mapOf("id" to fembedId)
            
            // 3. Realizar o POST request
            val postRes = app.post(apiSourceUrl, data = postBody, headers = headers)

            // 4. Extrair o link de streaming (.m3u8) do JSON
            val json = postRes.text
            
            val m3u8Match = Regex("""\s*["']file["']\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(json)
            
            val m3u8Link = m3u8Match?.groupValues?.get(1)
            
            if (m3u8Link != null) {
                val quality = extractQualityFromUrl(m3u8Link)
                
                // Usando newExtractorLink (Corrige o erro de depreciação na Linha ~290)
                callback.invoke(
                    newExtractorLink( 
                        source = name,
                        name = "$name (Fembed ${quality}p)",
                        url = m3u8Link,
                        referer = fembedUrl, 
                        quality = quality,
                        isM3u8 = true
                    )
                )
                return true
            }

            return false

        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
    
    // --------------------------------------------------------------------------------
    // FUNÇÃO LOADLINKS FINAL E FUNCIONAL
    // --------------------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        
        val fixedDataUrl = fixUrl(data)
        
        return try {
            var fembedUrl: String? = null
            
            // 1. Identificar o Link
            if (fixedDataUrl.contains("fembed.sx")) {
                fembedUrl = fixedDataUrl
            } else if (fixedDataUrl.contains("s=") && fixedDataUrl.contains("c=")) {
                // Resolve o Player Wrapper do SuperFlix para obter a URL do Fembed
                fembedUrl = resolveWrapperPlayer(fixedDataUrl, mainUrl)
            }
            
            if (fembedUrl != null) {
                // Tenta a extração manual (como o Web Caster)
                val success = manualFembedExtractor(fembedUrl, callback)
                
                if (success) return true
                
                // Fallback para o extrator nativo do CloudStream
                return loadExtractor(fembedUrl, mainUrl, subtitleCallback, callback)
            }
            
            // 3. FALLBACK: Tenta extrair a URL do player da página do filme/episódio
            val res = app.get(fixedDataUrl, referer = mainUrl, timeout = 30)
            val doc = res.document
            
            val fembedUrlFromPage = findFembedUrlInPage(doc)
            
            if (fembedUrlFromPage != null) {
                // Chama loadLinks recursivamente com a URL do player/fembed encontrada
                return loadLinks(fembedUrlFromPage, isCasting, subtitleCallback, callback)
            }
            
            false
            
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    // --------------------------------------------------------------------------------
    // FUNÇÃO AUXILIAR JSON-LD
    // --------------------------------------------------------------------------------

    private data class JsonLdInfo(
        // ... (data class original)
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
        // ... (código original)
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
