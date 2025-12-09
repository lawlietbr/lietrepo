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
    // CARREGAR DETALHES (VERSÃO SIMPLIFICADA)
    // =========================================================================
    override suspend fun load(url: String): LoadResponse? {
        println("🎬 SuperFlix: Carregando página: $url")
        
        try {
            val document = app.get(url).document
            
            // 1. Extrair título
            val title = document.selectFirst("h1")?.text() ?: return null
            
            // 2. Determinar tipo pela URL
            val isSerie = url.contains("/serie/")
            
            // 3. Extrair ano do título
            val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
            val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
            
            println("🎬 SuperFlix: Carregando '$cleanTitle' (${if (isSerie) "Série" else "Filme"}, Ano: $year)")
            
            // 4. Extrair poster
            val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            
            // 5. Extrair descrição
            val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            
            // 6. Extrair tags/gêneros
            val tags = document.select("a[href*='/categoria/']").map { it.text() }.takeIf { it.isNotEmpty() }
            
            if (isSerie) {
                // 7. Extrair episódios
                val episodes = extractEpisodesFromDocument(document, url)
                println("📺 Encontrados ${episodes.size} episódios")
                
                // Se não encontrou episódios, criar pelo menos 1 episódio
                val finalEpisodes = if (episodes.isEmpty()) {
                    listOf(
                        newEpisode(url) {
                            name = "Episódio 1"
                            season = 1
                            episode = 1
                        }
                    )
                } else {
                    episodes
                }
                
                return newTvSeriesLoadResponse(cleanTitle, url, TvType.TvSeries, finalEpisodes) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = description
                    this.tags = tags
                }
            } else {
                return newMovieLoadResponse(cleanTitle, url, TvType.Movie, "") {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = description
                    this.tags = tags
                }
            }
            
        } catch (e: Exception) {
            println("❌ Erro ao carregar página: ${e.message}")
            return null
        }
    }

    // =========================================================================
    // EXTRAIR EPISÓDIOS (MELHORADA)
    // =========================================================================
    private fun extractEpisodesFromDocument(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Estratégia 1: Botões com data-url (mais comum)
        document.select("button[data-url], a[data-url]").forEachIndexed { index, element ->
            val episodeUrl = element.attr("data-url")?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
            val episodeTitle = element.attr("title")?.takeIf { it.isNotBlank() }
                          ?: element.selectFirst(".ep-title, .title, .name")?.text()?.takeIf { it.isNotBlank() }
                          ?: "Episódio ${index + 1}"
            
            episodes.add(newEpisode(fixUrl(episodeUrl)) {
                name = episodeTitle.trim()
                episode = index + 1
                season = 1
            })
        }
        
        // Estratégia 2: Links que parecem ser de episódios
        if (episodes.isEmpty()) {
            document.select("a[href*='episodio'], a[href*='episode'], a[href*='assistir']").forEachIndexed { index, element ->
                val href = element.attr("href")?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
                val episodeTitle = element.text().takeIf { it.isNotBlank() } ?: "Episódio ${index + 1}"
                
                episodes.add(newEpisode(fixUrl(href)) {
                    name = episodeTitle.trim()
                    episode = index + 1
                    season = 1
                })
            }
        }
        
        return episodes.distinctBy { it.url }
    }

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