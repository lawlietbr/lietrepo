package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.metaproviders.TmdbLinkType
import org.jsoup.nodes.Element

class SuperFlix : MainAPI(), TmdbProvider {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val usesWebView = true

    // Configuração do TMDB via multimeta
    override val tmdbLinkType = TmdbLinkType.SeasonOnName
    override val tmdbLanguage = "pt-BR"
    override val useImdbId = false // O SuperFlix não tem IDs IMDB

    // =========================================================================
    // PÁGINA PRINCIPAL
    // =========================================================================
    override val mainPage = mainPageOf(
        "$mainUrl/lancamentos" to "Lançamentos",
        "$mainUrl/filmes" to "Últimos Filmes",
        "$mainUrl/series" to "Últimas Séries"
    )

    // =========================================================================
    // PÁGINA PRINCIPAL (SIMPLES - o multimeta faz o trabalho pesado)
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
    // FUNÇÃO AUXILIAR OTIMIZADA
    // =========================================================================
    private fun Element.toSearchResult(): SearchResponse? {
        val title = attr("title") ?: selectFirst("img")?.attr("alt") ?: return null
        val href = attr("href") ?: return null

        // Imagem local apenas como fallback inicial
        val localPoster = selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        val isSerie = href.contains("/serie/")

        // O multimeta vai buscar os dados do TMDB automaticamente depois
        return if (isSerie) {
            newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = localPoster
                this.year = year
            }
        } else {
            newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                this.posterUrl = localPoster
                this.year = year
            }
        }
    }

    // =========================================================================
    // BUSCA (SIMPLES - o multimeta enriquece depois)
    // =========================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select("a.card").mapNotNull { it.toSearchResult() }
    }

    // =========================================================================
    // CARREGAR DETALHES (MULTIMETA FAZ TUDO!)
    // =========================================================================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // 1. Extrai info básica para o multimeta
        val titleElement = document.selectFirst("h1, .title")
        val title = titleElement?.text() ?: return null
        
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        val isSerie = url.contains("/serie/")

        // 2. Usa o multimeta para buscar no TMDB
        val tmdbResult = if (isSerie) {
            tmdbSearchTv(cleanTitle, year)
        } else {
            tmdbSearchMovie(cleanTitle, year)
        }

        // 3. Se o multimeta encontrou no TMDB, usa os dados enriquecidos
        return if (tmdbResult != null) {
            createLoadResponseWithTMDB(tmdbResult, url, document, isSerie)
        } else {
            // 4. Fallback: usa dados do site local
            createLoadResponseFromSite(document, url, cleanTitle, year, isSerie)
        }
    }

    // =========================================================================
    // CRIAR RESPOSTA COM DADOS DO TMDB (MULTIMETA)
    // =========================================================================
    private fun createLoadResponseWithTMDB(
        tmdbResult: TmdbSearchResult,
        url: String,
        document: org.jsoup.nodes.Document,
        isSerie: Boolean
    ): LoadResponse {
        return if (isSerie) {
            val episodes = extractEpisodesFromButtons(document, url)
            
            newTvSeriesLoadResponse(
                title = tmdbResult.title ?: "",
                url = url,
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                this.posterUrl = tmdbResult.poster
                this.backgroundPosterUrl = tmdbResult.background
                this.year = tmdbResult.year
                this.plot = tmdbResult.description
                this.tags = tmdbResult.genres
                this.rating = tmdbResult.rating?.div(10.0)
                
                // Atores do TMDB
                tmdbResult.actors?.take(10)?.map { actor ->
                    Actor(actor.name ?: "", actor.imageUrl)
                }?.let { addActors(it) }
                
                // Trailer do YouTube
                tmdbResult.youtubeTrailer?.let { addTrailer(it) }
                
                // Recomendações do TMDB
                this.recommendations = tmdbResult.recommendations?.map { rec ->
                    if (rec.isMovie) {
                        newMovieSearchResponse(rec.title ?: "", "", TvType.Movie) {
                            this.posterUrl = rec.poster
                            this.year = rec.year
                        }
                    } else {
                        newTvSeriesSearchResponse(rec.title ?: "", "", TvType.TvSeries) {
                            this.posterUrl = rec.poster
                            this.year = rec.year
                        }
                    }
                }
            }
        } else {
            val playerUrl = findPlayerUrl(document)
            
            newMovieLoadResponse(
                title = tmdbResult.title ?: "",
                url = url,
                type = TvType.Movie,
                data = playerUrl ?: url
            ) {
                this.posterUrl = tmdbResult.poster
                this.backgroundPosterUrl = tmdbResult.background
                this.year = tmdbResult.year
                this.plot = tmdbResult.description
                this.tags = tmdbResult.genres
                this.rating = tmdbResult.rating?.div(10.0)
                this.duration = tmdbResult.duration
                
                // Atores do TMDB
                tmdbResult.actors?.take(10)?.map { actor ->
                    Actor(actor.name ?: "", actor.imageUrl)
                }?.let { addActors(it) }
                
                // Trailer do YouTube
                tmdbResult.youtubeTrailer?.let { addTrailer(it) }
                
                // Recomendações do TMDB
                this.recommendations = tmdbResult.recommendations?.map { rec ->
                    if (rec.isMovie) {
                        newMovieSearchResponse(rec.title ?: "", "", TvType.Movie) {
                            this.posterUrl = rec.poster
                            this.year = rec.year
                        }
                    } else {
                        newTvSeriesSearchResponse(rec.title ?: "", "", TvType.TvSeries) {
                            this.posterUrl = rec.poster
                            this.year = rec.year
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // FALLBACK: CARREGAR DO SITE (se TMDB não encontrar)
    // =========================================================================
    private fun createLoadResponseFromSite(
        document: org.jsoup.nodes.Document,
        url: String,
        title: String,
        year: Int?,
        isSerie: Boolean
    ): LoadResponse {
        // Extrai dados básicos do site
        val ogImage = document.selectFirst("meta[property='og:image']")?.attr("content")
        val poster = ogImage?.let { fixUrl(it) }

        val description = document.selectFirst("meta[name='description']")?.attr("content")
        val synopsis = document.selectFirst(".syn, .description")?.text()
        val plot = description ?: synopsis

        val tags = document.select("a.chip, .chip").map { it.text() }.takeIf { it.isNotEmpty() }

        return if (isSerie) {
            val episodes = extractEpisodesFromButtons(document, url)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
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
    // EXTRATÇÃO DE LINKS (mantida como está)
    // =========================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return SuperFlixExtractor.extractVideoLinks(data, mainUrl, name, callback)
    }

    // =========================================================================
    // FUNÇÕES AUXILIARES (mantidas)
    // =========================================================================
    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        val playButton = document.selectFirst("button.bd-play[data-url]")
        if (playButton != null) return playButton.attr("data-url")
        val iframe = document.selectFirst("iframe[src*='fembed'], iframe[src*='filemoon'], iframe[src*='player'], iframe[src*='embed']")
        if (iframe != null) return iframe.attr("src")
        val videoLink = document.selectFirst("a[href*='.m3u8'], a[href*='.mp4'], a[href*='watch']")
        return videoLink?.attr("href")
    }

    private fun extractEpisodesFromButtons(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        return document.select("button.bd-play[data-url]").map { button ->
            newEpisode(button.attr("data-url")) {
                this.name = button.parents()
                    .find { it.hasClass("episode-item") || it.hasClass("episode") }
                    ?.selectFirst(".ep-title, .title, .name, h3, h4")
                    ?.text()
                    ?.trim()
                    ?: "Episódio ${button.attr("data-ep")}"
                this.season = button.attr("data-season").toIntOrNull() ?: 1
                this.episode = button.attr("data-ep").toIntOrNull() ?: 1
            }
        }
    }
}