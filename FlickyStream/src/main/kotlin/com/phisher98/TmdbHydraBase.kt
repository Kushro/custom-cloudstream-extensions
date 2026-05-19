package com.phisher98

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

abstract class TmdbHydraBase : MainAPI() {
    override val hasMainPage         = true
    override var lang                = "en"
    override val hasDownloadSupport  = true
    override val supportedTypes      = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    abstract fun movieEmbedUrl(tmdbId: Int): String
    abstract fun tvEmbedUrl(tmdbId: Int, season: Int, episode: Int): String

    private val apiKey  = "8d6d91941230817f7807d643736e8a49"
    private val api     = "https://api.themoviedb.org/3"
    private val imgBase = "https://image.tmdb.org/t/p/w500"

    override val mainPage = mainPageOf(
        "$api/trending/all/week?api_key=$apiKey&page="          to "Trending",
        "$api/movie/popular?api_key=$apiKey&page="             to "Popular Movies",
        "$api/tv/popular?api_key=$apiKey&page="                to "Popular TV Shows",
        "$api/movie/top_rated?api_key=$apiKey&page="           to "Top Rated Movies",
        "$api/tv/top_rated?api_key=$apiKey&page="              to "Top Rated TV Shows",
        "$api/movie/now_playing?api_key=$apiKey&page="         to "Now Playing",
        "$api/tv/on_the_air?api_key=$apiKey&page="             to "Currently Airing",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val resp = app.get(request.data + page).parsedSafe<TmdbListResponse>()
        val items = resp?.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$api/search/multi?api_key=$apiKey&query=${query.replace(" ", "+")}")
            .parsedSafe<TmdbListResponse>()?.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val parts = url.split("|")
        val type    = parts[0]
        val tmdbId  = parts[1].toIntOrNull() ?: return null
        return if (type == "movie") loadMovie(tmdbId, url) else loadTv(tmdbId, url)
    }

    private suspend fun loadMovie(tmdbId: Int, url: String): LoadResponse? {
        val d = app.get("$api/movie/$tmdbId?api_key=$apiKey").parsedSafe<TmdbDetail>() ?: return null
        return newMovieLoadResponse(d.title ?: return null, url, TvType.Movie, "movie|$tmdbId|0|0") {
            posterUrl           = d.poster_path?.let { "$imgBase$it" }
            backgroundPosterUrl = d.backdrop_path?.let { "$imgBase$it" }
            plot                = d.overview
            year                = d.release_date?.take(4)?.toIntOrNull()
            tags                = d.genres?.mapNotNull { it.name }
            rating              = d.vote_average?.times(1000)?.toInt()
            addDuration(d.runtime?.toString())
        }
    }

    private suspend fun loadTv(tmdbId: Int, url: String): LoadResponse? {
        val d = app.get("$api/tv/$tmdbId?api_key=$apiKey").parsedSafe<TmdbDetail>() ?: return null
        val totalSeasons = d.number_of_seasons ?: 1
        val episodes = mutableListOf<Episode>()
        (1..totalSeasons).forEach { s ->
            app.get("$api/tv/$tmdbId/season/$s?api_key=$apiKey").parsedSafe<TmdbSeason>()
                ?.episodes?.forEach { ep ->
                    val e = ep.episode_number ?: return@forEach
                    episodes.add(newEpisode("tv|$tmdbId|$s|$e") {
                        name        = ep.name
                        season      = s
                        episode     = e
                        posterUrl   = ep.still_path?.let { "$imgBase$it" }
                        description = ep.overview
                    })
                }
        }
        return newTvSeriesLoadResponse(d.name ?: return null, url, TvType.TvSeries, episodes) {
            posterUrl           = d.poster_path?.let { "$imgBase$it" }
            backgroundPosterUrl = d.backdrop_path?.let { "$imgBase$it" }
            plot                = d.overview
            year                = d.first_air_date?.take(4)?.toIntOrNull()
            tags                = d.genres?.mapNotNull { it.name }
            rating              = d.vote_average?.times(1000)?.toInt()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts   = data.split("|")
        val type    = parts[0]
        val tmdbId  = parts[1].toIntOrNull() ?: return false
        val season  = parts.getOrNull(2)?.toIntOrNull() ?: 0
        val episode = parts.getOrNull(3)?.toIntOrNull() ?: 0
        val embedUrl = if (type == "movie") movieEmbedUrl(tmdbId) else tvEmbedUrl(tmdbId, season, episode)
        loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
        return true
    }

    private fun TmdbResult.toSearchResponse(): SearchResponse? {
        val effectiveType = media_type ?: if (name != null && title == null) "tv" else "movie"
        val t  = title ?: name ?: return null
        val id = id   ?: return null
        val poster = poster_path?.let { "$imgBase$it" }
        return if (effectiveType == "movie")
            newMovieSearchResponse(t, "movie|$id", TvType.Movie)    { posterUrl = poster }
        else
            newTvSeriesSearchResponse(t, "tv|$id",    TvType.TvSeries) { posterUrl = poster }
    }

    // ── Data classes ─────────────────────────────────────────────────────────

    data class TmdbListResponse(
        @JsonProperty("results") val results: List<TmdbResult>?
    )

    data class TmdbResult(
        @JsonProperty("id")           val id: Int?,
        @JsonProperty("title")        val title: String?,
        @JsonProperty("name")         val name: String?,
        @JsonProperty("poster_path")  val poster_path: String?,
        @JsonProperty("media_type")   val media_type: String?,
    )

    data class TmdbDetail(
        @JsonProperty("id")                val id: Int?,
        @JsonProperty("title")             val title: String?,
        @JsonProperty("name")              val name: String?,
        @JsonProperty("poster_path")       val poster_path: String?,
        @JsonProperty("backdrop_path")     val backdrop_path: String?,
        @JsonProperty("overview")          val overview: String?,
        @JsonProperty("release_date")      val release_date: String?,
        @JsonProperty("first_air_date")    val first_air_date: String?,
        @JsonProperty("vote_average")      val vote_average: Double?,
        @JsonProperty("runtime")           val runtime: Int?,
        @JsonProperty("number_of_seasons") val number_of_seasons: Int?,
        @JsonProperty("genres")            val genres: List<TmdbGenre>?,
    )

    data class TmdbGenre(
        @JsonProperty("name") val name: String?
    )

    data class TmdbSeason(
        @JsonProperty("episodes") val episodes: List<TmdbEpisode>?
    )

    data class TmdbEpisode(
        @JsonProperty("episode_number") val episode_number: Int?,
        @JsonProperty("name")           val name: String?,
        @JsonProperty("still_path")     val still_path: String?,
        @JsonProperty("overview")       val overview: String?,
    )
}
