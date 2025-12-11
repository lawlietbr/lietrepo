package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import java.text.SimpleDateFormat
import com.lietrepo.superflix.BuildConfig
import android.util.Log // Adicionado para logs

class SuperFlix : MainAPI() {
    // Tag para logs
    private val TAG = "SuperFlix_TMDB_Debug"
    
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = true

    private val tmdbApiKey = BuildConfig.TMDB_API_KEY
    private val tmdbBaseUrl = "https://api.themoviedb.org/3"
    private val tmdbImageUrl = "https://image.tmdb.org/t/p"

    // API do AnimeSkip para timestamps de abertura
    private val aniskipApiUrl = "https://api.aniskip.com/v2"

    // Função auxiliar para logs
    private fun logDebug(message: String) {
        Log.d(TAG, message)
    }
    
    private fun logError(message: String, exception: Exception? = null) {
        Log.e(TAG, message, exception)
    }
    
    private fun logWarning(message: String) {
        Log.w(TAG, message)
    }

    override val mainPage = mainPageOf(
        "$mainUrl/lancamentos" to "Lançamentos",
        "$mainUrl/filmes" to "Últimos Filmes",
        "$mainUrl/series" to "Últimas Séries",
        "$mainUrl/animes" to "Últimas Animes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        logDebug("Carregando página principal: ${request.name} - Página $page")
        val url = request.data + if (page > 1) "?page=$page" else ""
        logDebug("URL da página: $url")
        val document = app.get(url).document

        val home = document.select("a.card, div.recs-grid a.rec-card").mapNotNull { element ->
            element.toSearchResult()
        }

        logDebug("Itens encontrados na página principal: ${home.size}")
        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = attr("title") ?: selectFirst("img")?.attr("alt") ?: return null
        val href = attr("href") ?: return null

        val localPoster = selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

        val badge = selectFirst(".badge-kind")?.text()?.lowercase() ?: ""
        val isAnime = badge.contains("anime") || href.contains("/anime/") ||
                      title.contains("(Anime)", ignoreCase = true)
        val isSerie = badge.contains("série") || badge.contains("serie") ||
                     href.contains("/serie/") ||
                     (!isAnime && (badge.contains("tv") || href.contains("/tv/")))
        val isMovie = !isSerie && !isAnime

        logDebug("Resultado encontrado: $cleanTitle | Ano: $year | Tipo: ${if (isAnime) "Anime" else if (isSerie) "Série" else "Filme"}")

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

    override suspend fun search(query: String): List<SearchResponse> {
        logDebug("Realizando busca por: $query")
        val searchUrl = "$mainUrl/buscar?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        logDebug("URL de busca: $searchUrl")
        val document = app.get(searchUrl).document

        val results = document.select(".grid .card, a.card").mapNotNull { card ->
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
                logError("Erro ao processar resultado de busca", e)
                null
            }
        }

        logDebug("Resultados da busca: ${results.size} encontrados")
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        logDebug("=== INICIANDO CARREGAMENTO ===")
        logDebug("URL: $url")
        val document = app.get(url).document

        val titleElement = document.selectFirst("h1, .title")
        val title = titleElement?.text() ?: return null

        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

        val isAnime = url.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
        val isSerie = url.contains("/serie/") || url.contains("/tv/") ||
                     (!isAnime && document.selectFirst(".episode-list, .season-list, .seasons") != null)

        logDebug("Título: $cleanTitle")
        logDebug("Ano: $year")
        logDebug("Tipo: ${if (isAnime) "Anime" else if (isSerie) "Série" else "Filme"}")
        logDebug("Procurando no TMDB...")

        val tmdbInfo = if (isAnime || isSerie) {
            searchOnTMDB(cleanTitle, year, true)
        } else {
            searchOnTMDB(cleanTitle, year, false)
        }

        val siteRecommendations = extractRecommendationsFromSite(document)
        logDebug("Recomendações do site: ${siteRecommendations.size} encontradas")

        return if (tmdbInfo != null) {
            logDebug("TMDB encontrado: ${tmdbInfo.title} (ID: ${tmdbInfo.id})")
            createLoadResponseWithTMDB(tmdbInfo, url, document, isAnime, isSerie, siteRecommendations)
        } else {
            logWarning("TMDB não encontrado, usando dados do site")
            createLoadResponseFromSite(document, url, cleanTitle, year, isAnime, isSerie)
        }
    }

