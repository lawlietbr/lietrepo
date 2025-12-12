package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.metaproviders.TmdbLink

class SuperFlix : TmdbProvider() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override var lang = "pt-br"
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
        println("📄 [DEBUG] getMainPage: ${request.name} - Página $page")
        println("🔗 [DEBUG] URL: ${request.data}${if (page > 1) "?page=$page" else ""}")
        
        val url = request.data + if (page > 1) "?page=$page" else ""
        val document = app.get(url).document
        
        val items = document.select("a.card, div.recs-grid a.rec-card").mapNotNull { element ->
            element.toSearchResult()
        }
        
        println("✅ [DEBUG] ${request.name}: ${items.size} itens encontrados")
        return newHomePageResponse(request.name, items.distinctBy { it.url })
    }
    
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.attr("title") ?: this.selectFirst("img")?.attr("alt") ?: return null
        val href = this.attr("href") ?: return null
        
        println("🔍 [DEBUG] toSearchResult: $title | $href")
        
        // Detecta o tipo
        val isAnime = href.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
        val isSerie = href.contains("/serie/") || href.contains("/tv/")
        
        println("🎯 [DEBUG] Tipo detectado: Anime=$isAnime, Série=$isSerie")
        
        val result = when {
            isAnime -> newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
                this.posterUrl = this@toSearchResult.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                println("📸 [DEBUG] Poster URL: ${this.posterUrl}")
            }
            isSerie -> newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = this@toSearchResult.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                println("📸 [DEBUG] Poster URL: ${this.posterUrl}")
            }
            else -> newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = this@toSearchResult.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                println("📸 [DEBUG] Poster URL: ${this.posterUrl}")
            }
        }
        
        println("✅ [DEBUG] SearchResponse criado: ${result.name} (${result.url})")
        return result
    }
    
    // ============ BUSCA ============
    override suspend fun search(query: String): List<SearchResponse> {
        println("🔎 [DEBUG] search: Buscando '$query'")
        
        val searchUrl = "$mainUrl/buscar?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        println("🔗 [DEBUG] URL de busca: $searchUrl")
        
        val document = app.get(searchUrl).document
        
        val results = document.select(".grid .card, a.card").mapNotNull { card ->
            val title = card.attr("title") ?: card.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
            val href = card.attr("href") ?: return@mapNotNull null
            
            println("🔍 [DEBUG] Resultado encontrado: $title | $href")
            
            val isAnime = href.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
            val isSerie = href.contains("/serie/") || href.contains("/tv/")
            
            println("🎯 [DEBUG] Tipo: Anime=$isAnime, Série=$isSerie")
            
            val result = when {
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
            
            println("✅ [DEBUG] Resultado processado: ${result.name}")
            result
        }
        
        println("✅ [DEBUG] Total de resultados: ${results.size}")
        return results
    }
    
    // ============ CARREGAR CONTEÚDO ============
    override suspend fun load(url: String): LoadResponse? {
        println("📥 [DEBUG] load: Carregando URL: $url")
        
        val document = app.get(url).document
        
        val titleElement = document.selectFirst("h1, .title")
        val title = titleElement?.text() ?: return null
        
        println("🎬 [DEBUG] Título encontrado no site: $title")
        
        val isAnime = url.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
        val isSerie = url.contains("/serie/") || url.contains("/tv/") ||
                     document.selectFirst(".episode-list, .season-list") != null
        
        println("🎯 [DEBUG] Tipo detectado: Anime=$isAnime, Série=$isSerie")
        
        return if (isAnime || isSerie) {
            println("📺 [DEBUG] É uma série/anime")
            
            // SUA FUNÇÃO DE EXTRAIR EPISÓDIOS DO SITE (mantida!)
            val episodes = extractEpisodesFromSite(document, url, isAnime, isSerie)
            println("🎞️ [DEBUG] Total de episódios extraídos do site: ${episodes.size}")
            
            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            
            // Criar resposta de série
            val response = newTvSeriesLoadResponse(title, url, type, episodes) {
                println("🔄 [DEBUG] Configurando TvSeriesLoadResponse...")
                
                // TMDB preenche automaticamente:
                // - poster, backdrop, sinopse, gêneros
                // - atores, trailer, ano, classificação
                // - NÃO preenche episódios (isso vem do seu site)
                
                // Adiciona recomendações do site
                val recommendations = extractRecommendationsFromSite(document)
                this.recommendations = recommendations
                println("🌟 [DEBUG] Recomendações do site: ${recommendations.size}")
                
                // Adiciona tags/sinopse do site como fallback
                val siteDescription = document.selectFirst("meta[name='description']")?.attr("content")
                println("📝 [DEBUG] Descrição do site: ${siteDescription?.take(50)}...")
                
                if (siteDescription?.isNotEmpty() == true && this.plot.isNullOrEmpty()) {
                    this.plot = siteDescription
                    println("✅ [DEBUG] Usando descrição do site como fallback")
                }
                
                println("✅ [DEBUG] TvSeriesLoadResponse configurada com sucesso!")
            }
            
            println("🎉 [DEBUG] Série criada com ${episodes.size} episódios")
            response
            
        } else {
            println("🎬 [DEBUG] É um filme")
            
            val playerUrl = findPlayerUrl(document)
            println("▶️ [DEBUG] Player URL encontrado: $playerUrl")
            
            // Criar resposta de filme
            val response = newMovieLoadResponse(title, url, TvType.Movie, playerUrl ?: url) {
                println("🔄 [DEBUG] Configurando MovieLoadResponse...")
                
                // TMDB preenche tudo automaticamente
                val recommendations = extractRecommendationsFromSite(document)
                this.recommendations = recommendations
                println("🌟 [DEBUG] Recomendações do site: ${recommendations.size}")
                
                println("✅ [DEBUG] MovieLoadResponse configurada com sucesso!")
            }
            
            println("🎉 [DEBUG] Filme criado com player URL: $playerUrl")
            response
        }
    }
    
    // ============ MANTENHA SUAS FUNÇÕES DE EXTRAÇÃO! ============
    
    private suspend fun extractEpisodesFromSite(
        document: org.jsoup.nodes.Document,
        url: String,
        isAnime: Boolean,
        isSerie: Boolean = false
    ): List<Episode> {
        println("🔍 [DEBUG] extractEpisodesFromSite: Extraindo episódios da URL: $url")
        
        val episodes = mutableListOf<Episode>()
        
        val episodeElements = document.select("button.bd-play[data-url], a.episode-card, .episode-item")
        println("🔍 [DEBUG] Elementos de episódio encontrados: ${episodeElements.size}")
        
        if (episodeElements.isEmpty()) {
            println("⚠️ [DEBUG] Nenhum elemento de episódio encontrado!")
            println("🔍 [DEBUG] HTML relevante (primeiros 1000 chars):")
            println(document.html().take(1000))
        }
        
        episodeElements.forEachIndexed { index, element ->
            try {
                println("🔍 [DEBUG] Processando episódio $index...")
                
                val dataUrl = element.attr("data-url") ?: element.attr("href") ?: ""
                if (dataUrl.isBlank()) {
                    println("⚠️ [DEBUG] Episódio $index sem data-url/href")
                    return@forEachIndexed
                }
                
                println("🔗 [DEBUG] Episódio $index data-url: $dataUrl")
                
                val epNumber = extractEpisodeNumber(element, index + 1)
                val seasonNumber = element.attr("data-season").toIntOrNull() ?: 1
                
                println("🎯 [DEBUG] Episódio $index: Temporada $seasonNumber, Episódio $epNumber")
                
                val episode = newEpisode(fixUrl(dataUrl)) {
                    this.name = "Episódio $epNumber"
                    this.season = seasonNumber
                    this.episode = epNumber
                    
                    // Pode adicionar sinopse do site se quiser
                    element.selectFirst(".ep-desc, .description")?.text()?.trim()?.let { desc ->
                        if (desc.isNotBlank()) {
                            this.description = desc
                            println("📝 [DEBUG] Sinopse do episódio: ${desc.take(50)}...")
                        }
                    }
                }
                
                episodes.add(episode)
                println("✅ [DEBUG] Episódio $index adicionado: ${episode.name}")
                
            } catch (e: Exception) {
                println("❌ [DEBUG] Erro ao processar episódio $index: ${e.message}")
                e.printStackTrace()
            }
        }
        
        println("✅ [DEBUG] Total de episódios extraídos: ${episodes.size}")
        
        if (episodes.isEmpty()) {
            println("⚠️ [DEBUG] NENHUM EPISÓDIO EXTRAÍDO!")
            println("🔍 [DEBUG] Tentando seletores alternativos...")
            
            // Tentar seletores alternativos
            val altSelectors = listOf(
                "a[href*='episodio']",
                "a[href*='episode']",
                ".video-item",
                ".play-button",
                "[class*='season']",
                "[class*='episode']"
            )
            
            for (selector in altSelectors) {
                val altElements = document.select(selector)
                println("🔍 [DEBUG] Seletor '$selector': ${altElements.size} elementos")
                
                if (altElements.isNotEmpty()) {
                    println("🔍 [DEBUG] Exemplo do primeiro elemento:")
                    println(altElements.first().outerHtml().take(200))
                    break
                }
            }
        }
        
        return episodes
    }
    
    private fun extractEpisodeNumber(element: Element, default: Int): Int {
        println("🔢 [DEBUG] extractEpisodeNumber: Extraindo número do episódio")
        
        // 1. Tentar data-ep
        val dataEp = element.attr("data-ep")
        if (dataEp.isNotBlank()) {
            val num = dataEp.toIntOrNull()
            if (num != null) {
                println("✅ [DEBUG] Número do data-ep: $num")
                return num
            }
        }
        
        // 2. Tentar classe ep-number
        val epNumberElement = element.selectFirst(".ep-number, .number, .episode-number")
        val epNumberText = epNumberElement?.text()
        if (epNumberText?.isNotBlank() == true) {
            val num = epNumberText.toIntOrNull()
            if (num != null) {
                println("✅ [DEBUG] Número do .ep-number: $num")
                return num
            }
        }
        
        // 3. Tentar regex no texto
        val text = element.text()
        println("🔍 [DEBUG] Texto do elemento: $text")
        
        val epRegex = Regex("Ep\\.?\\s*(\\d+)")
        val match = epRegex.find(text)
        if (match != null) {
            val num = match.groupValues[1].toIntOrNull()
            if (num != null) {
                println("✅ [DEBUG] Número do regex 'Ep': $num")
                return num
            }
        }
        
        // 4. Tentar regex em português
        val ptRegex = Regex("Epis[oó]dio\\s*(\\d+)")
        val ptMatch = ptRegex.find(text)
        if (ptMatch != null) {
            val num = ptMatch.groupValues[1].toIntOrNull()
            if (num != null) {
                println("✅ [DEBUG] Número do regex 'Episódio': $num")
                return num
            }
        }
        
        println("⚠️ [DEBUG] Nenhum número encontrado, usando default: $default")
        return default
    }
    
    private fun extractRecommendationsFromSite(document: org.jsoup.nodes.Document): List<SearchResponse> {
        println("🌟 [DEBUG] extractRecommendationsFromSite: Extraindo recomendações")
        
        val recommendations = document.select(".recs-grid .rec-card").mapNotNull { element ->
            try {
                val href = element.attr("href") ?: return@mapNotNull null
                if (href.isBlank() || href == "#") {
                    println("⚠️ [DEBUG] Recomendação sem href válido")
                    return@mapNotNull null
                }
                
                val imgElement = element.selectFirst("img")
                val title = imgElement?.attr("alt") ?: 
                           element.selectFirst(".rec-title")?.text() ?: 
                           element.attr("title") ?: 
                           return@mapNotNull null
                
                println("🔍 [DEBUG] Recomendação encontrada: $title | $href")
                
                val poster = imgElement?.attr("src")?.let { fixUrl(it) }
                println("📸 [DEBUG] Poster da recomendação: $poster")
                
                newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                    this.posterUrl = poster
                }
            } catch (e: Exception) {
                println("❌ [DEBUG] Erro ao processar recomendação: ${e.message}")
                null
            }
        }
        
        println("✅ [DEBUG] Total de recomendações extraídas: ${recommendations.size}")
        return recommendations
    }
    
    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        println("▶️ [DEBUG] findPlayerUrl: Buscando URL do player")
        
        // 1. Tentar botão bd-play
        val playButton = document.selectFirst("button.bd-play[data-url]")
        if (playButton != null) {
            val url = playButton.attr("data-url")
            println("✅ [DEBUG] Player URL encontrado no botão: $url")
            return url
        }
        
        // 2. Tentar iframe
        val iframe = document.selectFirst("iframe[src*='fembed'], iframe[src*='filemoon'], iframe[src*='player'], iframe[src*='embed']")
        if (iframe != null) {
            val url = iframe.attr("src")
            println("✅ [DEBUG] Player URL encontrado no iframe: $url")
            return url
        }
        
        // 3. Tentar links diretos
        val videoLink = document.selectFirst("a[href*='.m3u8'], a[href*='.mp4'], a[href*='watch']")
        if (videoLink != null) {
            val url = videoLink.attr("href")
            println("✅ [DEBUG] Player URL encontrado no link: $url")
            return url
        }
        
        // 4. Tentar scripts
        val scripts = document.select("script")
        for (script in scripts) {
            val scriptText = script.html()
            if (scriptText.contains("m3u8") || scriptText.contains("mp4")) {
                println("🔍 [DEBUG] Script encontrado com possível URL de vídeo")
                val urlMatch = Regex("(https?://[^\"' ]+\\.(m3u8|mp4))").find(scriptText)
                if (urlMatch != null) {
                    val url = urlMatch.value
                    println("✅ [DEBUG] Player URL encontrado no script: $url")
                    return url
                }
            }
        }
        
        println("⚠️ [DEBUG] Nenhum player URL encontrado!")
        return null
    }
    
    // ============ EXTRATOR DE LINKS (mantém igual) ============
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔗 [DEBUG] loadLinks: Extraindo links de vídeo")
        println("📦 [DEBUG] Data recebida: ${data.take(100)}...")
        
        return try {
            val result = SuperFlixExtractor.extractVideoLinks(data, mainUrl, name, callback)
            println("✅ [DEBUG] Extrator retornou: $result")
            result
        } catch (e: Exception) {
            println("❌ [DEBUG] Erro no extrator: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    // ============ DEBUG: Log quando o plugin é carregado ============
    init {
        println("🚀 [DEBUG] Plugin SuperFlix inicializado!")
        println("🌐 [DEBUG] Main URL: $mainUrl")
        println("🗣️ [DEBUG] Idioma: $lang")
        println("🎬 [DEBUG] Tipos suportados: $supportedTypes")
        println("🔧 [DEBUG] Usando TmdbProvider: Sim")
        println("🔧 [DEBUG] useMetaLoadResponse: $useMetaLoadResponse")
    }
}