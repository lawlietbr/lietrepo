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
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = true

    // SUA API KEY DO TMDB (funcional!)
    private val tmdbApiKey = "f9a1e262f2251496b1efa1cd5759680a"
    private val tmdbBaseUrl = "https://api.themoviedb.org/3"
    private val tmdbImageUrl = "https://image.tmdb.org/t/p"

    // =========================================================================
    // PÁGINA PRINCIPAL
    // =========================================================================
    override val mainPage = mainPageOf(
        "$mainUrl/lancamentos" to "Lançamentos",
        "$mainUrl/filmes" to "Últimos Filmes",
        "$mainUrl/series" to "Últimas Séries",
        "$mainUrl/animes" to "Últimas Animes"
    )

    // =========================================================================
    // PÁGINA PRINCIPAL
    // =========================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "?page=$page" else ""
        val document = app.get(url).document

        val home = document.select("a.card, div.recs-grid a.rec-card").mapNotNull { element ->
            element.toSearchResult()
        }

        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    // =========================================================================
    // FUNÇÃO AUXILIAR
    // =========================================================================
    private fun Element.toSearchResult(): SearchResponse? {
        val title = attr("title") ?: selectFirst("img")?.attr("alt") ?: return null
        val href = attr("href") ?: return null

        val localPoster = selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        // Verifica tipo de conteúdo
        val badge = selectFirst(".badge-kind")?.text()?.lowercase() ?: ""
        val isAnime = badge.contains("anime") || href.contains("/anime/") || 
                      title.contains("(Anime)", ignoreCase = true)
        val isSerie = badge.contains("série") || badge.contains("serie") || 
                     href.contains("/serie/") || 
                     (!isAnime && (badge.contains("tv") || href.contains("/tv/")))
        val isMovie = !isSerie && !isAnime

        return when {
            isAnime -> {
                newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                    this.posterUrl = localPoster
                    this.year = year
                }
            }
            isSerie -> {
                newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = localPoster
                    this.year = year
                }
            }
            else -> {
                newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                    this.posterUrl = localPoster
                    this.year = year
                }
            }
        }
    }

    // =========================================================================
    // BUSCA CORRIGIDA
    // =========================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/buscar?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select(".grid .card, a.card").mapNotNull { card ->
            try {
                val title = card.attr("title") ?: card.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
                val href = card.attr("href") ?: return@mapNotNull null

                val poster = card.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

                val badge = card.selectFirst(".badge-kind")?.text()?.lowercase() ?: ""
                val isAnime = badge.contains("anime") || href.contains("/anime/") || 
                             title.contains("(Anime)", ignoreCase = true)
                val isSerie = badge.contains("série") || badge.contains("serie") || 
                             href.contains("/serie/") || 
                             (!isAnime && (badge.contains("tv") || href.contains("/tv/")))

                when {
                    isAnime -> newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    isSerie -> newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    else -> newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                    }
                }
            } catch (e: Exception) {
                println("❌ Erro ao processar card de busca: ${e.message}")
                null
            }
        }
    }

    // =========================================================================
    // CARREGAR DETALHES (COM TMDB INTEGRADO)
    // =========================================================================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // 1. Extrai info básica
        val titleElement = document.selectFirst("h1, .title")
        val title = titleElement?.text() ?: return null

        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        // Determina tipo de conteúdo
        val isAnime = url.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
        val isSerie = url.contains("/serie/") || url.contains("/tv/") || 
                     (!isAnime && document.selectFirst(".episode-list, .season-list") != null)

        println("🎬 SuperFlix: Carregando '$cleanTitle' (Tipo: ${when {
            isAnime -> "Anime"
            isSerie -> "Série"
            else -> "Filme"
        }}, Ano: $year)")

        // 2. Tenta buscar no TMDB
        val tmdbInfo = if (isAnime || isSerie) {
            searchOnTMDB(cleanTitle, year, true)
        } else {
            searchOnTMDB(cleanTitle, year, false)
        }

        // 3. Se encontrou no TMDB, usa dados enriquecidos
        return if (tmdbInfo != null) {
            println("✅ SuperFlix: Dados do TMDB encontrados para '$cleanTitle'")
            createLoadResponseWithTMDB(tmdbInfo, url, document, isAnime, isSerie)
        } else {
            println("⚠️ SuperFlix: Usando dados do site para '$cleanTitle'")
            // 4. Fallback para dados do site
            createLoadResponseFromSite(document, url, cleanTitle, year, isAnime, isSerie)
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
                    ?.find { it.site == "YouTube" && (it.type == "Trailer" || it.type == "Teaser") }
                    ?.key,
                duration = if (!isTv) details?.runtime else null,
                recommendations = details?.recommendations?.results?.take(5)?.map { rec ->
                    TMDBRecommendation(
                        title = if (isTv) rec.name else rec.title,
                        posterUrl = rec.poster_path?.let { "$tmdbImageUrl/w500$it" },
                        year = if (isTv) rec.first_air_date?.substring(0, 4)?.toIntOrNull()
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
    // DETALHES DO TMDB COM INFORMAÇÕES DE TEMPORADAS
    // =========================================================================
    private suspend fun getTMDBDetails(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        return try {
            val type = if (isTv) "tv" else "movie"
            val url = "$tmdbBaseUrl/$type/$id?" +
                     "api_key=$tmdbApiKey" +
                     "&language=pt-BR" +
                     "&append_to_response=credits,videos,recommendations" +
                     if (isTv) ",season/1" else ""

            val response = app.get(url, timeout = 10_000)
            val details = response.parsedSafe<TMDBDetailsResponse>()
            
            if (isTv && details != null) {
                // Para séries, busca informações da primeira temporada
                val season1 = getTMDBSeasonDetails(id, 1)
                if (season1 != null) {
                    // Anexa informações da primeira temporada
                    details.season1Episodes = season1.episodes
                }
            }
            
            details
        } catch (e: Exception) {
            println("❌ TMDB: Erro nos detalhes - ${e.message}")
            null
        }
    }

    // =========================================================================
    // DETALHES DA TEMPORADA DO TMDB
    // =========================================================================
    private suspend fun getTMDBSeasonDetails(seriesId: Int, seasonNumber: Int): TMDBSeasonResponse? {
        return try {
            val url = "$tmdbBaseUrl/tv/$seriesId/season/$seasonNumber?" +
                     "api_key=$tmdbApiKey" +
                     "&language=pt-BR"
            
            app.get(url, timeout = 10_000).parsedSafe<TMDBSeasonResponse>()
        } catch (e: Exception) {
            println("❌ TMDB: Erro na temporada - ${e.message}")
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
        isAnime: Boolean,
        isSerie: Boolean
    ): LoadResponse {
        return if (isAnime || isSerie) {
            // Para séries/animes: extrai episódios e tenta obter títulos do TMDB
            val episodes = extractEpisodesWithTMDBInfo(
                document = document,
                url = url,
                tmdbInfo = tmdbInfo,
                isAnime = isAnime
            )

            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            
            newTvSeriesLoadResponse(
                name = tmdbInfo.title ?: "",
                url = url,
                type = type,
                episodes = episodes
            ) {
                this.posterUrl = tmdbInfo.posterUrl
                this.backgroundPosterUrl = tmdbInfo.backdropUrl
                this.year = tmdbInfo.year
                this.plot = tmdbInfo.overview
                this.tags = tmdbInfo.genres

                tmdbInfo.actors?.let { addActors(it) }
                
                // CORREÇÃO: Adiciona trailer corretamente para CloudStream 3
                tmdbInfo.youtubeTrailer?.let { trailerKey ->
                    val trailerUrl = "https://www.youtube.com/watch?v=$trailerKey"
                    addTrailer(trailerUrl)
                }

                this.recommendations = tmdbInfo.recommendations?.map { rec ->
                    if (rec.isMovie) {
                        newMovieSearchResponse(rec.title ?: "", "", TvType.Movie) {
                            this.posterUrl = rec.posterUrl
                            this.year = rec.year
                        }
                    } else {
                        newTvSeriesSearchResponse(rec.title ?: "", "", TvType.TvSeries) {
                            this.posterUrl = rec.posterUrl
                            this.year = rec.year
                        }
                    }
                }
            }
        } else {
            // Para filmes
            val playerUrl = findPlayerUrl(document)

            newMovieLoadResponse(
                name = tmdbInfo.title ?: "",
                url = url,
                type = TvType.Movie,
                dataUrl = playerUrl ?: url
            ) {
                this.posterUrl = tmdbInfo.posterUrl
                this.backgroundPosterUrl = tmdbInfo.backdropUrl
                this.year = tmdbInfo.year
                this.plot = tmdbInfo.overview
                this.tags = tmdbInfo.genres
                this.duration = tmdbInfo.duration

                tmdbInfo.actors?.let { addActors(it) }
                
                // CORREÇÃO: Adiciona trailer corretamente para CloudStream 3
                tmdbInfo.youtubeTrailer?.let { trailerKey ->
                    val trailerUrl = "https://www.youtube.com/watch?v=$trailerKey"
                    addTrailer(trailerUrl)
                }

                this.recommendations = tmdbInfo.recommendations?.map { rec ->
                    if (rec.isMovie) {
                        newMovieSearchResponse(rec.title ?: "", "", TvType.Movie) {
                            this.posterUrl = rec.posterUrl
                            this.year = rec.year
                        }
                    } else {
                        newTvSeriesSearchResponse(rec.title ?: "", "", TvType.TvSeries) {
                            this.posterUrl = rec.posterUrl
                            this.year = rec.year
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // EXTRATIR EPISÓDIOS COM INFORMAÇÕES DO TMDB
    // =========================================================================
    private suspend fun extractEpisodesWithTMDBInfo(
        document: org.jsoup.nodes.Document,
        url: String,
        tmdbInfo: TMDBInfo?,
        isAnime: Boolean
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Primeiro tenta extrair do TMDB se disponível
        val tmdbEpisodes = tmdbInfo?.season1Episodes
        
        // Extrai botões de episódios do site
        val episodeButtons = document.select("button.bd-play[data-url], a[data-url]")
        
        if (episodeButtons.isNotEmpty()) {
            episodeButtons.forEachIndexed { index, button ->
                val episodeNumber = index + 1
                val dataUrl = button.attr("data-url")
                
                // Tenta obter informações do TMDB para este episódio
                val tmdbEpisode = tmdbEpisodes?.find { it.episode_number == episodeNumber }
                
                // Cria o episódio com todas as informações disponíveis
                val episode = newEpisode(fixUrl(dataUrl)) {
                    this.name = tmdbEpisode?.name ?: "Episódio $episodeNumber"
                    this.season = button.attr("data-season").toIntOrNull() ?: 1
                    this.episode = button.attr("data-ep").toIntOrNull() ?: episodeNumber
                    
                    // Adiciona thumbnail do episódio
                    this.posterUrl = tmdbEpisode?.still_path?.let { "$tmdbImageUrl/w300$it" }
                    
                    // Adiciona sinopse do episódio (description no CloudStream)
                    this.description = tmdbEpisode?.overview
                    
                    // Adiciona data de lançamento se disponível
                    tmdbEpisode?.air_date?.let { airDate ->
                        // Converte para timestamp se possível
                        try {
                            val dateFormatter = java.text.SimpleDateFormat("yyyy-MM-dd")
                            val date = dateFormatter.parse(airDate)
                            this.date = date.time
                        } catch (e: Exception) {
                            // Se não conseguir converter, usa como string
                            this.description = (this.description ?: "") + "\n\nLançado em: $airDate"
                        }
                    }
                    
                    // Adiciona duração para animes
                    if (isAnime) {
                        val duration = tmdbEpisode?.runtime ?: 24 // Padrão 24 minutos para animes
                        this.description = (this.description ?: "") + "\n\nDuração: ${duration}min"
                    }
                }
                
                episodes.add(episode)
            }
        } else {
            // Fallback: procura por lista de episódios em divs
            document.select(".episode-item, .episode-card").forEach { episodeItem ->
                val episodeLink = episodeItem.selectFirst("a[href], button[data-url]")
                val dataUrl = episodeLink?.attr("data-url") ?: episodeLink?.attr("href") ?: ""
                
                val epNumber = episodeItem.selectFirst(".ep-number, .number")?.text()?.toIntOrNull() ?: 
                              Regex("Ep\\.?\\s*(\\d+)").find(episodeItem.text())?.groupValues?.get(1)?.toIntOrNull() ?: 
                              episodes.size + 1
                
                val tmdbEpisode = tmdbEpisodes?.find { it.episode_number == epNumber }
                
                val episode = newEpisode(fixUrl(dataUrl)) {
                    this.name = tmdbEpisode?.name ?: 
                               episodeItem.selectFirst(".ep-title, .title")?.text()?.trim() ?: 
                               "Episódio $epNumber"
                    this.season = 1
                    this.episode = epNumber
                    
                    // Adiciona thumbnail
                    this.posterUrl = tmdbEpisode?.still_path?.let { "$tmdbImageUrl/w300$it" } ?:
                                   episodeItem.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                    
                    // Adiciona sinopse do episódio
                    this.description = tmdbEpisode?.overview
                    
                    // Adiciona data de lançamento
                    tmdbEpisode?.air_date?.let { airDate ->
                        try {
                            val dateFormatter = java.text.SimpleDateFormat("yyyy-MM-dd")
                            val date = dateFormatter.parse(airDate)
                            this.date = date.time
                        } catch (e: Exception) {
                            this.description = (this.description ?: "") + "\n\nLançado em: $airDate"
                        }
                    }
                }
                
                episodes.add(episode)
            }
        }
        
        return episodes
    }

    // =========================================================================
    // FALLBACK: DADOS DO SITE
    // =========================================================================
    private suspend fun createLoadResponseFromSite(
        document: org.jsoup.nodes.Document,
        url: String,
        title: String,
        year: Int?,
        isAnime: Boolean,
        isSerie: Boolean
    ): LoadResponse {
        val ogImage = document.selectFirst("meta[property='og:image']")?.attr("content")
        val poster = ogImage?.let { fixUrl(it) }

        val description = document.selectFirst("meta[name='description']")?.attr("content")
        val synopsis = document.selectFirst(".syn, .description, .sinopse, .plot")?.text()
        val plot = description ?: synopsis

        val tags = document.select("a.chip, .chip, .genre, .tags").map { it.text() }
            .takeIf { it.isNotEmpty() }?.toList()

        return if (isAnime || isSerie) {
            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            val episodes = extractEpisodesWithTMDBInfo(document, url, null, isAnime)
            
            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        } else {
            val playerUrl = findPlayerUrl(document)
            newMovieLoadResponse(title, url, TvType.Movie, playerUrl ?: url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        }
    }

    // =========================================================================
    // CLASSES DE DADOS PARA TMDB (ATUALIZADAS)
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
        val recommendations: List<TMDBRecommendation>?,
        val season1Episodes: List<TMDBEpisode>? = null
    )

    private data class TMDBRecommendation(
        val title: String?,
        val posterUrl: String?,
        val year: Int?,
        val isMovie: Boolean
    )

    private data class TMDBEpisode(
        val episode_number: Int,
        val name: String,
        val overview: String?,
        val still_path: String?,
        val runtime: Int?,
        val air_date: String?  // Adicionado: data de lançamento
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
        val recommendations: TMDBRecommendationsResponse?,
        var season1Episodes: List<TMDBEpisode>? = null
    )

    private data class TMDBSeasonResponse(
        @JsonProperty("episodes") val episodes: List<TMDBEpisode>,
        @JsonProperty("air_date") val air_date: String?  // Data da temporada
    )

    private data class TMDBGenre(val name: String)
    private data class TMDBCredits(val cast: List<TMDBCast>)
    private data class TMDBCast(val name: String, val profile_path: String?)
    private data class TMDBVideos(val results: List<TMDBVideo>)
    private data class TMDBVideo(val key: String, val site: String, val type: String)
    private data class TMDBRecommendationsResponse(val results: List<TMDBRecommendationResult>)

    private data class TMDBRecommendationResult(
        val title: String? = null,
        val name: String? = null,
        val poster_path: String?,
        val release_date: String? = null,
        val first_air_date: String? = null
    )

    // =========================================================================
    // FUNÇÕES RESTANTES
    // =========================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return SuperFlixExtractor.extractVideoLinks(data, mainUrl, name, callback)
    }

    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        val playButton = document.selectFirst("button.bd-play[data-url]")
        if (playButton != null) return playButton.attr("data-url")
        val iframe = document.selectFirst("iframe[src*='fembed'], iframe[src*='filemoon'], iframe[src*='player'], iframe[src*='embed']")
        if (iframe != null) return iframe.attr("src")
        val videoLink = document.selectFirst("a[href*='.m3u8'], a[href*='.mp4'], a[href*='watch']")
        return videoLink?.attr("href")
    }
}