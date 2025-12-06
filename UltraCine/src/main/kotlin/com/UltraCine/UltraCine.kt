package com.UltraCine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element
import java.net.URLEncoder

class UltraCine : MainAPI() {
    override var mainUrl = "https://ultracine.org"
    override var name = "UltraCine"
    override val hasMainPage = true
    override var lang = "pt"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/category/lancamentos/" to "Lançamentos",
        "$mainUrl/category/acao/" to "Ação",
        "$mainUrl/category/animacao/" to "Animação",
        "$mainUrl/category/comedia/" to "Comédia",
        "$mainUrl/category/crime/" to "Crime",
        "$mainUrl/category/documentario/" to "Documentário",
        "$mainUrl/category/drama/" to "Drama",
        "$mainUrl/category/familia/" to "Família",
        "$mainUrl/category/fantasia/" to "Fantasia",
        "$mainUrl/category/ficcao-cientifica/" to "Ficção Científica",
        "$mainUrl/category/guerra/" to "Guerra",
        "$mainUrl/category/kids/" to "Kids",
        "$mainUrl/category/misterio/" to "Mistério",
        "$mainUrl/category/romance/" to "Romance",
        "$mainUrl/category/terror/" to "Terror",
        "$mainUrl/category/thriller/" to "Thriller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "page/$page/" else ""
        val doc = app.get(url).document
        val items = doc.select("div.aa-cn ul.post-lst li").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = selectFirst("h2.entry-title, h3") ?: return null
        val href = selectFirst("a.lnk-blk")?.attr("href") ?: return null
        val poster = selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("data-src")

        val year = selectFirst("span.year")?.text()?.toIntOrNull()

        return newMovieSearchResponse(titleEl.text(), href, TvType.Movie) {
            this.posterUrl = poster?.let { fixUrl(it) }
            this.year = year
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(url).document
        return doc.select("div.aa-cn ul.post-lst li").mapNotNull { it.toSearchResult() }
    }
// ... (dentro da classe UltraCine)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        // ... (Extração de metadados como title, poster, year, plot, tags, actors, trailerUrl permanece) ...
        
        val title = document.selectFirst("aside.fg1 header.entry-header h1.entry-title")?.text() ?: return null
        // ... (variáveis poster, year, duration, rating, plot, genres, actors, trailerUrl) ...

        val duration = document.selectFirst("aside.fg1 header.entry-header div.entry-meta span.duration")?.text()?.substringAfter("far\">")
        val rating = document.selectFirst("div.vote-cn span.vote span.num")?.text()?.toDoubleOrNull() // Mantido
        
        // CORREÇÃO: Usamos o seletor da versão nova, pois a versão antiga é muito específica
        val actors = document.select("ul.cast-lst a").mapNotNull { 
            val name = it.text().trim()
            val img = it.selectFirst("img")?.attr("src")
            if (name.isNotBlank()) Actor(name, img) else null
        }
        
        val trailerUrl = document.selectFirst("div.mdl-cn div.video iframe")?.let { iframe ->
            iframe.attr("src").takeIf { it.isNotBlank() } ?: iframe.attr("data-src")
        }

        val iframeElement = document.selectFirst("iframe[src*='assistirseriesonline']")
        val iframeUrl = iframeElement?.let { iframe ->
            iframe.attr("src").takeIf { it.isNotBlank() } ?: iframe.attr("data-src")
        }

        val isSerie = url.contains("/serie/")
        
        return if (isSerie) {
            if (iframeUrl != null) {
                val iframeDocument = app.get(iframeUrl).document
                val episodes = parseSeriesEpisodes(iframeDocument, iframeUrl)
                
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = plot
                    // CORREÇÃO DE TIPO DE DADO
                    this.score = rating?.times(1000)?.toInt()?.let { Score(it, null) }
                    this.tags = genres
                    addActors(actors)
                    addTrailer(trailerUrl)
                }
            } else {
                // Caso não encontre o iframe do player, retorna sem episódios (Em Breve)
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, emptyList()) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = plot
                    // CORREÇÃO DE TIPO DE DADO
                    this.score = rating?.times(1000)?.toInt()?.let { Score(it, null) }
                    this.tags = genres
                    addActors(actors)
                    addTrailer(trailerUrl)
                }
            }
        } else {
            // Filmes
            newMovieLoadResponse(title, url, TvType.Movie, iframeUrl ?: "") {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                // CORREÇÃO DE TIPO DE DADO
                this.score = rating?.times(1000)?.toInt()?.let { Score(it, null) }
                this.tags = genres
                this.duration = parseDuration(duration)
                addActors(actors)
                addTrailer(trailerUrl)
            }
        }
    }

    // Função de extração de episódios copiada do código fornecido
    private suspend fun parseSeriesEpisodes(iframeDocument: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val seasons = iframeDocument.select("header.header ul.header-navigation li")
        
        for (seasonElement in seasons) {
            val seasonNumber = seasonElement.attr("data-season-number").toIntOrNull() ?: continue
            val seasonId = seasonElement.attr("data-season-id")
            
            val seasonEpisodes = iframeDocument.select("li[data-season-id='$seasonId']")
                .mapNotNull { episodeElement ->
                    val episodeId = episodeElement.attr("data-episode-id")
                    val episodeTitle = episodeElement.selectFirst("a")?.text() ?: return@mapNotNull null
                    
                    val episodeNumber = episodeTitle.substringBefore(" - ").toIntOrNull() ?: 1
                    val cleanTitle = if (episodeTitle.contains(" - ")) {
                        episodeTitle.substringAfter(" - ")
                    } else {
                        episodeTitle
                    }
                    
                    Episode(
                        // CORREÇÃO: Passa o ID do episódio (que será usado no loadLinks)
                        data = episodeId, 
                        name = cleanTitle,
                        season = seasonNumber,
                        episode = episodeNumber
                    )
                }
            
            episodes.addAll(seasonEpisodes)
        }
        
        return episodes
    }
    
