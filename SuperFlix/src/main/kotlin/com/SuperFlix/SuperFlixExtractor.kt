package com.SuperFlix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

object SuperFlixExtractor {

    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔗 SuperFlixExtractor: Extraindo de $url")
            
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""\.(m3u8|mp4|mkv)"""),
                useOkhttp = false,
                timeout = 15_000L
            )

            val response = app.get(url, interceptor = streamResolver)
            val intercepted = response.url

            println("🌐 SuperFlixExtractor: URL interceptada: $intercepted")

            if (intercepted.isNotEmpty() && intercepted.contains(".m3u8")) {
                val headers = mapOf(
                    "Referer" to url,
                    "Origin" to mainUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )

                println("✅ SuperFlixExtractor: Gerando links M3U8")
                M3u8Helper.generateM3u8(
                    name,
                    intercepted,
                    mainUrl,
                    headers = headers
                ).forEach(callback)

                true
            } else {
                println("❌ SuperFlixExtractor: Nenhum link M3U8 encontrado")
                false
            }
        } catch (e: Exception) {
            println("💥 SuperFlixExtractor: Erro - ${e.message}")
            false
        }
    }
}