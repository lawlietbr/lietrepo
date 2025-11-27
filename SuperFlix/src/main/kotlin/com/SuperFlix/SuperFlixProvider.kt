package com.SuperFlix

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import android.content.Context // <--- ESTE IMPORT É ESSENCIAL E DEVE ESTAR AQUI!

@CloudstreamPlugin
class SuperFlixProvider : BasePlugin() {
    // A assinatura desta função deve ser exatamente assim para fazer o 'override'
    override fun load(context: Context) { 
        registerMainAPI(SuperFlix())
    }
}
