package com.UltraCine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink // Adicionado para clareza
import com.lagradost.cloudstream3.utils.ExtractorLinkType // Adicionado para clareza
import com.lagradost.cloudstream3.utils.Qualities // Adicionado para clareza
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.WebViewResolver 

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
            // Lógica de Extração de Episódios
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

    // ====================================================================
    // FUNÇÃO loadLinks UNIFICADA E APERFEIÇOADA
    // ====================================================================
    
    // ... (parte inicial do loadLinks) ...

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        return try {
            // 1. DETERMINA A URL FINAL DO PLAYER (OU PÁGINA DO EPISÓDIO)
            val finalUrl = when {
                // ID numérico (EPISÓDIO DE SÉRIE)
                data.matches(Regex("^\\d+$")) -> {
                    "https://assistirseriesonline.icu/episodio/$data"
                }
                // URL do ultracine com ID (EPISÓDIO DE SÉRIE)
                // CORRIGIDO: Usando 'ignoreCase = true' para forçar a comparação de String e evitar conflito de Regex.
                data.contains("ultracine.org/", ignoreCase = true) && data.matches(Regex(".*/\\d+$")) -> { 
                    val id = data.substringAfterLast("/")
                    "https://assistirseriesonline.icu/episodio/$id"
                }
                // URL normal (GERALMENTE FILMES)
                else -> data
            }

            val res = app.get(finalUrl, referer = mainUrl, timeout = 30)
            val doc = res.document
            val html = res.text
            
            var success = false

            // ========== 2. DETECÇÃO DE IFRAMES/BOTÕES (Players conhecidos) ==========

            // A. Tenta botões com data-source (EmbedPlay, Upns, etc.)
            doc.select("button[data-source]").forEach { button ->
                val source = button.attr("data-source")
                if (source.isNotBlank()) {
                    if (loadExtractor(source, finalUrl, subtitleCallback, callback)) {
                        success = true
                    }
                }
            }
            if (success) return true

            // B. Tenta iframes específicos do player EmbedPlay/Upns
            doc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && (
                    src.contains("embedplay.upns.pro", ignoreCase = true) || 
                    src.contains("embedplay.upn.one", ignoreCase = true) ||
                    src.contains("embedplay.upns.ink", ignoreCase = true)) 
                ) {
                    if (loadExtractor(src, finalUrl, subtitleCallback, callback)) {
                        success = true
                    }
                }
            }
            if (success) return true

            // C. Tenta qualquer iframe que pareça ser um player de vídeo
            doc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && (src.contains("player", ignoreCase = true) || src.contains("video", ignoreCase = true))) {
                    if (loadExtractor(src, finalUrl, subtitleCallback, callback)) {
                        success = true
                    }
                }
            }
            if (success) return true

             // Linha ~252:
// Linha ~252:
// ========== 3. ESTRATÉGIA DE FALLBACK (WebViewResolver) ==========
if (
    html.contains("apiblogger.click", ignoreCase = true) ||
    finalUrl.contains("episodio/", ignoreCase = true)
) {
    // Método 1: Usando contains() diretamente (mais seguro)
    val shouldUseWebView = html.contains("apiblogger.click", ignoreCase = true) ||
                          finalUrl.contains("episodio/", ignoreCase = true)
    
    if (shouldUseWebView) {
        val resolver = WebViewResolver(html)

        val (mainRequest, subRequests) = resolver.resolveUsingWebView(finalUrl)

        mainRequest?.url?.toString()?.takeIf {
            it.contains(".m3u8", ignoreCase = true) || it.contains(".mp4", ignoreCase = true)
        }?.let { loadExtractor(it, finalUrl, subtitleCallback, callback) }

        subRequests.forEach { req ->
            req.url?.toString()?.takeIf {
                it.contains(".m3u8", ignoreCase = true) || it.contains(".mp4", ignoreCase = true)
            }?.let { loadExtractor(it, finalUrl, subtitleCallback, callback) }
        }

        return true
    }
}
            return false

        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

// ... (Resto da classe UltraCine) ...


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
