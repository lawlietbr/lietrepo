package com.SuperFlix

import android.content.Context
import com.lagradost.cloudstream3.extractors.Filemoon
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SuperFlixProviderPlugin: Plugin() {
    override fun load(context: Context) {
        // Registrar o provider principal
        registerMainAPI(SuperFlix())
        
        // Registrar o extractor do Filemoon
        registerExtractorAPI(Filemoon())
    }
}