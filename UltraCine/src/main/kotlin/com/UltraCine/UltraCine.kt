package com.UltraCine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.toScore // Necessário para converter Int para Score?
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element
import kotlin.math.roundToInt 

class UltraCine : MainAPI() {
    override var name = "UltraCine"
    override var mainUrl = "https://ultracine.org"
    override var lang = "pt-br"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/category/lancamentos/" to "Lançamentos",
        "$mainUrl/category/acao/" to "Ação",
        "$mainUrl/category/animacao/" to "Animação",
        "$mainUrl/category/comedia/" to "Comédia",
        "$mainUrl/category/drama/" to "Drama",
        "$mainUrl/category/terror/" to "Terror",
        "$mainUrl/category/ficcao-cientifica/" to "Ficção Científica",
        "$mainUrl/category/thriller/" to "Thriller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url).document
        val items = doc.select("ul.post-lst li").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a.lnk-blk") ?: return null
        val title = a.selectFirst("h2")?.text() ?: a.attr("title")
        val href = fixUrl(a.attr("href"))
        val poster = selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("data-src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster?.replace("/w500/", "/original/")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("ul.post-lst li").mapNotNull { it.toSearchResult() }
    }

    // 🎯 FUNÇÃO 'LOAD' REINTRODUZIDA E CORRIGIDA PARA ENCONTRAR O LINK DO PLAYER
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val title = document.selectFirst("aside.fg1 header.entry-header h1.entry-title")?.text() 
            ?: document.selectFirst("header.entry-header h1.entry-title")?.text() ?: return null

        val poster = document.selectFirst("div.bghd img.TPostBg")?.let { img ->
            img.attr("src").takeIf { it.isNotBlank() } ?: img.attr("data-src")
        }
        val plot = document.selectFirst("aside.fg1 div.description p")?.text()
        val year = document.selectFirst("aside.fg1 header.entry-header div.entry-meta span.year")?.text()?.substringAfter("far\">")?.toIntOrNull()
        val rating = document.selectFirst("div.vote-cn span.vote span.num")?.text()?.toDoubleOrNull()
        val genres = document.select("aside.fg1 header.entry-header div.entry-meta span.genres a").map { it.text() }
        val duration = document.selectFirst("aside.fg1 header.entry-header div.entry-meta span.duration")?.text()?.substringAfter("far\">")
        
        // --- Extração do Link do Player / Embed ---
        val iframeElement = document.selectFirst("div.play-overlay div#player iframe")
            ?: document.selectFirst("div.video iframe[src*='player.ultracine.org']")

        val playerUrl = iframeElement?.let { iframe ->
            iframe.attr("src").takeIf { it.isNotBlank() } ?: iframe.attr("data-src")
        } ?: url // Se não encontrar o iframe, passamos a URL da página para o loadLinks inspecionar.

        val isSerie = url.contains("/serie/")

        return if (isSerie) {
            // Se for série, a lógica é mais complexa, mas usamos playerUrl como data base
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, emptyList()) {
                this.posterUrl = poster?.replace("/w1280/", "/original/")
                this.plot = plot
                this.year = year
                // CORREÇÃO DE TIPO: vezes 10 (0-100) e conversão para Score?
                this.rating = rating?.times(10)?.toInt()?.toScore() 
                this.tags = genres
            }
        } else {
            // Se for filme, passamos o playerUrl como o "data" para o loadLinks
            newMovieLoadResponse(title, url, TvType.Movie, playerUrl) {
                this.posterUrl = poster?.replace("/w1280/", "/original/")
                this.plot = plot
                this.year = year
                // CORREÇÃO DE TIPO: vezes 10 (0-100) e conversão para Score?
                this.rating = rating?.times(10)?.toInt()?.toScore() 
                this.tags = genres
                this.duration = parseDuration(duration)
            }
        }
    }

    // 🎯 FUNÇÃO 'LOADLINKS' ORIGINAL (Depende do playerUrl da função load)
    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val playerUrl = data.trim()
    if (playerUrl == mainUrl || playerUrl.isBlank()) return false // Evita buscar links na página inicial

    try {
        val response = app.get(playerUrl, referer = mainUrl) // Usando mainUrl como referer
        if (!response.isSuccessful) return false

        val script = response.text

        // Extrai o .m3u8 do script (padrão do ultracine)
        val m3u8Url = Regex("""["']([^"']*\.m3u8[^"']*)["']""").find(script)?.groupValues?.get(1)
            ?: Regex("""file:\s*["']([^"']*\.m3u8[^"']*)["']""").find(script)?.groupValues?.get(1)
            ?: return false

        callback.invoke(
            ExtractorLink(
                source = name,
                name = "$name - HD",
                url = m3u8Url,
                referer = playerUrl,
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8,
                headers = mapOf("Origin" to mainUrl) // Usando mainUrl como Origin
            )
        )
        return true
    } catch (e: Exception) {
        e.printStackTrace()
        return false

      }

   }
    
    // 🎯 FUNÇÃO parseDuration (Ajustada para ser privada)
    private fun parseDuration(duration: String?): Int? {
        if (duration == null) return null
        val regex = Regex("(\\d+)h\\s*(\\d+)m")
        val matchResult = regex.find(duration)
        return if (matchResult != null) {
            val hours = matchResult.groupValues[1].toIntOrNull() ?: 0
            val minutes = matchResult.groupValues[2].toIntOrNull() ?: 0
            hours * 60 + minutes
        } else {
            val minutesRegex = Regex("(\\d+)m")
            val minutesMatch = minutesRegex.find(duration)
            minutesMatch?.groupValues?.get(1)?.toIntOrNull()
        }
    }
}
