package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class SuperFlix : MainAPI() {

    override var mainUrl = "https://superflixhd2.net" // Domínio fixo conhecido
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br" // Mudado para pt-br (mais comum)
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Páginas principais mais específicas
    override val mainPage = mainPageOf(
        "$mainUrl/filmes/em-alta/" to "Filmes em Alta",
        "$mainUrl/filmes/lancamentos/" to "Lançamentos",
        "$mainUrl/series/em-alta/" to "Séries em Alta"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            request.data + "page/$page/"
        }
        
        val doc = app.get(url).document
        
        // Seletores mais específicos
        val items = doc.select("article.movie, article.series, div.movie-item, div.serie-item").mapNotNull { 
            it.toSearchResponse()
        }
        
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        // Tenta vários seletores diferentes
        val title = selectFirst("h2, h3, .title, .entry-title")?.text()?.trim() ?: return null
        val href = selectFirst("a")?.attr("href")?.trim() ?: return null
        
        // Tenta várias fontes para o poster
        val poster = selectFirst("img")?.let { img ->
            img.attr("src").takeIf { it.isNotBlank() } ?: 
            img.attr("data-src").takeIf { it.isNotBlank() }
        }?.let { fixUrl(it) }
        
        // Determina se é série ou filme pela URL
        val isSeries = href.contains("/series/") || href.contains("/serie/") || 
                       href.contains("/tv/") || selectFirst(".series-label") != null
        
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { 
                this.posterUrl = poster 
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { 
                this.posterUrl = poster 
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("article.movie, article.series, div.movie-item").mapNotNull { 
            it.toSearchResponse() 
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        // Título
        val title = doc.selectFirst("h1.entry-title, h1.title, h1")?.text()?.trim() ?: ""
        
        // Poster
        val poster = doc.selectFirst(".poster img, .thumbnail img, .featured-image img")?.attr("src")?.let { fixUrl(it) }
        
        // Sinopse
        val plot = doc.selectFirst(".sinopse, .description, .entry-content, .plot")?.text()?.trim()
        
        // Ano
        val year = doc.selectFirst(".year, .release-date, time")?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        
        // Determina se é série
        val isSeries = url.contains("/series/") || url.contains("/serie/") || url.contains("/tv/") || 
                       doc.selectFirst(".episodios, .seasons, .temporadas") != null
        
        if (isSeries) {
            // Extrai episódios
            val episodes = doc.select(".episodios li, .episode-item, .episodio").mapNotNull { ep ->
                val epUrl = ep.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val epName = ep.selectFirst(".titulo, .title, h3")?.text()?.trim() ?: "Episódio"
                val epNum = ep.attr("data-episode")?.toIntOrNull() ?: 
                           ep.selectFirst(".episode-num")?.text()?.filter { it.isDigit() }?.toIntOrNull()
                val seasonNum = ep.attr("data-season")?.toIntOrNull() ?: 
                               ep.selectFirst(".season-num")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 1
                
                newEpisode(epUrl) {
                    name = epName
                    season = seasonNum
                    episode = epNum
                }
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            // Para filmes
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                // Duração se disponível
                val duration = doc.selectFirst(".duration, .runtime")?.text()?.filter { it.isDigit() }?.toIntOrNull()
                this.duration = duration
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val doc = app.get(data).document
            
            var foundLinks = false
            
            // 1. Procura por iframes
            doc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src").trim()
                if (src.isNotBlank() && !src.startsWith("javascript:")) {
                    if (loadExtractor(src, data, subtitleCallback, callback)) {
                        foundLinks = true
                    }
                }
            }
            
            // 2. Procura por players diretos
            doc.select("video source[src]").forEach { source ->
                val src = source.attr("src").trim()
                if (src.isNotBlank()) {
                    callback.invoke(
                        ExtractorLink(
                            name = "SuperFlix",
                            source = name,
                            url = src,
                            referer = data,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                    foundLinks = true
                }
            }
            
            // 3. Procura por links m3u8
            val html = doc.html()
            val m3u8Pattern = Regex("""(https?://[^"\s<>]+\.m3u8[^"\s<>]*)""", RegexOption.IGNORE_CASE)
            m3u8Pattern.findAll(html).forEach { match ->
                val url = match.value.trim()
                callback.invoke(
                    ExtractorLink(
                        name = "SuperFlix",
                        source = name,
                        url = url,
                        referer = data,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8
                    )
                )
                foundLinks = true
            }
            
            // 4. Procura por legendas
            doc.select("track[kind=subtitles][src]").forEach { track ->
                val lang = track.attr("label").ifBlank { "Português" }
                val url = track.attr("src").trim()
                if (url.isNotBlank()) {
                    subtitleCallback.invoke(SubtitleFile(lang, url))
                }
            }
            
            return foundLinks
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}