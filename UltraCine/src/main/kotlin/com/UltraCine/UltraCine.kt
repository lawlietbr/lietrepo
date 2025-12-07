@file:Suppress("DEPRECATION")

package com.UltraCine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element

class UltraCine : MainAPI() {
    override var mainUrl = "https://ultracine.org"
    override var name = "UltraCine"
    override val hasMainPage = true
    override var lang = "pt-br"
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
        "$mainUrl/category/misterio/" to "Misterio",
        "$mainUrl/category/romance/" to "Romance",
        "$mainUrl/category/terror/" to "Terror",
        "$mainUrl/category/thriller/" to "Thriller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "page/$page/" else ""
        val document = app.get(url).document
        val home = document.select("div.aa-cn div#movies-a ul.post-lst li").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("header.entry-header h2.entry-title")?.text() ?: return null
        val href = selectFirst("a.lnk-blk")?.attr("href") ?: return null
        
        // VERSÃO ORIGINAL QUE FUNCIONAVA
        val posterUrl = selectFirst("div.post-thumbnail figure img")?.attr("src")
            ?.takeIf { it.isNotBlank() } 
            ?.let { fixUrl(it).replace("/w500/", "/original/") }
            ?: selectFirst("div.post-thumbnail figure img")?.attr("data-src")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it).replace("/w500/", "/original/") }

        val year = selectFirst("span.year")?.text()?.toIntOrNull()

        val isSerie = href.contains("/serie/") || title.contains("Temporada")

        return if (isSerie) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
                this.quality = getQualityFromString(selectFirst("span.post-ql")?.text())
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
                this.quality = getQualityFromString(selectFirst("span.post-ql")?.text())
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.aa-cn div#movies-a ul.post-lst li").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("aside.fg1 header.entry-header h1.entry-title")?.text() ?: return null
        
        // POSTER ORIGINAL QUE FUNCIONAVA
        val poster = document.selectFirst("div.bghd img.TPostBg")?.attr("src")
            ?.takeIf { it.isNotBlank() } 
            ?.let { fixUrl(it).replace("/w1280/", "/original/") }
            ?: document.selectFirst("div.bghd img.TPostBg")?.attr("data-src")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it).replace("/w1280/", "/original/") }

        val yearText = document.selectFirst("aside.fg1 header.entry-header div.entry-meta span.year")?.ownText()
        val year = yearText?.toIntOrNull()
        val durationText = document.selectFirst("aside.fg1 header.entry-header div.entry-meta span.duration")?.ownText()
        val plot = document.selectFirst("aside.fg1 div.description p")?.text()
        val tags = document.select("aside.fg1 header.entry-header div.entry-meta span.genres a").map { it.text() }
        val actors = document.select("aside.fg1 ul.cast-lst p a").map {
            Actor(it.text(), it.attr("href"))
        }
        val trailer = document.selectFirst("div.mdl-cn div.video iframe")?.attr("src")
            ?.takeIf { it.isNotBlank() } ?: document.selectFirst("div.mdl-cn div.video iframe")?.attr("data-src")

        val isSerie = url.contains("/serie/")

        return if (isSerie) {
            // EPISÓDIOS ORIGINAL QUE FUNCIONA
            val episodes = parseSeriesEpisodes(document, url)
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = null
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            // Para filmes, usa iframe normal
            val iframeUrl = document.selectFirst("iframe[src*='assistirseriesonline']")?.attr("src")
                ?.takeIf { it.isNotBlank() } ?: document.selectFirst("iframe[src*='assistirseriesonline']")?.attr("data-src")
            
            newMovieLoadResponse(title, url, TvType.Movie, iframeUrl ?: "") {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = parseDuration(durationText)
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    private fun parseSeriesEpisodes(doc: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        println("=== ANALISANDO EPISÓDIOS DA SÉRIE ===")
        println("📌 URL base: $baseUrl")
        
        // Método 1: EPISÓDIOS ORIGINAL QUE FUNCIONAVA
        doc.select("header.header ul.header-navigation li").forEach { seasonEl ->
            val seasonNum = seasonEl.attr("data-season-number").toIntOrNull() ?: return@forEach
            val seasonId = seasonEl.attr("data-season-id")

            doc.select("li[data-season-id='$seasonId']").mapNotNull { epEl ->
                val epId = epEl.attr("data-episode-id")
                if (epId.isBlank()) return@mapNotNull null

                val title = epEl.selectFirst("a")?.text() ?: "Episódio"
                val epNum = title.substringBefore(" - ").toIntOrNull() ?: 1

                newEpisode(epId) {
                    this.name = title.substringAfter(" - ").takeIf { it.isNotEmpty() } ?: title
                    this.season = seasonNum
                    this.episode = epNum
                }
            }.also { episodes.addAll(it) }
        }
        
        // Método 2: Fallback - tenta encontrar qualquer estrutura de episódios
        if (episodes.isEmpty()) {
            doc.select("ul.episodios li, div.episodios article, .episode-item").forEachIndexed { index, item ->
                val episodeLink = item.selectFirst("a")
                val href = episodeLink?.attr("href") ?: return@forEachIndexed
                
                val episodeTitle = episodeLink.selectFirst("h3")?.text()
                    ?: item.selectFirst(".episodiotitle")?.text()
                    ?: episodeLink.text().trim()
                
                // Tenta extrair número do episódio
                val epNum = extractEpisodeNumber(episodeTitle, index + 1)
                val seasonNum = extractSeasonNumber(episodeTitle)
                
                println("🎬 Encontrado (fallback): $episodeTitle -> Temporada $seasonNum Episódio $epNum")
                
                // Usa a URL completa como data
                val episodeData = if (href.startsWith("http")) href else "$mainUrl$href"
                
                episodes.add(
                    Episode(
                        data = episodeData,
                        name = episodeTitle,
                        season = seasonNum,
                        episode = epNum,
                        posterUrl = null
                    )
                )
            }
        }
        
        println("\n✅ Total de episódios encontrados: ${episodes.size}")
        return episodes
    }

    private fun extractSeasonNumber(text: String): Int {
        val patterns = listOf(
            Regex("""Temporada\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""T(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""S(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""\s*(\d+)ª?\s*Temporada""", RegexOption.IGNORE_CASE)
        )
        
        patterns.forEach { pattern ->
            pattern.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        
        return 1 // Temporada padrão
    }

    private fun extractEpisodeNumber(text: String, default: Int): Int {
        val patterns = listOf(
            Regex("""Episódio\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""E(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""\s*(\d+)""")
        )
        
        patterns.forEach { pattern ->
            pattern.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        
        return default
    }

    private fun parseDuration(duration: String?): Int? {
        if (duration.isNullOrBlank()) return null
        val regex = Regex("""(\d+)h.*?(\d+)m""")
        val match = regex.find(duration)
        return if (match != null) {
            val h = match.groupValues[1].toIntOrNull() ?: 0
            val m = match.groupValues[2].toIntOrNull() ?: 0
            h * 60 + m
        } else {
            Regex("""(\d+)m""").find(duration)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    // VERSÃO MELHORADA DO loadLinks QUE TRATA FILMES E EPISÓDIOS
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎬 ULTRA CINE loadLinks CHAMADO!")
        println("📦 Data recebido: $data")
        
        if (data.isBlank()) return false

        return try {
            // Verifica se é um episódio de série (ID numérico)
            val isEpisode = data.matches(Regex("^\\d+$"))
            
            if (isEpisode) {
                // É um episódio - usa estratégia específica
                return loadEpisodeLinks(data, subtitleCallback, callback)
            } else if (data.contains("assistirseriesonline")) {
                // É uma URL do assistirseriesonline (filme ou episódio via URL)
                return loadAssistirSeriesOnlineLinks(data, subtitleCallback, callback)
            } else {
                // É uma URL normal (provavelmente filme)
                return loadStandardLinks(data, subtitleCallback, callback)
            }
            
        } catch (e: Exception) {
            println("💥 ERRO em loadLinks: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    // ESTRATÉGIA PARA EPISÓDIOS (ID numérico)
    private suspend fun loadEpisodeLinks(
        episodeId: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎬 Carregando links de EPISÓDIO...")
        println("🆔 Episode ID: $episodeId")
        
        // Primeiro tenta a estratégia de URLs diretas
        val episodeUrl = "https://assistirseriesonline.icu/episodio/$episodeId/"
        
        println("🔗 Tentando página do episódio: $episodeUrl")
        
        return try {
            // Adiciona headers para evitar bloqueios
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Referer" to mainUrl,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
            )
            
            val response = app.get(episodeUrl, headers = headers, timeout = 30)
            val html = response.text
            
            // ESTRATÉGIA 1: Procura por JW Player específico
            val jwPlayerPattern = Regex("""<video[^>]+class=["'][^"']*jw[^"']*["'][^>]+src=["'](https?://[^"']+)["']""")
            val jwMatches = jwPlayerPattern.findAll(html).toList()
            
            if (jwMatches.isNotEmpty()) {
                jwMatches.forEach { match ->
                    val videoUrl = match.groupValues[1]
                    if (videoUrl.isNotBlank() && 
                        (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8"))) {
                        
                        println("✅ JW Player encontrado: $videoUrl")
                        
                        val quality = extractQualityFromUrl(videoUrl)
                        val linkName = if (quality != Qualities.Unknown.value) {
                            "${this.name} (${quality}p)"
                        } else {
                            "${this.name} (Série)"
                        }
                        
                        val link = newExtractorLink(
                            source = this.name,
                            name = linkName,
                            url = videoUrl
                        )
                        callback.invoke(link)
                        return true
                    }
                }
            }
            
            // ESTRATÉGIA 2: Procura por iframes do EmbedPlay
            val iframePattern = Regex("""<iframe[^>]+src=["'](https?://[^"']+embedplay[^"']*)["']""")
            val iframeMatches = iframePattern.findAll(html).toList()
            
            if (iframeMatches.isNotEmpty()) {
                iframeMatches.forEach { match ->
                    val iframeUrl = match.groupValues[1]
                    println("🎬 Iframe EmbedPlay encontrado: $iframeUrl")
                    
                    // Usa o loadExtractor do Cloudstream para processar o iframe
                    if (loadExtractor(iframeUrl, episodeUrl, subtitleCallback, callback)) {
                        return true
                    }
                }
            }
            
            // ESTRATÉGIA 3: Procura por iframes genéricos
            val genericIframePattern = Regex("""<iframe[^>]+src=["'](https?://[^"']+player[^"']*)["']""")
            val genericIframeMatches = genericIframePattern.findAll(html).toList()
            
            if (genericIframeMatches.isNotEmpty()) {
                genericIframeMatches.forEach { match ->
                    val iframeUrl = match.groupValues[1]
                    println("🖼️ Iframe Player encontrado: $iframeUrl")
                    
                    if (loadExtractor(iframeUrl, episodeUrl, subtitleCallback, callback)) {
                        return true
                    }
                }
            }
            
            // ESTRATÉGIA 4: Procura por qualquer link .mp4
            val mp4Pattern = Regex("""(https?://[^"'\s<>]+\.mp4[^"'\s<>]*)""")
            val mp4Matches = mp4Pattern.findAll(html).toList()
            
            if (mp4Matches.isNotEmpty()) {
                mp4Matches.forEach { match ->
                    val videoUrl = match.value
                    if (videoUrl.isNotBlank() && videoUrl.length > 30 &&
                        !videoUrl.contains("banner") && !videoUrl.contains("ads")) {
                        
                        println("🎬 MP4 direto encontrado: $videoUrl")
                        
                        val link = newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = videoUrl
                        )
                        callback.invoke(link)
                        return true
                    }
                }
            }
            
            // ESTRATÉGIA 5: Usa JSoup para parsear a página
            val doc = response.document
            
            // Procura por botões com data-source
            doc.select("button[data-source]").forEach { button ->
                val source = button.attr("data-source")
                if (source.isNotBlank()) {
                    println("🔘 Botão com data-source: $source")
                    if (loadExtractor(source, episodeUrl, subtitleCallback, callback)) {
                        return true
                    }
                }
            }
            
            // Procura por scripts com URLs de vídeo
            doc.select("script").forEach { script ->
                val scriptText = script.html()
                
                // Padrões de URLs em scripts
                val patterns = listOf(
                    Regex("""['"]file['"]\s*:\s*['"](https?://[^"']+)['"]"""),
                    Regex("""['"]url['"]\s*:\s*['"](https?://[^"']+)['"]"""),
                    Regex("""['"]src['"]\s*:\s*['"](https?://[^"']+)['"]"""),
                    Regex("""videoUrl\s*[:=]\s*['"](https?://[^"']+)['"]""")
                )
                
                patterns.forEach { pattern ->
                    pattern.findAll(scriptText).forEach { match ->
                        val videoUrl = match.groupValues[1]
                        if (videoUrl.isNotBlank() && 
                            (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8"))) {
                            
                            println("📜 URL em script: $videoUrl")
                            
                            val link = newExtractorLink(
                                source = this.name,
                                name = this.name,
                                url = videoUrl
                            )
                            callback.invoke(link)
                            return true
                        }
                    }
                }
            }
            
            println("❌ Nenhum link encontrado para episódio $episodeId")
            false
            
        } catch (e: Exception) {
            println("💥 ERRO ao carregar episódio $episodeId: ${e.message}")
            false
        }
    }

    // ESTRATÉGIA PARA URL DO assistirseriesonline (filmes)
    private suspend fun loadAssistirSeriesOnlineLinks(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎬 Carregando links de assistirseriesonline...")
        println("🔗 URL: $url")
        
        return try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Referer" to mainUrl
            )
            
            val response = app.get(url, headers = headers, timeout = 30)
            val doc = response.document
            
            // Procura por iframes (EmbedPlay funciona bem para filmes)
            doc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && loadExtractor(src, url, subtitleCallback, callback)) {
                    return true
                }
            }
            
            // Procura por botões com data-source
            doc.select("button[data-source]").forEach { button ->
                val source = button.attr("data-source")
                if (source.isNotBlank() && loadExtractor(source, url, subtitleCallback, callback)) {
                    return true
                }
            }
            
            // Procura por links MP4 diretos
            val html = response.text
            val mp4Pattern = Regex("""(https?://[^"'\s<>]+\.mp4[^"'\s<>]*)""")
            val mp4Matches = mp4Pattern.findAll(html).toList()
            
            if (mp4Matches.isNotEmpty()) {
                mp4Matches.forEach { match ->
                    val videoUrl = match.value
                    if (videoUrl.isNotBlank() && videoUrl.length > 30 &&
                        !videoUrl.contains("banner") && !videoUrl.contains("ads")) {
                        
                        println("🎬 MP4 direto encontrado: $videoUrl")
                        
                        val link = newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = videoUrl
                        )
                        callback.invoke(link)
                        return true
                    }
                }
            }
            
            false
        } catch (e: Exception) {
            println("💥 ERRO em loadAssistirSeriesOnlineLinks: ${e.message}")
            false
        }
    }

    // ESTRATÉGIA PADRÃO PARA URLS NORMAIS
    private suspend fun loadStandardLinks(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎬 Carregando links padrão...")
        println("🔗 URL: $url")
        
        return try {
            val doc = app.get(url).document
            
            // Procura por iframes
            doc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && loadExtractor(src, url, subtitleCallback, callback)) {
                    return true
                }
            }
            
            false
        } catch (e: Exception) {
            println("💥 ERRO em loadStandardLinks: ${e.message}")
            false
        }
    }

    // Função auxiliar para extrair qualidade (mantém a mesma)
    private fun extractQualityFromUrl(url: String): Int {
        val qualityPattern = Regex("""/(\d+)p?/""")
        val match = qualityPattern.find(url)
        
        if (match != null) {
            val qualityNum = match.groupValues[1].toIntOrNull()
            return when (qualityNum) {
                360 -> 360
                480 -> 480
                720 -> 720
                1080 -> 1080
                2160 -> 2160
                else -> Qualities.Unknown.value
            }
        }
        
        return when {
            url.contains("360p", ignoreCase = true) -> 360
            url.contains("480p", ignoreCase = true) -> 480
            url.contains("720p", ignoreCase = true) -> 720
            url.contains("1080p", ignoreCase = true) -> 1080
            url.contains("2160p", ignoreCase = true) -> 2160
            else -> Qualities.Unknown.value
        }
    }
}