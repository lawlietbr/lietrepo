package com.EmbedTV

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

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

    private val mainSite = "https://embedtv.lat"
    private val baseUrl = "https://ww4.embedtv.lat"
  
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainSite).document
        val allCategories = mutableListOf<HomePageList>()

        val jogosSection = doc.selectFirst(".session.futebol")
        if (jogosSection != null) {
            val jogosCards = jogosSection.select(".card")
            val jogosList = mutableListOf<SearchResponse>()

            for (card in jogosCards) {
                val channelId = card.attr("data-channel")
                if (channelId.isBlank() || channelId in blockedChannels) continue

                val gameName = card.selectFirst("h3")?.text()?.trim() ?: continue
                val imgElement = card.selectFirst("img")
                val imageUrl = imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") } ?: continue
                val time = card.selectFirst("span")?.text()?.trim() ?: ""
            
                val displayName = if (time.isNotBlank()) "$gameName ($time)" else gameName
                val jogoUrl = "$baseUrl/$channelId?source=jogos"

                jogosList.add(
                    newLiveSearchResponse(
                        displayName,
                        jogoUrl,
                        TvType.Live
                    ) {
                        this.posterUrl = fixImageUrl(imageUrl)
                    }
                )
            }

            if (jogosList.isNotEmpty()) {
                allCategories.add(HomePageList("Jogos de Hoje", jogosList, isHorizontalImages = true))
            }
        }

        val categories = doc.select(".categorie")
        for (category in categories) {
            val titleElement = category.selectFirst(".title") ?: continue
            val categoryTitle = titleElement.text().trim()
            val channelList = mutableListOf<SearchResponse>()
            val cards = category.select(".card")

            for (card in cards) {
                val channelId = card.attr("data-channel")
                if (channelId.isBlank() || channelId in blockedChannels) continue

                val channelName = card.selectFirst("h3")?.text()?.trim() ?: continue
                val imgElement = card.selectFirst("img")
                val imageUrl = imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") } ?: continue
                val canalUrl = "$baseUrl/$channelId"

                channelList.add(
                    newLiveSearchResponse(
                        channelName,
                        canalUrl,
                        TvType.Live
                    ) {
                        this.posterUrl = fixImageUrl(imageUrl)
                    }
                )
            }

            if (channelList.isNotEmpty()) {
                allCategories.add(HomePageList(categoryTitle, channelList, isHorizontalImages = true))
            }
        }

        if (allCategories.isEmpty()) {
            throw ErrorLoadingException("Nenhum canal encontrado.")
        }

        return newHomePageResponse(allCategories, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse {
        val isFromJogos = url.contains("source=jogos")
        val cleanUrl = url.replace("?source=jogos", "")
        val channelId = cleanUrl.substringAfterLast("/")

        if (channelId in blockedChannels) {
            throw ErrorLoadingException("Canal não disponível")
        }

        val mainPage = app.get(mainSite).document
        val channelCard = mainPage.selectFirst(".card[data-channel=\"$channelId\"]")

        if (channelCard == null) {
            return newMovieLoadResponse(
                "Canal $channelId",
                cleanUrl,
                TvType.Live,
                cleanUrl
            ) {
                this.posterUrl = "https://embedtv.lat/assets/icon.png"
            }
        }

        val realChannelName = channelCard.selectFirst("h3")?.text()?.trim() ?: "Canal $channelId"

        val channelImg = channelCard.selectFirst("img")
        val defaultImage = channelImg?.attr("data-src")?.ifEmpty { channelImg.attr("src") } ?: "https://embedtv.lat/assets/icon.png"

        val displayImage = if (isFromJogos) {
            val gameCard = mainPage.selectFirst(".session.futebol .card[data-channel=\"$channelId\"]")
            val gameImg = gameCard?.selectFirst("img")
            gameImg?.attr("data-src")?.ifEmpty { gameImg.attr("src") } ?: defaultImage
        } else {
            defaultImage
        }

        val displayTitle = if (isFromJogos) {
            val gameCard = mainPage.selectFirst(".session.futebol .card[data-channel=\"$channelId\"]")
            val gameName = gameCard?.selectFirst("h3")?.text()?.trim() ?: realChannelName
            val time = gameCard?.selectFirst("span")?.text()?.trim() ?: ""
            if (time.isNotBlank()) "$realChannelName - $gameName ($time)" else realChannelName
        } else {
            realChannelName
        }

        val plot = if (isFromJogos) {
            val gameCard = mainPage.selectFirst(".session.futebol .card[data-channel=\"$channelId\"]")
            val gameName = gameCard?.selectFirst("h3")?.text()?.trim() ?: realChannelName
            val time = gameCard?.selectFirst("span")?.text()?.trim() ?: ""
            "$realChannelName - $gameName ($time)"
        } else {
            "Assista $realChannelName ao vivo no EmbedTv"
        }

        return newMovieLoadResponse(
            displayTitle,
            cleanUrl,
            TvType.Live,
            cleanUrl
        ) {
            this.posterUrl = fixImageUrl(displayImage)
            this.plot = plot
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val doc = app.get(mainSite).document
        val allCards = doc.select(".card")
        val results = mutableListOf<SearchResponse>()

        for (card in allCards) {
            val channelId = card.attr("data-channel")
            if (channelId.isBlank() || channelId in blockedChannels) continue

            val channelName = card.selectFirst("h3")?.text()?.trim() ?: continue
            if (!channelName.contains(query, ignoreCase = true)) continue

            val imgElement = card.selectFirst("img")
            val imageUrl = imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") } ?: continue
            val timeElement = card.selectFirst("span")
            val hasHorario = timeElement != null

            val finalUrl = if (hasHorario) {
                "$baseUrl/$channelId?source=jogos"
            } else {
                "$baseUrl/$channelId"
            }

            val time = timeElement?.text()?.trim()
            val displayName = if (!time.isNullOrBlank()) "$channelName ($time)" else channelName

            results.add(
                newLiveSearchResponse(
                    displayName,
                    finalUrl,
                    TvType.Live
                ) {
                    this.posterUrl = fixImageUrl(imageUrl)
                }
            )
        }

        return results
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanUrl = data.split("?")[0]
        
        return try {
            val headers = mapOf(
                "Referer" to baseUrl,
                "Origin" to baseUrl,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

            val html = app.get(cleanUrl, headers = headers).document
            var streamUrl: String? = null
            
            val scriptContent = html.html()
            
            // Regex nova para buscar tudo que estiver dentro de startPlayer("URL")
            val startPlayerPattern = Regex("""startPlayer\(\s*["'](https?://[^"']+)["']\s*\)""")
            val matches = startPlayerPattern.findAll(scriptContent)

            // Varre as correspondências e pega a URL real, ignorando o fallback
            for (match in matches) {
                val foundUrl = match.groupValues[1]
                if (!foundUrl.contains("live-chunks.mediacdn.net")) {
                    streamUrl = foundUrl
                }
            }
            
            // Fallback caso a técnica deles mude e usem tags html
            if (streamUrl == null) {
                val dataStreamElement = html.select("[data-stream]").firstOrNull()
                streamUrl = dataStreamElement?.attr("data-stream")
            }
            
            if (streamUrl != null) {
                streamUrl = streamUrl!!.replace("xn--d1ma04s8hp12.cloudfront.lat", "d1ma04s8hp12.cloudfront.lat")
               
                val streamHeaders = mapOf(
                    "Accept" to "*/*",
                    "Accept-Language" to "pt-BR,pt;q=0.9",
                    "Connection" to "keep-alive",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "cross-site",
                    "Referer" to baseUrl,
                    "Origin" to baseUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
                
                // Se for arquivo .txt (usado pelo P2P media loader deles) ele extrai o m3u8
                if (streamUrl!!.endsWith(".txt")) {
                    M3u8Helper.generateM3u8(
                        name,
                        streamUrl!!,
                        baseUrl,
                        headers = streamHeaders
                    ).forEach(callback)
                } else {
                    // Se for direto .m3u8
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = streamUrl!!,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = baseUrl
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