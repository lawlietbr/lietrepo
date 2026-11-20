package com.AnimesFHD

import com.fasterxml.jackson.annotation.JsonProperty

// Respostas da API pública do AnimesFHD (https://animesfhd.net/api/...).
// Campos desconhecidos são ignorados pelo leitor de JSON do CloudStream.

data class SeriesPage(
    @JsonProperty("series") val series: List<Series>? = null,
    @JsonProperty("has_more") val hasMore: Boolean? = null,
)

data class Series(
    @JsonProperty("id") val id: Long? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("cover_image") val coverImage: String? = null,
    @JsonProperty("backdrop_image") val backdropImage: String? = null,
    @JsonProperty("genre") val genre: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("category") val category: String? = null,
    @JsonProperty("year") val year: Int? = null,
)

data class SeriesDetail(
    @JsonProperty("series") val series: Series? = null,
    @JsonProperty("episodes") val episodes: List<EpisodeItem>? = null,
    @JsonProperty("has_more") val hasMore: Boolean? = null,
    @JsonProperty("seo") val seo: Seo? = null,
    @JsonProperty("versions") val versions: List<Version>? = null,
    @JsonProperty("mal") val mal: Mal? = null,
)

data class Seo(
    @JsonProperty("heading") val heading: String? = null,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("audio") val audio: String? = null,
    @JsonProperty("format") val format: String? = null,
)

data class Version(
    @JsonProperty("series_id") val seriesId: Long? = null,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("part") val part: Int? = null,
    @JsonProperty("audio") val audio: String? = null,
)

data class Mal(
    @JsonProperty("id") val id: Int? = null,
)

data class EpisodeItem(
    @JsonProperty("id") val id: Long? = null,
    @JsonProperty("episode_number") val number: Double? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("thumbnail_url") val thumbnail: String? = null,
    @JsonProperty("aired_at") val airedAt: String? = null,
    @JsonProperty("created_at") val createdAt: String? = null,
)

data class EpisodeDetail(
    @JsonProperty("episode") val episode: EpisodeInfo? = null,
    @JsonProperty("sources") val sources: List<Source>? = null,
)

data class EpisodeInfo(
    @JsonProperty("series_id") val seriesId: Long? = null,
    @JsonProperty("subtitle_url") val subtitleUrl: String? = null,
)

data class Source(
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("audio") val audio: String? = null,
    @JsonProperty("kind") val kind: String? = null,
    @JsonProperty("media_kind") val mediaKind: String? = null,
    @JsonProperty("native_video") val nativeVideo: Boolean? = null,
)
