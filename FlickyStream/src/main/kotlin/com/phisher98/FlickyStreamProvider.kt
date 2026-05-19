package com.phisher98

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class FlickyStreamPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(FlickyStreamProvider())
    }
}

// FlickyStream uses VidZee (mid.vidzee.wtf) as its embed backend
// Confirmed by network scan showing mid.vidzee.wtf in flickystream.ru connections
class FlickyStreamProvider : TmdbHydraBase() {
    override var mainUrl = "https://flickystream.ru"
    override var name    = "FlickyStream"
    override fun movieEmbedUrl(tmdbId: Int)                          = "https://mid.vidzee.wtf/movie/$tmdbId"
    override fun tvEmbedUrl(tmdbId: Int, season: Int, episode: Int) = "https://mid.vidzee.wtf/tv/$tmdbId/$season/$episode"
}
