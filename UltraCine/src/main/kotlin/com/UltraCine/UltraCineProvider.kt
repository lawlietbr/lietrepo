package com.UltraCine

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

// IMPORTS CORRIGIDAS:
// Importa o extrator do subpacote 'extractors' (PlayEmbedApiSite)
import com.UltraCine.extractors.PlayEmbedApiSite

// Importa os novos extratores que estão no PACOTE PRINCIPAL (com.UltraCine)
import com.UltraCine.EmbedPlayUpnsInk
import com.UltraCine.EmbedPlayUpnsPro
import com.UltraCine.EmbedPlayUpnOne


@CloudstreamPlugin
class UltraCineProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(UltraCine()) 
        
        // REGISTRA OS EXTRATORES CONFIRMADOS PELO DOMÍNIO ULTRACINE:
        registerExtractorAPI(PlayEmbedApiSite()) // Para 'playembedapi.site'
        registerExtractorAPI(EmbedPlayUpnsInk()) // Para 'embedplay.upns.ink'
        
        // MANTENHA ESTES CASO SEJAM NECESSÁRIOS:
        // registerExtractorAPI(EmbedPlayUpnsPro())
        // registerExtractorAPI(EmbedPlayUpnOne())
    }
}
