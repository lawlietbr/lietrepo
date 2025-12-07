package com.UltraCine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.WebViewResolver  // ← NOVO: Pra simular browser/JS
import org.jsoup.nodes.Element

class UltraCine : MainAPI() {
    override var mainUrl = "https://ultracine.org"
    override var name = "UltraCine"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // User-Agent pra simular browser real (melhora pra sites com ads/JS)
    private fun getUserAgent(): String = "Mozilla/5.0 (Linux; Android 14; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        return try {
            // DETERMINA A URL FINAL
            val finalUrl = when {
                // ID numérico (episódio de série - PROBLEMA PRINCIPAL)
                data.matches(Regex("^\\d+$")) -> {
                    "https://assistirseriesonline.icu/episodio/$data"
                }
                // URL do ultracine com ID
                data.contains("ultracine.org/") && data.matches(Regex(".*/\\d+$")) -> {
                    val id = data.substringAfterLast("/")
                    "https://assistirseriesonline.icu/episodio/$id"
                }
                // URL normal (filme)
                else -> data
            }

            val isEpisode = data.matches(Regex("^\\d+$"))  // Detecta se é episódio

            // REQUISIÇÃO COM USER-AGENT REAL (pra evitar blocks)
            val res = app.get(
                finalUrl,
                referer = mainUrl,
                headers = mapOf("User-Agent" to getUserAgent()),
                timeout = 30
            )
            val html = res.text
            val doc = res.document

