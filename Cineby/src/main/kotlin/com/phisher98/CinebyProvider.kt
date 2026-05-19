package com.phisher98

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CinebyPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(CinebyProvider())
    }
}

// Cineby uses its own VidApi player at em.cineby.qzz.io
// Confirmed from https://em.cineby.qzz.io which documents:
//   /movie/{tmdbId}  and  /tv/{tmdbId}/{season}/{episode}
class CinebyProvider : TmdbHydraBase() {
    override var mainUrl = "https://cineby.sc"
    override var name    = "Cineby"
    override fun movieEmbedUrl(tmdbId: Int)                          = "https://em.cineby.qzz.io/movie/$tmdbId"
    override fun tvEmbedUrl(tmdbId: Int, season: Int, episode: Int) = "https://em.cineby.qzz.io/tv/$tmdbId/$season/$episode"
}
