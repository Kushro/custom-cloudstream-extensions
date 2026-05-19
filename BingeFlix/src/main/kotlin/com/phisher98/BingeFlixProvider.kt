package com.phisher98

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class BingeFlixPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(BingeFlixProvider())
    }
}

// BingeFlix uses VidSrc.me as its embed backend (same hydra-site pattern)
class BingeFlixProvider : TmdbHydraBase() {
    override var mainUrl = "https://bingeflix.tv"
    override var name    = "BingeFlix"
    override fun movieEmbedUrl(tmdbId: Int)                          = "https://vidsrc.me/embed/movie?tmdb=$tmdbId"
    override fun tvEmbedUrl(tmdbId: Int, season: Int, episode: Int) = "https://vidsrc.me/embed/tv?tmdb=$tmdbId&season=$season&episode=$episode"
}
