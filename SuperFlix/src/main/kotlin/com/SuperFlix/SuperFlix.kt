package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class SuperFlixProvider : MainAPI() {
    override var mainUrl = "https://superflixapi.top"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/api/movie/trending?page=" to "Filmes em Alta",
        "$mainUrl/api/serie/trending?page=" to "Séries em Alta",
        "$mainUrl/api/movie/popular?page=" to "Filmes Populares",
        "$mainUrl/api/serie/popular?page=" to "Séries Populares"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val response = app.get(url).parsed<ApiResponse>()
        
        val home = response.results?.mapNotNull { item ->
            item.toSearchResponse()
        } ?: emptyList()
        
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val movieUrl = "$mainUrl/api/search/multi?query=$query&page=1"
        val response = app.get(movieUrl).parsed<ApiResponse>()
        
        return response.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val id = url.substringAfterLast("/")
        val type = if (url.contains("/movie/")) "movie" else "tv"
        
        val apiUrl = "$mainUrl/api/$type/$id"
        val data = app.get(apiUrl).parsed<MediaDetail>()
        
        return if (type == "movie") {
            newMovieLoadResponse(
                data.title ?: data.name ?: "",
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = data.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                this.year = data.release_date?.substringBefore("-")?.toIntOrNull()
                this.plot = data.overview
                this.tags = data.genres?.map { it.name }
                this.rating = data.vote_average?.times(1000)?.toInt()
            }
        } else {
            val episodes = data.seasons?.flatMap { season ->
                season.episodes?.map { episode ->
                    Episode(
                        data = "$mainUrl/api/tv/$id/${season.season_number}/${episode.episode_number}",
                        name = episode.name,
                        season = season.season_number,
                        episode = episode.episode_number,
                        posterUrl = episode.still_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                    )
                } ?: emptyList()
            } ?: emptyList()
            
            newTvSeriesLoadResponse(
                data.name ?: data.title ?: "",
                url,
                TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = data.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                this.year = data.first_air_date?.substringBefore("-")?.toIntOrNull()
                this.plot = data.overview
                this.tags = data.genres?.map { it.name }
                this.rating = data.vote_average?.times(1000)?.toInt()
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoUrl = "$data/watch"
        val response = app.get(videoUrl).parsed<WatchResponse>()
        
        response.sources?.forEach { source ->
            callback.invoke(
                ExtractorLink(
                    name,
                    source.quality ?: "Unknown",
                    source.url ?: return@forEach,
                    referer = mainUrl,
                    quality = getQualityFromName(source.quality),
                    type = ExtractorLinkType.M3U8
                )
            )
        }
        
        response.subtitles?.forEach { sub ->
            subtitleCallback.invoke(
                newSubtitleFile(
                    sub.lang ?: "Unknown",
                    sub.url ?: return@forEach
                )
            )
        }
        
        return true
    }

    private fun getQualityFromName(quality: String?): Int {
        return when {
            quality?.contains("1080") == true -> Qualities.P1080.value
            quality?.contains("720") == true -> Qualities.P720.value
            quality?.contains("480") == true -> Qualities.P480.value
            quality?.contains("360") == true -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun MediaItem.toSearchResponse(): SearchResponse? {
        val isMovie = media_type == "movie"
        val url = "$mainUrl/${if (isMovie) "movie" else "tv"}/${id}"
        
        return if (isMovie) {
            newMovieSearchResponse(
                title ?: name ?: return null,
                url,
                TvType.Movie
            ) {
                this.posterUrl = poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                this.year = release_date?.substringBefore("-")?.toIntOrNull()
            }
        } else {
            newTvSeriesSearchResponse(
                name ?: title ?: return null,
                url,
                TvType.TvSeries
            ) {
                this.posterUrl = poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                this.year = first_air_date?.substringBefore("-")?.toIntOrNull()
            }
        }
    }

    // Data classes
    data class ApiResponse(
        val results: List<MediaItem>?
    )

    data class MediaItem(
        val id: Int?,
        val title: String?,
        val name: String?,
        val poster_path: String?,
        val release_date: String?,
        val first_air_date: String?,
        val media_type: String?
    )

    data class MediaDetail(
        val id: Int?,
        val title: String?,
        val name: String?,
        val overview: String?,
        val poster_path: String?,
        val release_date: String?,
        val first_air_date: String?,
        val vote_average: Double?,
        val genres: List<Genre>?,
        val seasons: List<Season>?
    )

    data class Genre(val name: String?)

    data class Season(
        val season_number: Int?,
        val episodes: List<Episode>?
    )

    data class Episode(
        val episode_number: Int?,
        val name: String?,
        val still_path: String?
    )

    data class WatchResponse(
        val sources: List<Source>?,
        val subtitles: List<Subtitle>?
    )

    data class Source(
        val url: String?,
        val quality: String?
    )

    data class Subtitle(
        val url: String?,
        val lang: String?
    )
}