// ... (O restante da classe loadLinks e parseDuration vêm abaixo)

    
                            }
                        }
                    }
                } catch (_: Exception) {}

            } else {
                // 2. FALLBACK: PROCURA A LISTA DE EPISÓDIOS NA PÁGINA PRINCIPAL
                doc.select("div.seasons ul li a[href*='/episodio/']").forEach { epLink ->
                    val href = epLink.attr("href") // Link completo (DATA)
                    val epTitle = epLink.text().trim()

                    if (href.isNotBlank()) {
                         episodes += newEpisode(href) { 
                            this.name = epTitle
                        }
                    }
                }
            }

            // Retorno da SÉRIE (CORRIGE Score)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                // CORREÇÃO: Usa 'Score(it, null)' que é o construtor público.
                this.score = null
                addActors(actors)
                trailer?.let { addTrailer(it) }
            }
        } else {
            // FLUXO DE FILMES (CORRIGE Score)
            newMovieLoadResponse(title, url, TvType.Movie, playerLinkFromButton ?: url) { 
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = duration
                // CORREÇÃO: Usa 'Score(it, null)' que é o construtor público.
                this.score = null 
                addActors(actors)
                trailer?.let { addTrailer(it) }
            }
        }
    } 

    // --- O BLOCO loadLinks E AS FUNÇÕES AUXILIARES DEVEM ESTAR DENTRO DA CLASSE ---
    
    // ... (dentro da classe UltraCine, após a função load)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        
        // Se a DATA é o ID numérico do episódio (usado na função parseSeriesEpisodes)
        if (data.matches(Regex("\\d+"))) {
            val episodeUrl = "https://assistirseriesonline.icu/episodio/$data"
            
            try {
                // Carrega a página do episódio
                val episodeDocument = app.get(episodeUrl).document
            
                // Procura o botão de embed play
                val embedPlayButton = episodeDocument.selectFirst("button[data-source*='embedplay.upns.pro']") 
                    ?: episodeDocument.selectFirst("button[data-source*='embedplay.upn.one']")
                
                if (embedPlayButton != null) {
                    val embedPlayLink = embedPlayButton.attr("data-source")
                    
                    if (embedPlayLink.isNotBlank()) {
                        loadExtractor(embedPlayLink, episodeUrl, subtitleCallback, callback)
                        return true
                    }
                }
                
                // Procura um iframe de player único como fallback
                val singlePlayerIframe = episodeDocument.selectFirst("div.play-overlay div#player iframe")
                if (singlePlayerIframe != null) {
                    val singlePlayerSrc = singlePlayerIframe.attr("src")
                    if (singlePlayerSrc.isNotBlank()) {
                        loadExtractor(singlePlayerSrc, episodeUrl, subtitleCallback, callback)
                        return true
                    }
                }
            } catch (e: Exception) {
                // Não é necessário imprimir o stack trace em produção, mas pode ser útil para debug.
                // e.printStackTrace() 
            }
                
        // Se a DATA é uma URL HTTP (usado para filmes ou links diretos)
        } else if (data.startsWith("http")) {
            try {
                // Se a data já é o link do extrator (data-source do filme), tenta direto
                if (data.contains("embedplay.upns.") || data.contains("playembedapi.")) {
                    loadExtractor(data, data, subtitleCallback, callback)
                    return true
                }
                
                // Caso seja uma URL de iframe ou player que ainda precisa ser resolvida
                val iframeDocument = app.get(data).document
            
                val embedPlayButton = iframeDocument.selectFirst("button[data-source*='embedplay.upns.pro']")
                    ?: iframeDocument.selectFirst("button[data-source*='embedplay.upn.one']")
                
                if (embedPlayButton != null) {
                    val embedPlayLink = embedPlayButton.attr("data-source")
                    
                    if (embedPlayLink.isNotBlank()) {
                        loadExtractor(embedPlayLink, data, subtitleCallback, callback)
                        return true
                    }
                }
                
                // Fallback para iframe simples
                val singlePlayerIframe = iframeDocument.selectFirst("div.play-overlay div#player iframe")
                if (singlePlayerIframe != null) {
                    val singlePlayerSrc = singlePlayerIframe.attr("src")
                    if (singlePlayerSrc.isNotBlank()) {
                        loadExtractor(singlePlayerSrc, data, subtitleCallback, callback)
                        return true
                    }
                }
            } catch (e: Exception) {
                // e.printStackTrace()
            }
        }
        
        return false
    }

// ... (Função parseDuration permanece)

    private fun parseDuration(text: String): Int? {
        if (text.isBlank()) return null
        val h = Regex("(\\d+)h").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = Regex("(\\d+)m").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return if (h > 0 || m > 0) h * 60 + m else null
    }
} // <-- FECHAMENTO CORRETO DA CLASSE UltraCine
