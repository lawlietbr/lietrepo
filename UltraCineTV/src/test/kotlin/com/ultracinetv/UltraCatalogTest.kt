package com.ultracinetv

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class UltraCatalogTest {
    @Test fun resolvesLazyPosterAndSeriesType() {
        val doc = Jsoup.parse("""<article class="post"><h2 class="entry-title">Série</h2><img data-src="/poster.jpg"><a href="/serie/test/"></a></article>""", "https://www.ultracinetv.lat/")
        val item = UltraCatalog.items(doc).single()
        assertEquals("Série", item.title)
        assertEquals("https://www.ultracinetv.lat/poster.jpg", item.poster)
        assertTrue(item.series)
    }

    @Test fun includesClosedSeasonsAndPreservesAudio() {
        val doc = Jsoup.parse("""<li data-season-id="a" data-season-number="2"></li><div class="card"><h2 class="card-title">Legendado</h2><li class="d-none" data-season-id="a" data-episode-id="42"><a>3 - Episódio</a></li></div>""")
        val ep = UltraCatalog.episodes(doc, "https://www.painel-aso.sbs").single()
        assertEquals(2, ep.season)
        assertEquals(3, ep.number)
        assertEquals("Legendado", ep.audio)
        assertEquals("https://www.painel-aso.sbs/episodio/42", ep.url)
    }
}
