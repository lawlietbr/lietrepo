package com.SuperFlix21

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element

class SuperFlix21 : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix21"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/filmes" to "Filmes",
        "$mainUrl/series" to "Séries",
        "$mainUrl/lancamentos" to "Lançamentos",
        "$mainUrl/acao" to "Ação",
        "$mainUrl/animacao" to "Animação",
        "$mainUrl/aventura" to "Aventura",
        "$mainUrl/comedia" to "Comédia",
        "$mainUrl/documentario" to "Documentário",
        "$mainUrl/drama" to "Drama",
        "$mainUrl/terror" to "Terror",
        "$mainUrl/suspense" to "Suspense"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "?page=$page" else ""
        val document = app.get(url).document
        
        val home = document.select("div.movie-card, article.movie, .item").mapNotNull {
            it.toSearchResult()
        }
        
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("div.movie-title, h2, h3, .title")?.text() ?: return null
        val href = selectFirst("a")?.attr("href") ?: return null
        
        val posterUrl = selectFirst("img")?.attr("src")
            ?.takeIf { it.isNotBlank() } 
            ?.let { fixUrl(it) }
        
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: selectFirst(".movie-year, .year")?.text()?.toIntOrNull()
        
        val quality = selectFirst("div.quality-tag, .quality")?.text()
        val isSerie = href.contains("/serie/")
        
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        return if (isSerie) {
            newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
                this.quality = getQualityFromString(quality)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.movie-card, article, .item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val html = document.html()

        // 🔥🔥🔥 EXTRAIR DADOS DO JSON-LD (SUA DESCOBERTA!) 🔥🔥🔥
        val jsonLd = extractJsonLdData(html)
        
        val title = jsonLd.title ?: document.selectFirst("h1")?.text() ?: return null
        val year = jsonLd.year ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        
        // POSTER DO TMDB (QUALIDADE ORIGINAL)
        val poster = jsonLd.posterUrl?.replace("/w500/", "/original/")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
        
        val plot = jsonLd.description ?: document.selectFirst(".description, .sinopse, p")?.text()
        
        // GÊNEROS DO JSON-LD
        val tags = jsonLd.genres ?: document.select("a[href*='/genero/']").map { it.text() }
        
        // ATORES DO JSON-LD
        val actors = jsonLd.actors?.map { Actor(it, "") } ?: 
            document.select(".cast a, .actors a").map { Actor(it.text(), it.attr("href")) }
        
        // DIRETOR
        val director = jsonLd.director?.firstOrNull()
        
        // TRAILER
        val trailer = document.selectFirst("iframe[src*='youtube']")?.attr("src")
        
        // 🔥 ID DO TMDB (PARA FEMBED) 🔥
        val tmdbId = jsonLd.tmdbId
            ?: url.substringAfterLast("-").toIntOrNull()
            ?: extractTmdbIdFromHtml(html)
        
        // URL DO FEMBED
        val fembedUrl = if (tmdbId != null) {
            "https://fembed.sx/e/$tmdbId"
        } else {
            // Fallback: procura iframe do Fembed
            document.selectFirst("iframe[src*='fembed']")?.attr("src")
        }
        
        // VERIFICAR SE É SÉRIE
        val isSerie = url.contains("/serie/") || jsonLd.type == "TVSeries"
        
        return if (isSerie) {
            val episodes = extractEpisodes(document, tmdbId)
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, fembedUrl ?: "") {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    // 🔥 NOVA FUNÇÃO: EXTRAIR DADOS DO JSON-LD 🔥
    private data class JsonLdData(
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

    private fun extractJsonLdData(html: String): JsonLdData {
        val jsonLdPattern = Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        val matches = jsonLdPattern.findAll(html)
        
        matches.forEach { match ->
            try {
                val jsonStr = match.groupValues[1].trim()
                if (jsonStr.contains("\"@type\":\"Movie\"") || jsonStr.contains("\"@type\":\"TVSeries\"")) {
                    // Extrair dados básicos com regex simples
                    val title = Regex("\"name\":\"([^\"]+)\"").find(jsonStr)?.groupValues?.get(1)
                    val description = Regex("\"description\":\"([^\"]+)\"").find(jsonStr)?.groupValues?.get(1)
                    val image = Regex("\"image\":\"([^\"]+)\"").find(jsonStr)?.groupValues?.get(1)
                    
                    // Extrair ano da dataPublished
                    val datePublished = Regex("\"datePublished\":\"([^\"]+)\"").find(jsonStr)?.groupValues?.get(1)
                    val year = datePublished?.substring(0, 4)?.toIntOrNull()
                    
                    // Extrair gêneros
                    val genresMatch = Regex("\"genre\":\\s*\\[([^\\]]+)\\]").find(jsonStr)
                    val genres = genresMatch?.groupValues?.get(1)
                        ?.split(",")
                        ?.map { it.trim(' ', '"', '\'') }
                        ?.filter { it.isNotBlank() }
                    
                    // Extrair atores
                    val actorsSection = Regex("\"actor\":\\s*\\[([^\\]]+)\\]").find(jsonStr)
                    val actors = actorsSection?.groupValues?.get(1)
                        ?.split("},")
                        ?.mapNotNull { actorStr ->
                            Regex("\"name\":\"([^\"]+)\"").find(actorStr)?.groupValues?.get(1)
                        }
                    
                    // Extrair diretor
                    val directorSection = Regex("\"director\":\\s*\\[([^\\]]+)\\]").find(jsonStr)
                    val director = directorSection?.groupValues?.get(1)
                        ?.split("},")
                        ?.mapNotNull { dirStr ->
                            Regex("\"name\":\"([^\"]+)\"").find(dirStr)?.groupValues?.get(1)
                        }
                    
                    // Extrair TMDB ID
                    val sameAs = Regex("\"sameAs\":\\s*\\[([^\\]]+)\\]").find(jsonStr)
                    val tmdbId = sameAs?.groupValues?.get(1)
                        ?.split(",")
                        ?.find { it.contains("themoviedb.org") }
                        ?.substringAfterLast("/")
                        ?.trim(' ', '"', '\'')
                    
                    // Verificar tipo
                    val type = if (jsonStr.contains("\"@type\":\"Movie\"")) "Movie" 
                              else if (jsonStr.contains("\"@type\":\"TVSeries\"")) "TVSeries"
                              else null
                    
                    return JsonLdData(
                        title = title,
                        year = year,
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
                // Continua para o próximo JSON-LD
            }
        }
        
        return JsonLdData()
    }

    private fun extractTmdbIdFromHtml(html: String): String? {
        // Procura por padrões com TMDB
        val patterns = listOf(
            Regex("https://www.themoviedb.org/movie/(\\d+)"),
            Regex("tmdb.org/movie/(\\d+)"),
            Regex("""data-id=["'](\d+)["']"""),
            Regex("""id=["']movie_(\d+)["']""")
        )
        
        patterns.forEach { pattern ->
            pattern.find(html)?.groupValues?.get(1)?.let { id ->
                if (id.isNotBlank() && id.length in 4..10) {
                    return id
                }
            }
        }
        
        return null
    }

    private fun extractEpisodes(document: org.jsoup.nodes.Document, tmdbId: String?): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Tentar extrair episódios
        document.select(".season, .temporada").forEachIndexed { seasonIndex, season ->
            val seasonNum = seasonIndex + 1
            
            season.select(".episode, .episodio").forEachIndexed { epIndex, ep ->
                val epTitle = ep.selectFirst(".title, .name")?.text() ?: "Episódio ${epIndex + 1}"
                val epUrl = ep.selectFirst("a")?.attr("href") ?: ""
                val epNum = ep.selectFirst(".number, .ep")?.text()?.toIntOrNull() ?: (epIndex + 1)
                
                // Criar URL do Fembed para episódio (se tiver TMDB ID)
                val finalUrl = if (tmdbId != null && epUrl.isBlank()) {
                    // Tentar criar URL baseada no padrão
                    "https://fembed.sx/e/$tmdbId?ep=$epNum"
                } else {
                    fixUrl(epUrl)
                }
                
                if (finalUrl.isNotBlank()) {
                    episodes.add(
                        newEpisode(finalUrl) {
                            this.name = epTitle
                            this.season = seasonNum
                            this.episode = epNum
                        }
                    )
                }
            }
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
        
        // 🔥 ESTRATÉGIA PRINCIPAL: FEMBED 🔥
        if (data.contains("fembed.sx")) {
            // Limpar e garantir URL correta
            val cleanUrl = if (data.startsWith("http")) data else "https://$data"
            return loadExtractor(cleanUrl, mainUrl, subtitleCallback, callback)
        }
        
        // Se não for Fembed, fazer requisição
        try {
            val finalUrl = if (data.startsWith("/")) "$mainUrl$data" else data
            val res = app.get(finalUrl, referer = mainUrl)
            val html = res.text
            
            // Procurar Fembed em scripts
            val fembedPattern = Regex("""https?://fembed\.sx/e/\d+""")
            val fembedMatch = fembedPattern.find(html)
            
            if (fembedMatch != null) {
                val fembedUrl = fembedMatch.value
                return loadExtractor(fembedUrl, finalUrl, subtitleCallback, callback)
            }
            
            // Procurar iframes do Fembed
            val iframePattern = Regex("""<iframe[^>]+src=["'](https?://[^"']+fembed[^"']+)["']""")
            val iframeMatch = iframePattern.find(html)
            
            if (iframeMatch != null) {
                val iframeUrl = iframeMatch.groupValues[1]
                return loadExtractor(iframeUrl, finalUrl, subtitleCallback, callback)
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return false
    }
}