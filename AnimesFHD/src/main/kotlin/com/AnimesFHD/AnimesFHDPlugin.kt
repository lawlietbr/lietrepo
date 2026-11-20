package com.AnimesFHD

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimesFHDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimesFHD())
    }
}
