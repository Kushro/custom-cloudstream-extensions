package com.phisher98

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class HydraHDPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(HydraHDProvider())
    }
}

// HydraHD uses VidSrc as its embed backend
// Confirmed by MPA submission: "Most hydra sites depend heavily on Vidsrc for VOD"
class HydraHDProvider : TmdbHydraBase() {
    override var mainUrl = "https://hydrahd.ru"
    override var name    = "HydraHD"
    override fun movieEmbedUrl(tmdbId: Int)                          = "https://vidsrc.cc/v2/embed/movie/$tmdbId"
    override fun tvEmbedUrl(tmdbId: Int, season: Int, episode: Int) = "https://vidsrc.cc/v2/embed/tv/$tmdbId/$season/$episode"
}
