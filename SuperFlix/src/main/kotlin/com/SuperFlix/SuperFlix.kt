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
        // ... (lógica de URL inalterada) ...

        val response = app.get(url, headers = defaultHeaders)
        val document = response.document

        // NOVO CÓDIGO: Lógica do SearchResponse diretamente no getMainPage
        val list = document.select("a.card").mapNotNull { element -> 
            val title = element.attr("title")
            val url = fixUrl(element.attr("href"))
            // Usando o seletor card-img correto
            val posterUrl = element.selectFirst("img.card-img")?.attr("src")?.let { fixUrl(it) }

            if (title.isNullOrEmpty() || url.isNullOrEmpty()) return@mapNotNull null

            val year = title.substringAfterLast("(").substringBeforeLast(")").toIntOrNull()
            val cleanTitle = title.substringBeforeLast("(").trim()

            val type = if (url.contains("/filme/")) TvType.Movie else TvType.TvSeries

            // Usando newSearchResponse para maior compatibilidade
            newSearchResponse(cleanTitle, url, type) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }

        return newHomePageResponse(request.name, list, list.isNotEmpty())
    }

override suspend fun search(query: String): List<SearchResponse> {
    // 1. Constrói a URL de busca
    val url = "$mainUrl/?s=$query"
    
    // 2. Faz a requisição HTTP (mantendo os headers completos)
    val response = app.get(url, headers = defaultHeaders)
    val document = response.document 

    // 3. Seleciona e mapeia os resultados
    // Busca por contêineres de resultado que são links (a.card) ou divs (div.card).
    val results = document.select("a.card, div.card").mapNotNull { element ->
        
        // 3a. Extração do Título (Seletor: .card-title)
        val title = element.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
        
        // 3b. Extração do Poster (Seletor: .card-img)
        val posterUrl = element.selectFirst(".card-img")?.attr("src")?.let { fixUrl(it) } ?: return@mapNotNull null
        
        // 3c. Extração do Link (Href)
        // O link deve estar na própria tag 'a.card'. Se for 'div.card', tentamos o primeiro link dentro.
        val href = element.attr("href").ifEmpty { 
            element.selectFirst("a")?.attr("href") 
        } ?: return@mapNotNull null
        
        // 3d. Extração do Tipo (Seletor: .card-meta)
        val typeText = element.selectFirst(".card-meta")?.text()?.trim() ?: "Filme" 

        // 3e. Determina o TvType (Movie ou TvSeries)
        val type = if (typeText.contains("Série", ignoreCase = true)) TvType.TvSeries else TvType.Movie

        // 3f. Retorna o objeto SearchResponse
        newSearchResponse(title, fixUrl(href), type) {
            // Usa 'this.' para atribuir a propriedade do objeto
            this.posterUrl = posterUrl
        }
    }

    // 4. Diagnóstico de segurança (Removido o throw para evitar quebra, mas mantemos o retorno)
    // Se a busca retornar vazia, o Cloudstream reportará "No search responses".
    return results
}



                // ... DENTRO DA CLASSE SuperFlix

// ... (Outras funções)

override suspend fun load(url: String): LoadResponse {
    val response = app.get(url, headers = defaultHeaders) 
    val document = response.document

    val isMovie = url.contains("/filme/")

    // 1. TÍTULO (Mantido)
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

    // 2. POSTER e SINOPSE (Mantido)
    val posterUrl = document.selectFirst(".poster img")?.attr("src")?.let { fixUrl(it) }
        ?: document.selectFirst(".poster")?.attr("src")?.let { fixUrl(it) }

    val plot = document.selectFirst(".syn")?.text()?.trim()
        ?: "Sinopse não encontrada."

    // 3. TAGS/GÊNEROS (Seleção direta por .chip)
    val tags = document.select("a.chip").map { it.text().trim() }.filter { it.isNotEmpty() }

    // 4. ELENCO (ATORES): CORREÇÃO LÓGICA FINAL
    // Buscamos links que estão em um parágrafo que contém a palavra 'Elenco'.
    val actorLinks = document.select("p, div").filter {
        // Tenta encontrar o bloco que contém 'Elenco:' ou 'Elenco'
        it.text().contains("Elenco", ignoreCase = true) 
    }.flatMap { 
        // Dentro desse bloco, pegamos apenas os links (<a>)
        it.select("a") 
    }.map { 
        it.text().trim() 
    }.filter { 
        // Filtramos para ter certeza que não estamos pegando links curtos ou lixo
        it.isNotEmpty() && it.length > 2 
    }.distinct().toList()
    
    val actors = if (actorLinks.isNotEmpty()) actorLinks else emptyList()

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
            addActors(actors) // Atores
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
            addActors(actors) // Atores
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
