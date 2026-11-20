package com.ultracinetv

import org.jsoup.nodes.Document
import java.net.URI

internal object UltraCatalog {
    data class Item(val title: String, val url: String, val poster: String?, val series: Boolean)
    data class Episode(val url: String, val season: Int?, val number: Int?, val audio: String)

    fun items(doc: Document): List<Item> = doc.select("article.post").mapNotNull { card ->
        val link = card.selectFirst("a[href*=/filme/], a[href*=/serie/]") ?: return@mapNotNull null
        val title = card.selectFirst(".entry-title")?.text()?.trim().orEmpty()
        if (title.isBlank()) return@mapNotNull null
        val image = card.selectFirst("img")
        val src = image?.attr("data-src")?.takeIf { it.isNotBlank() } ?: image?.attr("src")
        val poster = src?.trim()?.takeIf { it.isNotBlank() }?.let { raw ->
            when {
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("http://") || raw.startsWith("https://") -> raw
                else -> URI(doc.baseUri()).resolve(raw).toString()
            }
        }
        val href = link.absUrl("href")
        Item(title, href, poster, href.contains("/serie/"))
    }.distinctBy { it.url }

    fun episodes(doc: Document, base: String): List<Episode> {
        val seasons = doc.select("[data-season-number]").associate {
            it.attr("data-season-id") to it.attr("data-season-number").toIntOrNull()
        }
        return doc.select("[data-episode-id]").map { row ->
            Episode("$base/episodio/${row.attr("data-episode-id")}",
                seasons[row.attr("data-season-id")],
                Regex("^\\s*(\\d+)").find(row.text())?.groupValues?.get(1)?.toIntOrNull(),
                row.parents().firstOrNull { it.hasClass("card") }?.selectFirst(".card-title")?.text().orEmpty())
        }.distinctBy { it.url }
    }
}
