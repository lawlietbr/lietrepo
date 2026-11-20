package com.ultracinetv

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import java.net.URI
import java.net.URLEncoder

@CloudstreamPlugin
class UltraCineTVPlugin : Plugin() {
    private var http: AbyssHttp? = null
    private var proxy: AbyssProxy? = null
    override fun load(context: Context) {
        val upstream = AbyssHttp()
        val server = AbyssProxy(upstream::fetch)
        http = upstream; proxy = server
        val api = UltraCineTV(server)
        registerMainAPI(api)
    }
    override fun beforeUnload() { proxy?.close(); http?.close(); proxy = null; http = null }
}

class UltraCineTV internal constructor(private val proxy: AbyssProxy) : MainAPI() {
    override var mainUrl = "https://www.ultracinetv.lat"
    override var name = "UltraCineTV"
    override var lang = "pt-br"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val mainPage = mainPageOf("$mainUrl/filmes/" to "Filmes", "$mainUrl/series/" to "Séries")

    private fun UltraCatalog.Item.response(): SearchResponse = if (series) {
        newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = poster }
    } else newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url).document
        val next = doc.select("a[href]").any {
            it.absUrl("href").trimEnd('/') == "${request.data}page/${page + 1}".trimEnd('/')
        }
        return newHomePageResponse(request.name, UltraCatalog.items(doc).map { it.response() }, next)
    }

    override suspend fun search(query: String): List<SearchResponse> = UltraCatalog.items(
        app.get("$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}").document
    ).map { it.response() }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.entry-title")?.text() ?: throw ErrorLoadingException("Título não encontrado")
        val panel = doc.selectFirst("iframe[src*=painel-aso]")?.absUrl("src")
            ?: throw ErrorLoadingException("Player não encontrado")
        val poster = doc.selectFirst("article.single img")?.let { img ->
            val raw = img.attr("data-src").ifBlank { img.attr("src") }.trim()
            when {
                raw.startsWith("//") -> "https:$raw"
                raw.isNotBlank() -> fixUrl(raw)
                else -> null
            }
        }
        val plot = doc.selectFirst(".description")?.text()
        if (url.contains("/serie/")) {
            val base = URI(panel).let { "${it.scheme}://${it.authority}" }
            val episodes = UltraCatalog.episodes(app.get(panel, referer = url).document, base).map { ep ->
                newEpisode(ep.url) {
                    season = ep.season; episode = ep.number
                    name = "Episódio ${ep.number ?: ""} (${ep.audio})"
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { posterUrl = poster; this.plot = plot }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, panel) { posterUrl = poster; this.plot = plot }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
                                   callback: (ExtractorLink) -> Unit): Boolean {
        val doc = app.get(data, referer = mainUrl).document
        var found = false
        for (button in doc.select("[data-source]")) {
            val source = button.attr("data-source")
            val host = runCatching { URI(source).host }.getOrNull().orEmpty()
            try {
                val resolved = when {
                    host.contains("byse") -> ByseResolver().resolve(source, data, button.text(), subtitleCallback, callback)
                    (host == "abyssplayer.com" || host.endsWith(".abyssplayer.com")) && !isCasting ->
                        AbyssResolver(proxy).resolve(source, data, button.text(), callback)
                    else -> false
                }
                found = resolved || found
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                android.util.Log.w("UltraCineTV", "$host: ${e.javaClass.simpleName}: ${e.message?.replace(Regex("https?://[^\\s]+"), "[url]")?.take(160)}")
            }
        }
        if (!found) throw ErrorLoadingException("Abyss/Byse não liberaram vídeo; o servidor pode estar indisponível ou ter mudado")
        return true
    }
}