    private suspend fun searchOnTMDB(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        logDebug("=== INICIANDO BUSCA TMDB ===")
        logDebug("Query: $query")
        logDebug("Year: $year")
        logDebug("Type: ${if (isTv) "TV" else "Movie"}")
        
        // Verificar se a chave da API está disponível
        if (tmdbApiKey.isNullOrEmpty()) {
            logError("Chave da API TMDB está vazia ou nula!")
            logError("Valor da chave: '$tmdbApiKey'")
            return null
        } else {
            logDebug("Chave da API disponível (${tmdbApiKey.length} caracteres)")
            logDebug("Primeiros 10 caracteres da chave: ${tmdbApiKey.take(10)}...")
        }
        
        return try {
            val type = if (isTv) "tv" else "movie"
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val yearParam = year?.let { "&year=$it" } ?: ""

            val searchUrl = "$tmdbBaseUrl/search/$type?" +
                           "api_key=$tmdbApiKey" +
                           "&language=pt-BR" +
                           "&query=$encodedQuery" +
                           yearParam +
                           "&page=1"

            logDebug("URL da busca TMDB: ${searchUrl.replace(tmdbApiKey, "API_KEY_HIDDEN")}")
            
            val response = app.get(searchUrl, timeout = 10_000)
            logDebug("Status da resposta TMDB: ${response.code}")
            logDebug("Tamanho da resposta: ${response.text.length} caracteres")
            
            // Verificar se há erro na resposta
            if (!response.isSuccessful) {
                logError("Falha na requisição TMDB: ${response.code} - ${response.text.take(200)}")
                return null
            }
            
            val searchResult = response.parsedSafe<TMDBSearchResponse>()
            logDebug("Resultados encontrados: ${searchResult?.results?.size ?: 0}")

            val result = searchResult?.results?.firstOrNull()
            if (result == null) {
                logWarning("Nenhum resultado encontrado no TMDB para: $query")
                return null
            }
            
            logDebug("Resultado TMDB encontrado: ID=${result.id}, Nome='${result.title ?: result.name}'")

            val details = getTMDBDetailsWithFullCredits(result.id, isTv)
            if (details == null) {
                logWarning("Não foi possível obter detalhes do TMDB para ID: ${result.id}")
                return null
            }
            
            val seasonEpisodes = if (isTv && details != null) {
                logDebug("Buscando episódios de temporadas...")
                getTMDBAllSeasons(result.id)
            } else {
                emptyMap()
            }

            val allActors = details?.credits?.cast?.mapNotNull { actor ->
                if (actor.name.isNotBlank()) {
                    Actor(
                        name = actor.name,
                        image = actor.profile_path?.let { "$tmdbImageUrl/w185$it" }
                    )
                } else {
                    null
                }
            }
            logDebug("Atores encontrados: ${allActors?.size ?: 0}")

            val youtubeTrailer = getHighQualityTrailer(details?.videos?.results)
            logDebug("Trailer YouTube encontrado: ${youtubeTrailer != null}")

            val info = TMDBInfo(
                id = result.id,
                title = if (isTv) result.name else result.title,
                year = if (isTv) result.first_air_date?.substring(0, 4)?.toIntOrNull()
                      else result.release_date?.substring(0, 4)?.toIntOrNull(),
                posterUrl = result.poster_path?.let { "$tmdbImageUrl/w500$it" },
                backdropUrl = details?.backdrop_path?.let { "$tmdbImageUrl/original$it" },
                overview = details?.overview,
                genres = details?.genres?.map { it.name },
                actors = allActors?.take(15),
                youtubeTrailer = youtubeTrailer,
                duration = if (!isTv) details?.runtime else null,
                seasonsEpisodes = seasonEpisodes
            )
            
            logDebug("=== BUSCA TMDB CONCLUÍDA COM SUCESSO ===")
            logDebug("Título: ${info.title}")
            logDebug("Ano: ${info.year}")
            logDebug("Gêneros: ${info.genres?.size ?: 0}")
            logDebug("Temporadas encontradas: ${seasonEpisodes.size}")
            
            info
        } catch (e: Exception) {
            logError("Erro na busca TMDB para '$query'", e)
            logError("Mensagem de erro: ${e.message}")
            logError("Stack trace: ${e.stackTrace.take(5).joinToString("\n")}")
            null
        }
    }

