package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

/**
 * Extractor para Filemoon e Fembed
 * Suporta URLs:
 * - https://filemoon.in/e/{id}
 * - https://fembed.sx/e/{id}
 * - https://fembed.sx/v/{id}
 */
class Filemoon : ExtractorApi() {
    override val name = "Filemoon"
    override val mainUrl = "https://filemoon.in"
    override val requiresReferer = true

 // ESSENCIAL: Diz ao Cloudstream quais URLs este Extractor suporta
     override fun isUrlSupported(url: String): Boolean {
        // Verifica se a URL contém qualquer um dos domínios suportados
        println("FilemoonExtractor: isUrlSupported - Checando URL: $url")
        return url.contains("filemoon.") || url.contains("fembed.") || url.contains("ico3c.")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("FilemoonExtractor: getUrl - INÍCIO")
        println("FilemoonExtractor: URL recebida: $url")
        println("FilemoonExtractor: Referer: $referer")
        
        // Extrair ID do vídeo
        val videoId = extractVideoId(url)
        println("FilemoonExtractor: Video ID extraído: $videoId")
        
        if (videoId.isEmpty()) {
            println("FilemoonExtractor: ERRO: Não consegui extrair ID da URL")
            return
        }
        
        try {
            // Se for URL do fembed, converter para filemoon
            val processedUrl = if (url.contains("fembed.sx")) {
                "https://filemoon.in/e/$videoId"
            } else {
                url
            }
            
            println("FilemoonExtractor: URL processada: $processedUrl")
            
            // Fazer requisição com headers
            val headers = getHeaders(processedUrl, referer)
            println("FilemoonExtractor: Headers usados: ${headers.keys}")
            
            println("FilemoonExtractor: Fazendo requisição GET para $processedUrl")
            val response = app.get(processedUrl, headers = headers)
            println("FilemoonExtractor: Status code: ${response.code}")
            
            if (!response.isSuccessful) {
                println("FilemoonExtractor: ERRO: Requisição falhou com status ${response.code}")
                return
            }
            
            val playerResponse = response.text
            println("FilemoonExtractor: Página carregada (${playerResponse.length} chars)")
            println("FilemoonExtractor: Primeiros 500 chars da resposta:")
            println(playerResponse.take(500))
            
            // Procurar iframe
            val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
            val iframeMatch = iframeRegex.find(playerResponse)
            
            if (iframeMatch != null) {
                var iframeUrl = iframeMatch.groupValues[1]
                println("FilemoonExtractor: Iframe encontrado: $iframeUrl")
                
                // Garantir que a URL do iframe seja completa
                if (!iframeUrl.startsWith("http")) {
                    iframeUrl = "https:$iframeUrl"
                    println("FilemoonExtractor: Iframe URL corrigida: $iframeUrl")
                }
                
                // Acessar o iframe
                println("FilemoonExtractor: Acessando iframe: $iframeUrl")
                val iframeHeaders = getHeaders(iframeUrl, processedUrl)
                val iframeResponse = app.get(iframeUrl, headers = iframeHeaders)
                
                if (!iframeResponse.isSuccessful) {
                    println("FilemoonExtractor: ERRO: Iframe falhou com status ${iframeResponse.code}")
                    return
                }
                
                val iframeHtml = iframeResponse.text
                println("FilemoonExtractor: Iframe carregado (${iframeHtml.length} chars)")
                println("FilemoonExtractor: Primeiros 500 chars do iframe:")
                println(iframeHtml.take(500))
                
                // Procurar URL m3u8
                val m3u8Url = extractM3u8Url(iframeHtml)
                
                if (m3u8Url != null) {
                    println("FilemoonExtractor: M3U8 encontrado: $m3u8Url")
                    
                    // Extrair streams do m3u8
                    println("FilemoonExtractor: Gerando streams M3U8...")
                    val links = M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = m3u8Url,
                        referer = iframeUrl,
                        headers = getHeaders(m3u8Url, iframeUrl)
                    )
                    
                    println("FilemoonExtractor: ${links.size} link(s) gerado(s)")
                    links.forEachIndexed { index, link ->
                        println("FilemoonExtractor: Link $index: ${link.name} - ${link.url.take(100)}...")
                        callback(link)
                    }
                    
                    println("FilemoonExtractor: SUCCESS: Links gerados com sucesso")
                    return
                } else {
                    println("FilemoonExtractor: ERRO: Não encontrou URL M3U8 no iframe")
                }
            } else {
                println("FilemoonExtractor: ERRO: Não encontrou iframe na página")
            }
            
            // Tentar extrair URL m3u8 diretamente da página
            println("FilemoonExtractor: Tentando extrair M3U8 diretamente da página principal")
            val directM3u8 = extractM3u8Url(playerResponse)
            if (directM3u8 != null) {
                println("FilemoonExtractor: M3U8 encontrado diretamente: $directM3u8")
                
                val links = M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = directM3u8,
                    referer = processedUrl,
                    headers = getHeaders(directM3u8, processedUrl)
                )
                
                println("FilemoonExtractor: ${links.size} link(s) gerado(s) diretamente")
                links.forEach(callback)
                return
            }
            
