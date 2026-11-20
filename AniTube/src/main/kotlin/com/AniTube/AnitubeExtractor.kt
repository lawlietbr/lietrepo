package com.AniTube

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.Jsoup
import java.security.MessageDigest
import java.util.*

object AniTubeVideoExtractor {
    
    private val itagQualityMap = mapOf(
        18 to 360,
        22 to 720,
        37 to 1080,
        59 to 480
    )
    
    private fun getRandomUserAgent(): String {
        return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
    }
    
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val response = app.get(url, headers = mapOf(
                "User-Agent" to getRandomUserAgent(),
                "Referer" to mainUrl
            ))
            
            val doc = Jsoup.parse(response.text)
            
            val abas = doc.select("div.pagEpiAbasItem")
            
            var anySuccess = false
            
            for (aba in abas) {
                val tabName = aba.text().trim()
                val tabTarget = aba.attr("aba-target")
                
                val container = doc.selectFirst("div#$tabTarget") ?: continue
                val iframe = container.selectFirst("iframe.metaframe") ?: continue
                val iframeSrc = iframe.attr("src")
                
                val success = when {
                    iframeSrc.contains("videohls.php") || iframeSrc.contains(".m3u8") -> {
                        extractHlsVideo(iframeSrc, tabName, url, name, callback)
                    }
                    iframeSrc.contains("anitube.zip/") && !iframeSrc.contains("videohls.php") -> {
                        extractFromProxy(iframeSrc, tabName, url, name, callback)
                    }
                    iframeSrc.contains("blogger.com") -> {
                        extractBloggerDirect(iframeSrc, tabName, url, name, callback)
                    }
                    else -> false
                }
                
                if (success) anySuccess = true
            }
            
