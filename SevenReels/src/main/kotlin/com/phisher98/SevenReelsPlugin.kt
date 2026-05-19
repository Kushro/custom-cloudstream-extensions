package com.phisher98

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class SevenReelsPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(SevenReelsProvider())
    }
}
