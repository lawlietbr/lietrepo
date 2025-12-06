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

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
            this.quality = getQualityFromString(selectFirst("span.post-ql")?.text())
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

        val iframeUrl = document.selectFirst("iframe[src*='assistirseriesonline']")?.attr("src")
            ?.takeIf { it.isNotBlank() } ?: document.selectFirst("iframe[src*='assistirseriesonline']")?.attr("data-src")

        val isSerie = url.contains("/serie/")

        return if (isSerie) {
            // EPISÓDIOS ORIGINAL QUE FUNCIONAVA
            val episodes = if (iframeUrl != null) {
                try {
                    val iframeDoc = app.get(iframeUrl).document
                    parseSeriesEpisodes(iframeDoc)
                } catch (e: Exception) {
                    emptyList()
                }
            } else emptyList()

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

    private fun parseSeriesEpisodes(doc: org.jsoup.nodes.Document): List<Episode> {
        val episodes = mutableListOf<Episode>()

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

        return episodes
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

    // VARIÁVEL PARA ARMAZENAR O EMBED ID DA SÉRIE ATUAL
    private var currentSeriesEmbedId: String? = null
    
    // NOSSO NOVO loadLinks MELHORADO
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        return try {
            println("=== DEBUG loadLinks ===")
            println("Data recebido: $data")
            
            // PARA SÉRIES: O data é o episodeId
            if (data.matches(Regex("^\\d+$"))) {
                println("DEBUG: ID de episódio detectado: $data")
                
                // 1. PRIMEIRO TENTA A URL DO EPISÓDIO DIRETO
                val episodeUrl = "https://assistirseriesonline.icu/episodio/$data"
                println("DEBUG: Tentando URL direta: $episodeUrl")
                
                try {
                    val episodeRes = app.get(episodeUrl, timeout = 30)
                    val episodeHtml = episodeRes.text
                    
                    // Procura o embed ID na página do episódio
                    val embedMatch = Regex("""/embed/(\d+)""").find(episodeHtml)
                    if (embedMatch != null) {
                        val embedId = embedMatch.groupValues[1]
                        println("DEBUG: Embed ID encontrado: $embedId")
                        currentSeriesEmbedId = embedId
                        
                        // Tenta encontrar o player principal
                        val playerPattern = Regex("""(https?://[^"'\s]+/embed/\d+[^"'\s]*)""")
                        val playerMatch = playerPattern.find(episodeHtml)
                        
                        if (playerMatch != null) {
                            val playerUrl = playerMatch.value
                            println("DEBUG: Player URL encontrada: $playerUrl")
                            
                            // Extrai vídeo do player
                            return extractFromPlayerPage(playerUrl, callback)
                        }
                    }
                } catch (e: Exception) {
                    println("DEBUG: Erro ao acessar página do episódio: ${e.message}")
                }
                
                // 2. SE NÃO ENCONTROU, TENTA COM O EMBED ID SALVO
                if (currentSeriesEmbedId != null) {
                    val embedId = currentSeriesEmbedId!!
                    println("DEBUG: Usando embed ID salvo: $embedId")
                    
                    // Constrói URL do player: /embed/EMBED_ID#SERIE_ID_EPISODIO_ID
                    // Aqui precisamos descobrir o seriesId também
                    val playerUrl = "https://assistirseriesonline.icu/embed/$embedId#$data"
                    println("DEBUG: Construindo player URL: $playerUrl")
                    
                    return extractFromPlayerPage(playerUrl, callback)
                }
                
                return false
            }
            
            // PARA FILMES E LINKS DIRETOS (JÁ FUNCIONA)
            println("DEBUG: Tratando como filme/link direto")
            
            // DETERMINA A URL FINAL
            val finalUrl = when {
                data.contains("ultracine.org/") && data.matches(Regex(".*/\\d+$")) -> {
                    val id = data.substringAfterLast("/")
                    "https://assistirseriesonline.icu/episodio/$id"
                }
                else -> data
            }

            // FAZ A REQUISIÇÃO
            val res = app.get(finalUrl, referer = mainUrl, timeout = 30)
            val html = res.text

            // ========== DETECTOR PARA JW PLAYER ==========
            val jwPlayerPattern = Regex("""<video[^>]+class=["'][^"']*jw[^"']*["'][^>]+src=["'](https?://[^"']+)["']""")
            val jwMatches = jwPlayerPattern.findAll(html).toList()

            if (jwMatches.isNotEmpty()) {
                jwMatches.forEach { match ->
                    val videoUrl = match.groupValues[1]
                    if (videoUrl.isNotBlank() && 
                        (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8")) &&
                        !videoUrl.contains("banner") && 
                        !videoUrl.contains("ads")) {

                        val quality = extractQualityFromUrl(videoUrl)
                        val linkName = if (quality != Qualities.Unknown.value) {
                            "${this.name} (${quality}p)"
                        } else {
                            "${this.name} (Série)"
                        }

                        val link = ExtractorLink(
                            source = this.name,
                            name = linkName,
                            url = videoUrl,
                            referer = finalUrl,
                            quality = quality,
                            isM3u8 = videoUrl.contains(".m3u8")
                        )
                        callback.invoke(link)
                        return true
                    }
                }
            }

            // Procura por links MP4 do Google Storage
            val googlePattern = Regex("""https?://storage\.googleapis\.com/[^"'\s<>]+\.mp4[^"'\s<>]*""")
            val googleMatches = googlePattern.findAll(html).toList()

            if (googleMatches.isNotEmpty()) {
                googleMatches.forEach { match ->
                    val videoUrl = match.value
                    if (videoUrl.isNotBlank() && !videoUrl.contains("banner")) {
                        val link = ExtractorLink(
                            source = this.name,
                            name = "${this.name} (Google Storage)",
                            url = videoUrl,
                            referer = finalUrl,
                            quality = extractQualityFromUrl(videoUrl),
                            isM3u8 = false
                        )
                        callback.invoke(link)
                        return true
                    }
                }
            }

            // Procura por QUALQUER link .mp4 no HTML
            val mp4Pattern = Regex("""(https?://[^"'\s<>]+\.mp4[^"'\s<>]*)""")
            val mp4Matches = mp4Pattern.findAll(html).toList()

            if (mp4Matches.isNotEmpty()) {
                mp4Matches.forEach { match ->
                    val videoUrl = match.value
                    if (videoUrl.isNotBlank() && 
                        !videoUrl.contains("banner") && 
                        !videoUrl.contains("ads") &&
                        videoUrl.length > 30) {

                        val link = ExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = videoUrl,
                            referer = finalUrl,
                            quality = extractQualityFromUrl(videoUrl),
                            isM3u8 = false
                        )
                        callback.invoke(link)
                        return true
                    }
                }
            }

            // ========== ESTRATÉGIA PARA FILMES ==========
            val doc = res.document

            // 1. Tenta iframes (EmbedPlay)
            doc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && loadExtractor(src, finalUrl, subtitleCallback, callback)) {
                    return true
                }
            }

            // 2. Tenta botões com data-source
            doc.select("button[data-source]").forEach { button ->
                val source = button.attr("data-source")
                if (source.isNotBlank() && loadExtractor(source, finalUrl, subtitleCallback, callback)) {
                    return true
                }
            }

            // Para séries, tenta extrair do player
            if (finalUrl.contains("assistirseriesonline")) {
                return extractFromPlayerPage(finalUrl, callback)
            }

            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // FUNÇÃO AUXILIAR PARA EXTRAIR DE PÁGINAS DE PLAYER
    private suspend fun extractFromPlayerPage(playerUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            println("DEBUG: Acessando player page: $playerUrl")
            
            val res = app.get(playerUrl, timeout = 30)
            val html = res.text
            
            // ESTRATÉGIA 1: Procura por iframe com vídeo
            val iframePattern = Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""")
            val iframeMatches = iframePattern.findAll(html).toList()
            
            for (match in iframeMatches) {
                val iframeSrc = match.groupValues[1]
                println("DEBUG: Iframe encontrado: $iframeSrc")
                
                // Se for um iframe de vídeo, tenta extrair dele
                if (iframeSrc.contains("embed") || iframeSrc.contains("player")) {
                    val videoUrl = extractVideoFromIframe(iframeSrc)
                    if (videoUrl != null) {
                        println("DEBUG: Vídeo extraído do iframe: $videoUrl")
                        
                        val link = ExtractorLink(
                            source = this.name,
                            name = "${this.name} (Player)",
                            url = videoUrl,
                            referer = playerUrl,
                            quality = extractQualityFromUrl(videoUrl),
                            isM3u8 = videoUrl.contains(".m3u8")
                        )
                        callback.invoke(link)
                        return true
                    }
                }
            }
            
            // ESTRATÉGIA 2: Procura vídeo direto (tags video)
            val videoPattern = Regex("""<video[^>]+src=["'](https?://[^"']+)["']""")
            val videoMatches = videoPattern.findAll(html).toList()
            
            for (match in videoMatches) {
                val videoUrl = match.groupValues[1]
                println("DEBUG: Vídeo direto encontrado: $videoUrl")
                
                if (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8")) {
                    val link = ExtractorLink(
                        source = this.name,
                        name = "${this.name} (Direct)",
                        url = videoUrl,
                        referer = playerUrl,
                        quality = extractQualityFromUrl(videoUrl),
                        isM3u8 = videoUrl.contains(".m3u8")
                    )
                    callback.invoke(link)
                    return true
                }
            }
            
            // ESTRATÉGIA 3: Procura em scripts
            val scriptPattern = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            val scriptMatches = scriptPattern.findAll(html).toList()
            
            for (match in scriptMatches) {
                val scriptContent = match.groupValues[1]
                
                // Padrões comuns de URLs de vídeo em scripts
                val patterns = listOf(
                    Regex("""file\s*:\s*["'](https?://[^"']+)["']"""),
                    Regex("""src\s*:\s*["'](https?://[^"']+)["']"""),
                    Regex("""url\s*:\s*["'](https?://[^"']+)["']"""),
                    Regex("""(https?://[^"']+\.m3u8[^"']*)"""),
                    Regex("""(https?://[^"']+\.mp4[^"']*)""")
                )
                
                for (pattern in patterns) {
                    val videoMatches = pattern.findAll(scriptContent).toList()
                    for (videoMatch in videoMatches) {
                        val videoUrl = videoMatch.groupValues[1]
                        if (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8")) {
                            println("DEBUG: URL em script: $videoUrl")
                            
                            val link = ExtractorLink(
                                source = this.name,
                                name = "${this.name} (Script)",
                                url = videoUrl,
                                referer = playerUrl,
                                quality = extractQualityFromUrl(videoUrl),
                       