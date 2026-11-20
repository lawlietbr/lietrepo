package com.ultracinetv

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URI

internal class AbyssResolver(private val proxy: AbyssProxy) {
    suspend fun resolve(url: String, parent: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(url, referer = parent).text
        val encoded = Regex("(?:const|var|let)\\s+datas\\s*=\\s*[\"']([^\"']+)").find(html)?.groupValues?.get(1)
            ?: error("Abyss metadata not found")
        val data = JSONObject(String(Base64.decode(encoded, Base64.DEFAULT), Charsets.ISO_8859_1))
        val seed = "${data.get("user_id")}:${data.getString("slug")}:${data.get("md5_id")}"
        val media = if (data.get("media") is JSONObject) data.getJSONObject("media") else JSONObject(
            String(AbyssProtocol.decrypt(data.getString("media").toByteArray(Charsets.ISO_8859_1), seed), Charsets.UTF_8))
        val mp4 = media.getJSONObject("mp4")
        val sources = mp4.getJSONArray("sources")
        val referer = URI(url).let { "${it.scheme}://${it.authority}/" }
        var found = false
        for (i in 0 until sources.length()) {
            val source = sources.getJSONObject(i)
            if (!source.optBoolean("status", false)) continue
            val quality = source.optString("label", "MP4")
            val height = Regex("\\d+").find(quality)?.value?.toIntOrNull() ?: Qualities.Unknown.value
            val path = source.optString("path").trimStart('/')
            val base = source.optString("url").trimEnd('/')
            if (path.isBlank() || base.isBlank()) {
                val sub = source.optString("sub")
                val domains = mp4.getJSONArray("domains")
                val domain = (0 until domains.length()).map { domains.getString(it) }
                    .firstOrNull { sub.isNotBlank() && it.contains(sub) && Regex("[a-zA-Z0-9.-]+").matches(it) } ?: continue
                val size = source.getLong("size")
                val route = "/mp4/${data.get("md5_id")}/${source.get("res_id")}/$size?v=${data.getString("slug")}"
                val native = "https://$domain/sora/$size/${AbyssProtocol.legacyToken(route, size)}"
                callback(newExtractorLink("UltraCineTV", "Abyss • $label • $quality", native, ExtractorLinkType.VIDEO) {
                    this.quality = height; this.referer = referer
                })
                found = true
                continue
            }
            require(URI(base).scheme == "https" && !path.contains(".."))
            val origins = mutableListOf("$base/$path")
            val domains = mp4.optJSONArray("domains")
            if (domains != null) for (j in 0 until domains.length()) {
                val domain = domains.getString(j)
                if (Regex("[a-zA-Z0-9.-]+").matches(domain)) origins += "https://$domain/$path"
            }
            val size = source.getLong("size")
            val partSize = source.optLong("partSize").takeIf { it > 0 } ?: size
            val local = proxy.register(AbyssProxy.Source(origins.distinct(), size, partSize,
                path.substringAfterLast('/'), referer))
            callback(newExtractorLink("UltraCineTV", "Abyss • $label • $quality", local, ExtractorLinkType.VIDEO) {
                this.quality = height
            })
            found = true
        }
        return found
    }
}
