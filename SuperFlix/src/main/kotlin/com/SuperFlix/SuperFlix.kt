package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    // CORREÇÃO 1: 'val' alterado para 'var'
    override var lang = "pt"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // Helper para converter Elemento Jsoup em SearchResponse
    private fun Element.toSearchResponse(): SearchResponse? {
        val title = this.attr("title")
        val url = fixUrl(this.attr("href"))
        val posterUrl = this.selectFirst("img.card-img")?.attr("src")

        if (title.isNullOrEmpty() || url.isNullOrEmpty()) return null

        val year = title.substringAfterLast("(").substringBeforeLast(")").toIntOrNull()
        val cleanTitle = title.substringBeforeLast("(").trim()

        val type = if (url.contains("/filme/")) TvType.Movie else TvType.TvSeries

        // Usando new*SearchResponse correto
        return newMovieSearchResponse(cleanTitle, url, type) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    // Função para extrair a URL de embed do Fembed
    private fun getFembedUrl(element: Element): String? {
        // Para filmes, o iframe#player está na página de detalhes
        val iframeSrc = element.selectFirst("iframe#player")?.attr("src")
        if (!iframeSrc.isNullOrEmpty() && iframeSrc.contains("fembed")) {
            return iframeSrc
        }

        // Para séries, o link está no atributo data-url do botão PLAY
        val dataUrl = element.selectFirst("button[data-url]")?.attr("data-url")
        if (!dataUrl.isNullOrEmpty() && dataUrl.contains("fembed")) {
            return dataUrl
        }

        return null
    }

    // 1. name, hasMainPage, lang, supportedTypes - JÁ FEITO ACIMA

    // 2. mainPage (categorias)
    override val mainPage = listOf(
        MainPageData("Lançamentos", "$mainUrl/lancamentos"),
        MainPageData("Últimos Filmes", "$mainUrl/filmes"),
        MainPageData("Últimas Séries", "$mainUrl/series"),
        MainPageData("Últimos Animes", "$mainUrl/animes"),

    // 3. getMainPage()
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            // Padrão de paginação: /tipo/page/numero
            val type = request.data.substringAfterLast("/")
            if (type.contains("genero")) {
                // Para categorias: /genero/nome/page/numero
                val genre = request.data.substringAfterLast("genero/").substringBefore("/")
                "$mainUrl/genero/$genre/page/$page"
            } else {
                // Para filmes/series/animes: /tipo/page/numero
                "$mainUrl/$type/page/$page"
            }
        }

        val response = app.get(url)
        val document = response.document

        val list = document.select("a.card").mapNotNull { it.toSearchResponse() }

        return newHomePageResponse(request.name, list, list.isNotEmpty())
    }

    // 5. search()
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val response = app.get(url)
        val document = response.document

        return document.select("a.card").mapNotNull { it.toSearchResponse() }
    }

    // 6. load()
    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url)
        val document = response.document

        val isMovie = url.contains("/filme/")

        val title = document.selectFirst("h1.text-3xl")?.text()?.trim() ?: throw ErrorLoadingException("Título não encontrado")
        val posterUrl = document.selectFirst("div.poster img")?.attr("src")
        val plot = document.selectFirst("p.text-gray-400")?.text()?.trim()
        val tags = document.select("a[href*=/genero/]").map { it.text().trim() }
        val year = title.substringAfterLast("(").substringBeforeLast(")").toIntOrNull()

        val type = if (isMovie) TvType.Movie else TvType.TvSeries

        return if (isMovie) {
            val embedUrl = getFembedUrl(document)
            newMovieLoadResponse(title, url, type, embedUrl) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = tags
                this.year = year
                // Adicionar mais metadados se necessário (duração, elenco, etc.)
            }
        } else {
            val seasons = document.select("div#season-tabs button").mapIndexed { index, element ->
                val seasonName = element.text().trim()
                // CORREÇÃO 2: Usando newEpisode em vez do construtor deprecated
                newEpisode(url) {
                    name = seasonName
                    season = index + 1
                    episode = 1 // Placeholder, já que o site não lista aqui
                    data = url // A URL da série será usada para carregar os links
                }
            }

            // CORREÇÃO 3: Mapeando List<Episode> para List<List<Episode>>
            val episodes = seasons.map { listOf(it) }

            newTvSeriesLoadResponse(title, url, type, seasons) { 
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }
    }

    // 7. loadLinks()
    override suspend fun loadLinks(
        data: String,
        isMovie: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (isMovie) {
            // Para filmes, 'data' é a URL do Fembed
            return loadExtractor(data, data, subtitleCallback, callback)
        } else {
            // Para séries, 'data' é a URL da série
            val response = app.get(data)
            val document = response.document

            // O CloudStream passa a URL da série e o número da temporada/episódio
            // O site usa botões com data-url. Vamos simular a seleção da temporada.
            // Como o site carrega todos os episódios na mesma página e usa botões para alternar,
            // vamos extrair todos os links de episódio visíveis.

            // Seleciona todos os botões de play que contêm a URL do Fembed
            val episodeButtons = document.select("button[data-url*=\"fembed\"]")

            for (button in episodeButtons) {
                val embedUrl = button.attr("data-url")
                // O nome do episódio não está diretamente no botão, mas na linha da tabela.
                // Isso é complexo de extrair com precisão sem a estrutura completa.
                // Por simplicidade, vamos usar o nome do player e a qualidade.
                if (!embedUrl.isNullOrEmpty()) {
                    loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
                }
            }
            return true
        }
    }
}
