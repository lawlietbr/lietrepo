package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import java.text.SimpleDateFormat
import org.json.JSONObject

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = true

    // ============ CONFIGURAÇÃO DO PROXY TMDB ============
    private val TMDB_PROXY_URL = "https://lawliet.euluan1912.workers.dev"
    private val TMDB_IMAGE_URL = "https://image.tmdb.org/t/p"

    // ============ FUNÇÃO DE BUSCA NO TMDB ============
    private suspend fun searchOnTMDB(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
    val type = if (isTv) "tv" else "movie"
    
    try {
        // PASSO 1: BUSCA BÁSICA
        val searchUrl = "$TMDB_PROXY_URL/search/$type?" +
                       "query=${URLEncoder.encode(query, "UTF-8")}&" +
                       "language=pt-BR"
        
        val searchResponse = app.get(searchUrl, timeout = 10_000)
        
        if (searchResponse.status != 200) return null
        
        val searchJson = JSONObject(searchResponse.text)
        val results = searchJson.getJSONArray("results")
        if (results.length() == 0) return null
        
        val firstItem = results.getJSONObject(0)
        val itemId = firstItem.getInt("id")
        
        // PASSO 2: DETALHES COMPLETOS (AGORA SIM!)
        val detailsUrl = "$TMDB_PROXY_URL/$type/$itemId?" +
                        "language=pt-BR&" +
                        "append_to_response=credits,videos" + // ← MÁGICA AQUI!
                        (if (isTv) ",recommendations" else "")
        
        val detailsResponse = app.get(detailsUrl, timeout = 10_000)
        
        if (detailsResponse.status != 200) {
            // Fallback: usa dados da busca se detalhes falharem
            return createBasicTMDBInfo(firstItem, isTv)
        }
        
        val detailsJson = JSONObject(detailsResponse.text)
        return parseFullTMDBInfo(detailsJson, isTv)
        
    } catch (e: Exception) {
        e.printStackTrace()
        return null
    }
}

