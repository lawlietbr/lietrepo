package com.Goyabu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Element

class Goyabu : MainAPI() {
    override var mainUrl = "https://goyabu.io"
    override var name = "Goyabu"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)
    override val usesWebView = false

    companion object {
        private const val SEARCH_PATH = "/?s="
        private val loadingMutex = Mutex()

        private val ALL_GENRES = listOf(
            "/generos/aventura" to "Aventura",
            "/generos/acao" to "Ação",
            "/generos/comedia" to "Comédia",
            "/generos/escolar" to "Escolar",
            "/generos/drama" to "Drama",
            "/generos/demonios" to "Demônios",
            "/generos/crime" to "Crime",
            "/generos/ficcao-cientifica" to "Ficção Científica",
            "/generos/fantasia" to "Fantasia",
            "/generos/esporte" to "Esporte",
            "/generos/harem" to "Harém",
            "/generos/guerra" to "Guerra"
        )

        private val TITLE_CLEANUP_PATTERNS = listOf(
            "(?i)\\s*\\(dublado\\)".toRegex(),
            "(?i)\\s*\\(legendado\\)".toRegex(),
            "(?i)\\s*\\(\\d{4}\\)".toRegex(),
            "(?i)\\s*dublado\\s*$".toRegex(),
            "(?i)\\s*legendado\\s*$".toRegex(),
            "(?i)\\s*online\\s*$".toRegex(),
            "(?i)\\s*assistir\\s*".toRegex(),
            "(?i)\\s*anime\\s*$".toRegex(),
            "(?i)\\s*-\\s*todos os epis[oó]dios".toRegex(),
            "(?i)\\s*-\\s*completo".toRegex(),
            "(?i)\\s*\\|.*".toRegex()
        )
    }

    override val mainPage = mainPageOf(
        *ALL_GENRES.map { (path, name) -> 
            "$mainUrl$path" to name 
        }.toTypedArray()
    )

    private fun cleanTitle(dirtyTitle: String): String {
        var clean = dirtyTitle.trim()
        TITLE_CLEANUP_PATTERNS.forEach { pattern ->
            clean = pattern.replace(clean, "")
        }
        clean = clean.replace(Regex("\\s+"), " ").trim()
        return if (clean.isBlank()) dirtyTitle else clean
    }

    private fun cleanSynopsis(dirtySynopsis: String): String {
        var clean = dirtySynopsis.trim()
        
        if (clean.isBlank()) {
            return "Sinopse não disponível."
        }
        
        val phrasesToRemove = listOf(
            "Assistir.*?Online",
            "Todos os Episodios.*?Online", 
            "Anime.*?Completo",
            "assistir.*?online",
            "todos os episodios.*?online",
            "anime.*?completo",
            "Spy x Family.*?Online",
            "Spy.*?Family.*?Online"
        )
        
        phrasesToRemove.forEach { phrasePattern ->
            val regex = Regex("""[^.!?]*$phrasePattern[^.!?]*([.!?]|\.\.\.)?\s*""", RegexOption.IGNORE_CASE)
            clean = regex.replace(clean, "")
        }
        
        clean = clean.replace(Regex("""[^.!?]*\.\.\.\s*$"""), "")
        
        val sentences = clean.split(Regex("""[.!?]+""")).map { it.trim() }.filter { it.isNotBlank() }
        val uniqueSentences = mutableListOf<String>()
        
        sentences.forEach { sentence ->
            val normalizedSentence = sentence.lowercase().replace(Regex("\\s+"), " ")
            if (uniqueSentences.none { existing -> 
                existing.lowercase().replace(Regex("\\s+"), " ").contains(normalizedSentence) ||
                normalizedSentence.contains(existing.lowercase().replace(Regex("\\s+"), " "))
            }) {
                uniqueSentences.add(sentence)
            }
        }
        
        clean = uniqueSentences.joinToString(". ") + "."
        
        clean = clean.replace(Regex(",\\.\\s*$"), ".")
        clean = clean.replace(Regex("\\.\\.+\$"), ".")
        clean = clean.replace(Regex("\\.\\s+\\.\$"), ".")
        clean = clean.replace(Regex("\\s+"), " ").trim()
        
        if (clean.endsWith("...") && clean.length < 50) {
            clean = clean.replace(Regex("\\.\\.\\.\$"), ".")
        }
        
        if (clean.isNotEmpty() && !clean.endsWith(".") && !clean.endsWith("!") && !clean.endsWith("?") && clean.length > 10) {
            clean += "."
        }
        
        return when {
            clean.length < 20 -> "Sinopse não disponível."
            else -> clean
        }
    }

    private fun extractGoyabuStatus(doc: org.jsoup.nodes.Document): ShowStatus? {
        return try {
            var statusText = ""

            val statusSelectors = listOf(
                "li.status",
                ".status",
                "[class*='status']",
                ".streamer-info li:contains(Status)",
                ".streamer-info-list li",
                "li:contains('Completo')",
                "li:contains('Lançamento')"
            )

            for (selector in statusSelectors) {
                val element = doc.selectFirst(selector)
                if (element != null) {
                    statusText = element.text().trim().lowercase()
                    break
                }
            }

            if (statusText.isEmpty()) {
                doc.select("li, span, div").forEach { element ->
                    val text = element.text().trim().lowercase()
                    if (text.contains("complet") || text.contains("lançament")) {
                        statusText = text
                        return@forEach
                    }
                }
            }

            when {
                statusText.contains("complet") -> ShowStatus.Completed
                statusText.contains("lançament") -> ShowStatus.Ongoing
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseScore(text: String?): Score? {
        if (text.isNullOrBlank()) return null

        try {
            val regex = Regex("""(\d+\.?\d*)""")
            val match = regex.find(text)

            return match?.groupValues?.get(1)?.toFloatOrNull()?.let { rawScore ->
                val multipliedScore = rawScore * 2
                val finalScore = multipliedScore.coerceAtMost(10.0f)
                Score.from10(finalScore)
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun Element.toSearchResponse(): AnimeSearchResponse? {
        val href = attr("href") ?: return null
        val isEpisodePage = href.matches(Regex("""^/\d+/?$"""))
        val isAnimePage = href.contains("/anime/")
        if (!isAnimePage || isEpisodePage) return null

        // tema novo usa h3/h2 (validado em provider ativo de terceiros) + img alt
        val titleElement = selectFirst(".title, .hidden-text, h3, h2")
        val rawTitle = titleElement?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val cleanedTitle = cleanTitle(rawTitle)

        if (cleanedTitle.isBlank()) return null

        val posterUrl = extractPosterUrl()
        val scoreElement = selectFirst(".rating-score-box, .rating")
        val scoreText = scoreElement?.text()?.trim()
        val score = parseScore(scoreText)
        val hasDubBadge = selectFirst(".audio-box.dublado, .dublado") != null

        return newAnimeSearchResponse(cleanedTitle, fixUrl(href)) {
            this.posterUrl = posterUrl
            this.type = TvType.Anime
            this.score = score

            if (hasDubBadge) {
                addDubStatus(dubExist = true, subExist = false)
            } else {
                addDubStatus(dubExist = false, subExist = true)
            }
        }
    }

    private fun Element.extractPosterUrl(): String? {
        selectFirst(".coverImg")?.attr("style")?.let { style ->
            val regex = Regex("""url\(['"]?([^'"()]+)['"]?\)""")
            regex.find(style)?.groupValues?.get(1)?.let { url ->
                return fixUrl(url)
            }
        }
        selectFirst("[data-thumb]")?.attr("data-thumb")?.let { url ->
            return fixUrl(url)
        }
        selectFirst("img[src]")?.attr("src")?.let { url ->
            return fixUrl(url)
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return loadingMutex.withLock {
            try {
                val url = if (page > 1) "${request.data}page/$page/" else request.data
                val document = app.get(url, timeout = 20).document

                val elements = document.select("article a, .boxAN a, .anime-item a, .post a, a[href*='/anime/']")

                val homeItems = elements.mapNotNull { it.toSearchResponse() }
                    .distinctBy { it.url }
                    .take(30)

                newHomePageResponse(request.name, homeItems, false)
            } catch (e: Exception) {
                newHomePageResponse(request.name, emptyList(), false)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()

        val searchUrl = "$mainUrl$SEARCH_PATH${query.trim().replace(" ", "+")}"

        return try {
            val document = app.get(searchUrl, timeout = 20).document

            document.select("article a, .boxAN a, .anime-item a, .post a, a[href*='/anime/']")
                .mapNotNull { it.toSearchResponse() }
                .distinctBy { it.url }
                .take(25)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val document = app.get(url, timeout = 30).document

            val rawTitle = document.selectFirst("h1.text-hidden, h1")?.text()?.trim() ?: "Sem Título"
            val title = cleanTitle(rawTitle)

            val poster = document.selectFirst(".streamer-poster img, .cover")?.attr("src")
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }

            val rawSynopsis = document.selectFirst(".streamer-sinopse")?.text()?.trim()
                ?.replace("ler mais", "")
                ?.trim()
                ?: "Sinopse não disponível."

            val synopsis = cleanSynopsis(rawSynopsis)

            val yearElement = document.selectFirst("li#year")
            val year = yearElement?.text()?.trim()?.toIntOrNull()

            val status = extractGoyabuStatus(document)

            val genres = mutableListOf<String>()
            document.select(".filter-btn.btn-style, a[href*='/generos/']").forEach { element ->
                element.text().trim().takeIf { it.isNotBlank() }?.let { 
                    if (it.length > 1 && !genres.contains(it)) genres.add(it) 
                }
            }

            val scoreElement = document.selectFirst(".rating-total, .rating-score")
            val scoreText = scoreElement?.text()?.trim()
            val score = parseScore(scoreText)

            val isDubbed = rawTitle.contains("dublado", ignoreCase = true) ||
                          document.selectFirst(".audio-box.dublado, .dublado") != null

            val episodes = extractEpisodesFromJavaScript(document, url)
            val fallbackEpisodes = if (episodes.isEmpty()) {
                extractEpisodesFallback(document, url)
            } else {
                emptyList()
            }
            
            val allEpisodes = episodes + fallbackEpisodes
            val sortedEpisodes = allEpisodes.sortedBy { it.episode }

            newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
                this.plot = synopsis
                this.tags = genres
                this.score = score
                this.showStatus = status

                if (sortedEpisodes.isNotEmpty()) {
                    val dubStatus = if (isDubbed) DubStatus.Dubbed else DubStatus.Subbed
                    addEpisodes(dubStatus, sortedEpisodes)
                }
            }

        } catch (e: Exception) {
            newAnimeLoadResponse("Erro", url, TvType.Anime) {
                this.plot = "Erro: ${e.message}"
            }
        }
    }

    private fun extractEpisodesFromJavaScript(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()

        try {
            val scripts = document.select("script")

            for (script in scripts) {
                val scriptContent = script.html()

                if (scriptContent.contains("allEpisodes")) {
                    val arrayContent = extractArrayContent(scriptContent, "allEpisodes")
                    
                    if (arrayContent.isNotBlank()) {
                        val episodeObjects = extractJsonObjects(arrayContent)

                        episodeObjects.forEachIndexed { objIndex, jsonObj ->
                            try {
                                val epNumber = extractValueFromJson(jsonObj, "episodio", "episode", "number")?.toIntOrNull() ?: (objIndex + 1)
                                val epId = extractValueFromJson(jsonObj, "id") ?: ""
                                val epTitle = extractValueFromJson(jsonObj, "title", "name") ?: "Episódio $epNumber"
                                
                                val epUrl = buildEpisodeUrl(epId, epNumber)

                                episodes.add(newEpisode(epUrl) {
                                    this.name = epTitle
                                    this.episode = epNumber
                                    this.season = 1
                                })

                            } catch (e: Exception) {
                                // Ignorar erro
                            }
                        }

                        if (episodes.isNotEmpty()) {
                            return episodes
                        }
                    }
                }
            }

        } catch (e: Exception) {
            // Ignorar erro
        }

        return episodes
    }

    private fun extractEpisodesFallback(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()

        val episodeLinks = document.select("a[href]").filter { 
            val href = it.attr("href")
            href.matches(Regex("""^/\d+/$""")) || href.contains("/episodio/")
        }

        episodeLinks.forEachIndexed { index, link ->
            try {
                val href = link.attr("href").trim()
                if (href.isBlank()) return@forEachIndexed

                val episodeNum = extractEpisodeNumberFromHref(href, index + 1)

                episodes.add(newEpisode(fixUrl(href)) {
                    this.name = "Episódio $episodeNum"
                    this.episode = episodeNum
                    this.season = 1
                })

            } catch (e: Exception) {
                // Ignorar erro
            }
        }

        return episodes
    }

    private fun extractEpisodeNumberFromHref(href: String, default: Int): Int {
        val regex1 = Regex("""/(\d+)/?$""")
        val regex2 = Regex("""/episodio[-_]?(\d+)/?$""", RegexOption.IGNORE_CASE)
        
        regex1.find(href)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        regex2.find(href)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        return default
    }

    private fun extractArrayContent(scriptContent: String, arrayName: String): String {
        val startIndex = scriptContent.indexOf("$arrayName = [")
        if (startIndex == -1) return ""
        
        var braceCount = 0
        var inString = false
        var escapeNext = false
        var i = startIndex + arrayName.length + 3
        
        while (i < scriptContent.length) {
            val char = scriptContent[i]
            
            when {
                escapeNext -> escapeNext = false
                char == '\\' -> escapeNext = true
                char == '"' -> inString = !inString
                !inString && char == '[' -> braceCount++
                !inString && char == ']' -> {
                    braceCount--
                    if (braceCount == 0) {
                        return scriptContent.substring(startIndex + arrayName.length + 3, i)
                    }
                }
            }
            i++
        }
        
        return ""
    }

    private fun extractJsonObjects(jsonArray: String): List<String> {
        val objects = mutableListOf<String>()
        var depth = 0
        var currentObject = StringBuilder()
        var inString = false
        var escapeNext = false

        for (char in jsonArray) {
            when {
                escapeNext -> {
                    currentObject.append(char)
                    escapeNext = false
                }
                char == '\\' -> {
                    currentObject.append(char)
                    escapeNext = true
                }
                char == '"' -> {
                    currentObject.append(char)
                    inString = !inString
                }
                !inString && char == '{' -> {
                    if (depth == 0) {
                        currentObject = StringBuilder("{")
                    } else {
                        currentObject.append(char)
                    }
                    depth++
                }
                !inString && char == '}' -> {
                    depth--
                    currentObject.append(char)
                    if (depth == 0) {
                        objects.add(currentObject.toString())
                    }
                }
                else -> {
                    if (depth > 0) currentObject.append(char)
                }
            }
        }
        
        return objects
    }

    private fun extractValueFromJson(json: String, vararg keys: String): String? {
        for (key in keys) {
            val pattern1 = Regex(""""$key"\s*:\s*"([^"]*)"""")
            val match1 = pattern1.find(json)
            if (match1 != null) return match1.groupValues.getOrNull(1)
            
            val pattern2 = Regex(""""$key"\s*:\s*(\d+)""")
            val match2 = pattern2.find(json)
            if (match2 != null) return match2.groupValues.getOrNull(1)
        }
        return null
    }

    private fun buildEpisodeUrl(idOrPath: String, episodeNumber: Int): String {
        return when {
            idOrPath.matches(Regex("""^\d+$""")) -> "$mainUrl/$idOrPath"
            idOrPath.startsWith("/") -> "$mainUrl$idOrPath"
            idOrPath.startsWith("http") -> idOrPath
            idOrPath.isNotBlank() -> fixUrl(idOrPath)
            else -> "$mainUrl/$episodeNumber"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return GoyabuExtractor.extractVideoLinks(
            url = data,
            name = "Vídeo Goyabu",
            callback = callback
        )
    }
}
