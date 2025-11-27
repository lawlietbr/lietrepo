package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix20.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false // Fembed pode dificultar
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime) // Adicionando Anime conforme a análise

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a") ?: return null
        val href = fixUrl(link.attr("href"))
        
        val title = link.selectFirst("img")?.attr("alt")?.trim() 
            ?: link.selectFirst("h3")?.text()?.trim()
            ?: return null
            
        val posterUrl = link.selectFirst("img")?.attr("src")
        
        val type = when {
            href.contains("/filme/") -> TvType.Movie
            href.contains("/serie/") -> TvType.TvSeries
            else -> TvType.Movie
        }

        // CORREÇÃO: Usamos o construtor direto (data class) com todos os campos necessários.
        return SearchResponse(
            name = title,
            url = href,
            type = type,
            posterUrl = posterUrl // Passa posterUrl diretamente no construtor
        )
    }

    // Função para obter itens em uma página (usada em getMainPage e Search)
    private suspend fun getListItems(url: String): List<SearchResponse> {
        val document = app.get(url).document
        // Seletor genérico que deve envolver o item (Ajuste o seletor conforme o HTML do site!)
        // Assumindo um container principal para todos os itens em grade
        return document.select("div.lista-de-filmes-e-series > a").mapNotNull { it.toSearchResult() }
    }

    // 1. Implementação da Página Principal
    override val mainPage = mainPageOf(
        "/lancamentos?page=1&f=filmes" to "Lançamentos (Filmes)",
        "/lancamentos?page=1&f=series" to "Lançamentos (Séries)",
        "/filmes?page=1" to "Filmes Populares",
        "/series?page=1" to "Séries Populares"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // A paginação é simples, apenas alteramos o número da página na URL
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            // Se a URL já contiver ?page=, substituímos. Senão, adicionamos.
            val baseUrl = "$mainUrl${request.data.substringBeforeLast("page=")}page=$page"
            baseUrl
        }
        
        val home = getListItems(url)

        // Assume-se que sempre há próxima página devido ao grande volume (9000+ filmes)
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    // 2. Implementação da Busca
    override suspend fun search(query: String): List<SearchResponse> {
        // URL da Busca: https://superflix20.lol/?q=termo_de_busca (Análise 4.)
        val url = "$mainUrl/?q=$query"
        return getListItems(url)
    }

    // 3. Implementação da Página de Detalhes (Filmes e Séries)
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val isMovie = url.contains("/filme/")
        val type = if (isMovie) TvType.Movie else TvType.TvSeries

        // Extração de Metadados
        val title = document.selectFirst("h1.movie-title")?.text()?.trim() 
            ?: document.selectFirst("h1.series-title")?.text()?.trim()
            ?: throw ErrorLoadingException("Título não encontrado")
            
        val plot = document.selectFirst("div.plot-content p")?.text()?.trim()
        val posterUrl = document.selectFirst("div.poster-wrapper img")?.attr("src")
        val tags = document.select("div.info-section a.genre-link").map { it.text().trim() }
        
        // Se for Filme, ele só tem o player principal. Se for Série, precisamos de episódios.
        if (isMovie) {
            return newMovieLoadResponse(title, url, type, url) { // Passamos a URL para loadLinks
                this.plot = plot
                this.posterUrl = posterUrl
                this.tags = tags
            }
        } else {
            // Lógica de Extração de Episódios para Séries (Análise 7.)
            // A Análise 7 diz que episódios são carregados dinamicamente via JS (clique em T1, T2...).
            // Isso geralmente significa que o Cloudstream precisa raspar a URL de cada temporada/episódio.
            
            val episodes = mutableListOf<Episode>()
            val seasonSections = document.select("div.season-tab") // Seletor para T1, T2, etc.
            
            seasonSections.forEachIndexed { index, seasonTab ->
                val seasonNum = index + 1 // Temporada 1, 2, 3...
                val episodeLinks = document.select("#tab-content-${seasonNum} a.episode-link") // Seletor do link do episódio dentro da aba
                
                episodeLinks.forEachIndexed { epIndex, link ->
                    val episodeNum = epIndex + 1
                    val epUrl = fixUrl(link.attr("href"))
                    
                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = link.selectFirst("span.ep-title")?.text()?.trim() ?: "Episódio $episodeNum"
                            this.season = seasonNum
                            this.episode = episodeNum
                        }
                    )
                }
            }
            
            return newTvSeriesLoadResponse(title, url, type, episodes) {
                this.plot = plot
                this.posterUrl = posterUrl
                this.tags = tags
            }
        }
    }

    // 4. Implementação do Carregamento de Links
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Data é a URL da página do filme/episódio.
        val document = app.get(data).document

        // Análise 6: O player é um iframe com o ID do Fembed
        val iframeSrc = document.selectFirst("iframe#player")?.attr("src") ?: return false
        
        val fembedUrl = fixUrl(iframeSrc)

        // Usamos o loadExtractor, que irá identificar que fembedUrl é um link Fembed
        // e usará o extrator interno do Cloudstream (mais robusto).
        // Se a URL for diretamente do Fembed, o loadExtractor sabe o que fazer.
        return loadExtractor(fembedUrl, mainUrl, subtitleCallback, callback)
    }
}