    private fun getHighQualityTrailer(videos: List<TMDBVideo>?): String? {
        val result = videos?.mapNotNull { video ->
            when {
                video.site == "YouTube" && video.type == "Trailer" ->
                    Triple(video.key, 10, "YouTube Trailer")
                video.site == "YouTube" && video.type == "Teaser" ->
                    Triple(video.key, 8, "YouTube Teaser")
                video.site == "YouTube" && (video.type == "Clip" || video.type == "Featurette") ->
                    Triple(video.key, 5, "YouTube Clip")
                else -> null
            }
        }
        ?.sortedByDescending { it.second }
        ?.firstOrNull()
        ?.let { (key, _, _) ->
            "https://www.youtube.com/watch?v=$key"
        }
        
        logDebug("Trailer encontrado: ${result != null}")
        return result
    }

    private suspend fun getTMDBDetailsWithFullCredits(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        logDebug("Buscando detalhes TMDB para ID: $id, Tipo: ${if (isTv) "TV" else "Movie"}")
        return try {
            val type = if (isTv) "tv" else "movie"
            val url = "$tmdbBaseUrl/$type/$id?" +
                     "api_key=$tmdbApiKey" +
                     "&language=pt-BR" +
                     "&append_to_response=credits,videos,recommendations"

            logDebug("URL detalhes TMDB: ${url.replace(tmdbApiKey, "API_KEY_HIDDEN")}")
            
            val response = app.get(url, timeout = 10_000)
            logDebug("Status detalhes TMDB: ${response.code}")
            
            if (!response.isSuccessful) {
                logError("Falha ao buscar detalhes TMDB: ${response.code}")
                logError("Resposta: ${response.text.take(200)}")
                return null
            }
            
            val details = response.parsedSafe<TMDBDetailsResponse>()
            logDebug("Detalhes TMDB obtidos com sucesso: ${details != null}")
            
            details
        } catch (e: Exception) {
            logError("Erro ao buscar detalhes TMDB para ID $id", e)
            null
        }
    }

    private suspend fun getTMDBAllSeasons(seriesId: Int): Map<Int, List<TMDBEpisode>> {
        logDebug("Buscando todas as temporadas para série ID: $seriesId")
        return try {
            val seriesDetailsUrl = "$tmdbBaseUrl/tv/$seriesId?" +
                                  "api_key=$tmdbApiKey" +
                                  "&language=pt-BR"

            logDebug("URL séries TMDB: ${seriesDetailsUrl.replace(tmdbApiKey, "API_KEY_HIDDEN")}")
            
            val seriesResponse = app.get(seriesDetailsUrl, timeout = 10_000)
            logDebug("Status séries TMDB: ${seriesResponse.code}")
            
            val seriesDetails = seriesResponse.parsedSafe<TMDBTVDetailsResponse>()
            
            val seasonsEpisodes = mutableMapOf<Int, List<TMDBEpisode>>()

            seriesDetails?.seasons?.forEach { season ->
                if (season.season_number > 0) {
                    val seasonNumber = season.season_number
                    logDebug("Buscando temporada $seasonNumber (${season.episode_count} episódios)")
                    
                    val seasonData = getTMDBSeasonDetails(seriesId, seasonNumber)
                    seasonData?.episodes?.let { episodes ->
                        seasonsEpisodes[seasonNumber] = episodes
                        logDebug("Temporada $seasonNumber carregada: ${episodes.size} episódios")
                    }
                }
            }

            logDebug("Total de temporadas carregadas: ${seasonsEpisodes.size}")
            seasonsEpisodes
        } catch (e: Exception) {
            logError("Erro ao buscar temporadas TMDB para série $seriesId", e)
            emptyMap()
        }
    }

    private suspend fun getTMDBSeasonDetails(seriesId: Int, seasonNumber: Int): TMDBSeasonResponse? {
        logDebug("Buscando detalhes da temporada $seasonNumber para série $seriesId")
        return try {
            val url = "$tmdbBaseUrl/tv/$seriesId/season/$seasonNumber?" +
                     "api_key=$tmdbApiKey" +
                     "&language=pt-BR"

            logDebug("URL temporada TMDB: ${url.replace(tmdbApiKey, "API_KEY_HIDDEN")}")
            
            val response = app.get(url, timeout = 10_000)
            logDebug("Status temporada TMDB: ${response.code}")
            
            val seasonData = response.parsedSafe<TMDBSeasonResponse>()
            logDebug("Detalhes da temporada obtidos: ${seasonData != null}")
            
            seasonData
        } catch (e: Exception) {
            logError("Erro ao buscar detalhes da temporada $seasonNumber da série $seriesId", e)
            null
        }
    }

