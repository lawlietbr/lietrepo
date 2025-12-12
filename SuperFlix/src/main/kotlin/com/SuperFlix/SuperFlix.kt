package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class SuperFlix : TmdbProvider() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override var lang = "pt-br"  // Corrigido: var ao invés de val
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    
    // ============ CONFIGURAÇÕES TMDB ============
    override val useMetaLoadResponse = true
    
    // ============ PÁGINA PRINCIPAL ============
    override val mainPage = mainPageOf(
        "$mainUrl/lancamentos" to "Lançamentos",
        "$mainUrl/filmes" to "Últimos Filmes",
        "$mainUrl/series" to "Últimas Séries",
        "$mainUrl/animes" to "Últimas Animes"
    )
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "?page=$page" else ""
        val document = app.get(url).document
        
        val items = document.select("a.card, div.recs-grid a.rec-card").mapNotNull { element ->
            element.toSearchResult()
        }
        
        return newHomePageResponse(request.name, items.distinctBy { it.url })
    }
    
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.attr("title") ?: this.selectFirst("img")?.attr("alt") ?: return null
        val href = this.attr("href") ?: return null
        
        // Detecta o tipo
        val isAnime = href.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
        val isSerie = href.contains("/serie/") || href.contains("/tv/")
        
        return when {
            isAnime -> newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
                this.posterUrl = this@toSearchResult.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            }
            isSerie -> newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = this@toSearchResult.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            }
            else -> newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = this@toSearchResult.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            }
        }
    }
    
    // ============ BUSCA ============
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/buscar?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document
        
        return document.select(".grid .card, a.card").mapNotNull { card ->
            val title = card.attr("title") ?: card.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
            val href = card.attr("href") ?: return@mapNotNull null
            
            val isAnime = href.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
            val isSerie = href.contains("/serie/") || href.contains("/tv/")
            
            when {
                isAnime -> newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
                    this.posterUrl = card.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                }
                isSerie -> newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = card.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                }
                else -> newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                    this.posterUrl = card.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                }
            }
        }
    }
    
    // ============ CARREGAR CONTEÚDO ============
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val titleElement = document.selectFirst("h1, .title")
        val title = titleElement?.text() ?: return null
        
        val isAnime = url.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
        val isSerie = url.contains("/serie/") || url.contains("/tv/") ||
                     document.selectFirst(".episode-list, .season-list") != null
        
        return if (isAnime || isSerie) {
            // SUA FUNÇÃO DE EXTRAIR EPISÓDIOS DO SITE (mantida!)
            val episodes = extractEpisodesFromSite(document, url, isAnime, isSerie)
            
            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            
            newTvSeriesLoadResponse(title, url, type, episodes) {
                // TMDB preenche automaticamente:
                // - poster, backdrop, sinopse, gêneros
                // - atores, trailer, ano, classificação
                // - NÃO preenche episódios (isso vem do seu site)
                
                // Adiciona recomendações do site
                this.recommendations = extractRecommendationsFromSite(document)
                
                // Adiciona tags/sinopse do site como fallback
                val siteDescription = document.selectFirst("meta[name='description']")?.attr("content")
                if (siteDescription?.isNotEmpty() == true && this.plot.isNullOrEmpty()) {
                    this.plot = siteDescription
                }
            }
        } else {
            // FILME
            val playerUrl = findPlayerUrl(document)
            
            newMovieLoadResponse(title, url, TvType.Movie, playerUrl ?: url) {
                // TMDB preenche tudo automaticamente
                this.recommendations = extractRecommendationsFromSite(document)
            }
        }
    }
    
    // ============ MANTENHA SUAS FUNÇÕES DE EXTRAÇÃO! ============
    
    private suspend fun extractEpisodesFromSite(
        document: org.jsoup.nodes.Document,
        url: String,
        isAnime: Boolean,
        isSerie: Boolean = false
    ): List<Episode> {
        println("🔍 [DEBUG] Extraindo episódios da URL: $url")
        val episodes = mutableListOf<Episode>()
        
        val episodeElements = document.select("button.bd-play[data-url], a.episode-card, .episode-item")
        println("🔍 [DEBUG] Elementos de episódio encontrados: ${episodeElements.size}")
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val dataUrl = element.attr("data-url") ?: element.attr("href") ?: ""
                if (dataUrl.isBlank()) return@forEachIndexed
                
                val epNumber = extractEpisodeNumber(element, index + 1)
                val seasonNumber = element.attr("data-season").toIntOrNull() ?: 1
                
                val episode = newEpisode(fixUrl(dataUrl)) {
                    this.name = "Episódio $epNumber"
                    this.season = seasonNumber
                    this.episode = epNumber
                    
                    // Pode adicionar sinopse do site se quiser
                    element.selectFirst(".ep-desc, .description")?.text()?.trim()?.let { desc ->
                        if (desc.isNotBlank()) {
                            this.description = desc
                        }
                    }
                }
                
                episodes.add(episode)
            } catch (e: Exception) {
                println("❌ [DEBUG] Erro episódio $index: ${e.message}")
            }
        }
        
        println("✅ [DEBUG] Total de episódios extraídos: ${episodes.size}")
        return episodes
    }
    
    private fun extractEpisodeNumber(element: Element, default: Int): Int {
        return element.attr("data-ep").toIntOrNull() ?:
               element.selectFirst(".ep-number")?.text()?.toIntOrNull() ?:
               Regex("Ep\\.?\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               default
    }
    
    private fun extractRecommendationsFromSite(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select(".recs-grid .rec-card").mapNotNull { element ->
            val href = element.attr("href") ?: return@mapNotNull null
            val title = element.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
            
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            }
        }
    }
    
    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        return document.selectFirst("button.bd-play[data-url]")?.attr("data-url") ?:
               document.selectFirst("iframe[src*='fembed']")?.attr("src")
    }
    
    // ============ EXTRATOR DE LINKS (mantém igual) ============
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return SuperFlixExtractor.extractVideoLinks(data, mainUrl, name, callback)
    }
}