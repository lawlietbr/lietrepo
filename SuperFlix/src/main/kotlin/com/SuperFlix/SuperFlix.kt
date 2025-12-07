package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // Headers completos
    private val defaultHeaders = mapOf(
        "Referer" to mainUrl,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
    )
    
    // Helper: Extrai a URL de embed do Fembed
    private fun getFembedUrl(element: Element): String? {
        val iframeSrc = element.selectFirst("iframe#player")?.attr("src")
        if (!iframeSrc.isNullOrEmpty() && iframeSrc.contains("fembed")) {
            return iframeSrc
        }
        val dataUrl = element.selectFirst("button[data-url]")?.attr("data-url")
        if (!dataUrl.isNullOrEmpty() && dataUrl.contains("fembed")) {
            return dataUrl
        }
        return null
    }

    override val mainPage = listOf(
        MainPageData("Lançamentos", "$mainUrl/lancamentos"),
        MainPageData("Últimos Filmes", "$mainUrl/filmes"),
        MainPageData("Últimas Séries", "$mainUrl/series"),
        MainPageData("Últimos Animes", "$mainUrl/animes")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            val type = request.data.substringAfterLast("/")
            if (type.contains("genero")) {
                val genre = request.data.substringAfterLast("genero/").substringBefore("/")
                "$mainUrl/genero/$genre/page/$page"
            } else {
                "$mainUrl/$type/page/$page"
            }
        }
        
        val response = app.get(url, headers = defaultHeaders)
        val document = response.document

        val list = document.select("a.card").mapNotNull { element -> 
            val title = element.attr("title")
            val url = fixUrl(element.attr("href"))
            
            if (title.isNullOrEmpty() || url.isNullOrEmpty()) return@mapNotNull null

            val year = title.substringAfterLast("(").substringBeforeLast(")").toIntOrNull()
            val cleanTitle = title.substringBeforeLast("(").trim()

            val type = if (url.contains("/filme/")) TvType.Movie else TvType.TvSeries
            
            // CORREÇÃO (Sem variável local posterUrl)
            return@mapNotNull newSearchResponse(cleanTitle, url, type) {
                // Extrai o poster URL diretamente aqui
                this.posterUrl = element.selectFirst("img")?.attr("data-src")
                    .takeIf { it?.isNotEmpty() == true } 
                    ?: element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                this.year = year
            }
        }

        return newHomePageResponse(request.name, list, list.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"

        val response = app.get(url, headers = defaultHeaders)
        val document = response.document 

        val results = document.select("a.card, div.card").mapNotNull { element ->

            val title = element.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
            
            val href = element.attr("href").ifEmpty { 
                element.selectFirst("a")?.attr("href") 
            } ?: return@mapNotNull null

            val typeText = element.selectFirst(".card-meta")?.text()?.trim() ?: "Filme" 
            val type = if (typeText.contains("Série", ignoreCase = true)) TvType.TvSeries else TvType.Movie

            // CORREÇÃO (Sem variável local posterUrl)
            return@mapNotNull newSearchResponse(title, fixUrl(href), type) {
                this.posterUrl = element.selectFirst("img")?.attr("data-src")
                    .takeIf { it?.isNotEmpty() == true } 
                    ?: element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            }
        }

        if (results.isEmpty()) {
            val errorHtml = document.html().take(150)
            throw ErrorLoadingException("ERRO BUSCA: Nenhum resultado. HTML Recebido (150 chars): $errorHtml")
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = defaultHeaders) 
        val document = response.document

        val isMovie = url.contains("/filme/")

        // 1. TÍTULO
        val dynamicTitle = document.selectFirst(".title")?.text()?.trim()
        val title: String

        if (dynamicTitle.isNullOrEmpty()) {
            val fullTitle = document.selectFirst("title")?.text()?.trim()
                ?: throw ErrorLoadingException("Não foi possível extrair a tag <title>.")

            title = fullTitle.substringAfter("Assistir").substringBefore("Grátis").trim()
                .ifEmpty { fullTitle.substringBefore("Grátis").trim() } 
        } else {
            title = dynamicTitle
        }

        // 2. POSTER e SINOPSE
        val posterUrl = document.selectFirst(".poster img")?.attr("src")?.let { fixUrl(it) }
            ?: document.selectFirst(".poster")?.attr("src")?.let { fixUrl(it) }

        val plot = document.selectFirst(".syn")?.text()?.trim()
            ?: "Sinopse não encontrada."

        // 3. TAGS/GÊNEROS
        val tags = document.select("a.chip").map { it.text().trim() }.filter { it.isNotEmpty() }

        // 4. ELENCO (ATORES): Usando a Estratégia de Exclusão (que compilava)
        val allDivLinks = document.select("div a").map { it.text().trim() }
        val chipTexts = tags.toSet() 

        val actors = allDivLinks
            .filter { linkText -> linkText !in chipTexts }
            .filter { linkText -> !linkText.contains("Assista sem anúncios") }
            .filter { it.isNotEmpty() && it.length > 2 }
            .distinct() 
            .take(15) 
            .toList()

        // Outros campos
        val year = title.substringAfterLast("(").substringBeforeLast(")").toIntOrNull()

        val type = if (isMovie) TvType.Movie else TvType.TvSeries

        return if (isMovie) {
            val embedUrl = getFembedUrl(document)
            newMovieLoadResponse(title, url, type, embedUrl) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = tags
                this.year = year
                addActors(actors)
            }
        } else {
            val seasons = document.select("div#season-tabs button").mapIndexed { index, element ->
                val seasonName = element.text().trim()
                newEpisode(url) {
                    name = seasonName
                    season = index + 1
                    episode = 1 
                    data = url 
                }
            }
            newTvSeriesLoadResponse(title, url, type, seasons) { 
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = tags
                this.year = year
                addActors(actors)
            }
        }
    }


    override suspend fun loadLinks(
        data: String,
        isMovie: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (isMovie) {
            return loadExtractor(data, data, subtitleCallback, callback)
        } else {
            val response = app.get(data, headers = defaultHeaders) 
            val document = response.document

            val episodeButtons = document.select("button[data-url*=\"fembed\"]")

            for (button in episodeButtons) {
                val embedUrl = button.attr("data-url")
                if (!embedUrl.isNullOrEmpty()) {
                    loadExtractor(embedUrl, mainUrl, subtitleCallback, callback) 
                }
            }
            return true
        }
    }
}
