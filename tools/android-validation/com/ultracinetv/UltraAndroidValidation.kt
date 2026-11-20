package com.ultracinetv

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import kotlinx.coroutines.*
import org.json.JSONObject
import java.lang.reflect.Proxy

internal object UltraAndroidValidation {
    fun start(context: Context, api: UltraCineTV) {
        val activity = context as? Activity
        if (activity == null) { Log.i("UltraValidation", "NO_ACTIVITY ${context.javaClass.simpleName}"); return }
        CoroutineScope(Dispatchers.Main).launch {
            delay(3000)
            val surface = SurfaceView(activity)
            activity.addContentView(surface, FrameLayout.LayoutParams(960, 540))
            surface.keepScreenOn = true
            val cache = mutableMapOf<String, List<ExtractorLink>>()
            try {
                val report = withContext(Dispatchers.IO) {
                    val home = api.getMainPage(1, MainPageRequest("Filmes", "https://www.ultracinetv.lat/filmes/", false))
                    val count = home.items.sumOf { it.list.size }
                    require(count > 0) { "EMPTY_HOME" }
                    val search = api.search("Vingadores")
                    require(search.any { it.name.contains("Ultimato", true) }) { "SEARCH_MISSED_TITLE" }
                    val film = api.load("https://www.ultracinetv.lat/filme/assistir-vingadores-ultimato-online/")
                    require(film.name.contains("Ultimato", true))
                    val series = api.load("https://www.ultracinetv.lat/serie/assistir-o-senhor-dos-aneis-os-aneis-de-poder-online/") as TvSeriesLoadResponse
                    require(series.episodes.size >= 16) { "MISSING_EPISODES" }
                    JSONObject().put("homeItems", count).put("searchItems", search.size)
                        .put("episodes", series.episodes.size).put("seasons", series.episodes.mapNotNull { it.season }.distinct().size)
                }
                Log.i("UltraValidation", "CATALOG_PASS $report")
            } catch(e: Throwable) { Log.i("UltraValidation", "CATALOG_FAIL ${e.javaClass.simpleName} ${e.message?.take(120)}") }
            val cases = listOf(
                Triple("movie-abyss-480", "https://www.painel-aso.sbs/filme/tt4154796", "Abyss"),
                Triple("movie-byse", "https://www.painel-aso.sbs/filme/tt4154796", "Byse"),
                Triple("movie-abyss-720", "https://www.painel-aso.sbs/filme/tt4154796", "Abyss"),
                Triple("movie-abyss-1080", "https://www.painel-aso.sbs/filme/tt4154796", "Abyss"),
                Triple("episode-1-abyss", "https://www.painel-aso.sbs/episodio/184695", "Abyss"),
                Triple("episode-2-abyss", "https://www.painel-aso.sbs/episodio/184697", "Abyss"),
                Triple("episode-3-abyss", "https://www.painel-aso.sbs/episodio/410892", "Abyss"),
                Triple("episode-3-byse", "https://www.painel-aso.sbs/episodio/410892", "Byse"),
                Triple("episode-3-2-byse", "https://www.painel-aso.sbs/episodio/410893", "Byse")
            )
            for ((id, panel, host) in cases) {
                Log.i("UltraValidation", "START $id")
                var player: ExoPlayer? = null
                try {
                    val links = cache[panel] ?: withContext(Dispatchers.IO) {
                        val result = mutableListOf<ExtractorLink>()
                        api.loadLinks(panel, false, {}, { result += it })
                        result.toList()
                    }.also { cache[panel] = it }
                    val matches = links.filter { it.name.startsWith(host) }
                    val targetQuality = if (id.endsWith("720")) 720 else if (id.endsWith("1080")) 1080 else null
                    val link = if (targetQuality != null) matches.firstOrNull { it.quality == targetQuality }
                        else matches.minByOrNull { it.quality }
                    require(link != null) { "HOST_NOT_AVAILABLE" }
                    Log.i("UltraValidation", "RESOLVED $id quality=${link.quality} links=${matches.size}")
                    val headers = link.headers + if (link.referer.isBlank()) emptyMap() else mapOf("Referer" to link.referer)
                    val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
                        .setConnectTimeoutMs(15000).setReadTimeoutMs(20000).setAllowCrossProtocolRedirects(true)
                    val media = DefaultMediaSourceFactory(context).setDataSourceFactory(http)
                    val p = ExoPlayer.Builder(context).setMediaSourceFactory(media).build()
                    player = p
                    var playbackError: String? = null
                    val listener = Proxy.newProxyInstance(Player.Listener::class.java.classLoader,
                        arrayOf(Player.Listener::class.java)) { instance, method, args ->
                        when (method.name) {
                            "equals" -> instance === args?.get(0)
                            "hashCode" -> System.identityHashCode(instance)
                            "toString" -> "UltraValidationListener"
                            else -> { if (method.name == "onPlayerError") playbackError = (args?.get(0) as? PlaybackException)?.errorCodeName; null }
                        }
                    } as Player.Listener
                    p.addListener(listener)
                    p.setVideoSurfaceView(surface); p.volume = 0f
                    p.setMediaItem(MediaItem.fromUri(link.url)); p.prepare(); p.play()
                    suspend fun waitForVideo(target: Long) {
                        withTimeout(50000) {
                            var baselineFrames: Int? = null
                            var baselinePosition = 0L
                            while (true) {
                                playbackError?.let { error(it) }
                                val frames = p.videoDecoderCounters?.renderedOutputBufferCount ?: 0
                                if (p.currentPosition >= target && frames > 0 && p.playbackState == Player.STATE_READY) {
                                    if (baselineFrames == null) { baselineFrames = frames; baselinePosition = p.currentPosition }
                                    if (p.currentPosition >= baselinePosition + 2000 && frames >= baselineFrames + 10) break
                                }
                                delay(250)
                            }
                        }
                    }
                    waitForVideo(0)
                    val initialFrames = p.videoDecoderCounters?.renderedOutputBufferCount ?: 0
                    val seek = if (id == "movie-abyss-480") 6_500_000L else 60_000L
                    p.seekTo(seek); waitForVideo(seek)
                    Log.i("UltraValidation", "PASS " + JSONObject().put("case", id).put("quality", link.quality)
                        .put("durationMs", p.duration).put("seekMs", seek).put("positionMs", p.currentPosition)
                        .put("initialFrames", initialFrames).put("frames", p.videoDecoderCounters?.renderedOutputBufferCount)
                        .put("width", p.videoFormat?.width).put("height", p.videoFormat?.height)
                        .put("audioBuffers", p.audioDecoderCounters?.renderedOutputBufferCount).toString())
                } catch (e: Throwable) {
                    Log.i("UltraValidation", "FAIL $id ${e.javaClass.simpleName} ${e.message?.replace(Regex("https?://[^\\s]+"), "[url]")?.take(160)}")
                } finally { player?.release() }
            }
            (surface.parent as? ViewGroup)?.removeView(surface)
            Log.i("UltraValidation", "FINISHED")
        }
    }
}
