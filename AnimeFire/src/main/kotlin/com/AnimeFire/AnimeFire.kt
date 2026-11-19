package com.AnimeFire

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.one"
    override var name = "AnimeFire"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)
    override val usesWebView = false

    companion object {
        private const val API = "https://api.animefire.one"

        private val API_HEADERS = mapOf(
            "Accept" to "application/json",
            "Referer" to "https://animefire.one/",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
        )

        private fun genre(name: String): String =
            "https://api.animefire.one/animes?genre=${URLEncoder.encode(name, "UTF-8")}"
    }

    override val mainPage = mainPageOf(
        "$API/home" to "Destaques",
        "$API/animes/lancamentos" to "Lançamentos",
        "$API/animes" to "Animes",
        "$API/animes/filmes" to "Filmes",
        "$API/animes/em-breve" to "Em breve",
        genre("Ação") to "Ação",
        genre("Comédia") to "Comédia",
        genre("Terror") to "Terror",
        genre("Romance") to "Romance",
        genre("Isekai") to "Isekai",
        genre("Aventura") to "Aventura"
    )

    private fun titleOf(o: JSONObject): String {
        val t = o.optJSONObject("titles")
        return when {
            t != null && t.has("BR") -> t.getString("BR")
            t != null && t.has("US") -> t.getString("US")
            t != null -> t.optString("JP", "Sem título")
            else -> o.optString("title", "Sem título")
        }
    }

    private fun yearOf(o: JSONObject): Int? =
        o.optString("published_at", "").take(4).toIntOrNull()

    private fun cardToResponse(o: JSONObject): SearchResponse? {
        val id = o.optString("id")
        if (id.isBlank()) return null
        val title = titleOf(o)
        val poster = o.optString("poster_src").ifBlank { null }
        val isDub = o.optString("audio", "").contains("Dublado", true)
        return newAnimeSearchResponse(title, "$mainUrl/anime/$id") {
            this.posterUrl = poster
            this.year = yearOf(o)
            this.type = TvType.Anime
            addDubStatus(dubExist = isDub, subExist = true)
        }
    }

    private fun cardsOf(arr: JSONArray?): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            cardToResponse(o)?.let { out.add(it) }
        }
        return out
    }

    private fun hasNextOf(json: JSONObject): Boolean {
        val meta = json.optJSONObject("meta") ?: return false
        return meta.optInt("current_page", 1) < meta.optInt("last_page", 1)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val sep = if (request.data.contains("?")) "&" else "?"
            val url = if (page > 1) "${request.data}${sep}page=$page" else request.data
            val json = JSONObject(app.get(url, headers = API_HEADERS).text)
            val items = mutableListOf<SearchResponse>()

            if (request.data.endsWith("/home")) {
                val d = json.getJSONObject("data")
                items.addAll(cardsOf(d.optJSONObject("hero")?.optJSONArray("items")))
                val carousels = d.optJSONArray("carousels")
                if (carousels != null) {
                    for (i in 0 until carousels.length()) {
                        items.addAll(cardsOf(carousels.optJSONObject(i)?.optJSONArray("items")))
                    }
                }
            } else if (request.data.endsWith("/animes/lancamentos")) {
                val d = json.getJSONObject("data")
                items.addAll(cardsOf(d.optJSONArray("animes")))
                items.addAll(cardsOf(d.optJSONObject("upcoming")?.optJSONArray("items")))
            } else {
                items.addAll(cardsOf(json.optJSONArray("data")))
            }

            newHomePageResponse(request.name, items.distinctBy { it.url }, hasNext = hasNextOf(json))
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        return try {
            val enc = URLEncoder.encode(query.trim(), "UTF-8")
            val json = JSONObject(app.get("$API/animes/pesquisar?q=$enc", headers = API_HEADERS).text)
            cardsOf(json.optJSONArray("data"))
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (query.length < 2) return null
        return try {
            val enc = URLEncoder.encode(query.trim(), "UTF-8")
            val json = JSONObject(app.get("$API/animes/pesquisar?q=$enc&page=$page", headers = API_HEADERS).text)
            cardsOf(json.optJSONArray("data")).toNewSearchResponseList(hasNextOf(json))
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfterLast("/anime/").substringBefore("?").substringBefore("#")
        if (id.isBlank()) return null
        return try {
            val data = JSONObject(app.get("$API/anime/$id", headers = API_HEADERS).text)
                .getJSONObject("data")
            val hero = data.getJSONObject("hero")
            val title = titleOf(hero)

            val dubEps = mutableListOf<Episode>()
            val subEps = mutableListOf<Episode>()
            val epsArr = data.optJSONArray("episodes")
            if (epsArr != null) {
                for (i in 0 until epsArr.length()) {
                    val e = epsArr.optJSONObject(i) ?: continue
                    val epId = e.optString("id")
                    if (epId.isBlank()) continue
                    val ep = newEpisode("$mainUrl/episode/$epId") {
                        this.name = e.optString("title").ifBlank { null }
                        this.episode = e.optInt("number", 0).takeIf { it > 0 }
                        this.season = e.optInt("season", 1).takeIf { it > 0 }
                        this.posterUrl = e.optString("still_src").ifBlank { null }
                    }
                    if (e.optString("audio", "").contains("dublado", true)) {
                        dubEps.add(ep)
                    } else {
                        subEps.add(ep)
                    }
                }
            }

            newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = hero.optString("poster_src").ifBlank { null }
                this.backgroundPosterUrl = hero.optString("backdrop_src").ifBlank { null }
                this.plot = hero.optString("synopsis").ifBlank { null }
                this.year = yearOf(hero)
                val sc = hero.optDouble("score", 0.0)
                if (sc > 0) this.score = Score.from10(sc.toFloat())
                val genresArr = hero.optJSONArray("genres")
                if (genresArr != null && genresArr.length() > 0) {
                    this.tags = (0 until genresArr.length()).map { genresArr.getString(it) }
                }
                this.showStatus = when (hero.optString("status")) {
                    "completed" -> ShowStatus.Completed
                    "airing" -> ShowStatus.Ongoing
                    else -> null
                }
                if (dubEps.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEps)
                if (subEps.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEps)

                val recs = mutableListOf<SearchResponse>()
                recs.addAll(cardsOf(data.optJSONObject("relations")?.optJSONArray("items")))
                recs.addAll(cardsOf(data.optJSONObject("recommendations")?.optJSONArray("items")))
                if (recs.isNotEmpty()) this.recommendations = recs.distinctBy { it.url }
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = data.substringAfterLast("/episode/").substringBefore("?").substringBefore("#")
        if (id.isBlank()) return false
        return try {
            val json = JSONObject(app.get("$API/episode/$id", headers = API_HEADERS).text)
                .getJSONObject("data")
            var found = false
            val streams = json.optJSONArray("streams")
            if (streams != null) {
                for (i in 0 until streams.length()) {
                    val s = streams.optJSONObject(i) ?: continue
                    val streamUrl = s.optString("url")
                    if (streamUrl.isBlank()) continue
                    if (s.optBoolean("is_mtl") || s.optBoolean("is_offline")) continue

                    val isDub = s.optString("audio", "legendado").contains("dublado", true)
                    val label = if (isDub) "Dublado" else "Legendado"
                    val qualities = s.optString("qualities", "")
                    val height = Regex("\"(\\d{3,4})p\"").findAll(qualities)
                        .map { it.groupValues[1].toInt() }.maxOrNull()

                    callback.invoke(
                        newExtractorLink(
                            source = "AnimeFire",
                            name = "AnimeFire $label" + (height?.let { " (${it}p)" } ?: ""),
                            url = streamUrl,
                            type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.quality = height ?: 0
                            this.referer = "https://animefire.one/"
                        }
                    )
                    found = true
                }
            }
            found
        } catch (e: Exception) {
            false
        }
    }
}
