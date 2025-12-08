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
        println("SuperFlix: getMainPage - Iniciando, page=$page, request=${request.name}, data=${request.data}")
        val url = request.data + if (page > 1) "?page=$page" else ""
        println("SuperFlix: getMainPage - URL final: $url")
        
        val document = app.get(url).document
        println("SuperFlix: getMainPage - Documento obtido, título: ${document.title()}")

        val home = mutableListOf<SearchResponse>()

        document.select("div.recs-grid a.rec-card, .movie-card, article, .item").forEach { element ->
            println("SuperFlix: getMainPage - Processando elemento: ${element.tagName()}")
            element.toSearchResult()?.let { 
                home.add(it)
                println("SuperFlix: getMainPage - Item adicionado: ${it.name}")
            }
        }

        if (home.isEmpty()) {
            println("SuperFlix: getMainPage - Nenhum item encontrado nos seletores padrão, tentando fallback")
            document.select("a[href*='/filme/'], a[href*='/serie/']").forEach { link ->
                val href = link.attr("href")
                println("SuperFlix: getMainPage - Fallback - Link encontrado: $href")
                if (href.isNotBlank() && !href.contains("#")) {
                    val imgElement = link.selectFirst("img")
                    val altTitle = imgElement?.attr("alt") ?: ""
                    
                    val titleElement = link.selectFirst(".rec-title, .title, h2, h3")
                    val elementTitle = titleElement?.text() ?: ""
                    
                    val title = if (altTitle.isNotBlank()) altTitle
                        else if (elementTitle.isNotBlank()) elementTitle
                        else href.substringAfterLast("/").replace("-", " ").replace(Regex("\\d{4}$"), "").trim()

                    println("SuperFlix: getMainPage - Fallback - Título extraído: $title")
                    
                    if (title.isNotBlank()) {
                        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                        val poster = imgElement?.attr("src")?.let { fixUrl(it) }
                        val isSerie = href.contains("/serie/")

                        println("SuperFlix: getMainPage - Fallback - Dados: cleanTitle=$cleanTitle, year=$year, isSerie=$isSerie")

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
                        println("SuperFlix: getMainPage - Fallback - Item adicionado: $cleanTitle")
                    }
                }
            }
        }

        println("SuperFlix: getMainPage - Total de itens encontrados: ${home.size}")
        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    private fun Element.toSearchResult(): SearchResponse? {
        println("SuperFlix: toSearchResult - Iniciando para elemento: ${this.tagName()}")
        
        val titleElement = selectFirst(".rec-title, .movie-title, h2, h3, .title")
        val title = titleElement?.text()
            ?: selectFirst("img")?.attr("alt")
            ?: return null.also { println("SuperFlix: toSearchResult - Título não encontrado") }

        println("SuperFlix: toSearchResult - Título encontrado: $title")
        
        val href = attr("href") ?: selectFirst("a")?.attr("href") ?: return null.also { 
            println("SuperFlix: toSearchResult - href não encontrado")
        }

        println("SuperFlix: toSearchResult - href encontrado: $href")

        val imgElement = selectFirst("img")
        val posterSrc = imgElement?.attr("src")
        val posterDataSrc = imgElement?.attr("data-src")
        
        val poster = if (posterSrc.isNullOrBlank()) {
            posterDataSrc?.let { fixUrl(it) }
        } else {
            fixUrl(posterSrc)
        }

        println("SuperFlix: toSearchResult - Poster encontrado: $poster")

        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: selectFirst(".rec-meta, .movie-year, .year")?.text()?.let {
                Regex("\\b(\\d{4})\\b").find(it)?.groupValues?.get(1)?.toIntOrNull()
            }

        println("SuperFlix: toSearchResult - Ano encontrado: $year")

        val isSerie = href.contains("/serie/")
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

        println("SuperFlix: toSearchResult - isSerie=$isSerie, cleanTitle=$cleanTitle")

        return if (isSerie) {
            println("SuperFlix: toSearchResult - Criando resposta para série")
            newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            println("SuperFlix: toSearchResult - Criando resposta para filme")
            newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("SuperFlix: search - Iniciando busca por: $query")
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        println("SuperFlix: search - URL de busca: $searchUrl")
        
        val document = app.get(searchUrl).document
        println("SuperFlix: search - Documento obtido, título: ${document.title()}")

        val results = mutableListOf<SearchResponse>()

        document.select("div.recs-grid a.rec-card, a[href*='/filme/'], a[href*='/serie/']").forEach { element ->
            println("SuperFlix: search - Processando elemento de busca: ${element.tagName()}")
            element.toSearchResult()?.let { 
                results.add(it)
                println("SuperFlix: search - Resultado adicionado: ${it.name}")
            }
        }

        println("SuperFlix: search - Total de resultados: ${results.size}")
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        println("SuperFlix: load - Iniciando carregamento de: $url")
        
        val document = app.get(url).document
        println("SuperFlix: load - Documento obtido, título: ${document.title()}")
        
        val html = document.html()
        println("SuperFlix: load - HTML obtido (${html.length} chars)")
        
        if (html.length < 100) {
            println("SuperFlix: load - ALERTA: HTML muito curto, possivelmente bloqueado")
        }

        val jsonLd = extractJsonLd(html)
        println("SuperFlix: load - JSON-LD extraído: title=${jsonLd.title}, type=${jsonLd.type}")

        // Usar valores extraídos do JSON-LD ou fallback para scraping
        val titleElement = document.selectFirst("h1, .title")
        val scrapedTitle = titleElement?.text()
        
        val title = jsonLd.title ?: scrapedTitle ?: return null.also { 
            println("SuperFlix: load - ERRO: Título não encontrado")
        }
        
        println("SuperFlix: load - Título final: $title")
        
        val year = jsonLd.year ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        println("SuperFlix: load - Ano: $year")

        val ogImageElement = document.selectFirst("meta[property='og:image']")
        val ogImage = ogImageElement?.attr("content")
        
        val poster = jsonLd.posterUrl?.replace("/w500/", "/original/")
            ?: ogImage?.let { fixUrl(it) }?.replace("/w500/", "/original/")
        
        println("SuperFlix: load - Poster: $poster")

        val descriptionElement = document.selectFirst("meta[name='description']")
        val metaDescription = descriptionElement?.attr("content")
        
        val synopsisElement = document.selectFirst(".syn, .description")
        val synopsisText = synopsisElement?.text()
        
        val plot = jsonLd.description ?: metaDescription ?: synopsisText
        
        println("SuperFlix: load - Plot (${plot?.length ?: 0} chars): ${plot?.take(100)}...")

        val tags = jsonLd.genres ?: document.select("a.chip, .chip").map { it.text() }
        println("SuperFlix: load - Tags encontradas: ${tags.size} - ${tags.take(5)}")

        val actors = jsonLd.actors?.map { Actor(it, "") } ?: emptyList()
        println("SuperFlix: load - Atores encontrados: ${actors.size}")

        val director = jsonLd.director?.firstOrNull()
        println("SuperFlix: load - Diretor: $director")

        val isSerie = url.contains("/serie/") || jsonLd.type == "TVSeries"
        println("SuperFlix: load - isSerie=$isSerie (URL tem '/serie/': ${url.contains("/serie/")}, JSON type: ${jsonLd.type})")

        return if (isSerie) {
            println("SuperFlix: load - Processando como SÉRIE")
            val episodes = extractEpisodesFromButtons(document, url)
            println("SuperFlix: load - Episódios encontrados: ${episodes.size}")
            
            episodes.forEachIndexed { index, episode ->
                println("SuperFlix: load - Episódio $index: ${episode.name}, URL: ${episode.data}")
            }

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
            // Para filmes, extrair a URL do player
            val playerUrl = findPlayerUrl(document)
            println("SuperFlix: load - URL do player encontrada: $playerUrl")
            
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

    private fun extractEpisodesFromButtons(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        println("SuperFlix: extractEpisodesFromButtons - Iniciando extração de episódios")
        val episodes = mutableListOf<Episode>()

        val buttons = document.select("button.bd-play[data-url]")
        println("SuperFlix: extractEpisodesFromButtons - Botões encontrados: ${buttons.size}")

        buttons.forEachIndexed { index, button ->
            println("SuperFlix: extractEpisodesFromButtons - Processando botão $index")
            val fembedUrl = button.attr("data-url")
            val season = button.attr("data-season").toIntOrNull() ?: 1
            val episodeNum = button.attr("data-ep").toIntOrNull() ?: 1

            println("SuperFlix: extractEpisodesFromButtons - URL: $fembedUrl, Season: $season, Episode: $episodeNum")

            var episodeTitle = "Episódio $episodeNum"

            val parent = button.parents().find { it.hasClass("episode-item") || it.hasClass("episode") }
            parent?.let {
                val titleElement = it.selectFirst(".ep-title, .title, .name, h3, h4")
                if (titleElement != null) {
                    episodeTitle = titleElement.text().trim()
                    println("SuperFlix: extractEpisodesFromButtons - Título encontrado: $episodeTitle")
                }
            }

            episodes.add(
                newEpisode(fembedUrl) {
                    this.name = episodeTitle
                    this.season = season
                    this.episode = episodeNum
                }
            )
            println("SuperFlix: extractEpisodesFromButtons - Episódio adicionado: $episodeTitle")
        }

        println("SuperFlix: extractEpisodesFromButtons - Total de episódios extraídos: ${episodes.size}")
        return episodes
    }

    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        println("SuperFlix: findPlayerUrl - Iniciando busca por URL do player")
        
        // Procurar por botões com data-url (principal método)
        val playButton = document.selectFirst("button.bd-play[data-url]")
        if (playButton != null) {
            val url = playButton.attr("data-url")
            println("SuperFlix: findPlayerUrl - URL encontrada em botão: $url")
            return url
        }
        println("SuperFlix: findPlayerUrl - Nenhum botão com data-url encontrado")

        // Procurar por iframes
        val iframes = document.select("iframe")
        println("SuperFlix: findPlayerUrl - Iframes encontrados: ${iframes.size}")
        
        iframes.forEachIndexed { index, iframe ->
            val src = iframe.attr("src")
            println("SuperFlix: findPlayerUrl - Iframe $index: $src")
        }
        
        val iframe = document.selectFirst("iframe[src*='fembed'], iframe[src*='filemoon'], iframe[src*='player'], iframe[src*='embed']")
        if (iframe != null) {
            val url = iframe.attr("src")
            println("SuperFlix: findPlayerUrl - URL encontrada em iframe: $url")
            return url
        }
        println("SuperFlix: findPlayerUrl - Nenhum iframe relevante encontrado")

        // Procurar por links de vídeo
        val videoLinks = document.select("a[href*='.m3u8'], a[href*='.mp4'], a[href*='watch']")
        println("SuperFlix: findPlayerUrl - Links de vídeo encontrados: ${videoLinks.size}")
        
        if (videoLinks.isNotEmpty()) {
            val url = videoLinks.first().attr("href")
            println("SuperFlix: findPlayerUrl - URL encontrada em link: $url")
            return url
        }

        println("SuperFlix: findPlayerUrl - Nenhuma URL de player encontrada")
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("SuperFlix: loadLinks - INÍCIO")
        println("SuperFlix: loadLinks - Carregando links de: $data")
        println("SuperFlix: loadLinks - isCasting: $isCasting")

        if (data.isEmpty()) {
            println("SuperFlix: loadLinks - ERRO CRÍTICO: URL vazia")
            return false
        }

        println("SuperFlix: loadLinks - Tentando loadExtractor com a URL")
        
        return try {
            val result = loadExtractor(data, subtitleCallback, callback)
            println("SuperFlix: loadLinks - loadExtractor retornou: $result")
            result
        } catch (e: Exception) {
            println("SuperFlix: loadLinks - ERRO EXCEÇÃO: ${e.message}")
            println("SuperFlix: loadLinks - Stack trace:")
            e.printStackTrace()
            false
        }
    }

    // Data class para armazenar informações do JSON-LD
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
        println("SuperFlix: extractJsonLd - Iniciando extração de JSON-LD")
        
        val pattern = Regex("<script type=\"application/ld\\+json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
        val matches = pattern.findAll(html)
        
        println("SuperFlix: extractJsonLd - Encontrados ${matches.count()} scripts JSON-LD")

        matches.forEachIndexed { index, match ->
            try {
                println("SuperFlix: extractJsonLd - Processando JSON-LD $index")
                val json = match.groupValues[1].trim()
                println("SuperFlix: extractJsonLd - JSON (${json.length} chars): ${json.take(200)}...")
                
                if (json.contains("\"@type\":\"Movie\"") || json.contains("\"@type\":\"TVSeries\"")) {
                    println("SuperFlix: extractJsonLd - JSON é do tipo Movie ou TVSeries")

                    val title = Regex("\"name\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    println("SuperFlix: extractJsonLd - Título extraído: $title")
                    
                    val image = Regex("\"image\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    println("SuperFlix: extractJsonLd - Imagem extraída: $image")
                    
                    val description = Regex("\"description\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    println("SuperFlix: extractJsonLd - Descrição extraída (${description?.length ?: 0} chars)")

                    val genresMatch = Regex("\"genre\":\\s*\\[([^\\]]+)\\]").find(json)
                    val genres = genresMatch?.groupValues?.get(1)
                        ?.split(",")
                        ?.map { it.trim().trim('"', '\'') }
                        ?.filter { it.isNotBlank() }
                    println("SuperFlix: extractJsonLd - Gêneros extraídos: ${genres?.size ?: 0} - ${genres?.take(3)}")

                    val actorsMatch = Regex("\"actor\":\\s*\\[([^\\]]+)\\]").find(json)
                    val actors = actorsMatch?.groupValues?.get(1)
                        ?.split("},")
                        ?.mapNotNull { actor ->
                            Regex("\"name\":\"([^\"]+)\"").find(actor)?.groupValues?.get(1)
                        }
                    println("SuperFlix: extractJsonLd - Atores extraídos: ${actors?.size ?: 0} - ${actors?.take(3)}")

                    val directorMatch = Regex("\"director\":\\s*\\[([^\\]]+)\\]").find(json)
                    val director = directorMatch?.groupValues?.get(1)
                        ?.split("},")
                        ?.mapNotNull { dir ->
                            Regex("\"name\":\"([^\"]+)\"").find(dir)?.groupValues?.get(1)
                        }
                    println("SuperFlix: extractJsonLd - Diretores extraídos: ${director?.size ?: 0} - ${director?.take(3)}")

                    val sameAsMatch = Regex("\"sameAs\":\\s*\\[([^\\]]+)\\]").find(json)
                    val tmdbId = sameAsMatch?.groupValues?.get(1)
                        ?.split(",")
                        ?.find { it.contains("themoviedb.org") }
                        ?.substringAfterLast("/")
                        ?.trim(' ', '"', '\'')
                    println("SuperFlix: extractJsonLd - TMDB ID extraído: $tmdbId")

                    val type = if (json.contains("\"@type\":\"Movie\"")) "Movie" else "TVSeries"
                    println("SuperFlix: extractJsonLd - Tipo extraído: $type")

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
                } else {
                    println("SuperFlix: extractJsonLd - JSON não é do tipo Movie/TVSeries, ignorando")
                }
            } catch (e: Exception) {
                println("SuperFlix: extractJsonLd - ERRO ao processar JSON-LD $index: ${e.message}")
            }
        }

        println("SuperFlix: extractJsonLd - Nenhum JSON-LD válido encontrado, retornando objeto vazio")
        return JsonLdInfo()
    }
}