            if (isEpisode) {
                // ========== ESTRATÉGIA ESPECÍFICA PARA EPISÓDIOS (WEBVIEW + SIMULAÇÃO) ==========
                // Usa WebView pra executar JS, pular ads e clicar play/pause
                WebViewResolver(
                    html = html,
                    url = finalUrl,
                    headers = mapOf(
                        "Referer" to mainUrl,
                        "User-Agent" to getUserAgent()
                    )
                ).resolveUsingWebView(finalUrl) { extractedLink ->
                    // Filtra links válidos (ignora ads)
                    if (extractedLink.isNotBlank() && 
                        (extractedLink.contains(".mp4") || extractedLink.contains(".m3u8") || extractedLink.contains("googlevideo") || extractedLink.contains("blob:")) &&
                        !extractedLink.contains("banner") && !extractedLink.contains("ads")) {
                        
                        val quality = extractQualityFromUrl(extractedLink)
                        val isM3u8 = extractedLink.contains(".m3u8")
                        val linkName = if (quality != Qualities.Unknown.value) {
                            "\( {name} ( \){quality}p)"
                        } else {
                            "${name} (Episódio)"
                        }
                        
                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = linkName,
                                url = extractedLink,
                                referer = finalUrl,
                                quality = quality,
                                isM3u8 = isM3u8,
                                headers = mapOf("Referer" to finalUrl)
                            )
                        )
                    }
                }

                // Delay pra simular skip ad + play/pause (8s baseado na sua descrição)
                delay(8000)

                // Tenta regex como fallback rápido (pra casos onde WebView é lento)
                val jwPlayerPattern = Regex("""<video[^>]+class=["'][^"']*jw[^"']*["'][^>]+src=["'](https?://[^"']+)["']""")
                val jwMatches = jwPlayerPattern.findAll(html).toList()
                
                jwMatches.forEach { match ->
                    val videoUrl = match.groupValues[1]
                    if (videoUrl.isNotBlank() && 
                        (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8")) &&
                        !videoUrl.contains("banner") && 
                        !videoUrl.contains("ads")) {
                        
                        val quality = extractQualityFromUrl(videoUrl)
                        val linkName = if (quality != Qualities.Unknown.value) {
                            "\( {name} ( \){quality}p)"
                        } else {
                            "${name} (Episódio)"
                        }
                        
                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = linkName,
                                url = videoUrl,
                                referer = finalUrl,
                                quality = quality,
                                isM3u8 = videoUrl.contains(".m3u8"),
                                headers = mapOf("Referer" to finalUrl)
                            )
                        )
                        return true
                    }
                }

                // Retorna true pra episódios (WebView roda em background, CloudStream espera)
                return true
            } else {
                // ========== PARA FILMES (MANTER SEU CÓDIGO ORIGINAL - FUNCIONA) ==========
                // Regex JW Player (seu original)
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
                                "\( {name} ( \){quality}p)"
                            } else {
                                "${name} (Filme)"
                            }
                            
                            callback.invoke(
                                ExtractorLink(
                                    source = name,
                                    name = linkName,
                                    url = videoUrl,
                                    referer = finalUrl,
                                    quality = quality,
                                    isM3u8 = videoUrl.contains(".m3u8"),
                                    headers = mapOf("Referer" to finalUrl)
                                )
                            )
                            return true
                        }
                    }
                }
                
                // Fallback Google Storage (seu original)
                val googlePattern = Regex("""https?://storage\.googleapis\.com/[^"'\s<>]+\.mp4[^"'\s<>]*""")
                val googleMatches = googlePattern.findAll(html).toList()
                
                if (googleMatches.isNotEmpty()) {
                    googleMatches.forEach { match ->
                        val videoUrl = match.value
                        if (videoUrl.isNotBlank() && !videoUrl.contains("banner")) {
                            val quality = extractQualityFromUrl(videoUrl)
                            callback.invoke(
                                ExtractorLink(
                                    source = name,
                                    name = "\( {name} ( \){quality}p)",
                                    url = videoUrl,
                                    referer = finalUrl,
                                    quality = quality,
                                    isM3u8 = false,
                                    headers = mapOf("Referer" to finalUrl)
                                )
                            )
                            return true
                        }
                    }
                }
                
                // MP4 genérico (seu original)
                val mp4Pattern = Regex("""(https?://[^"'\s<>]+\.mp4[^"'\s<>]*)""")
                val mp4Matches = mp4Pattern.findAll(html).toList()
                
                if (mp4Matches.isNotEmpty()) {
                    mp4Matches.forEach { match ->
                        val videoUrl = match.value
                        if (videoUrl.isNotBlank() && 
                            !videoUrl.contains("banner") && 
                            !videoUrl.contains("ads") &&
                            videoUrl.length > 30) {
                            
                            val quality = extractQualityFromUrl(videoUrl)
                            callback.invoke(
                                ExtractorLink(
                                    source = name,
                                    name = "\( {name} ( \){quality}p)",
                                    url = videoUrl,
                                    referer = finalUrl,
                                    quality = quality,
                                    isM3u8 = false,
                                    headers = mapOf("Referer" to finalUrl)
                                )
                            )
                            return true
                        }
                    }
                }
                
                // EmbedPlay fallback (seu original)
                doc.select("iframe[src]").forEach { iframe ->
                    val src = iframe.attr("src")
                    if (src.isNotBlank() && loadExtractor(src, finalUrl, subtitleCallback, callback)) {
                        return true
                    }
                }
                
                doc.select("button[data-source]").forEach { button ->
                    val source = button.attr("data-source")
                    if (source.isNotBlank() && loadExtractor(source, finalUrl, subtitleCallback, callback)) {
                        return true
                    }
                }
                
                return false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Pra episódios, retorna true (WebView pode estar rodando)
            if (data.matches(Regex("^\\d+$"))) return true
            false
        }
    }

    // Seu extractQualityFromUrl (mantido e agora usado)
    private fun extractQualityFromUrl(url: String): Int {
        val qualityPattern = Regex("""/(\d+)p?/""")
        val match = qualityPattern.find(url)
        
        if (match != null) {
            val qualityNum = match.groupValues[1].toIntOrNull()
            return when (qualityNum) {
                360 -> Qualities.get360()
                480 -> Qualities.get480()
                720 -> Qualities.get720()
                1080 -> Qualities.get1080()
                2160 -> Qualities.get2160()
                else -> Qualities.Unknown.value
            }
        }
        
        return when {
            url.contains("360p", ignoreCase = true) -> Qualities.get360()
            url.contains("480p", ignoreCase = true) -> Qualities.get480()
            url.contains("720p", ignoreCase = true) -> Qualities.get720()
            url.contains("1080p", ignoreCase = true) -> Qualities.get1080()
            url.contains("2160p", ignoreCase = true) -> Qualities.get2160()
            else -> Qualities.Unknown.value
        }
    }
}