            anySuccess
            
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun extractHlsVideo(
        iframeSrc: String,
        tabName: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val hlsUrl = if (iframeSrc.contains("videohls.php")) {
                val match = Regex("""[?&]d=([^&]+)""").find(iframeSrc)
                if (match != null) {
                    val encodedUrl = match.groupValues[1]
                    java.net.URLDecoder.decode(encodedUrl, "UTF-8")
                } else {
                    iframeSrc
                }
            } else {
                iframeSrc
            }
            
            val quality = when {
                tabName.contains("FHD", ignoreCase = true) -> 1080
                tabName.contains("HD", ignoreCase = true) -> 720
                tabName.contains("SD", ignoreCase = true) -> 480
                else -> 720
            }
            
            val qualityLabel = when(quality) {
                1080 -> "FHD"
                720 -> "HD"
                480 -> "SD"
                else -> "SD"
            }
            
            val m3u8Links = M3u8Helper.generateM3u8(
                source = "AniTube - $tabName",
                streamUrl = hlsUrl,
                referer = referer,
                headers = mapOf(
                    "Referer" to referer,
                    "User-Agent" to getRandomUserAgent(),
                    "Accept" to "*/*"
                )
            )
            
            m3u8Links.forEach { m3u8Link ->
                callback(
                    newExtractorLink(
                        source = "AniTube",
                        name = "$name ($qualityLabel)",
                        url = m3u8Link.url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer
                        this.quality = quality
                        this.headers = mapOf(
                            "Referer" to referer,
                            "User-Agent" to getRandomUserAgent(),
                            "Accept" to "*/*"
                        )
                    }
                )
            }
            
            true
            
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun extractFromProxy(
        proxyUrl: String,
        tabName: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fullProxyUrl = if (proxyUrl.startsWith("http")) {
            proxyUrl
        } else {
            "https://www.anitube.zip$proxyUrl"
        }
        
        val proxyResponse = app.get(
            fullProxyUrl,
            headers = mapOf(
                "User-Agent" to getRandomUserAgent(),
                "Referer" to referer,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "pt-BR"
            )
        )
        
        val proxyHtml = proxyResponse.text
        val proxyDoc = Jsoup.parse(proxyHtml)
        
        val bloggerIframe = proxyDoc.selectFirst("iframe[src*='blogger.com/video.g']") ?: return false
        val bloggerUrl = bloggerIframe.attr("src")
        
        return extractBloggerDirect(bloggerUrl, tabName, referer, name, callback)
    }
    
    private suspend fun extractBloggerDirect(
        bloggerUrl: String,
        tabName: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val token = extractTokenFromUrl(bloggerUrl) ?: return false
        
        val apiUrl = "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute"
        val reqid = (10000..99999).random()
        val fSid = "-7535563745894756252"
        val bl = "boq_bloggeruiserver_20260223.02_p0"
        
        val headers = mapOf(
            "authority" to "www.blogger.com",
            "accept" to "*/*",
            "content-type" to "application/x-www-form-urlencoded;charset=UTF-8",
            "origin" to "https://www.blogger.com",
            "referer" to "https://www.blogger.com/",
            "user-agent" to getRandomUserAgent(),
            "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "x-client-data" to "COjuygE=",
            "x-same-domain" to "1"
        )
        
        val urlWithParams = "$apiUrl?rpcids=WcwnYd&source-path=%2Fvideo.g&f.sid=$fSid&bl=$bl&hl=pt-BR&_reqid=$reqid&rt=c"
        
        val body = mapOf(
            "f.req" to "%5B%5B%5B%22WcwnYd%22%2C%22%5B%5C%22$token%5C%22%2C%5C%22%5C%22%2C0%5D%22%2Cnull%2C%22generic%22%5D%5D%5D"
        )
        
        val response = app.post(
            url = urlWithParams,
            headers = headers,
            data = body
        )
        
        val videos = extractVideoUrlsFromResponse(response.text)
        
        if (videos.isEmpty()) return false
        
        val timestamp = System.currentTimeMillis()
        
        videos.forEach { (videoUrl, itag) ->
            val quality = itagQualityMap[itag] ?: 480
            val videoId = extractVideoId(videoUrl)
            val cpn = generateCpn(token, videoId, timestamp)
            
            val urlBase = decodeUrl(videoUrl)
            val urlLimpa = urlBase.replace("\\&", "&")
            
            val urlFinal = buildString {
                append(urlLimpa)
                if (urlLimpa.contains("?")) {
                    append("&cpn=$cpn&c=WEB_EMBEDDED_PLAYER&cver=1.20260224.08.00")
                } else {
                    append("?cpn=$cpn&c=WEB_EMBEDDED_PLAYER&cver=1.20260224.08.00")
                }
            }
            
            val qualityLabel = when(quality) {
                1080 -> "FHD"
                720 -> "HD"
                480 -> "SD"
                360 -> "SD"
                else -> "SD"
            }
            
            callback(
                newExtractorLink(
                    source = "AniTube",
                    name = "$name ($qualityLabel)",
                    url = urlFinal,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://youtube.googleapis.com/"
                    this.quality = quality
                    this.headers = mapOf(
                        "Referer" to "https://youtube.googleapis.com/",
                        "User-Agent" to getRandomUserAgent(),
                        "Accept" to "*/*",
                        "Accept-Language" to "pt-BR",
                        "Range" to "bytes=0-",
                        "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
                        "sec-ch-ua-mobile" to "?1",
                        "sec-ch-ua-platform" to "\"Android\"",
                        "sec-fetch-dest" to "video",
                        "sec-fetch-mode" to "no-cors",
                        "sec-fetch-site" to "cross-site",
                        "x-client-data" to "COjuygE="
                    )
                }
            )
        }
        
        return true
    }

    private fun extractVideoUrlsFromResponse(response: String): List<Pair<String, Int>> {
        val videos = mutableListOf<Pair<String, Int>>()
        val jsonData = extractGoogleJson(response)
        
        val urlPattern = """\"((?:https?:\\/\\/)?[^"]+?googlevideo[^"]+?)\",\[(\d+)\]""".toRegex()
        val urlMatches = urlPattern.findAll(jsonData)
        
        for (match in urlMatches) {
            var url = match.groupValues[1]
            val itag = match.groupValues[2].toIntOrNull() ?: 18
            url = decodeUrl(url)
            videos.add(Pair(url, itag))
        }
        
        if (videos.isEmpty()) {
            val urlPattern2 = """https?:\\?/\\?/[^"'\s]+?googlevideo[^"'\s]+""".toRegex()
            val rawMatches = urlPattern2.findAll(jsonData)
            for (match in rawMatches) {
                var url = match.value
                if (!url.startsWith("http")) url = "https://$url"
                url = decodeUrl(url)
                val itag = extractItagFromUrl(url)
                videos.add(Pair(url, itag))
            }
        }
        
        val qualityOrder = listOf(37, 22, 18, 59)
        return videos.distinctBy { it.second }.sortedBy { qualityOrder.indexOf(it.second) }
    }

    private fun extractGoogleJson(response: String): String {
        try {
            var data = response.replace(Regex("""^\)\]\}'\s*\n?"""), "")
            val pattern = """\[\s*\[\s*"wrb\.fr"\s*,\s*"[^"]*"\s*,\s*"(.+?)"\s*\]""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = pattern.find(data)
            if (match != null) {
                var jsonStr = match.groupValues[1]
                jsonStr = jsonStr.replace("\\\"", "\"")
                jsonStr = jsonStr.replace("\\\\", "\\")
                jsonStr = decodeUnicodeEscapes(jsonStr)
                return jsonStr
            }
        } catch (e: Exception) {
        }
        return response
    }

    private fun decodeUnicodeEscapes(text: String): String {
        var result = text
        val pattern = """\\u([0-9a-fA-F]{4})""".toRegex()
        result = pattern.replace(result) { matchResult ->
            val hexCode = matchResult.groupValues[1]
            try {
                hexCode.toInt(16).toChar().toString()
            } catch (e: Exception) {
                "?"
            }
        }
        return result
    }

    private fun decodeUrl(url: String): String {
        var decoded = url
        decoded = decodeUnicodeEscapes(decoded)
        decoded = decoded.replace("\\/", "/")
        decoded = decoded.replace("\\\\", "\\")
        decoded = decoded.replace("\\=", "=")
        decoded = decoded.replace("\\&", "&")
        if (decoded.endsWith("\\")) decoded = decoded.dropLast(1)
        return decoded.trim('"')
    }

    private fun extractItagFromUrl(url: String): Int {
        val patterns = listOf(
            """itag[=?&](\d+)""".toRegex(),
            """itag%3D(\d+)""".toRegex(),
            """itag\\u003d(\d+)""".toRegex()
        )
        for (pattern in patterns) {
            pattern.find(url)?.let {
                return it.groupValues[1].toIntOrNull() ?: 18
            }
        }
        return when {
            "itag=22" in url -> 22
            "itag=18" in url -> 18
            "itag=37" in url -> 37
            "itag=59" in url -> 59
            else -> 18
        }
    }

    private fun extractVideoId(url: String): String {
        val pattern = """id=([a-f0-9]+)""".toRegex()
        return pattern.find(url)?.groupValues?.get(1) ?: "picasacid"
    }

    private fun extractTokenFromUrl(url: String): String? {
        val pattern = """token=([a-zA-Z0-9_\-]+)""".toRegex()
        return pattern.find(url)?.groupValues?.get(1)
    }

    private fun generateCpn(token: String, videoId: String, timestamp: Long): String {
        return try {
            val seed = "boq_bloggeruiserver_20260223.02_p0$videoId$timestamp$token"
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(seed.toByteArray())
            Base64.getEncoder().encodeToString(hash)
                .substring(0, 16)
                .replace("+", "")
                .replace("/", "")
                .replace("=", "")
        } catch (e: Exception) {
            generateRandomString(16)
        }
    }

    private fun generateRandomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { chars.random() }.joinToString("")
    }
}