            println("FilemoonExtractor: ERRO FINAL: Não consegui encontrar URL m3u8 em nenhum lugar")
            println("FilemoonExtractor: Conteúdo HTML completo da página principal:")
            println(playerResponse)
            
        } catch (e: Exception) {
            println("FilemoonExtractor: EXCEÇÃO: ${e.message}")
            println("FilemoonExtractor: Stack trace:")
            e.printStackTrace()
        }
    }
    
    private fun extractVideoId(url: String): String {
        println("FilemoonExtractor: extractVideoId - URL: $url")
        
        // Extrair ID de diferentes formatos
        val patterns = listOf(
            Regex("""/e/(\d+)"""),            // /e/1421
            Regex("""/v/([a-zA-Z0-9]+)"""),   // /v/abc123
            Regex("""embed/([a-zA-Z0-9]+)""") // embed/abc123
        )
        
        for ((index, pattern) in patterns.withIndex()) {
            println("FilemoonExtractor: extractVideoId - Testando padrão $index: $pattern")
            val match = pattern.find(url)
            if (match != null && match.groupValues.size > 1) {
                val id = match.groupValues[1]
                println("FilemoonExtractor: extractVideoId - ID encontrado com padrão $index: $id")
                return id
            }
        }
        
        // Fallback: último segmento
        val fallbackId = url.substringAfterLast("/").substringBefore("?").substringBefore("-")
        println("FilemoonExtractor: extractVideoId - Fallback ID: $fallbackId")
        return fallbackId
    }
    
    private fun extractM3u8Url(html: String): String? {
        println("FilemoonExtractor: extractM3u8Url - Analisando HTML (${html.length} chars)")
        
        // Padrões para encontrar URLs m3u8
        val patterns = listOf(
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
            Regex("""src\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
            Regex("""hls\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
            Regex("""(https://[^\s"']+\.m3u8[^\s"']*)""")
        )
        
        for ((index, pattern) in patterns.withIndex()) {
            println("FilemoonExtractor: extractM3u8Url - Testando padrão $index")
            val matches = pattern.findAll(html)
            var matchCount = 0
            for (match in matches) {
                matchCount++
                val url = match.groupValues.getOrNull(1) ?: continue
                println("FilemoonExtractor: extractM3u8Url - Match $matchCount do padrão $index: $url")
                if (url.contains(".m3u8")) {
                    println("FilemoonExtractor: extractM3u8Url - URL M3U8 encontrada com padrão $index")
                    return url
                }
            }
            println("FilemoonExtractor: extractM3u8Url - Padrão $index encontrou $matchCount matches")
        }
        
        println("FilemoonExtractor: extractM3u8Url - Nenhum M3U8 encontrado")
        return null
    }
    
    private fun getHeaders(url: String, referer: String?): Map<String, String> {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Accept-Encoding" to "gzip, deflate, br",
            "Referer" to (referer ?: "https://fembed.sx/"),
            "Origin" to "https://fembed.sx",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Cache-Control" to "max-age=0"
        )
        
        println("FilemoonExtractor: getHeaders - Headers gerados para $url")
        headers.forEach { (key, value) ->
            println("  $key: $value")
        }
        
        return headers
    }
}