    private suspend fun createLoadResponseWithTMDB(
        tmdbInfo: TMDBInfo,
        url: String,
        document: org.jsoup.nodes.Document,
        isAnime: Boolean,
        isSerie: Boolean,
        siteRecommendations: List<SearchResponse>
    ): LoadResponse {
        logDebug("Criando LoadResponse com dados do TMDB")
        return if (isAnime || isSerie) {
            val episodes = extractEpisodesWithTMDBInfo(
                document = document,
                url = url,
                tmdbInfo = tmdbInfo,
                isAnime = isAnime,
                isSerie = isSerie
            )

            val type = if (isAnime) TvType.Anime else TvType.TvSeries

            logDebug("Criando série/anime: ${episodes.size} episódios encontrados")
            
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

                tmdbInfo.actors?.let { actors ->
                    logDebug("Adicionando ${actors.size} atores")
                    addActors(actors)
                }

                tmdbInfo.youtubeTrailer?.let { trailerUrl ->
                    logDebug("Adicionando trailer: $trailerUrl")
                    addTrailer(trailerUrl)
                }

                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
                logDebug("Recomendações: ${siteRecommendations.size}")
            }
        } else {
            val playerUrl = findPlayerUrl(document)
            logDebug("Criando filme. Player URL: ${playerUrl ?: "não encontrado"}")

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

                tmdbInfo.actors?.let { actors ->
                    logDebug("Adicionando ${actors.size} atores")
                    addActors(actors)
                }

                tmdbInfo.youtubeTrailer?.let { trailerUrl ->
                    logDebug("Adicionando trailer: $trailerUrl")
                    addTrailer(trailerUrl)
                }

                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
                logDebug("Recomendações: ${siteRecommendations.size}")
            }
        }
    }

    private fun extractRecommendationsFromSite(document: org.jsoup.nodes.Document): List<SearchResponse> {
        logDebug("Extraindo recomendações do site")
        return document.select(".recs-grid .rec-card, .recs-grid a").mapNotNull { element ->
            try {
                val href = element.attr("href") ?: return@mapNotNull null
                if (href.isBlank() || href == "#") return@mapNotNull null

                val imgElement = element.selectFirst("img")
                val title = imgElement?.attr("alt") ?:
                           element.selectFirst(".rec-title")?.text() ?:
                           element.attr("title") ?:
                           return@mapNotNull null

                val poster = imgElement?.attr("src")?.let { fixUrl(it) }
                val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()

                val isAnime = href.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
                val isSerie = href.contains("/serie/") || href.contains("/tv/")
                val isMovie = !isSerie && !isAnime

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
                logError("Erro ao extrair recomendação", e)
                null
            }
        }
    }

    private suspend fun extractEpisodesWithTMDBInfo(
        document: org.jsoup.nodes.Document,
        url: String,
        tmdbInfo: TMDBInfo?,
        isAnime: Boolean,
        isSerie: Boolean = false
    ): List<Episode> {
        logDebug("Extraindo episódios. TMDB disponível: ${tmdbInfo != null}")
        val episodes = mutableListOf<Episode>()

        val episodeElements = document.select("button.bd-play[data-url], a.episode-card, .episode-item, .episode-link, [class*='episode']")
        logDebug("Elementos de episódio encontrados: ${episodeElements.size}")

        if (episodeElements.isNotEmpty()) {
            episodeElements.forEachIndexed { index, element ->
                try {
                    val dataUrl = element.attr("data-url") ?: element.attr("href") ?: ""
                    if (dataUrl.isBlank()) return@forEachIndexed

                    val epNumber = extractEpisodeNumber(element, index + 1)
                    val seasonNumber = element.attr("data-season").toIntOrNull() ?: 1

                    val tmdbEpisode = findTMDBEpisode(tmdbInfo, seasonNumber, epNumber)

                    // Buscar timestamps de abertura do AnimeSkip (apenas para animes)
                    val skipInfo = if (isAnime && tmdbInfo?.id != null) {
                        logDebug("Buscando timestamps do AnimeSkip para episódio $epNumber")
                        getAnimeSkipInfo(tmdbInfo.id, seasonNumber, epNumber)
                    } else {
                        null
                    }

                    val episode = createEpisode(
                        dataUrl = dataUrl,
                        seasonNumber = seasonNumber,
                        episodeNumber = epNumber,
                        element = element,
                        tmdbEpisode = tmdbEpisode,
                        skipInfo = skipInfo,
                        isAnime = isAnime,
                        isSerie = isSerie
                    )

                    episodes.add(episode)
                } catch (e: Exception) {
                    logError("Erro ao extrair episódio", e)
                }
            }
        } else {
            document.select("[class*='episodio']").forEach { element ->
                try {
                    val link = element.selectFirst("a[href*='episode'], a[href*='episodio'], button[data-url]")
                    val dataUrl = link?.attr("data-url") ?: link?.attr("href") ?: ""
                    if (dataUrl.isBlank()) return@forEach

                    val epNumber = extractEpisodeNumber(element, episodes.size + 1)
                    val seasonNumber = 1

                    val tmdbEpisode = findTMDBEpisode(tmdbInfo, seasonNumber, epNumber)

                    // Buscar timestamps de abertura do AnimeSkip (apenas para animes)
                    val skipInfo = if (isAnime && tmdbInfo?.id != null) {
                        logDebug("Buscando timestamps do AnimeSkip para episódio $epNumber")
                        getAnimeSkipInfo(tmdbInfo.id, seasonNumber, epNumber)
                    } else {
                        null
                    }

                    val episode = createEpisode(
                        dataUrl = dataUrl,
                        seasonNumber = seasonNumber,
                        episodeNumber = epNumber,
                        element = element,
                        tmdbEpisode = tmdbEpisode,
                        skipInfo = skipInfo,
                        isAnime = isAnime,
                        isSerie = isSerie
                    )

                    episodes.add(episode)
                } catch (e: Exception) {
                    logError("Erro ao extrair episódio alternativo", e)
                }
            }
        }

        logDebug("Total de episódios extraídos: ${episodes.size}")
        return episodes
    }

    private fun extractEpisodeNumber(element: Element, default: Int): Int {
        val epNumber = element.attr("data-ep").toIntOrNull() ?:
               element.selectFirst(".ep-number, .number, .episode-number")?.text()?.toIntOrNull() ?:
               Regex("Ep\\.?\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               Regex("Epis[oó]dio\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               default
        
        logDebug("Número do episódio extraído: $epNumber")
        return epNumber
    }

    private fun findTMDBEpisode(tmdbInfo: TMDBInfo?, season: Int, episode: Int): TMDBEpisode? {
        val episodeFound = tmdbInfo?.seasonsEpisodes?.get(season)?.find { it.episode_number == episode }
        logDebug("Episódio TMDB encontrado para S${season}E${episode}: ${episodeFound != null}")
        return episodeFound
    }

    private suspend fun getAnimeSkipInfo(malId: Int?, seasonNumber: Int, episodeNumber: Int): AnimeSkipInfo? {
        if (malId == null) {
            logDebug("ID MAL não disponível para AnimeSkip")
            return null
        }

        logDebug("Buscando AnimeSkip para MAL ID: $malId, S$seasonNumberE$episodeNumber")
        
        return try {
            val url = "$aniskipApiUrl/skip-times/$malId/$seasonNumber/$episodeNumber" +
                     "?types[]=op&types[]=ed&types[]=mixed-op&types[]=recap&episodeLength="

            logDebug("URL AnimeSkip: $url")
            
            val response = app.get(
                url,
                headers = mapOf(
                    "Accept" to "application/json",
                    "Content-Type" to "application/json"
                ),
                timeout = 10_000
            )
            
            logDebug("Status AnimeSkip: ${response.code}")

            val result = response.parsedSafe<AnimeSkipResponse>()

            // Filtrar apenas aberturas (op) e evitar recaps
            val validSkips = result?.results?.filter { skip ->
                (skip.skipType == "op" || skip.skipType == "mixed-op") && 
                skip.skipType != "recap" &&
                skip.interval?.startTime != null && 
                skip.interval?.endTime != null &&
                skip.interval.startTime >= 0 &&
                skip.interval.endTime > skip.interval.startTime
            }

            logDebug("Skips válidos encontrados: ${validSkips?.size ?: 0}")
            
            if (validSkips?.isNotEmpty() == true) {
                // Usar o primeiro timestamp válido
                validSkips[0]?.let { skip ->
                    val info = AnimeSkipInfo(
                        startTime = skip.interval?.startTime,
                        endTime = skip.interval?.endTime,
                        skipType = skip.skipType
                    )
                    logDebug("AnimeSkip encontrado: ${info.skipType} ${info.startTime}s-${info.endTime}s")
                    info
                }
            } else {
                logDebug("Nenhum skip válido encontrado")
                null
            }
        } catch (e: Exception) {
            logError("Erro ao buscar AnimeSkip", e)
            null
        }
    }

    private fun createEpisode(
        dataUrl: String,
        seasonNumber: Int,
        episodeNumber: Int,
        element: Element,
        tmdbEpisode: TMDBEpisode?,
        skipInfo: AnimeSkipInfo? = null,
        isAnime: Boolean,
        isSerie: Boolean = false
    ): Episode {
        logDebug("Criando episódio S${seasonNumber}E${episodeNumber}")
        
        return newEpisode(fixUrl(dataUrl)) {
            this.name = tmdbEpisode?.name ?:
                       element.selectFirst(".ep-title, .title, .episode-title, h3, h4")?.text()?.trim() ?:
                       "Episódio $episodeNumber"

            this.season = seasonNumber
            this.episode = episodeNumber

            this.posterUrl = tmdbEpisode?.still_path?.let { "$tmdbImageUrl/w300$it" } ?:
                            element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

            val descriptionBuilder = StringBuilder()

            // Adicionar informação de skip no description se disponível
            skipInfo?.let { info ->
                info.startTime?.let { startTime ->
                    info.endTime?.let { endTime ->
                        if (startTime >= 0 && endTime > startTime) {
                            val skipTypeText = when (info.skipType) {
                                "op" -> "Abertura"
                                "ed" -> "Encerramento"
                                "mixed-op" -> "Abertura Mista"
                                else -> "Skip"
                            }
                            descriptionBuilder.append("⏭️ $skipTypeText: ${startTime.toInt()}s - ${endTime.toInt()}s\n\n")
                        }
                    }
                }
            }

            // Adicionar descrição do TMDB se disponível
            tmdbEpisode?.overview?.let { overview ->
                descriptionBuilder.append(overview)
            }

            tmdbEpisode?.air_date?.let { airDate ->
                try {
                    val dateFormatter = SimpleDateFormat("yyyy-MM-dd")
                    val date = dateFormatter.parse(airDate)
                    this.date = date.time
                    logDebug("Data do episódio: $airDate")
                } catch (e: Exception) {
                    logError("Erro ao parsear data", e)
                }
            }

            val duration = when {
                isAnime -> tmdbEpisode?.runtime ?: 24
                else -> tmdbEpisode?.runtime ?: 0
            }

            if (duration > 0 && descriptionBuilder.isNotEmpty()) {
                descriptionBuilder.append("\n\n- ${duration}min")
            } else if (duration > 0) {
                descriptionBuilder.append("- ${duration}min")
            }

            if ((isSerie || isAnime) && descriptionBuilder.isEmpty()) {
                element.selectFirst(".ep-desc, .description, .synopsis")?.text()?.trim()?.let { siteDescription ->
                    if (siteDescription.isNotBlank()) {
                        descriptionBuilder.append(siteDescription)
                    }
                }
            }

            this.description = descriptionBuilder.toString().takeIf { it.isNotEmpty() }
            
            logDebug("Episódio criado: S${seasonNumber}E${episodeNumber} - ${this.name}")
        }
    }

    private suspend fun createLoadResponseFromSite(
        document: org.jsoup.nodes.Document,
        url: String,
        title: String,
        year: Int?,
        isAnime: Boolean,
        isSerie: Boolean
    ): LoadResponse {
        logDebug("Criando LoadResponse com dados do site")
        
        val ogImage = document.selectFirst("meta[property='og:image']")?.attr("content")
        val poster = ogImage?.let { fixUrl(it) }
        logDebug("Poster do site: ${poster != null}")

        val description = document.selectFirst("meta[name='description']")?.attr("content")
        val synopsis = document.selectFirst(".syn, .description, .sinopse, .plot")?.text()
        val plot = description ?: synopsis
        logDebug("Plot do site: ${plot?.length ?: 0} caracteres")

        val tags = document.select("a.chip, .chip, .genre, .tags").map { it.text() }
            .takeIf { it.isNotEmpty() }?.toList()
        logDebug("Tags do site: ${tags?.size ?: 0}")

        val siteRecommendations = extractRecommendationsFromSite(document)

        return if (isAnime || isSerie) {
            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            val episodes = extractEpisodesWithTMDBInfo(document, url, null, isAnime, isSerie)

            logDebug("Criando série/anime do site: ${episodes.size} episódios")
            
            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
            }
        } else {
            val playerUrl = findPlayerUrl(document)
            logDebug("Criando filme do site. Player URL: ${playerUrl ?: "não encontrado"}")
            
            newMovieLoadResponse(title, url, TvType.Movie, playerUrl ?: url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        logDebug("Extraindo links de vídeo para: $data")
        return SuperFlixExtractor.extractVideoLinks(data, mainUrl, name, callback)
    }

    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        logDebug("Buscando URL do player")
        val playButton = document.selectFirst("button.bd-play[data-url]")
        if (playButton != null) {
            logDebug("URL do player encontrado no botão: ${playButton.attr("data-url")}")
            return playButton.attr("data-url")
        }
        val iframe = document.selectFirst("iframe[src*='fembed'], iframe[src*='filemoon'], iframe[src*='player'], iframe[src*='embed']")
        if (iframe != null) {
            logDebug("URL do player encontrado no iframe: ${iframe.attr("src")}")
            return iframe.attr("src")
        }
        val videoLink = document.selectFirst("a[href*='.m3u8'], a[href*='.mp4'], a[href*='watch']")
        val result = videoLink?.attr("href")
        logDebug("URL do player encontrado no link: $result")
        return result
    }

    // Classes de dados para o AnimeSkip
    private data class AnimeSkipInfo(
        val startTime: Double?,
        val endTime: Double?,
        val skipType: String?
    )

    private data class AnimeSkipResponse(
        @JsonProperty("found") val found: Boolean,
        @JsonProperty("results") val results: List<AnimeSkipResult>?
    )

    private data class AnimeSkipResult(
        @JsonProperty("interval") val interval: AnimeSkipInterval?,
        @JsonProperty("skipType") val skipType: String?,
        @JsonProperty("skipId") val skipId: String?,
        @JsonProperty("episodeLength") val episodeLength: Double?
    )

    private data class AnimeSkipInterval(
        @JsonProperty("startTime") val startTime: Double?,
        @JsonProperty("endTime") val endTime: Double?
    )

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
        val seasonsEpisodes: Map<Int, List<TMDBEpisode>> = emptyMap()
    )

    private data class TMDBEpisode(
        @JsonProperty("episode_number") val episode_number: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("still_path") val still_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("air_date") val air_date: String?
    )

    private data class TMDBSearchResponse(
        @JsonProperty("results") val results: List<TMDBResult>
    )

    private data class TMDBResult(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("release_date") val release_date: String? = null,
        @JsonProperty("first_air_date") val first_air_date: String? = null,
        @JsonProperty("poster_path") val poster_path: String?
    )

    private data class TMDBDetailsResponse(
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("genres") val genres: List<TMDBGenre>?,
        @JsonProperty("credits") val credits: TMDBCredits?,
        @JsonProperty("videos") val videos: TMDBVideos?
    )

    private data class TMDBTVDetailsResponse(
        @JsonProperty("seasons") val seasons: List<TMDBSeasonInfo>
    )

    private data class TMDBSeasonInfo(
        @JsonProperty("season_number") val season_number: Int,
        @JsonProperty("episode_count") val episode_count: Int
    )

    private data class TMDBSeasonResponse(
        @JsonProperty("episodes") val episodes: List<TMDBEpisode>,
        @JsonProperty("air_date") val air_date: String?
    )

    private data class TMDBGenre(
        @JsonProperty("name") val name: String
    )

    private data class TMDBCredits(
        @JsonProperty("cast") val cast: List<TMDBCast>
    )

    private data class TMDBCast(
        @JsonProperty("name") val name: String,
        @JsonProperty("character") val character: String?,
        @JsonProperty("profile_path") val profile_path: String?
    )

    private data class TMDBVideos(
        @JsonProperty("results") val results: List<TMDBVideo>
    )

    private data class TMDBVideo(
        @JsonProperty("key") val key: String,
        @JsonProperty("site") val site: String,
        @JsonProperty("type") val type: String
    )
}