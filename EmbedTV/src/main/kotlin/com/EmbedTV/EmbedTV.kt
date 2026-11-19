package com.EmbedTV

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import org.jsoup.nodes.Element

@CloudstreamPlugin
class EmbedTvProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(EmbedTv())
    }
}

class EmbedTv : MainAPI() {
    override var name = "EmbedTV"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)

    private val mainSite = "https://embedtv.cc"
    private val baseUrl = "https://ww0.embedtv.cc"

    private val blockedChannels = listOf("sexyhot", "playboy")

    private fun fixImageUrl(url: String): String {
        return when {
            url.contains("cloudfront.net") -> {
                val cleanUrl = url.split("?")[0]
                "$cleanUrl?width=300&height=170&fit=crop&format=webp"
            }
            url.startsWith("//") -> "https:$url"
            url.startsWith("http") -> url
            url.startsWith("/") -> "$mainSite$url"
            else -> url
        }
    }

    private fun normalizePlayerUrl(raw: String, channelId: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.isNotBlank() -> "https://$trimmed"
            else -> "$baseUrl/$channelId"
        }
    }

    private fun extractArticleTitle(article: Element, isEvento: Boolean): String? {
        val busca = article.attr("data-busca").trim()
        val imgAlt = article.selectFirst("img")?.attr("alt")?.trim().orEmpty()
        val baseTitle = busca.ifBlank { imgAlt }
        if (baseTitle.isBlank()) return null

        if (!isEvento) return baseTitle

        val timeText = article.select("span")
            .map { it.text().trim() }
            .firstOrNull { Regex("""\d{2}:\d{2}""").containsMatchIn(it) }
            .orEmpty()

        return if (timeText.isNotBlank()) "$baseTitle ($timeText)" else baseTitle
    }

    private fun extractArticleImage(article: Element): String {
        val img = article.selectFirst("img")
        val raw = img?.attr("data-src")?.ifBlank { img.attr("src") }
            ?: img?.attr("src")
            ?: "$mainSite/assets/icon.png"
        return fixImageUrl(raw)
    }

    private fun extractArticleUrl(article: Element, channelId: String, isEvento: Boolean): String {
        val inputVal = article.selectFirst("input[readonly]")?.attr("value").orEmpty()
        val playerUrl = normalizePlayerUrl(inputVal, channelId)
        return if (isEvento) "$playerUrl?source=eventos" else playerUrl
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainSite).document
        val allCategories = mutableListOf<HomePageList>()

        val sections = doc.select("section")
        for (section in sections) {
            val sectionTitle = section.selectFirst("h2")?.text()?.trim() ?: continue
            val articles = section.select("article[data-id]")
            if (articles.isEmpty()) continue

            val isEvento = sectionTitle.equals("Eventos", ignoreCase = true)
            val items = mutableListOf<SearchResponse>()

            for (article in articles) {
                val channelId = article.attr("data-id").trim()
                if (channelId.isBlank() || channelId in blockedChannels) continue

                val title = extractArticleTitle(article, isEvento) ?: continue
                val imageUrl = extractArticleImage(article)
                val itemUrl = extractArticleUrl(article, channelId, isEvento)

                items.add(
                    newLiveSearchResponse(
                        title,
                        itemUrl,
                        TvType.Live
                    ) {
                        this.posterUrl = imageUrl
                    }
                )
            }

            if (items.isNotEmpty()) {
                val displayCategory = if (isEvento) "Jogos e Eventos" else sectionTitle
                allCategories.add(HomePageList(displayCategory, items, isHorizontalImages = true))
            }
        }

        if (allCategories.isEmpty()) {
            throw ErrorLoadingException("Nenhum canal encontrado.")
        }

        return newHomePageResponse(allCategories, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse {
        val isFromEventos = url.contains("source=eventos") || url.contains("source=jogos")
        val cleanUrl = url.substringBefore("?")
        val channelId = cleanUrl.substringAfterLast("/")

        if (channelId in blockedChannels) {
            throw ErrorLoadingException("Canal não disponível")
        }

        val doc = app.get(mainSite).document
        val matchingArticles = doc.select("article[data-id=\"$channelId\"]")

        if (matchingArticles.isEmpty()) {
            return newMovieLoadResponse(
                "Canal $channelId",
                cleanUrl,
                TvType.Live,
                cleanUrl
            ) {
                this.posterUrl = "$mainSite/assets/icon.png"
            }
        }

        val channelArticle = matchingArticles.last()
        val eventArticle = matchingArticles.first()
        val chosen = if (isFromEventos) eventArticle else channelArticle

        val title = extractArticleTitle(chosen, isFromEventos) ?: "Canal $channelId"
        val image = extractArticleImage(chosen)
        val plot = if (isFromEventos) {
            title
        } else {
            "Assista $title ao vivo no EmbedTV"
        }

        return newMovieLoadResponse(
            title,
            cleanUrl,
            TvType.Live,
            cleanUrl
        ) {
            this.posterUrl = image
            this.plot = plot
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val doc = app.get(mainSite).document
        val results = mutableListOf<SearchResponse>()

        for (section in doc.select("section")) {
            val sectionTitle = section.selectFirst("h2")?.text()?.trim().orEmpty()
            val isEvento = sectionTitle.equals("Eventos", ignoreCase = true)

            for (article in section.select("article[data-id]")) {
                val channelId = article.attr("data-id").trim()
                if (channelId.isBlank() || channelId in blockedChannels) continue

                val busca = article.attr("data-busca").trim()
                val title = extractArticleTitle(article, isEvento) ?: continue
                if (!title.contains(query, ignoreCase = true) && !busca.contains(query, ignoreCase = true)) continue

                results.add(
                    newLiveSearchResponse(
                        title,
                        extractArticleUrl(article, channelId, isEvento),
                        TvType.Live
                    ) {
                        this.posterUrl = extractArticleImage(article)
                    }
                )
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanUrl = normalizePlayerUrl(data.substringBefore("?"), data.substringAfterLast("/"))

        return try {
            val headers = mapOf(
                "Referer" to "$mainSite/",
                "Origin" to mainSite,
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "same-site",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            )

            val response = app.get(cleanUrl, headers = headers)
            val scriptContent = response.text
            val html = response.document
            var streamUrl: String? = null

            // Novo padrão da ww0.embedtv.cc: const src = "https://..."
            val constSrcPattern = Regex("""const\s+src\s*=\s*["'](https?://[^"']+)["']""")
            constSrcPattern.find(scriptContent)?.let {
                streamUrl = it.groupValues[1].replace("\\/", "/")
            }

            // Fallback: startPlayer("URL")
            if (streamUrl == null) {
                val startPlayerPattern = Regex("""startPlayer\(\s*["'](https?://[^"']+)["']\s*\)""")
                for (match in startPlayerPattern.findAll(scriptContent)) {
                    val foundUrl = match.groupValues[1].replace("\\/", "/")
                    if (!foundUrl.contains("live-chunks.mediacdn.net")) {
                        streamUrl = foundUrl
                    }
                }
            }

            // Fallback: atributo data-stream
            if (streamUrl == null) {
                streamUrl = html.selectFirst("[data-stream]")?.attr("data-stream")
            }

            if (!streamUrl.isNullOrBlank()) {
                streamUrl = streamUrl!!.replace("xn--d1ma04s8hp12.cloudfront.lat", "d1ma04s8hp12.cloudfront.lat")

                val streamHeaders = mapOf(
                    "Accept" to "*/*",
                    "Accept-Language" to "pt-BR,pt;q=0.9",
                    "Connection" to "keep-alive",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "cross-site",
                    "Referer" to "$baseUrl/",
                    "Origin" to baseUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )

                if (streamUrl!!.endsWith(".txt")) {
                    M3u8Helper.generateM3u8(
                        name,
                        streamUrl!!,
                        "$baseUrl/",
                        headers = streamHeaders
                    ).forEach(callback)
                } else {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = streamUrl!!,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$baseUrl/"
                            this.headers = streamHeaders
                        }
                    )
                }
                return true
            }

            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}