package com.CineAgora

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

object CineAgoraExtractor {
    private const val BASE_PLAYER = "https://watch.brstream.cc"
    private const val REFERER_CINEAGORA = "https://cineagora.club/"
    private const val PRIMARY_SOURCE = "CineAgora"

    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return when {
            url.contains("watch.brstream.cc/watch?v=") -> {
                extractHlsFromWatchPage(url, name, callback)
            }
            url.contains("watch.brstream.cc/watch/") && !url.contains("?v=") -> {
                extractHlsFromWatchPage(url, name, callback)
            }
            url.contains("cineagora.club") || url.contains("cineagora.net") -> {
                extractFromCineAgoraPage(url, name, callback)
            }
            else -> {
                extractHlsFromWatchPage(url, name, callback)
            }
        }
    }

    private suspend fun extractFromCineAgoraPage(
        cineAgoraUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val html = app.get(cineAgoraUrl, referer = REFERER_CINEAGORA).text

            val moviePatterns = listOf(
                """https://watch\.brstream\.cc/watch\?v=([A-Z0-9]+)""",
                """<iframe[^>]*src=["'](https://watch\.brstream\.cc/watch\?v=[^"']+)["']""",
                """data-link=["'](https://watch\.brstream\.cc/watch\?v=[^"']+)["']""",
                """["'](/watch\?v=([A-Z0-9]+))["']"""
            )
            
            val seriesPatterns = listOf(
                """https://watch\.brstream\.cc/watch/([A-Z0-9]+)""",
                """<iframe[^>]*src=["'](https://watch\.brstream\.cc/watch/[^"']+)["']""",
                """data-link=["'](https://watch\.brstream\.cc/watch/[^"']+)["']""",
                """["'](/watch/([A-Z0-9]+))["']"""
            )
            
            val tvPatterns = listOf(
                """https://watch\.brstream\.cc/tv/([^"'\s?&]+)""",
                """<iframe[^>]*src=["'](https://watch\.brstream\.cc/tv/[^"']+)["']""",
                """data-link=["'](https://watch\.brstream\.cc/tv/[^"']+)["']"""
            )

            for (pattern in moviePatterns) {
                val regex = Regex(pattern)
                val matches = regex.findAll(html).toList()
                
                if (matches.isNotEmpty()) {
                    for (match in matches) {
                        val fullUrl = match.groupValues.getOrNull(1) ?: match.value
                        var watchUrl = fullUrl
                        
                        if (watchUrl.startsWith("/")) {
                            watchUrl = BASE_PLAYER + watchUrl
                        }
                        
                        if (extractHlsFromWatchPage(watchUrl, name, callback)) {
                            return true
                        }
                    }
                }
            }
            
            for (pattern in seriesPatterns) {
                val regex = Regex(pattern)
                val matches = regex.findAll(html).toList()
                
                if (matches.isNotEmpty()) {
                    for (match in matches) {
                        val fullUrl = match.groupValues.getOrNull(1) ?: match.value
                        var watchUrl = fullUrl
                        
                        if (watchUrl.startsWith("/")) {
                            watchUrl = BASE_PLAYER + watchUrl
                        }
                        
                        if (!watchUrl.contains("?")) {
                            watchUrl += "?ref=&d=null"
                        }
                        
                        if (extractHlsFromWatchPage(watchUrl, name, callback)) {
                            return true
                        }
                    }
                }
            }
            
            for (pattern in tvPatterns) {
                val regex = Regex(pattern)
                val matches = regex.findAll(html).toList()
                
                if (matches.isNotEmpty()) {
                    for (match in matches) {
                        val fullUrl = match.groupValues.getOrNull(1) ?: match.value
                        var seriesUrl = fullUrl
                        
                        if (seriesUrl.startsWith("/")) {
                            seriesUrl = BASE_PLAYER + seriesUrl
                        }
                        
                        val videoSlug = extractVideoSlugFromSeriesPage(seriesUrl)
                        if (videoSlug != null) {
                            val watchUrl = "$BASE_PLAYER/watch/$videoSlug?ref=&d=null"
                            
                            if (extractHlsFromWatchPage(watchUrl, name, callback)) {
                                return true
                            }
                        }
                    }
                }
            }

            val fallbackPattern = """https://watch\.brstream\.cc/(watch\?v=|watch/|tv/)([^"'\s<>?&]+)"""
            val allMatches = Regex(fallbackPattern).findAll(html).toList()
            
            if (allMatches.isNotEmpty()) {
                allMatches.forEach { match ->
                    val pathType = match.groupValues[1]
                    val playerUrl = match.value
                    
                    when {
                        pathType.contains("watch?v=") -> {
                            if (extractHlsFromWatchPage(playerUrl, name, callback)) {
                                return true
                            }
                        }
                        pathType.contains("watch/") && !pathType.contains("?v=") -> {
                            val watchUrl = if (!playerUrl.contains("?")) {
                                "$playerUrl?ref=&d=null"
                            } else {
                                playerUrl
                            }
                            if (extractHlsFromWatchPage(watchUrl, name, callback)) {
                                return true
                            }
                        }
                        pathType.contains("tv/") -> {
                            val videoSlug = extractVideoSlugFromSeriesPage(playerUrl)
                            if (videoSlug != null) {
                                val watchUrl = "$BASE_PLAYER/watch/$videoSlug?ref=&d=null"
                                if (extractHlsFromWatchPage(watchUrl, name, callback)) {
                                    return true
                                }
                            }
                        }
                    }
                }
            }

            return false
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private suspend fun extractVideoSlugFromSeriesPage(seriesUrl: String): String? {
        try {
            val html = app.get(seriesUrl, referer = REFERER_CINEAGORA).text
            
            val patterns = listOf(
                """video_slug["']\s*:\s*["']([^"']+)["']""",
                """["']slug["']\s*:\s*["']([^"']+)["']""",
                """/watch/([^"'\s<>/]+)""",
                """data-link=["']([^"']+)["'].*?video_slug""",
                """var\s+video_slug\s*=\s*["']([^"']+)["']""",
                """video_slug\s*=\s*["']([^"']+)["']"""
            )
            
            for (pattern in patterns) {
                val match = Regex(pattern).find(html)
                if (match != null) {
                    val slug = match.groupValues[1]
                    if (slug.isNotBlank() && slug.matches(Regex("^[A-Z0-9]+$"))) {
                        return slug
                    }
                }
            }
            
            return null
        } catch (e: Exception) {
            return null
        }
    }

    private suspend fun extractHlsFromWatchPage(
        watchUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "Accept-Language" to "pt-BR",
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache",
                "Referer" to if (watchUrl.contains("/tv/")) watchUrl else "https://watch.brstream.cc/tv/severance",
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "same-origin",
                "Upgrade-Insecure-Requests" to "1",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
            )
            
            val html = app.get(watchUrl, headers = headers).text

            val videoParams = extractVideoParams(html)
            if (videoParams != null) {
                val masterUrl = "$BASE_PLAYER/m3u8/${videoParams.uid}/${videoParams.md5}/master.txt?s=1&id=${videoParams.id}&cache=${videoParams.status}"
                
                val hlsHeaders = mapOf(
                    "Referer" to watchUrl,
                    "Origin" to BASE_PLAYER,
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                )

                try {
                    val allLinks = M3u8Helper.generateM3u8(
                        source = PRIMARY_SOURCE,
                        streamUrl = masterUrl,
                        referer = watchUrl,
                        headers = hlsHeaders
                    )
                    
                    allLinks.forEach { link ->
                        callback(link)
                    }
                    
                    return true
                    
                } catch (e: Exception) {
                    val fallbackLink = newExtractorLink(
                        source = PRIMARY_SOURCE,
                        name = name,
                        url = masterUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = watchUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = hlsHeaders
                    }
                    callback(fallbackLink)
                    
                    return true
                }
            }

            val m3u8Url = extractM3u8UrlDirect(html)
            if (m3u8Url != null) {
                val hlsHeaders = mapOf(
                    "Referer" to watchUrl,
                    "Origin" to BASE_PLAYER
                )

                val directLink = newExtractorLink(
                    source = PRIMARY_SOURCE,
                    name = name,
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = watchUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = hlsHeaders
                }
                callback(directLink)
                return true
            }

            return false
            
        } catch (e: Exception) {
            return false
        }
    }

    private fun extractVideoParams(html: String): VideoParams? {
        val videoPattern = """var\s+video\s*=\s*\{[^}]+\}"""
        val videoMatch = Regex(videoPattern, RegexOption.DOT_MATCHES_ALL).find(html)
        
        if (videoMatch != null) {
            val videoJson = videoMatch.value
            
            val uid = extractFromRegex(videoJson, """"uid"\s*:\s*"([^"]+)"""")
            val md5 = extractFromRegex(videoJson, """"md5"\s*:\s*"([^"]+)"""")
            val id = extractFromRegex(videoJson, """"id"\s*:\s*"([^"]+)"""")
            val status = extractFromRegex(videoJson, """"status"\s*:\s*"([^"]+)"""") ?: "1"
            
            if (uid != null && md5 != null && id != null) {
                return VideoParams(uid, md5, id, status)
            }
        }
        
        val uid = extractFromRegex(html, """"uid"\s*:\s*"([^"]+)"""")
        val md5 = extractFromRegex(html, """"md5"\s*:\s*"([^"]+)"""")
        val id = extractFromRegex(html, """"id"\s*:\s*"([^"]+)"""")
        val status = extractFromRegex(html, """"status"\s*:\s*"([^"]+)"""") ?: "1"
        
        if (uid != null && md5 != null && id != null) {
            return VideoParams(uid, md5, id, status)
        }
        
        val configPattern = """jwplayer\('[^']+'\)\.setup\(([\s\S]*?)\);"""
        val configMatch = Regex(configPattern).find(html)
        
        if (configMatch != null) {
            val configText = configMatch.groupValues[1]
            
            val videoInConfig = extractFromRegex(configText, """video"\s*:\s*\{([^}]+)\}""")
            if (videoInConfig != null) {
                val uid2 = extractFromRegex(videoInConfig, """"uid"\s*:\s*"([^"]+)"""")
                val md5_2 = extractFromRegex(videoInConfig, """"md5"\s*:\s*"([^"]+)"""")
                val id2 = extractFromRegex(videoInConfig, """"id"\s*:\s*"([^"]+)"""")
                val status2 = extractFromRegex(videoInConfig, """"status"\s*:\s*"([^"]+)"""") ?: "1"
                
                if (uid2 != null && md5_2 != null && id2 != null) {
                    return VideoParams(uid2, md5_2, id2, status2)
                }
            }
        }
        
        return null
    }

    private fun extractFromRegex(text: String, pattern: String): String? {
        val regex = Regex(pattern)
        val match = regex.find(text)
        return match?.groupValues?.getOrNull(1)
    }

    private fun extractM3u8UrlDirect(html: String): String? {
        val patterns = listOf(
            """file\s*:\s*["']/m3u8/([^/]+)/([^/]+)/master\.txt\?s=1&id=([^&]+)&cache=([^"']+)["']""",
            """"file"\s*:\s*["']([^"']+/m3u8/[^"']+\.txt[^"']*)["']""",
            """sources\s*:\s*\[\{.*?"file"\s*:\s*["']([^"']+\.txt[^"']*)["']""",
            """master\.txt\?s=1&id=\d+&cache=\d+""",
            """["'](https?://[^"']+\.m3u8[^"']*)["']""",
            """["'](/m3u8/[^"']+\.txt[^"']*)["']"""
        )
        
        for (pattern in patterns) {
            val regex = Regex(pattern, RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(html)
            if (match != null) {
                var url = match.groupValues.getOrNull(1) ?: match.value
                
                if (url.startsWith("/m3u8/") && !url.startsWith("//")) {
                    url = BASE_PLAYER + url
                }
                
                return url
            }
        }
        
        return null
    }

    data class VideoParams(
        val uid: String,
        val md5: String,
        val id: String,
        val status: String
    )
}