// NOVA FUNÇÃO PARA PROCESSAR DETALHES COMPLETOS
private fun parseFullTMDBInfo(json: JSONObject, isTv: Boolean): TMDBInfo {
    // Extrai atores dos créditos
    val actors = mutableListOf<Actor>()
    try {
        val credits = json.getJSONObject("credits")
        val cast = credits.getJSONArray("cast")
        
        for (i in 0 until min(cast.length(), 15)) { // Pega até 15 atores
            val actor = cast.getJSONObject(i)
            val name = actor.getString("name")
            val profilePath = actor.optString("profile_path", "")
            
            if (name.isNotBlank()) {
                actors.add(
                    Actor(
                        name = name,
                        image = if (profilePath.isNotBlank()) 
                                "$TMDB_IMAGE_URL/w185$profilePath" 
                              else null
                    )
                )
            }
        }
    } catch (e: Exception) {
        // Se não conseguir créditos, continua sem atores
    }
    
    // Extrai trailer do YouTube
    var youtubeTrailer: String? = null
    try {
        val videos = json.getJSONObject("videos")
        val results = videos.getJSONArray("results")
        
        for (i in 0 until results.length()) {
            val video = results.getJSONObject(i)
            if (video.getString("site") == "YouTube" && 
                video.getString("type") == "Trailer") {
                youtubeTrailer = "https://www.youtube.com/watch?v=${video.getString("key")}"
                break
            }
        }
    } catch (e: Exception) {
        // Se não conseguir vídeos, sem trailer
    }
    
    // Extrai gêneros
    val genres = mutableListOf<String>()
    try {
        val genresArray = json.getJSONArray("genres")
        for (i in 0 until genresArray.length()) {
            val genre = genresArray.getJSONObject(i)
            genres.add(genre.getString("name"))
        }
    } catch (e: Exception) {
        // Se não conseguir gêneros, lista vazia
    }
    
    return TMDBInfo(
        id = json.getInt("id"),
        title = if (isTv) json.optString("name", "") 
                else json.optString("title", ""),
        year = if (isTv) json.optString("first_air_date", "").take(4).toIntOrNull()
                else json.optString("release_date", "").take(4).toIntOrNull(),
        posterUrl = json.optString("poster_path", "").takeIf { it.isNotEmpty() }
                    ?.let { "$TMDB_IMAGE_URL/w500$it" },
        backdropUrl = json.optString("backdrop_path", "").takeIf { it.isNotEmpty() }
                      ?.let { "$TMDB_IMAGE_URL/original$it" },
        overview = json.optString("overview", ""),
        genres = if (genres.isNotEmpty()) genres else null,
        actors = if (actors.isNotEmpty()) actors else null,
        youtubeTrailer = youtubeTrailer,
        duration = if (!isTv) json.optInt("runtime", 0) else null,
        seasonsEpisodes = if (isTv) {
            // Para séries, ainda precisa buscar temporadas/episódios separadamente
            getSeasonsFromTMDB(json.getInt("id"))
        } else {
            emptyMap()
        }
    )
}
    // ============ FUNÇÃO PARA BUSCAR DETALHES ============
    private suspend fun getTMDBDetails(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        return try {
            val type = if (isTv) "tv" else "movie"
            // IMPORTANTE: O proxy provavelmente precisa da URL completa
            val url = "$TMDB_PROXY_URL/$type/$id?language=pt-BR&append_to_response=credits,videos"
            
            val response = app.get(url, timeout = 10_000)
            if (response.code == 200) {
                // Use parsedSafe para parsear corretamente o JSON
                response.parsedSafe<TMDBDetailsResponse>()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ============ FUNÇÃO PARA BUSCAR TEMPORADAS E EPISÓDIOS ============
    private suspend fun getTMDBSeasonsWithEpisodes(seriesId: Int): Map<Int, List<TMDBEpisode>> {
        val seasonsEpisodes = mutableMapOf<Int, List<TMDBEpisode>>()
        
        try {
            // Primeiro, busca informações da série (para pegar as temporadas)
            val seriesUrl = "$TMDB_PROXY_URL/tv/$seriesId?language=pt-BR"
            val seriesResponse = app.get(seriesUrl, timeout = 10_000)
            
            if (seriesResponse.code != 200) return emptyMap()
            
            val seriesJson = JSONObject(seriesResponse.text)
            val seasonsArray = seriesJson.getJSONArray("seasons")
            
            // Para cada temporada
            for (i in 0 until seasonsArray.length()) {
                val seasonJson = seasonsArray.getJSONObject(i)
                val seasonNumber = seasonJson.getInt("season_number")
                
                // Ignora temporadas especiais (season 0)
                if (seasonNumber > 0 && seasonNumber <= 50) { // Limite razoável
                    val episodes = getTMDBEpisodesForSeason(seriesId, seasonNumber)
                    if (episodes.isNotEmpty()) {
                        seasonsEpisodes[seasonNumber] = episodes
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return seasonsEpisodes
    }

    // ============ FUNÇÃO PARA BUSCAR EPISÓDIOS DE UMA TEMPORADA ============
    private suspend fun getTMDBEpisodesForSeason(seriesId: Int, seasonNumber: Int): List<TMDBEpisode> {
        val episodes = mutableListOf<TMDBEpisode>()
        
        try {
            val url = "$TMDB_PROXY_URL/tv/$seriesId/season/$seasonNumber?language=pt-BR"
            val response = app.get(url, timeout = 10_000)
            
            if (response.code != 200) return emptyList()
            
            val json = JSONObject(response.text)
            val episodesArray = json.getJSONArray("episodes")
            
            for (i in 0 until episodesArray.length()) {
                val episodeJson = episodesArray.getJSONObject(i)
                
                episodes.add(
                    TMDBEpisode(
                        episode_number = episodeJson.getInt("episode_number"),
                        name = episodeJson.getString("name"),
                        overview = episodeJson.optString("overview", ""),
                        still_path = episodeJson.optString("still_path", null),
                        runtime = episodeJson.optInt("runtime", 0),
                        air_date = episodeJson.optString("air_date", null)
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return episodes
    }

    // ============ FUNÇÃO PRINCIPAL PARA CARREGAR DETALHES ============
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val titleElement = document.selectFirst("h1, .title")
        val title = titleElement?.text() ?: return null

        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

        val isAnime = url.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
        val isSerie = url.contains("/serie/") || url.contains("/tv/") ||
                     (!isAnime && document.selectFirst(".episode-list, .season-list, .seasons") != null)

        // Busca informações no TMDB
        val tmdbInfo = if (isAnime || isSerie) {
            searchOnTMDB(cleanTitle, year, true)
        } else {
            searchOnTMDB(cleanTitle, year, false)
        }

        // Recomendações do site
        val siteRecommendations = extractRecommendationsFromSite(document)

        return if (tmdbInfo != null) {
            createLoadResponseWithTMDB(tmdbInfo, url, document, isAnime, isSerie, siteRecommendations)
        } else {
            createLoadResponseFromSite(document, url, cleanTitle, year, isAnime, isSerie)
        }
    }

    // ============ FUNÇÃO PARA CRIAR RESPOSTA COM DADOS DO TMDB ============
    private suspend fun createLoadResponseWithTMDB(
        tmdbInfo: TMDBInfo,
        url: String,
        document: org.jsoup.nodes.Document,
        isAnime: Boolean,
        isSerie: Boolean,
        siteRecommendations: List<SearchResponse>
    ): LoadResponse {
        return if (isAnime || isSerie) {
            // Extrai episódios do site E do TMDB
            val episodes = extractEpisodesWithTMDBInfo(
                document = document,
                url = url,
                tmdbInfo = tmdbInfo,
                isAnime = isAnime,
                isSerie = isSerie
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

                tmdbInfo.actors?.let { actors ->
                    addActors(actors)
                }

                tmdbInfo.youtubeTrailer?.let { trailerUrl ->
                    addTrailer(trailerUrl)
                }

                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
            }
        } else {
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

                tmdbInfo.actors?.let { actors ->
                    addActors(actors)
                }

                tmdbInfo.youtubeTrailer?.let { trailerUrl ->
                    addTrailer(trailerUrl)
                }

                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
            }
        }
    }

    // ============ FUNÇÃO APRIMORADA PARA EXTRAIR EPISÓDIOS ============
    private suspend fun extractEpisodesWithTMDBInfo(
        document: org.jsoup.nodes.Document,
        url: String,
        tmdbInfo: TMDBInfo?,
        isAnime: Boolean,
        isSerie: Boolean = false
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Primeiro, tenta extrair episódios do site
        val siteEpisodes = extractEpisodesFromSite(document, url, tmdbInfo, isAnime, isSerie)
        episodes.addAll(siteEpisodes)
        
        // Se não encontrou episódios no site MAS temos informações do TMDB para séries
        if (episodes.isEmpty() && tmdbInfo?.seasonsEpisodes?.isNotEmpty() == true && isSerie) {
            // Cria episódios baseados no TMDB
            episodes.addAll(createEpisodesFromTMDB(tmdbInfo, url))
        }
        
        return episodes
    }

    // ============ FUNÇÃO PARA EXTRAIR EPISÓDIOS DO SITE ============
    private fun extractEpisodesFromSite(
        document: org.jsoup.nodes.Document,
        url: String,
        tmdbInfo: TMDBInfo?,
        isAnime: Boolean,
        isSerie: Boolean
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Tenta vários seletores possíveis
        val selectors = listOf(
            "button.bd-play[data-url]",
            "a.episode-card",
            ".episode-item",
            ".episode-link",
            "[class*='episode']",
            "[class*='episodio']",
            ".episode-list a",
            ".episodes a",
            "li a[href*='episodio']",
            "li a[href*='episode']"
        )
        
        var episodeCounter = 1
        
        selectors.forEach { selector ->
            document.select(selector).forEach { element ->
                try {
                    val dataUrl = element.attr("data-url") ?: element.attr("href")
                    if (dataUrl.isNullOrBlank()) return@forEach
                    
                    val epNumber = extractEpisodeNumber(element, episodeCounter)
                    val seasonNumber = element.attr("data-season").toIntOrNull() ?: 1
                    
                    // Busca informações deste episódio específico no TMDB
                    val tmdbEpisode = findTMDBEpisode(tmdbInfo, seasonNumber, epNumber)
                    
                    episodes.add(createEpisode(
                        dataUrl = dataUrl,
                        seasonNumber = seasonNumber,
                        episodeNumber = epNumber,
                        element = element,
                        tmdbEpisode = tmdbEpisode,
                        isAnime = isAnime
                    ))
                    
                    episodeCounter++
                } catch (e: Exception) {
                    // Ignora erro e continua
                }
            }
        }
        
        return episodes
    }

    // ============ FUNÇÃO PARA CRIAR EPISÓDIOS APENAS COM DADOS DO TMDB ============
    private fun createEpisodesFromTMDB(tmdbInfo: TMDBInfo, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        tmdbInfo.seasonsEpisodes.forEach { (seasonNumber, seasonEpisodes) ->
            seasonEpisodes.forEach { tmdbEpisode ->
                try {
                    // Cria uma URL de dados para o episódio
                    val dataUrl = "$baseUrl?season=$seasonNumber&episode=${tmdbEpisode.episode_number}"
                    
                    episodes.add(
                        newEpisode(fixUrl(dataUrl)) {
                            this.name = tmdbEpisode.name
                            this.season = seasonNumber
                            this.episode = tmdbEpisode.episode_number
                            
                            // Thumbnail do episódio
                            this.posterUrl = tmdbEpisode.still_path?.let { "$TMDB_IMAGE_URL/w300$it" }
                            
                            // Descrição/sinopse
                            val descriptionBuilder = StringBuilder()
                            tmdbEpisode.overview?.let { overview ->
                                descriptionBuilder.append(overview)
                            }
                            
                            // Duração
                            tmdbEpisode.runtime?.let { runtime ->
                                if (runtime > 0) {
                                    if (descriptionBuilder.isNotEmpty()) {
                                        descriptionBuilder.append("\n\n⏱️ Duração: ${runtime}min")
                                    } else {
                                        descriptionBuilder.append("⏱️ Duração: ${runtime}min")
                                    }
                                }
                            }
                            
                            this.description = descriptionBuilder.toString().takeIf { it.isNotEmpty() }
                            
                            // Data de lançamento
                            tmdbEpisode.air_date?.let { airDate ->
                                try {
                                    val dateFormatter = SimpleDateFormat("yyyy-MM-dd")
                                    val date = dateFormatter.parse(airDate)
                                    this.date = date?.time
                                } catch (e: Exception) {
                                    // Ignora erro de parse
                                }
                            }
                        }
                    )
                } catch (e: Exception) {
                    // Ignora episódio com erro
                }
            }
        }
        
        return episodes
    }

    // ============ FUNÇÃO PARA CRIAR EPISÓDIO INDIVIDUAL ============
    private fun createEpisode(
        dataUrl: String,
        seasonNumber: Int,
        episodeNumber: Int,
        element: Element,
        tmdbEpisode: TMDBEpisode?,
        isAnime: Boolean
    ): Episode {
        // Nome do episódio
        val name = tmdbEpisode?.name ?:
                  element.selectFirst(".ep-title, .title, .episode-title, h3, h4")?.text()?.trim() ?:
                  "Episódio $episodeNumber"
        
        // Thumbnail
        val posterUrl = tmdbEpisode?.still_path?.let { "$TMDB_IMAGE_URL/w300$it" } ?:
                       element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        
        // Descrição/sinopse
        val descriptionBuilder = StringBuilder()
        
        // Primeiro usa sinopse do TMDB
        tmdbEpisode?.overview?.takeIf { it.isNotEmpty() }?.let { overview ->
            descriptionBuilder.append(overview)
        }
        
        // Se não tem do TMDB, tenta do site
        if (descriptionBuilder.isEmpty()) {
            element.selectFirst(".ep-desc, .description, .synopsis")?.text()?.trim()?.let { siteDesc ->
                if (siteDesc.isNotEmpty()) {
                    descriptionBuilder.append(siteDesc)
                }
            }
        }
        
        // Duração
        val duration = when {
            isAnime -> tmdbEpisode?.runtime ?: 24
            else -> tmdbEpisode?.runtime ?: 0
        }
        
        if (duration > 0) {
            if (descriptionBuilder.isNotEmpty()) {
                descriptionBuilder.append("\n\n⏱️ Duração: ${duration}min")
            } else {
                descriptionBuilder.append("⏱️ Duração: ${duration}min")
            }
        }
        
        // Data de lançamento
        var episodeDate: Long? = null
        tmdbEpisode?.air_date?.takeIf { it.isNotEmpty() }?.let { airDate ->
            try {
                val dateFormatter = SimpleDateFormat("yyyy-MM-dd")
                episodeDate = dateFormatter.parse(airDate)?.time
            } catch (e: Exception) {
                // Tenta outro formato se necessário
            }
        }
        
        return newEpisode(fixUrl(dataUrl)) {
            this.name = name
            this.season = seasonNumber
            this.episode = episodeNumber
            this.posterUrl = posterUrl
            this.description = descriptionBuilder.toString().takeIf { it.isNotEmpty() }
            this.date = episodeDate
        }
    }

    // ============ FUNÇÕES AUXILIARES ============
    private fun extractEpisodeNumber(element: Element, default: Int): Int {
        return element.attr("data-ep").toIntOrNull() ?:
               element.selectFirst(".ep-number, .number, .episode-number")?.text()?.toIntOrNull() ?:
               Regex("Ep\\.?\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               Regex("Epis[oó]dio\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               default
    }

    private fun findTMDBEpisode(tmdbInfo: TMDBInfo?, season: Int, episode: Int): TMDBEpisode? {
        return tmdbInfo?.seasonsEpisodes?.get(season)?.find { it.episode_number == episode }
    }

    // ============ FUNÇÕES DO RESTANTE DO CÓDIGO (mantenha como estão) ============
    // getMainPage, search, extractRecommendationsFromSite, createLoadResponseFromSite, etc.
    // ... (mantenha o restante do seu código que já funciona)

    override val mainPage = mainPageOf(
        "$mainUrl/lancamentos" to "Lançamentos",
        "$mainUrl/filmes" to "Últimos Filmes",
        "$mainUrl/series" to "Últimas Séries",
        "$mainUrl/animes" to "Últimas Animes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "?page=$page" else ""
        val document = app.get(url).document

        val home = document.select("a.card, div.recs-grid a.rec-card").mapNotNull { element ->
            element.toSearchResult()
        }

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
                null
            }
        }
    }

    private fun extractRecommendationsFromSite(document: org.jsoup.nodes.Document): List<SearchResponse> {
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

                return@mapNotNull when {
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
                null
            }
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
        val ogImage = document.selectFirst("meta[property='og:image']")?.attr("content")
        val poster = ogImage?.let { fixUrl(it) }

        val description = document.selectFirst("meta[name='description']")?.attr("content")
        val synopsis = document.selectFirst(".syn, .description, .sinopse, .plot")?.text()
        val plot = description ?: synopsis

        val tags = document.select("a.chip, .chip, .genre, .tags").map { it.text() }
            .takeIf { it.isNotEmpty() }?.toList()

        val siteRecommendations = extractRecommendationsFromSite(document)

        return if (isAnime || isSerie) {
            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            val episodes = extractEpisodesWithTMDBInfo(document, url, null, isAnime, isSerie)

            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
            }
        } else {
            val playerUrl = findPlayerUrl(document)
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
        return false // Implementar conforme necessário
    }

    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        val playButton = document.selectFirst("button.bd-play[data-url]")
        if (playButton != null) return playButton.attr("data-url")
        val iframe = document.selectFirst("iframe[src*='fembed'], iframe[src*='filemoon'], iframe[src*='player'], iframe[src*='embed']")
        if (iframe != null) return iframe.attr("src")
        val videoLink = document.selectFirst("a[href*='.m3u8'], a[href*='.mp4'], a[href*='watch']")
        return videoLink?.attr("href")
    }

    // ============ CLASSES DE DADOS DO TMDB ============
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

    private data class TMDBDetailsResponse(
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("genres") val genres: List<TMDBGenre>?,
        @JsonProperty("credits") val credits: TMDBCredits?,
        @JsonProperty("videos") val videos: TMDBVideos?
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