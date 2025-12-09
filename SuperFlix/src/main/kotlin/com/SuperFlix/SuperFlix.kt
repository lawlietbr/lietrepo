package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val usesWebView = true

    // API do TMDB
    private val tmdbApiKey = "f9a1e262f2251496b1efa1cd5759680a"
    private val tmdbBaseUrl = "https://api.themoviedb.org/3"
    private val tmdbImageUrl = "https://image.tmdb.org/t/p"

    // =========================================================================
    // PÁGINA PRINCIPAL
    // =========================================================================
    override val mainPage = mainPageOf(
        "$mainUrl/lancamentos" to "Lançamentos",
        "$mainUrl/filmes" to "Filmes",
        "$mainUrl/series" to "Séries",
        "$mainUrl/animes" to "Animes"
    )

    // =========================================================================
    // PÁGINA PRINCIPAL
    // =========================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "?page=$page" else ""
        val document = app.get(url).document

        val home = document.select("a.card").mapNotNull { element ->
            element.toSearchResult()
        }

        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    // =========================================================================
    // FUNÇÃO AUXILIAR PARA EXTRAR DADOS DO CARD
    // =========================================================================
    private fun Element.toSearchResult(): SearchResponse? {
        try {
            val url = this.attr("href") ?: return null
            val titleElement = this.selectFirst(".card-title")
            val title = titleElement?.text()?.trim() ?: return null
            
            val image = this.selectFirst(".card-img")?.attr("src")
            
            // Determinar se é Filme ou Série pelo badge ou URL
            val badge = this.selectFirst(".badge-kind")?.text()?.lowercase()
            val type = when {
                badge?.contains("série") == true -> TvType.TvSeries
                badge?.contains("serie") == true -> TvType.TvSeries
                badge?.contains("filme") == true -> TvType.Movie
                url.contains("/serie/") -> TvType.TvSeries
                else -> TvType.Movie
            }
            
            // Extrair ano do título (ex: "Amy (2015)")
            val yearMatch = Regex("\\((\\d{4})\\)").find(title)
            val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()
            
            // Limpar título (remover ano)
            val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
            
            return if (type == TvType.TvSeries) {
                newTvSeriesSearchResponse(cleanTitle, fixUrl(url), TvType.TvSeries) {
                    this.posterUrl = image?.let { fixUrl(it) }
                    this.year = year
                }
            } else {
                newMovieSearchResponse(cleanTitle, fixUrl(url), TvType.Movie) {
                    this.posterUrl = image?.let { fixUrl(it) }
                    this.year = year
                }
            }
            
        } catch (e: Exception) {
            println("❌ Erro em toSearchResult: ${e.message}")
            return null
        }
    }

    // =========================================================================
    // BUSCA - CORRIGIDA COM ESTRUTURA REAL DO SITE
    // =========================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        println("🔍 SuperFlix: Buscando '$query'")
        
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        // URL CORRETA baseada na inspeção do site
        val searchUrl = "$mainUrl/buscar?q=$encodedQuery"
        println("🔍 URL de busca: $searchUrl")
        
        val document = app.get(searchUrl).document
        
        // Selecionar todos os cards de resultados (estrutura real)
        val cards = document.select("div.grid a.card")
        println("📊 Encontrados ${cards.size} cards")
        
        val results = cards.mapNotNull { card ->
            card.toSearchResult()
        }.distinctBy { it.url }
        
        println("✅ SuperFlix: ${results.size} resultados para '$query'")
        
        // Debug: mostrar primeiros resultados
        results.take(5).forEachIndexed { index, result ->
            println("  ${index + 1}. ${result.name} (${result.url})")
        }
        
        return results
    }

    // =========================================================================
    // CARREGAR DETALHES (COM TMDB INTEGRADO)
    // =========================================================================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // 1. Extrai título
        val title = document.selectFirst("h1")?.text() ?: return null

        // 2. Determinar tipo pela URL
        val isSerie = url.contains("/serie/")

        // 3. Extrair ano do título
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

        println("🎬 SuperFlix: Carregando '$cleanTitle' (${if (isSerie) "Série" else "Filme"}, Ano: $year)")

        // 4. Tenta buscar no TMDB
        val tmdbInfo = if (isSerie) {
            searchOnTMDB(cleanTitle, year, true)
        } else {
            searchOnTMDB(cleanTitle, year, false)
        }

        // 5. Se encontrou no TMDB, usa dados enriquecidos
        return if (tmdbInfo != null) {
            println("✅ SuperFlix: Dados do TMDB encontrados para '$cleanTitle'")
            createLoadResponseWithTMDB(tmdbInfo, url, document, isSerie)
        } else {
            println("⚠️ SuperFlix: Usando dados do site para '$cleanTitle'")
            // 6. Fallback para dados do site
            createLoadResponseFromSite(document, url, cleanTitle, year, isSerie)
        }
    }

    // =========================================================================
    // BUSCA NO TMDB (API DIRETA)
    // =========================================================================
    private suspend fun searchOnTMDB(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        return try {
            val type = if (isTv) "tv" else "movie"
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val yearParam = year?.let { "&year=$it" } ?: ""

            val searchUrl = "$tmdbBaseUrl/search/$type?" +
                           "api_key=$tmdbApiKey" +
                           "&language=pt-BR" +
                           "&query=$encodedQuery" +
                           yearParam

            println("🔍 TMDB: Buscando '$query' ($type)")
            val response = app.get(searchUrl, timeout = 10_000)
            val searchResult = response.parsedSafe<TMDBSearchResponse>()

            val result = searchResult?.results?.firstOrNull()
            if (result == null) {
                println("❌ TMDB: Nenhum resultado para '$query'")
                return null
            }

            println("✅ TMDB: Encontrado '${if (isTv) result.name else result.title}' (ID: ${result.id})")

            // Busca detalhes completos
            val details = getTMDBDetails(result.id, isTv)

            TMDBInfo(
                id = result.id,
                title = if (isTv) result.name else result.title,
                year = if (isTv) result.first_air_date?.substring(0, 4)?.toIntOrNull()
                      else result.release_date?.substring(0, 4)?.toIntOrNull(),
                posterUrl = result.poster_path?.let { "$tmdbImageUrl/w500$it" },
                backdropUrl = details?.backdrop_path?.let { "$tmdbImageUrl/original$it" },
                overview = details?.overview,
                genres = details?.genres?.map { it.name },
                actors = details?.credits?.cast?.take(10)?.map { actor ->
                    Actor(actor.name, actor.profile_path?.let { "$tmdbImageUrl/w185$it" })
                },
                youtubeTrailer = details?.videos?.results
                    ?.find { it.site == "YouTube" && it.type == "Trailer" }
                    ?.key,
                duration = if (!isTv) details?.runtime else null,
                recommendations = details?.recommendations?.results?.take(5)?.map { rec ->
                    TMDBRecommendation(
                        title = if (isTv) rec.name else rec.title,
                        posterUrl = rec.poster_path?.let { "$tmdbImageUrl/w500$it" },
                        recYear = if (isTv) rec.first_air_date?.substring(0, 4)?.toIntOrNull()
                                 else rec.release_date?.substring(0, 4)?.toIntOrNull(),
                        isMovie = !isTv
                    )
                }
            )
        } catch (e: Exception) {
            println("❌ TMDB: Erro na busca - ${e.message}")
            null
        }
    }

    // =========================================================================
    // DETALHES DO TMDB
    // =========================================================================
    private suspend fun getTMDBDetails(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        return try {
            val type = if (isTv) "tv" else "movie"
            val url = "$tmdbBaseUrl/$type/$id?" +
                     "api_key=$tmdbApiKey" +
                     "&language=pt-BR" +
                     "&append_to_response=credits,videos,recommendations"

            app.get(url, timeout = 10_000).parsedSafe<TMDBDetailsResponse>()
        } catch (e: Exception) {
            println("❌ TMDB: Erro nos detalhes - ${e.message}")
            null
        }
    }

    // =========================================================================
    // CRIAR RESPOSTA COM TMDB
    // =========================================================================
    private suspend fun createLoadResponseWithTMDB(
        tmdbInfo: TMDBInfo,
        url: String,
        document: org.jsoup.nodes.Document,
        isSerie: Boolean
    ): LoadResponse {
        return if (isSerie) {
            val episodes = extractEpisodesFromDocument(document, url)

            newTvSeriesLoadResponse(
                name = tmdbInfo.title ?: "",
                url = url,
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                this.posterUrl = tmdbInfo.posterUrl
                this.backgroundPosterUrl = tmdbInfo.backdropUrl
                this.year = tmdbInfo.year
                this.plot = tmdbInfo.overview
                this.tags = tmdbInfo.genres

                tmdbInfo.actors?.let { addActors(it) }
                tmdbInfo.youtubeTrailer?.let { addTrailer(it) }

                // CORREÇÃO: Criar recomendação sem suspensão
                this.recommendations = tmdbInfo.recommendations?.map { rec ->
                    if (rec.isMovie) {
                        newMovieSearchResponse(rec.title ?: "", "") {
                            this.posterUrl = rec.posterUrl
                            this.year = rec.recYear
                        }
                    } else {
                        newTvSeriesSearchResponse(rec.title ?: "", "") {
                            this.posterUrl = rec.posterUrl
                            this.year = rec.recYear
                        }
                    }
                }
            }
        } else {
            // CORREÇÃO: Usar a sobrecarga correta com dataUrl vazio
            newMovieLoadResponse(
                name = tmdbInfo.title ?: "",
                url = url,
                type = TvType.Movie,
                dataUrl = ""
            ) {
                this.posterUrl = tmdbInfo.posterUrl
                this.backgroundPosterUrl = tmdbInfo.backdropUrl
                this.year = tmdbInfo.year
                this.plot = tmdbInfo.overview
                this.tags = tmdbInfo.genres
                this.duration = tmdbInfo.duration

                tmdbInfo.actors?.let { addActors(it) }
                tmdbInfo.youtubeTrailer?.let { addTrailer(it) }

                // CORREÇÃO: Criar recomendação sem suspensão
                this.recommendations = tmdbInfo.recommendations?.map { rec ->
                    if (rec.isMovie) {
                        newMovieSearchResponse(rec.title ?: "", "") {
                            this.posterUrl = rec.posterUrl
                            this.year = rec.recYear
                        }
                    } else {
                        newTvSeriesSearchResponse(rec.title ?: "", "") {
                            this.posterUrl = rec.posterUrl
                            this.year = rec.recYear
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // FALLBACK: DADOS DO SITE
    // =========================================================================
    private suspend fun createLoadResponseFromSite(
        document: org.jsoup.nodes.Document,
        url: String,
        title: String,
        year: Int?,
        isSerie: Boolean
    ): LoadResponse {
        // Extrair imagem da meta tag
        val ogImage = document.selectFirst("meta[property='og:image']")?.attr("content")
        val poster = ogImage?.let { fixUrl(it) }

        // Extrair descrição
        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
        val plot = description ?: document.selectFirst("meta[name='description']")?.attr("content")

        // Extrair tags/gêneros
        val tags = document.select("a[href*='/categoria/']").map { it.text() }.takeIf { it.isNotEmpty() }

        return if (isSerie) {
            val episodes = extractEpisodesFromDocument(document, url)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        } else {
            // CORREÇÃO: Usar dataUrl vazio
            newMovieLoadResponse(title, url, TvType.Movie, "") {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        }
    }

    // =========================================================================
    // EXTRAIR EPISÓDIOS
    // =========================================================================
    private fun extractEpisodesFromDocument(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        // Tentar diferentes seletores para episódios
        val episodeButtons = document.select("button[data-url], a[href*='episodio']")
        
        return episodeButtons.mapIndexed { index, element ->
            val episodeUrl = element.attr("data-url") ?: element.attr("href")
            val episodeName = element.text().takeIf { it.isNotBlank() } ?: "Episódio ${index + 1}"
            
            newEpisode(fixUrl(episodeUrl)) {
                this.name = episodeName
                this.episode = index + 1
                this.season = 1
            }
        }
    }

    // =========================================================================
    // CLASSES DE DADOS PARA TMDB
    // =========================================================================
    private data class TMDBInfo(
        val id: Int,
        val title: String?,
        val year: Int?,
        val posterUrl: String?,
        val backdropUrl: String?,
        val overview: String?,
        val genres: List<String>?,
        val actors: List<Actor>?,
        val youtubeTrailer: String?,
        val duration: Int?,
        val recommendations: List<TMDBRecommendation>?
    )

    private data class TMDBRecommendation(
        val title: String?,
        val posterUrl: String?,
        val recYear: Int?, // CORREÇÃO: renomeado de 'year' para 'recYear'
        val isMovie: Boolean
    )

    private data class TMDBSearchResponse(
        @JsonProperty("results") val results: List<TMDBResult>
    )

    private data class TMDBResult(
        val id: Int,
        val title: String? = null,
        val name: String? = null,
        val release_date: String? = null,
        val first_air_date: String? = null,
        val poster_path: String?
    )

    private data class TMDBDetailsResponse(
        val overview: String?,
        val backdrop_path: String?,
        val runtime: Int?,
        val genres: List<TMDBGenre>?,
        val credits: TMDBCredits?,
        val videos: TMDBVideos?,
        val recommendations: TMDBRecommendationsResponse?
    )

    private data class TMDBGenre(val name: String)
    private data class TMDBCredits(val cast: List<TMDBCast>)
    private data class TMDBCast(val name: String, val profile_path: String?)
    private data class TMDBVideos(val results: List<TMDBVideo>)
    private data class TMDBVideo(val key: String, val site: String, val type: String)
    private data class TMDBRecommendationsResponse(val results: List<TMDBRecommendationResult>)

    private data class TMDBRecommendationResult(
        val id: Int,
        val title: String? = null,
        val name: String? = null,
        val poster_path: String?,
        val release_date: String? = null,
        val first_air_date: String? = null
    )

    // =========================================================================
    // CARREGAR LINKS DE VÍDEO
    // =========================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Procurar iframes ou players
            val document = app.get(data).document
            val iframeSrc = document.selectFirst("iframe[src*='fembed'], iframe[src*='player'], iframe[src*='embed']")?.attr("src")
            
            if (iframeSrc != null) {
                loadExtractor(iframeSrc, mainUrl, subtitleCallback, callback)
                true
            } else {
                // Fallback: tentar extrair links diretos
                val videoLinks = document.select("a[href*='.m3u8'], a[href*='.mp4']")
                videoLinks.forEach { link ->
                    loadExtractor(link.attr("href"), mainUrl, subtitleCallback, callback)
                }
                videoLinks.isNotEmpty()
            }
        } catch (e: Exception) {
            println("❌ Erro ao carregar links: ${e.message}")
            false
        }
    }
}