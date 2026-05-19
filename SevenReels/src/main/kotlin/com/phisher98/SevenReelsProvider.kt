package com.phisher98

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element

class SevenReelsProvider : MainAPI() {
    override var mainUrl = "https://7reels.cc"
    override var name = "7Reels"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movies?page=" to "Movies",
        "$mainUrl/tv-shows?page=" to "TV Shows",
        "$mainUrl/anime?page=" to "Anime",
        "$mainUrl/trending?page=" to "Trending",
        "$mainUrl/top-rated?page=" to "Top Rated",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.film-poster, div.flw-item, article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".film-name a, .film-detail .film-name a, h3.title a, .item-name a")?.text()?.trim() ?: return null
        val href = fixUrlNull(selectFirst(".film-name a, .film-detail .film-name a, h3.title a, .item-name a, a.film-poster-ahref")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(selectFirst("img.film-poster-img, img.lazyload, img")?.run {
            attr("data-src").ifEmpty { attr("src") }
        })
        return when {
            href.contains("/movie/") -> newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
            href.contains("/anime/") -> newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl }
            else -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?keyword=${query.replace(" ", "+")}").document
        return document.select("div.film-poster, div.flw-item, article.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h2.film-name, h1.heading-name, h1.title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("img.film-poster-img, div.dp-i-c-poster img")?.run {
            attr("data-src").ifEmpty { attr("src") }
        })
        val description = document.selectFirst("div.film-description .text, div.dp-i-c-desc p")?.text()?.trim()
        val tags = document.select("a[href*='/genre/'], a[href*='genre=']").map { it.text() }
        val year = document.selectFirst("a[href*='year='], span.item-year, .release-year")?.text()?.trim()?.toIntOrNull()
            ?: Regex("(\\d{4})").find(document.selectFirst(".dp-i-stats, .film-stats")?.text() ?: "")?.value?.toIntOrNull()
        val rating = document.selectFirst("span.item-imdb, .imdb-rating")?.text()
            ?.replace(Regex("[^\\d.]"), "")?.toFloatOrNull()?.times(1000)?.toInt()
        val isMovie = url.contains("/movie/")
        val isAnime = url.contains("/anime/")
        val id = Regex("-(\\d+)$").find(url.trimEnd('/'))?.groupValues?.get(1)

        return if (isMovie) {
            val episodeData = id?.let {
                runCatching {
                    app.get("$mainUrl/ajax/movie/episodes/$it", referer = url).document
                        .select("a").mapNotNull { a -> a.attr("href").ifEmpty { null } }.firstOrNull()
                }.getOrNull()
            } ?: url
            newMovieLoadResponse(title, url, TvType.Movie, episodeData) {
                this.posterUrl = poster; this.plot = description; this.tags = tags; this.year = year; this.rating = rating
            }
        } else {
            val episodes = mutableListOf<Episode>()
            val seasonList = document.select("div.os-list a.os-item")
            if (seasonList.isEmpty()) {
                id?.let {
                    runCatching {
                        app.get("$mainUrl/ajax/v2/episode/list/$it", referer = url).document
                    }.getOrNull()
                }?.select("div.eps-item, li.ep-item")?.forEach { ep ->
                    val epNum = ep.attr("data-number").toIntOrNull()
                    val epTitle = ep.selectFirst(".ep-name, .ep-title, h3")?.text()
                    val epLink = fixUrlNull(ep.selectFirst("a")?.attr("href")) ?: return@forEach
                    episodes.add(newEpisode(epLink) { name = epTitle; episode = epNum; season = 1 })
                }
            } else {
                seasonList.forEachIndexed { sIdx, seasonEl ->
                    val seasonId = seasonEl.attr("data-id").takeIf { it.isNotBlank() } ?: return@forEachIndexed
                    runCatching {
                        app.get("$mainUrl/ajax/v2/season/episodes/$seasonId", referer = url).document
                    }.getOrNull()?.select("div.eps-item, li.ep-item")?.forEach { ep ->
                        val epNum = ep.attr("data-number").toIntOrNull()
                        val epTitle = ep.selectFirst(".ep-name, .ep-title, h3")?.text()
                        val epLink = fixUrlNull(ep.selectFirst("a")?.attr("href")) ?: return@forEach
                        episodes.add(newEpisode(epLink) { name = epTitle; episode = epNum; season = sIdx + 1 })
                    }
                }
            }
            if (isAnime) {
                newAnimeLoadResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster; this.plot = description; this.tags = tags; this.year = year; this.rating = rating
                    addEpisodes(DubStatus.Subbed, episodes)
                }
            } else {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster; this.plot = description; this.tags = tags; this.year = year; this.rating = rating
                }
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

        runCatching {
            app.get("$mainUrl/ajax/v2/episode/servers/$episodeId", referer = data).document
        }.getOrNull()?.select("li.server-item, div.pc-item, li[data-id]")?.forEach { server ->
            val serverId = server.attr("data-id").takeIf { it.isNotBlank() } ?: return@forEach
            try {
                val link = tryParseJson<EpisodeSource>(
                    app.get("$mainUrl/ajax/v2/episode/sources/$serverId", referer = data).text
                )?.link ?: return@forEach
                loadExtractor(link, data, subtitleCallback, callback)
            } catch (_: Exception) {}
        }
        return true
    }

    data class EpisodeSource(val link: String?, val type: String?)
}
