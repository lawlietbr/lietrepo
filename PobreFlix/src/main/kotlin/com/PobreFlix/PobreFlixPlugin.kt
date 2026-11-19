package com.PobreFlix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PobreFlixProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PobreFlix())
    }
}
