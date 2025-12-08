package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element
import java.net.URLEncoder
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/filmes" to "Filmes",
        "$mainUrl/series" to "Séries",
        "$mainUrl/lancamentos" to "Lançamentos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "?page=$page" else ""
        val document = app.get(url).document

        val home = mutableListOf<SearchResponse>()

        document.select("div.recs-grid a.rec-card, .movie-card, article, .item").forEach { element ->
            element.toSearchResult()?.let { home.add(it) }
        }

        if (home.isEmpty()) {
            document.select("a[href*='/filme/'], a[href*='/serie/']").forEach { link ->
                val href = link.attr("href")
                if (href.isNotBlank() && !href.contains("#")) {
                    val title = link.selectFirst("img")?.attr("alt")
                        ?: link.selectFirst(".rec-title, .title, h2, h3")?.text()
                        ?: href.substringAfterLast("/").replace("-", " ").replace(Regex("\\d{4}$"), "").trim()

                    if (title.isNotBlank()) {
                        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                        val poster = link.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                        val isSerie = href.contains("/serie/")

                        val searchResponse = if (isSerie) {
                            newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                                this.posterUrl = poster
                                this.year = year
                            }
                        } else {
                            newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                                this.posterUrl = poster
                                this.year = year
                            }
                        }

                        home.add(searchResponse)
                    }
                }
            }
        }

        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".rec-title, .movie-title, h2, h3, .title")?.text()
            ?: selectFirst("img")?.attr("alt")
            ?: return null

        val href = attr("href") ?: selectFirst("a")?.attr("href") ?: return null

        val poster = selectFirst("img")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }
            ?: selectFirst("img")?.attr("data-src")?.let { fixUrl(it) }

        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: selectFirst(".rec-meta, .movie-year, .year")?.text()?.let {
                Regex("\\b(\\d{4})\\b").find(it)?.groupValues?.get(1)?.toIntOrNull()
            }

        val isSerie = href.contains("/serie/")
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

        return if (isSerie) {
            newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encodedQuery").document

        val results = mutableListOf<SearchResponse>()

        document.select("div.recs-grid a.rec-card, a[href*='/filme/'], a[href*='/serie/']").forEach { element ->
            element.toSearchResult()?.let { results.add(it) }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val html = document.html()

        val jsonLd = extractJsonLd(html)

        val title = jsonLd.title ?: document.selectFirst("h1, .title")?.text() ?: return null
        val year = jsonLd.year ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val poster = jsonLd.posterUrl?.replace("/w500/", "/original/")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?.replace("/w500/", "/original/")

        val plot = jsonLd.description ?: document.selectFirst("meta[name='description']")?.attr("content")
            ?: document.selectFirst(".syn, .description")?.text()

        val tags = jsonLd.genres ?: document.select("a.chip, .chip").map { it.text() }

        val actors = jsonLd.actors?.map { Actor(it, "") } ?: emptyList()

        val director = jsonLd.director?.firstOrNull()

        val isSerie = url.contains("/serie/") || jsonLd.type == "TVSeries"

        return if (isSerie) {
            val episodes = extractEpisodesFromButtons(document, url)

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                addActors(actors)
            }
        } else {
            val fembedUrl = findFembedUrl(document)

            newMovieLoadResponse(title, url, TvType.Movie, fembedUrl ?: "") {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                addActors(actors)
            }
        }
    }

    private fun extractEpisodesFromButtons(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()

        document.select("button.bd-play[data-url]").forEach { button ->
            val fembedUrl = button.attr("data-url")
            val season = button.attr("data-season").toIntOrNull() ?: 1
            val episodeNum = button.attr("data-ep").toIntOrNull() ?: 1

            var episodeTitle = "Episódio $episodeNum"

            val parent = button.parents().find { it.hasClass("episode-item") || it.hasClass("episode") }
            parent?.let {
                val titleElement = it.selectFirst(".ep-title, .title, .name, h3, h4")
                if (titleElement != null) {
                    episodeTitle = titleElement.text().trim()
                }
            }

            episodes.add(
                newEpisode(fembedUrl) {
                    this.name = episodeTitle
                    this.season = season
                    this.episode = episodeNum
                }
            )
        }

        if (episodes.isEmpty()) {
            document.select(".episode-item, .episode, .episodio").forEachIndexed { index, episodeElement ->
                val episodeNum = index + 1
                val button = episodeElement.selectFirst("button.bd-play[data-url]")
                val fembedUrl = button?.attr("data-url")

                if (fembedUrl != null) {
                    val title = episodeElement.selectFirst(".ep-title, .title, .name")?.text()
                        ?: "Episódio $episodeNum"
                    val season = button.attr("data-season").toIntOrNull() ?: 1

                    episodes.add(
                        newEpisode(fembedUrl) {
                            this.name = title
                            this.season = season
                            this.episode = episodeNum
                        }
                    )
                }
            }
        }

        return episodes
    }

    private fun findFembedUrl(document: org.jsoup.nodes.Document): String? {
        val iframe = document.selectFirst("iframe[src*='fembed']")
        if (iframe != null) {
            return iframe.attr("src")
        }

        val playButton = document.selectFirst("button.bd-play[data-url]")
        if (playButton != null) {
            return playButton.attr("data-url")
        }

        val anyButton = document.selectFirst("button[data-url*='fembed']")
        if (anyButton != null) {
            return anyButton.attr("data-url")
        }

        val html = document.html()
        val patterns = listOf(
            Regex("""https?://fembed\.sx/e/\d+"""),
            Regex("""data-url=["'](https?://[^"']+fembed[^"']+)["']"""),
            Regex("""src\s*[:=]\s*["'](https?://[^"']+fembed[^"']+)["']""")
        )

        patterns.forEach { pattern ->
            pattern.find(html)?.let { match ->
                val url = if (match.groupValues.size > 1) match.groupValues[1] else match.value
                if (url.isNotBlank()) return url
            }
        }

        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("SuperFlix DEBUG: loadLinks chamado com data = '$data'")

        return try {
            // Extrair ID do Fembed da URL
            val fembedId = extractFembedId(data)
            println("SuperFlix DEBUG: Fembed ID extraído = '$fembedId'")

            if (fembedId.isBlank()) {
                println("SuperFlix DEBUG: Não foi possível extrair Fembed ID")
                return false
            }

            // ESTRATÉGIA 1: Primeiro tentar o extrator normal do CloudStream
            val fembedUrl = "https://fembed.sx/v/$fembedId"
            println("SuperFlix DEBUG: Tentando extrator normal: '$fembedUrl'")

            if (loadExtractor(fembedUrl, "$mainUrl/", subtitleCallback, callback)) {
                println("SuperFlix DEBUG: ✅ Extrator normal funcionou!")
                return true
            }

            // ESTRATÉGIA 2: Tentar com domínio alternativo
            val fembedUrl2 = "https://www.fembed.com/v/$fembedId"
            println("SuperFlix DEBUG: Tentando domínio alternativo: '$fembedUrl2'")

            if (loadExtractor(fembedUrl2, "$mainUrl/", subtitleCallback, callback)) {
                println("SuperFlix DEBUG: ✅ Domínio alternativo funcionou!")
                return true
            }

            // ESTRATÉGIA 3: API DIRETA DO FEMBED (SOLUÇÃO PRINCIPAL)
            println("SuperFlix DEBUG: 🚀 Tentando API direta do Fembed...")
            val success = tryFembedApiDirect(fembedId, callback)

            if (success) {
                println("SuperFlix DEBUG: ✅ API direta do Fembed funcionou!")
                return true
            }

            // ESTRATÉGIA 4: Fallback para streamtape se houver
            println("SuperFlix DEBUG: Tentando fallback para Streamtape...")
            val streamtapeUrl = findStreamtapeUrl(data)
            if (streamtapeUrl != null && loadExtractor(streamtapeUrl, "$mainUrl/", subtitleCallback, callback)) {
                println("SuperFlix DEBUG: ✅ Streamtape funcionou!")
                return true
            }

            println("SuperFlix DEBUG: ❌ Nenhuma estratégia funcionou")
            false

        } catch (e: Exception) {
            println("SuperFlix DEBUG: Erro em loadLinks: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    // Função para extrair ID do Fembed
    private fun extractFembedId(data: String): String {
        // Padrões possíveis:
        // https://fembed.sx/e/304115/1-1
        // https://fembed.sx/v/304115/1-1
        // /e/304115/1-1
        // 304115/1-1
        // 304115

        val patterns = listOf(
            Regex("""(?:fembed\.(?:sx|com|to))/(?:e|v|f)/(\d+(?:/\d+-\d+)?)"""),
            Regex("""/(?:e|v|f)/(\d+(?:/\d+-\d+)?)"""),
            Regex("""(\d+(?:/\d+-\d+)?)""")
        )

        for (pattern in patterns) {
            val match = pattern.find(data)
            if (match != null && match.groupValues.size > 1) {
                val id = match.groupValues[1]
                println("SuperFlix DEBUG: Padrão encontrado: '${pattern.pattern}' -> '$id'")
                return id
            }
        }

        return data
    }

    // API DIRETA DO FEMBED - FUNCIONA SEM DEPENDER DO EXTRACTOR DO CLOUDSTREAM
    private suspend fun tryFembedApiDirect(fembedId: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            // Tentar diferentes domínios da API do Fembed
            val domains = listOf(
                "https://www.fembed.com",
                "https://fembed.sx",
                "https://fembed.to"
            )

            for (domain in domains) {
                try {
                    val apiUrl = "$domain/api/source/$fembedId"
                    println("SuperFlix DEBUG: Tentando API: $apiUrl")

                    val headers = mapOf(
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "Referer" to "$domain/",
                        "X-Requested-With" to "XMLHttpRequest",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )

                    val response = app.post(
                        apiUrl,
                        headers = headers,
                        data = mapOf(
                            "r" to "",
                            "d" to domain.replace("https://", "")
                        )
                    )

                    val json = response.parsedSafe<Map<String, Any>>()
                    println("SuperFlix DEBUG: Resposta da API: ${json?.keys}")

                    if (json?.get("success") == true) {
                        val data = json["data"] as? List<Map<String, Any>>
                        if (data != null && data.isNotEmpty()) {
                            println("SuperFlix DEBUG: Encontrados ${data.size} streams")

                            data.forEach { item ->
                                val file = item["file"]?.toString()
                                val label = item["label"]?.toString()

                                if (file != null && file.isNotBlank()) {
                                    val quality = getQualityFromLabel(label ?: "Unknown")
                                    val isM3u8 = file.contains(".m3u8") || file.contains("master.m3u8")

                                    // ✅ CORREÇÃO: Usando newExtractorLink corretamente
                                    val link = newExtractorLink(
                                        url = file,
                                        source = name,
                                        name = label ?: "Fembed",
                                        quality = quality,
                                        referer = "$domain/",
                                        isM3u8 = isM3u8
                                    )
                                    
                                    if (link != null) {
                                        callback.invoke(link)
                                        println("SuperFlix DEBUG: Adicionado stream: $label - $file")
                                    }
                                }
                            }

                            return true
                        }
                    } else {
                        println("SuperFlix DEBUG: API retornou success=false")
                    }
                } catch (e: Exception) {
                    println("SuperFlix DEBUG: Erro com domínio $domain: ${e.message}")
                }
            }
        } catch (e: Exception) {
            println("SuperFlix DEBUG: Erro geral na API Fembed: ${e.message}")
        }

        return false
    }

    // Função para encontrar URLs do Streamtape (fallback)
    private fun findStreamtapeUrl(data: String): String? {
        val patterns = listOf(
            Regex("""https?://(?:www\.)?streamtape\.(?:com|to)/[^"\s]+"""),
            Regex("""data-url=["'](https?://[^"']+streamtape[^"']+)["']"""),
            Regex("""(https?://[^"\s]+\.(?:mp4|m3u8|mkv))""")
        )

        for (pattern in patterns) {
            val match = pattern.find(data)
            if (match != null) {
                val url = match.value
                println("SuperFlix DEBUG: Encontrado Streamtape URL: $url")
                return url
            }
        }

        return null
    }

    // Converter label para qualidade (Int)
    private fun getQualityFromLabel(label: String): Int {
        return when {
            label.contains("1080") || label.contains("FHD") -> Qualities.P1080.value
            label.contains("720") || label.contains("HD") -> Qualities.P720.value
            label.contains("480") -> Qualities.P480.value
            label.contains("360") -> Qualities.P360.value
            label.contains("240") -> Qualities.P240.value
            label.contains("144") -> Qualities.P144.value
            else -> {
                // Tentar extrair número da qualidade
                val numMatch = Regex("""(\d+)""").find(label)
                numMatch?.groupValues?.get(1)?.toIntOrNull()?.let { num ->
                    when (num) {
                        in 1080..2160 -> Qualities.P1080.value
                        in 720..1079 -> Qualities.P720.value
                        in 480..719 -> Qualities.P480.value
                        in 360..479 -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                } ?: Qualities.Unknown.value
            }
        }
    }

    private data class JsonLdInfo(
        val title: String? = null,
        val year: Int? = null,
        val posterUrl: String? = null,
        val description: String? = null,
        val genres: List<String>? = null,
        val director: List<String>? = null,
        val actors: List<String>? = null,
        val tmdbId: String? = null,
        val type: String? = null
    )

    private fun extractJsonLd(html: String): JsonLdInfo {
        val pattern = Regex("<script type=\"application/ld\\+json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
        val matches = pattern.findAll(html)

        matches.forEach { match ->
            try {
                val json = match.groupValues[1].trim()
                if (json.contains("\"@type\":\"Movie\"") || json.contains("\"@type\":\"TVSeries\"")) {

                    val title = Regex("\"name\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    val image = Regex("\"image\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    val description = Regex("\"description\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)

                    val genresMatch = Regex("\"genre\":\\s*\\[([^\\]]+)\\]").find(json)
                    val genres = genresMatch?.groupValues?.get(1)
                        ?.split(",")
                        ?.map { it.trim().trim('"', '\'') }
                        ?.filter { it.isNotBlank() }

                    val actorsMatch = Regex("\"actor\":\\s*\\[([^\\]]+)\\]").find(json)
                    val actors = actorsMatch?.groupValues?.get(1)
                        ?.split("},")
                        ?.mapNotNull { actor ->
                            Regex("\"name\":\"([^\"]+)\"").find(actor)?.groupValues?.get(1)
                        }

                    val directorMatch = Regex("\"director\":\\s*\\[([^\\]]+)\\]").find(json)
                    val director = directorMatch?.groupValues?.get(1)
                        ?.split("},")
                        ?.mapNotNull { dir ->
                            Regex("\"name\":\"([^\"]+)\"").find(dir)?.groupValues?.get(1)
                        }

                    val sameAsMatch = Regex("\"sameAs\":\\s*\\[([^\\]]+)\\]").find(json)
                    val tmdbId = sameAsMatch?.groupValues?.get(1)
                        ?.split(",")
                        ?.find { it.contains("themoviedb.org") }
                        ?.substringAfterLast("/")
                        ?.trim(' ', '"', '\'')

                    val type = if (json.contains("\"@type\":\"Movie\"")) "Movie" else "TVSeries"

                    return JsonLdInfo(
                        title = title,
                        year = null,
                        posterUrl = image,
                        description = description,
                        genres = genres,
                        director = director,
                        actors = actors,
                        tmdbId = tmdbId,
                        type = type
                    )
                }
            } catch (e: Exception) {
                // Continua
            }
        }

        return JsonLdInfo()
    }
}