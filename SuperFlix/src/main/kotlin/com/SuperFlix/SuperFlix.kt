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
import java.util.Date

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

    // ============ FUNÇÃO DE TESTE DO PROXY ============
    private suspend fun testProxyConnection() {
        println("🧪 === TESTE DE CONEXÃO PROXY ===")
        println("🔗 URL do proxy: $TMDB_PROXY_URL")

        try {
            // Teste 1: Health check
            println("\n📡 Teste 1: Health check")
            val healthResponse = app.get("$TMDB_PROXY_URL/health", timeout = 5_000)
            println("   Status: ${healthResponse.code}")
            println("   Body: ${healthResponse.text.take(100)}")

            // Teste 2: Busca simples
            println("\n🔍 Teste 2: Busca 'avatar'")
            val searchResponse = app.get("$TMDB_PROXY_URL/search?query=avatar&type=movie", timeout = 5_000)
            println("   Status: ${searchResponse.code}")
            println("   Tamanho resposta: ${searchResponse.text.length} chars")

            if (searchResponse.code == 200) {
                try {
                    val json = JSONObject(searchResponse.text)
                    val results = json.optJSONArray("results")
                    println("   ✅ Resultados: ${results?.length() ?: 0}")
                } catch (e: Exception) {
                    println("   ❌ JSON inválido")
                }
            }

            println("\n🎯 Proxy ${if (healthResponse.code == 200 && searchResponse.code == 200) "✅ FUNCIONANDO" else "❌ COM PROBLEMAS"}")

        } catch (e: Exception) {
            println("💥 ERRO no teste do proxy: ${e.message}")
        }
        println("🧪 === FIM DO TESTE ===")
    }

    // ============ FUNÇÃO DE BUSCA NO PROXY COM DEBUG ============
    private suspend fun searchOnTMDB(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        println("\n🔍 === BUSCA TMDB INICIADA ===")
        println("📝 Query: '$query'")
        println("📅 Year: $year")
        println("🎬 Type: ${if (isTv) "TV" else "Movie"}")
        println("🌐 Proxy: $TMDB_PROXY_URL")

        val type = if (isTv) "tv" else "movie"

        return try {
            // Constrói URL para o proxy
            val urlBuilder = StringBuilder("$TMDB_PROXY_URL/search")
                .append("?query=${java.net.URLEncoder.encode(query, "UTF-8")}")
                .append("&type=$type")
                .append("&language=pt-BR")
                .append("&page=1")

            year?.let {
                if (it > 1900 && it < 2100) {
                    urlBuilder.append("&year=$it")
                }
            }

            val finalUrl = urlBuilder.toString()
            println("🔗 URL completa: $finalUrl")

            // Faz requisição para SEU proxy
            println("📡 Fazendo requisição...")
            val response = app.get(finalUrl, timeout = 15_000)
            println("📊 Status code: ${response.code}")
            println("📦 Tamanho resposta: ${response.text.length} chars")

            if (response.code == 200) {
                println("✅ Resposta OK, processando JSON...")

                try {
                    val json = JSONObject(response.text)
                    val results = json.optJSONArray("results")
                    val totalResults = json.optInt("total_results", 0)

                    println("📊 Total de resultados na TMDB: $totalResults")
                    println("📋 Resultados no array: ${results?.length() ?: 0}")

                    if (results != null && results.length() > 0) {
                        val firstItem = results.getJSONObject(0)
                        val itemId = firstItem.getInt("id")
                        val title = if (isTv) firstItem.optString("name", "") else firstItem.optString("title", "")

                        println("🎉 TMDB ENCONTRADO!")
                        println("   ID: $itemId")
                        println("   Título: $title")
                        println("   Poster: ${firstItem.optString("poster_path", "N/A")}")

                        // Tenta buscar detalhes (opcional)
                        println("\n🔍 Buscando detalhes adicionais...")
                        val details = try {
                            getTMDBDetailsViaProxy(itemId, isTv)
                        } catch (e: Exception) {
                            println("⚠️ Detalhes falharam: ${e.message}")
                            null
                        }

                        if (details != null) {
                            println("✅ Detalhes obtidos")
                            println("   Gêneros: ${details.genres?.size ?: 0}")
                            println("   Atores: ${details.credits?.cast?.size ?: 0}")
                        }

                        val tmdbInfo = TMDBInfo(
                            id = itemId,
                            title = title,
                            year = if (isTv) {
                                firstItem.optString("first_air_date", "").take(4).toIntOrNull()
                            } else {
                                firstItem.optString("release_date", "").take(4).toIntOrNull()
                            },
                            posterUrl = firstItem.optString("poster_path", "").takeIf { it.isNotEmpty() && it != "null" }
                                ?.let { "$TMDB_IMAGE_URL/w500$it" },
                            backdropUrl = details?.backdrop_path?.takeIf { it.isNotEmpty() && it != "null" }
                                ?.let { "$TMDB_IMAGE_URL/original$it" },
                            overview = details?.overview ?: firstItem.optString("overview", ""),
                            genres = details?.genres?.map { it.name },
                            actors = details?.credits?.cast?.take(15)?.mapNotNull { actor ->
                                if (actor.name.isNotBlank()) {
                                    Actor(
                                        name = actor.name,
                                        image = actor.profile_path?.takeIf { it.isNotEmpty() && it != "null" }
                                            ?.let { "$TMDB_IMAGE_URL/w185$it" }
                                    )
                                } else null
                            },
                            youtubeTrailer = details?.videos?.results?.find { 
                                it.site == "YouTube" && it.type == "Trailer" 
                            }?.key?.let { "https://www.youtube.com/watch?v=$it" },
                            duration = if (!isTv) details?.runtime else null,
                            seasonsEpisodes = emptyMap() // Simplificado por enquanto
                        )

                        println("\n✅ === TMDB SUCESSO ===")
                        println("   Título: ${tmdbInfo.title}")
                        println("   Ano: ${tmdbInfo.year}")
                        println("   Poster URL: ${tmdbInfo.posterUrl?.take(50)}...")
                        println("🔍 === FIM BUSCA TMDB ===")

                        return tmdbInfo
                    } else {
                        println("⚠️ Nenhum resultado encontrado na TMDB para: '$query'")
                    }
                } catch (e: Exception) {
                    println("❌ ERRO processando JSON: ${e.message}")
                    println("📄 Primeiros 200 chars da resposta:")
                    println(response.text.take(200))
                    e.printStackTrace()
                }
            } else {
                println("❌ Proxy retornou erro HTTP: ${response.code}")
                println("📄 Resposta (primeiros 300 chars):")
                println(response.text.take(300))
            }

            println("🔍 === TMDB FALHOU ===")
            null
        } catch (e: Exception) {
            println("💥 EXCEÇÃO na busca TMDB: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    // ============ FUNÇÕES AUXILIARES DO PROXY ============
    private suspend fun getTMDBDetailsViaProxy(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        println("   🔎 Buscando detalhes para ID: $id (${if (isTv) "TV" else "Movie"})")

        val type = if (isTv) "tv" else "movie"
        val url = "$TMDB_PROXY_URL/$type/$id"

        return try {
            val response = app.get(url, timeout = 10_000)

            if (response.code == 200) {
                response.parsedSafe<TMDBDetailsResponse>()
            } else {
                println("   ⚠️ Detalhes falhou: Status ${response.code}")
                null
            }
        } catch (e: Exception) {
            println("   ❌ Erro nos detalhes: ${e.message}")
            null
        }
    }

    // ============ CÓDIGO ORIGINAL DO SUPERFLIX COM DEBUGS ============
    override val mainPage = mainPageOf(
        "$mainUrl/lancamentos" to "Lançamentos",
        "$mainUrl/filmes" to "Últimos Filmes",
        "$mainUrl/series" to "Últimas Séries",
        "$mainUrl/animes" to "Últimas Animes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "?page=$page" else ""
        println("🏠 MainPage: $url")

        val document = app.get(url).document

        val home = document.select("a.card, div.recs-grid a.rec-card").mapNotNull { element ->
            element.toSearchResult()
        }

        println("🏠 Encontrados ${home.size} itens na página principal")
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
        println("🔎 Search: '$query'")

        val searchUrl = "$mainUrl/buscar?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
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
                null
            }
        }

        println("🔎 Search encontrou ${results.size} resultados")
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        println("\n🎬 === LOAD INICIADO ===")
        println("🔗 URL: $url")

        // Testa o proxy primeiro (temporário)
        testProxyConnection()

        val document = app.get(url).document

        val titleElement = document.selectFirst("h1, .title")
        val title = titleElement?.text() ?: return null

        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

        println("📝 Título extraído: '$cleanTitle'")
        println("📅 Ano extraído: $year")

        val isAnime = url.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
        val isSerie = url.contains("/serie/") || url.contains("/tv/") ||
                     (!isAnime && document.selectFirst(".episode-list, .season-list, .seasons") != null)

        println("🎬 Tipo: ${if (isAnime) "Anime" else if (isSerie) "Série" else "Filme"}")

        println("\n🔍 Tentando buscar no TMDB...")
        val tmdbInfo = if (isAnime || isSerie) {
            searchOnTMDB(cleanTitle, year, true)
        } else {
            searchOnTMDB(cleanTitle, year, false)
        }

        val siteRecommendations = extractRecommendationsFromSite(document)
        println("📊 Recomendações do site: ${siteRecommendations.size}")

        return if (tmdbInfo != null) {
            println("✅ Usando dados do TMDB")
            createLoadResponseWithTMDB(tmdbInfo, url, document, isAnime, isSerie, siteRecommendations)
        } else {
            println("⚠️ TMDB não encontrou, usando dados do site")
            createLoadResponseFromSite(document, url, cleanTitle, year, isAnime, isSerie)
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
        println("🎨 Criando LoadResponse com dados TMDB")

        return if (isAnime || isSerie) {
            println("📺 É série/anime, extraindo episódios...")
            val episodes = extractEpisodesWithTMDBInfo(
                document = document,
                url = url,
                tmdbInfo = tmdbInfo,
                isAnime = isAnime,
                isSerie = isSerie
            )

            println("📺 Episódios encontrados: ${episodes.size}")

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
                    println("🎭 Adicionando ${actors.size} atores")
                    addActors(actors)
                }

                tmdbInfo.youtubeTrailer?.let { trailerUrl ->
                    println("🎥 Trailer: $trailerUrl")
                    addTrailer(trailerUrl)
                }

                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
            }
        } else {
            val playerUrl = findPlayerUrl(document)
            println("🎬 URL do player: $playerUrl")

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
                    println("🎭 Adicionando ${actors.size} atores")
                    addActors(actors)
                }

                tmdbInfo.youtubeTrailer?.let { trailerUrl ->
                    println("🎥 Trailer: $trailerUrl")
                    addTrailer(trailerUrl)
                }

                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
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

    private suspend fun extractEpisodesWithTMDBInfo(
        document: org.jsoup.nodes.Document,
        url: String,
        tmdbInfo: TMDBInfo?,
        isAnime: Boolean,
        isSerie: Boolean = false
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()

        val episodeElements = document.select("button.bd-play[data-url], a.episode-card, .episode-item, .episode-link, [class*='episode']")

        println("📺 Elementos de episódio encontrados: ${episodeElements.size}")

        if (episodeElements.isNotEmpty()) {
            episodeElements.forEachIndexed { index, element ->
                try {
                    val dataUrl = element.attr("data-url") ?: element.attr("href") ?: ""
                    if (dataUrl.isBlank()) return@forEachIndexed

                    val epNumber = extractEpisodeNumber(element, index + 1)
                    val seasonNumber = element.attr("data-season").toIntOrNull() ?: 1

                    val tmdbEpisode = findTMDBEpisode(tmdbInfo, seasonNumber, epNumber)

                    val episode = createEpisode(
                        dataUrl = dataUrl,
                        seasonNumber = seasonNumber,
                        episodeNumber = epNumber,
                        element = element,
                        tmdbEpisode = tmdbEpisode,
                        isAnime = isAnime,
                        isSerie = isSerie
                    )

                    episodes.add(episode)
                } catch (e: Exception) {
                    // Ignora episódio com erro
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

                    val episode = createEpisode(
                        dataUrl = dataUrl,
                        seasonNumber = seasonNumber,
                        episodeNumber = epNumber,
                        element = element,
                        tmdbEpisode = tmdbEpisode,
                        isAnime = isAnime,
                        isSerie = isSerie
                    )

                    episodes.add(episode)
                } catch (e: Exception) {
                    // Ignora episódio com erro
                }
            }
        }

        return episodes
    }

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

    private fun createEpisode(
        dataUrl: String,
        seasonNumber: Int,
        episodeNumber: Int,
        element: Element,
        tmdbEpisode: TMDBEpisode?,
        isAnime: Boolean,
        isSerie: Boolean = false
    ): Episode {
        return newEpisode(fixUrl(dataUrl)) {
            val descriptionBuilder = StringBuilder()

            this.name = tmdbEpisode?.name ?:
                       element.selectFirst(".ep-title, .title, .episode-title, h3, h4")?.text()?.trim() ?:
                       "Episódio $episodeNumber"

            this.season = seasonNumber
            this.episode = episodeNumber

            this.posterUrl = tmdbEpisode?.still_path?.let { "$TMDB_IMAGE_URL/w300$it" } ?:
                            element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

            tmdbEpisode?.overview?.let { overview ->
                descriptionBuilder.append(overview)
            }

            tmdbEpisode?.air_date?.let { airDate ->
                try {
                    val dateFormatter = SimpleDateFormat("dd-MM-yyyy")
                    val airDateParsed = dateFormatter.parse(airDate)
                    if (airDateParsed != null) {
                        this.date = airDateParsed.time
                    }
                } catch (e: Exception) {
                    // Ignora erro de parse
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
        println("🏠 Criando resposta com dados do site")

        val ogImage = document.selectFirst("meta[property='og:image']")?.attr("content")
        val poster = ogImage?.let { fixUrl(it) }
        println("🖼️ Poster do site: ${poster?.take(50)}...")

        val description = document.selectFirst("meta[name='description']")?.attr("content")
        val synopsis = document.selectFirst(".syn, .description, .sinopse, .plot")?.text()
        val plot = description ?: synopsis
        println("📖 Plot do site: ${plot?.length ?: 0} chars")

        val tags = document.select("a.chip, .chip, .genre, .tags").map { it.text() }
            .takeIf { it.isNotEmpty() }?.toList()
        println("🏷️ Tags do site: ${tags?.size ?: 0}")

        val siteRecommendations = extractRecommendationsFromSite(document)

        return if (isAnime || isSerie) {
            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            val episodes = extractEpisodesWithTMDBInfo(document, url, null, isAnime, isSerie)
            println("📺 Episódios extraídos: ${episodes.size}")

            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
            }
        } else {
            val playerUrl = findPlayerUrl(document)
            println("🎬 Player URL: $playerUrl")

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
        println("🔗 Extraindo links de: ${data.take(50)}...")
        return SuperFlixExtractor.extractVideoLinks(data, mainUrl, name, callback)
    }

    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        val playButton = document.selectFirst("button.bd-play[data-url]")
        if (playButton != null) {
            val url = playButton.attr("data-url")
            println("🎯 Player encontrado no botão: $url")
            return url
        }

        val iframe = document.selectFirst("iframe[src*='fembed'], iframe[src*='filemoon'], iframe[src*='player'], iframe[src*='embed']")
        if (iframe != null) {
            val url = iframe.attr("src")
            println("🎯 Player encontrado no iframe: $url")
            return url
        }

        val videoLink = document.selectFirst("a[href*='.m3u8'], a[href*='.mp4'], a[href*='watch']")
        if (videoLink != null) {
            val url = videoLink.attr("href")
            println("🎯 Player encontrado no link: $url")
            return url
        }

        println("⚠️ Nenhum player encontrado")
        return null
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

    private data class TMDBTVDetailsResponse(
        @JsonProperty("seasons") val seasons: List<TMDBSeasonInfo>
    )

    private data class TMDBSeasonInfo(
        @JsonProperty("season_number") val season_number: Int,
        @JsonProperty("episode_count") val episode_count: Int
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