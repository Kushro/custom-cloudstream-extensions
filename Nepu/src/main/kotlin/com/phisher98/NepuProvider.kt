package com.phisher98

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element

class NepuProvider : MainAPI() {
    override var mainUrl = "https://nepu.to"
    override var name = "Nepu"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/anime?type=0&status=1&order=added&page=" to "Recent Sub",
        "$mainUrl/anime?type=0&status=1&order=added&dub=1&page=" to "Recent Dub",
        "$mainUrl/anime?type=0&order=popularity&page=" to "Popular Anime",
        "$mainUrl/anime?type=0&order=rating&page=" to "Top Rated",
        "$mainUrl/anime?type=2&order=added&page=" to "Movies",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.film-poster, div.flw-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = selectFirst(".film-name a, .film-detail .film-name a")?.text()?.trim() ?: return null
        val href = fixUrlNull(selectFirst(".film-name a, .film-detail .film-name a, a.film-poster-ahref")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(selectFirst("img.film-poster-img, img.lazyload")?.run {
            attr("data-src").ifEmpty { attr("src") }
        })
        val type = if (href.contains("/movie/")) TvType.AnimeMovie else TvType.Anime
        val epNum = selectFirst(".film-poster-quality")?.text()?.trim()
            ?.removePrefix("Ep ")?.split("/")?.firstOrNull()?.trim()?.toIntOrNull()
        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            addDubStatus(dubExist = false, subExist = true, subEpisodes = epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?keyword=${query.replace(" ", "+")}").document
        return document.select("div.film-poster, div.flw-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h2.film-name, h1.heading-name")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("img.film-poster-img")?.run {
            attr("data-src").ifEmpty { attr("src") }
        })
        val description = document.selectFirst("div.film-description .text")?.text()?.trim()
        val tags = document.select("a[href*='/genre/']").map { it.text() }
        val year = document.selectFirst("a[href*='?year='], span.item-year")?.text()?.trim()?.toIntOrNull()
            ?: Regex("(\\d{4})").find(document.selectFirst(".dp-i-stats, .film-stats")?.text() ?: "")?.value?.toIntOrNull()
        val score = Score.from10(
            document.selectFirst("span.item-imdb")?.text()?.replace(Regex("[^\\d.]"), "")
        )
        val isMovie = url.contains("/movie/")
        val id = Regex("-(\\d+)$").find(url.trimEnd('/'))?.groupValues?.get(1)

        return if (isMovie) {
            val serversDoc = id?.let { app.get("$mainUrl/ajax/movie/episodes/$it", referer = url).document }
            val episodeData = serversDoc?.select("a")?.mapNotNull { it.attr("href").ifEmpty { null } }?.firstOrNull() ?: url
            newMovieLoadResponse(title, url, TvType.AnimeMovie, episodeData) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                this.score = score
            }
        } else {
            val episodes = mutableListOf<Episode>()
            val seasonList = document.select("div.os-list a.os-item")
            if (seasonList.isEmpty()) {
                val epsDoc = id?.let { app.get("$mainUrl/ajax/v2/episode/list/$it", referer = url).document }
                epsDoc?.select("div.eps-item, li.ep-item")?.forEach { ep ->
                    val epNum = ep.attr("data-number").toIntOrNull()
                    val epTitle = ep.selectFirst(".ep-name, .ep-title, h3")?.text()
                    val epLink = fixUrlNull(ep.selectFirst("a")?.attr("href")) ?: return@forEach
                    episodes.add(newEpisode(epLink) { name = epTitle; episode = epNum; season = 1 })
                }
            } else {
                seasonList.forEachIndexed { sIdx, seasonEl ->
                    val seasonId = seasonEl.attr("data-id").takeIf { it.isNotBlank() } ?: return@forEachIndexed
                    app.get("$mainUrl/ajax/v2/season/episodes/$seasonId", referer = url).document
                        .select("div.eps-item, li.ep-item").forEach { ep ->
                            val epNum = ep.attr("data-number").toIntOrNull()
                            val epTitle = ep.selectFirst(".ep-name, .ep-title, h3")?.text()
                            val epLink = fixUrlNull(ep.selectFirst("a")?.attr("href")) ?: return@forEach
                            episodes.add(newEpisode(epLink) { name = epTitle; episode = epNum; season = sIdx + 1 })
                        }
                }
            }
            newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                this.score = score
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val episodeId = document.selectFirst("[data-id]")?.attr("data-id")
            ?: Regex("""data-id="(\d+)"""").find(document.outerHtml())?.groupValues?.get(1)
            ?: data.trimEnd('/').split("/").last().split("?").first()

        val serversDoc = app.get("$mainUrl/ajax/v2/episode/servers/$episodeId", referer = data).document
        serversDoc.select("li.server-item, div.pc-item, li[data-id]").forEach { server ->
            val serverId = server.attr("data-id").takeIf { it.isNotBlank() } ?: return@forEach
            try {
                val sourceJson = tryParseJson<EpisodeSource>(
                    app.get("$mainUrl/ajax/v2/episode/sources/$serverId", referer = data).text
                ) ?: return@forEach
                val link = sourceJson.link ?: return@forEach
                loadExtractor(link, data, subtitleCallback, callback)
            } catch (_: Exception) {}
        }
        return true
    }

    data class EpisodeSource(val link: String?, val type: String?)
}
