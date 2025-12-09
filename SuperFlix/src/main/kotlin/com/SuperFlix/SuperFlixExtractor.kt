package com.SuperFlix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.fixUrl

object SuperFlixExtractor {

    private const val TIMEOUT_MS = 20_000L

    suspend fun extractVideoLinks(
        url: String, // URL do player (Fembed, FileMoon, etc.)
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("SFX Extractor: Iniciando extração via WebViewResolver para URL: $url")
        
        return try {
            // 1. Configura o WebViewResolver para interceptar links de stream
            val streamResolver = WebViewResolver(
                // Expressão regular para capturar URLs de mídia final (.m3u8 para streams, .mp4 para downloads diretos)
                interceptUrl = Regex("""\.(m3u8|mp4|mkv)"""), 
                useOkhttp = false, // Usa o WebView padrão
                timeout = TIMEOUT_MS // Aumentado para 20s para hosts lentos
            )

            // 2. Navega até a URL do player, permitindo que o JS gere o link tokenizado
            val intercepted = app.get(url, interceptor = streamResolver, timeout = TIMEOUT_MS).url

            println("SFX Extractor: URL Interceptada: $intercepted")

            if (intercepted.isNotEmpty() && intercepted.startsWith("http")) {
                
                // --- DEBUG: Verifica se a interceptação falhou ou é um erro ---
                if (!intercepted.contains("m3u8") && !intercepted.contains("mp4") && !intercepted.contains("mkv")) {
                    println("SFX Extractor DEBUG: Interceptação ocorreu, mas URL não é de mídia. URL: $intercepted")
                    return false 
                }
                
                // 3. Define headers de referência para evitar bloqueio CORS/Referer
                val headers = mapOf(
                    "Referer" to url, // O referer deve ser a URL do player embed
                    "Origin" to mainUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
                
                // --- DEBUG: Processamento da Mídia ---
                if (intercepted.contains(".m3u8")) {
                    println("SFX Extractor DEBUG: URL é M3U8. Gerando links.")
                    // 3A. Se for M3U8, usa o M3u8Helper para obter todas as qualidades do stream
                    M3u8Helper.generateM3u8(
                        name,
                        intercepted,
                        url, 
                        headers = headers
                    ).forEach(callback)
                } else {
                    // 3B. Se for MP4/MKV direto, retorna como ExtractorLink
                    println("SFX Extractor DEBUG: URL é MP4/MKV direto. Retornando link.")
                    val quality = if (intercepted.contains("1080p", true)) Qualities.HD.value else Qualities.Unknown.value
                    
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "SuperFlix (Custom)",
                            url = fixUrl(intercepted),
                            referer = url,
                            quality = quality,
                            isHD = quality >= Qualities.HD.value
                        )
                    )
                }
                
                return true
            } else {
                println("SFX Extractor DEBUG: WebViewResolver falhou em interceptar uma URL válida.")
                false
            }
        } catch (e: Exception) {
            println("SFX Extractor FALHA CRÍTICA: Erro durante a extração customizada: ${e.message}")
            false
        }
    }
}
