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

    // Headers completos para simular o navegador e evitar bloqueios de servidor (Cloudflare/Anti-Scraping)
    private val defaultHeaders = mapOf(
        "Referer" to mainUrl,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
    )

    // Helper: Converte Elemento Jsoup em SearchResponse (Usado em getMainPage e search)
    private fun Element.toSearchResponse(): SearchResponse? {
        val title = this.attr("title")
        val url = fixUrl(this.attr("href"))
        val posterUrl = this.selectFirst("img.card-img")?.attr("src")?.let { fixUrl(it) }

        if (title.isNullOrEmpty() || url.isNullOrEmpty()) return null

        val year = title.substringAfterLast("(").substringBeforeLast(")").toIntOrNull()
        val cleanTitle = title.substringBeforeLast("(").trim()

        val type = if (url.contains("/filme/")) TvType.Movie else TvType.TvSeries

        return newMovieSearchResponse(cleanTitle, url, type) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

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

        val list = document.select("a.card").mapNotNull { it.toSearchResponse() }

        return newHomePageResponse(request.name, list, list.isNotEmpty())
    }

    // DENTRO DO SEU SuperFlix.kt

override suspend fun search(query: String): List<SearchResponse> {
    val url = "$mainUrl/?s=$query"
    // Usa headers completos
    val response = app.get(url, headers = defaultHeaders)
    val document = response.document 

    // Seleção original de resultados
    val results = document.select("a.card").mapNotNull { it.toSearchResponse() }

    // >>>>> CÓDIGO DE DIAGNÓSTICO <<<<<
    if (results.isEmpty()) {
        // Pega as primeiras 150 caracteres do HTML recebido
        val errorHtml = document.html().take(150)
        // Lança um erro que você verá no log do Cloudstream
        throw ErrorLoadingException("ERRO BUSCA: Nenhum resultado. HTML Recebido (150 chars): $errorHtml")
    }
    // >>>>> FIM DO CÓDIGO DE DIAGNÓSTICO <<<<<
    
    return results
}


                override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = defaultHeaders) 
        val document = response.document

        val isMovie = url.contains("/filme/")

        // 1. TÍTULO (Mantido o método de extração que funcionou)
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
        
        // 2. POSTER e SINOPSE (Mantidos os seletores que funcionaram)
        val posterUrl = document.selectFirst(".poster img")?.attr("src")?.let { fixUrl(it) }
            ?: document.selectFirst(".poster")?.attr("src")?.let { fixUrl(it) }

        val plot = document.selectFirst(".syn")?.text()?.trim()
            ?: "Sinopse não encontrada."
        
       // 3. TAGS/GÊNEROS (Seleção direta pela classe .chip que você encontrou)
val tags = document.select("a.chip").map { it.text().trim() }.filter { it.isNotEmpty() }

// 4. ELENCO (ATORES): Estratégia de EXCLUSÃO REFORÇADA

// A) Pega TODOS os links que são candidatos (dentro de DIVs, fora de NAV)
// Isso pega Atores, Diretores, e possivelmente outros links de texto no corpo do filme.
val allCandidateLinks = document.select("div:not(.navbar) a").map { it.text().trim() }
    
// B) Pega TODOS os textos de TAGS/GÊNEROS (a.chip)
val chipTexts = tags.toSet() 

// C) ATORES: Filtra os Candidatos A, removendo o Ruído
val actors = allCandidateLinks
    .filter { linkText -> linkText !in chipTexts } // Remove tags
    .filter { linkText -> !linkText.contains("Assista sem anúncios") } // Remove o link de propaganda (Imagem 1000039127.jpg)
    .filter { it.isNotEmpty() && it.length > 2 }   // Remove ruídos e links de 1 ou 2 letras (ex: 'a', 'de')
    .distinct() 
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
                addActors(actors) // Adiciona atores
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
                addActors(actors) // Adiciona atores
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
            // Filmes usam o loadExtractor para resolver a URL do Fembed
            return loadExtractor(data, data, subtitleCallback, callback)
        } else {
            // Séries usam headers para carregar a página de episódio
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
