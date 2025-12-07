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

    // FUNÇÃO AUXILIAR PARA CRIAR LINKS CORRETAMENTE (SUSPEND)
    private suspend fun createSeriesExtractorLink(videoUrl: String, episodeUrl: String, name: String = "Série"): ExtractorLink {
        val quality = extractQualityFromUrl(videoUrl)
        val isM3u8 = videoUrl.contains(".m3u8")
        
        return newExtractorLink(
            source = this.name,
            name = "${this.name} ($name)",
            url = videoUrl
        ) {
            referer = episodeUrl
            this.quality = quality
            this.isM3u8 = isM3u8
        }
    }

    // VERSÃO DO loadLinks QUE USA newExtractorLink CORRETAMENTE
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
            
            // PARA SÉRIES: O data é o episodeId (números)
            if (data.matches(Regex("^\\d+$"))) {
                println("DEBUG: ID de episódio detectado: $data")
                
                // STRATÉGIA PRINCIPAL: Tenta acessar a página do episódio
                val episodeUrl = "https://assistirseriesonline.icu/episodio/$data"
                println("DEBUG: Tentando URL: $episodeUrl")
                
                val res = app.get(episodeUrl, timeout = 30)
                val html = res.text
                
                // Procura por iframe principal
                val iframePattern = Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""")
                val iframeMatch = iframePattern.find(html)
                
                if (iframeMatch != null) {
                    val iframeSrc = iframeMatch.groupValues[1]
                    println("DEBUG: Iframe encontrado: $iframeSrc")
                    
                    // Tenta extrair do iframe
                    val videoUrl = extractVideoFromIframe(iframeSrc)
                    if (videoUrl != null) {
                        println("DEBUG: Vídeo extraído: $videoUrl")
                        
                        // USA newExtractorLink CORRETAMENTE
                        callback.invoke(createSeriesExtractorLink(videoUrl, episodeUrl, "Série"))
                        return true
                    }
                }
                
                // ESTRATÉGIA ALTERNATIVA: Procura URLs diretas de vídeo
                val videoUrls = findVideoUrlsInHtml(html)
                if (videoUrls.isNotEmpty()) {
                    println("DEBUG: URLs de vídeo encontradas: ${videoUrls.size}")
                    
                    videoUrls.forEach { videoUrl ->
                        // USA newExtractorLink CORRETAMENTE
                        callback.invoke(createSeriesExtractorLink(videoUrl, episodeUrl, "Direct"))
                    }
                    return true
                }
                
                // ÚLTIMA ESTRATÉGIA: Tenta carregar via extractor se tiver iframe
                val doc = res.document
                doc.select("iframe[src]").forEach { iframe ->
                    val src = iframe.attr("src")
                    if (src.isNotBlank() && loadExtractor(src, episodeUrl, subtitleCallback, callback)) {
                        return true
                    }
                }
                
                return false
            }
            
            // PARA FILMES (código original que funciona)
            val finalUrl = when {
                data.contains("ultracine.org/") && data.matches(Regex(".*/\\d+$")) -> {
                    val id = data.substringAfterLast("/")
                    "https://assistirseriesonline.icu/episodio/$id"
                }
                else -> data
            }

            val res = app.get(finalUrl, referer = mainUrl, timeout = 30)
            val doc = res.document
            
            // Tenta iframes
            doc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && loadExtractor(src, finalUrl, subtitleCallback, callback)) {
                    return true
                }
            }
            
            // Tenta botões com data-source
            doc.select("button[data-source]").forEach { button ->
                val source = button.attr("data-source")
                if (source.isNotBlank() && loadExtractor(source, finalUrl, subtitleCallback, callback)) {
                    return true
                }
            }
            
            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // FUNÇÕES AUXILIARES
    
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
    
    private suspend fun extractVideoFromIframe(iframeUrl: String): String? {
        return try {
            println("DEBUG: Extraindo do iframe: $iframeUrl")
            
            val res = app.get(iframeUrl, timeout = 30)
            val html = res.text
            
            // Procura vídeo direto
            val videoPattern = Regex("""<video[^>]+src=["'](https?://[^"']+)["']""")
            val videoMatch = videoPattern.find(html)
            
            if (videoMatch != null) {
                return videoMatch.groupValues[1]
            }
            
            // Procura em scripts
            val scriptPattern = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            val scriptMatches = scriptPattern.findAll(html).toList()
            
            for (match in scriptMatches) {
                val scriptContent = match.groupValues[1]
                
                val patterns = listOf(
                    Regex("""(https?://[^"']+\.m3u8[^"']*)"""),
                    Regex("""(https?://[^"']+\.mp4[^"']*)"""),
                    Regex("""file\s*:\s*["'](https?://[^"']+)["']""")
                )
                
                for (pattern in patterns) {
                    val videoMatch = pattern.find(scriptContent)
                    if (videoMatch != null) {
                        val videoUrl = videoMatch.groupValues[1]
                        if (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8")) {
                            return videoUrl
                        }
                    }
                }
            }
            
            null
        } catch (e: Exception) {
            println("DEBUG: Erro ao extrair iframe: ${e.message}")
            null
        }
    }
    
    private fun findVideoUrlsInHtml(html: String): List<String> {
        val videoUrls = mutableListOf<String>()
        
        // Procura por URLs .m3u8
        val m3u8Pattern = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""")
        val m3u8Matches = m3u8Pattern.findAll(html).toList()
        m3u8Matches.forEach { match ->
            val url = match.value
            if (url.isNotBlank() && !url.contains("banner")) {
                videoUrls.add(url)
            }
        }
        
        // Procura por URLs .mp4
        val mp4Pattern = Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""")
        val mp4Matches = mp4Pattern.findAll(html).toList()
        mp4Matches.forEach { match ->
            val url = match.value
            if (url.isNotBlank() && !url.contains("banner") && url.length > 30) {
                videoUrls.add(url)
            }
        }
        
        // Procura em tags video
        val videoTagPattern = Regex("""<video[^>]+src=["'](https?://[^"']+)["']""")
        val videoTagMatches = videoTagPattern.findAll(html).toList()
        videoTagMatches.forEach { match ->
            val url = match.groupValues[1]
            if (url.isNotBlank() && (url.contains(".mp4") || url.contains(".m3u8"))) {
                videoUrls.add(url)
            }
        }
        
        // Remove duplicados
        return videoUrls.distinct()
    }
}