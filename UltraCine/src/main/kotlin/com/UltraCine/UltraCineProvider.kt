package com.UltraCine

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

// 1. IMPORTAÇÃO EXPLÍCITA DAS CLASSES DE EXTRATORAS
// O compilador precisa ver onde essas classes estão definidas.
// Como estão no mesmo pacote (com.UltraCine), esta é a sintaxe correta.
import com.UltraCine.PlayEmbedApiSite
import com.UltraCine.EmbedPlayExtractor

@CloudstreamPlugin
class UltraCineProvider : BasePlugin() {
    override fun load() {
        // 2. REGISTRO DO API PRINCIPAL
        registerMainAPI(UltraCine()) 
        
        // 3. REGISTRO DOS EXTRATORES
        registerExtractorAPI(PlayEmbedApiSite())
        registerExtractorAPI(EmbedPlayExtractor()) 
    }
}
