package com.indo

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class MovieBox : MainAPI() {
    override var mainUrl = "https://themoviebox.org"
    private val apiBase = "https://h5-api.aoneroom.com"

    override var name = "MovieBox"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // id dari user (Trending / New Release / Popular)
    override val mainPage = mainPageOf(
        "872031290915189720" to "Trending🔥",
        "4380734070238626200" to "K-Drama: New Release",
        "6528093688173053896" to "Trending Anime"
    )

    private var clientToken: String? = null

    private val baseHeaders: Map<String, String>
        get() = mapOf(
            "Accept" to "application/json",
            "User-Agent" to USER_AGENT,
            "X-Client-Info" to "{\"timezone\":\"Asia/Jakarta\"}"
        ) + (clientToken?.let { mapOf("X-Client-Token" to it) } ?: emptyMap())

    private fun updateTokenFromHeaders(headers: Map<String, String>) {
        val xUser = headers.entries.firstOrNull { it.key.equals("x-user", true) }?.value ?: return
        val token = tryParseJson<Map<String, Any>>(xUser)?.get("token")?.toString()
        if (!token.isNullOrBlank()) clientToken = token
    }

    private suspend fun apiGet(path: String): String {
        val res = app.get("$apiBase$path", headers = baseHeaders)
        updateTokenFromHeaders(res.headers)
        return res.text
    }

    private fun toTvType(subjectType: Int?): TvType = when (subjectType) {
        2 -> TvType.Anime
        3 -> TvType.TvSeries
        else -> TvType.Movie
    }

    private fun detailPathFromUrl(url: String): String {
        return url.substringAfterLast("/").substringBefore("?")
    }

    data class ApiEnvelope<T>(
        val code: Int? = null,
        val message: String? = null,
        val data: T? = null
    )

    data class CoverObj(
        val url: String? = null
    )

    data class SubjectObj(
        val subjectId: String? = null,
        val subjectType: Int? = null,
        val title: String? = null,
        val description: String? = null,
        val releaseDate: String? = null,
        val genre: String? = null,
        val cover: CoverObj? = null,
        val detailPath: String? = null
    )

    data class SeasonObj(
        val se: Int? = null,
        val maxEp: Int? = null,
        val allEp: String? = null
    )

    data class ResourceObj(
        val seasons: List<SeasonObj>? = null
    )

    data class DetailData(
        val subject: SubjectObj? = null,
        val resource: ResourceObj? = null
    )

    data class RankingData(
        @JsonProperty("subjectList")
        val subjectList: List<SubjectObj>? = null
    )

    data class PlayItem(
        val url: String? = null,
        val resolutions: String? = null,
        val format: String? = null
    )

    data class PlayData(
        val hls: List<PlayItem>? = null,
        val streams: List<PlayItem>? = null
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val rankingId = request.data
        val raw = apiGet("/wefeed-h5api-bff/ranking-list/content?id=$rankingId&page=$page&perPage=12")
        val parsed = AppUtils.mapper.readValue<ApiEnvelope<RankingData>>(raw)

        val items = parsed.data?.subjectList.orEmpty().mapNotNull { s ->
            val path = s.detailPath ?: return@mapNotNull null
            val title = s.title ?: return@mapNotNull null
            val tvType = toTvType(s.subjectType)
            newSearchResponse(title, "$mainUrl/moviesDetail/$path", tvType) {
                posterUrl = s.cover?.url
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // fallback sederhana: cari dari 3 ranking list utama
        val pools = mainPage.map { (_, id) ->
            val raw = apiGet("/wefeed-h5api-bff/ranking-list/content?id=$id&page=1&perPage=24")
            AppUtils.mapper.readValue<ApiEnvelope<RankingData>>(raw).data?.subjectList.orEmpty()
        }.flatten()

        return pools.distinctBy { it.detailPath }
            .filter { (it.title ?: "").contains(query, true) }
            .mapNotNull { s ->
                val path = s.detailPath ?: return@mapNotNull null
                val title = s.title ?: return@mapNotNull null
                newSearchResponse(title, "$mainUrl/moviesDetail/$path", toTvType(s.subjectType)) {
                    posterUrl = s.cover?.url
                }
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val detailPath = detailPathFromUrl(url)
        val raw = apiGet("/wefeed-h5api-bff/detail?detailPath=$detailPath")
        val parsed = AppUtils.mapper.readValue<ApiEnvelope<DetailData>>(raw)

        val subject = parsed.data?.subject ?: throw ErrorLoadingException("Subject not found")
        val resource = parsed.data?.resource

        val title = subject.title ?: throw ErrorLoadingException("Title not found")
        val plot = subject.description
        val poster = subject.cover?.url
        val tags = subject.genre?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        val year = subject.releaseDate?.take(4)?.toIntOrNull()

        val seasons = resource?.seasons.orEmpty()
        val isSeriesLike = seasons.isNotEmpty() && (seasons.first().maxEp ?: 0) > 1

        if (isSeriesLike) {
            val episodes = mutableListOf<Episode>()
            seasons.forEach { se ->
                val seasonNo = se.se ?: return@forEach
                val eps = if (!se.allEp.isNullOrBlank()) {
                    se.allEp.split(',').mapNotNull { it.trim().toIntOrNull() }
                } else {
                    val max = se.maxEp ?: 0
                    (1..max).toList()
                }

                eps.forEach { ep ->
                    episodes.add(newEpisode("$mainUrl/moviesDetail/$detailPath?sid=${subject.subjectId}&se=$seasonNo&ep=$ep") {
                        this.season = seasonNo
                        this.episode = ep
                        this.name = "Episode $ep"
                    })
                }
            }

            return newTvSeriesLoadResponse(title, url, toTvType(subject.subjectType), episodes) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }

        val sid = subject.subjectId.orEmpty()
        return newMovieLoadResponse(title, url, toTvType(subject.subjectType), "$mainUrl/moviesDetail/$detailPath?sid=$sid&se=1&ep=1") {
            posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val detailPath = detailPathFromUrl(data)
        val sid = Regex("[?&]sid=([^&]+)").find(data)?.groupValues?.getOrNull(1)
        val se = Regex("[?&]se=(\\d+)").find(data)?.groupValues?.getOrNull(1) ?: "1"
        val ep = Regex("[?&]ep=(\\d+)").find(data)?.groupValues?.getOrNull(1) ?: "1"

        val subjectId = if (!sid.isNullOrBlank()) sid else {
            val detRaw = apiGet("/wefeed-h5api-bff/detail?detailPath=$detailPath")
            val det = AppUtils.mapper.readValue<ApiEnvelope<DetailData>>(detRaw)
            det.data?.subject?.subjectId ?: return false
        }

        val playRaw = apiGet("/wefeed-h5api-bff/subject/play?subjectId=$subjectId&se=$se&ep=$ep&detailPath=$detailPath")
        val play = AppUtils.mapper.readValue<ApiEnvelope<PlayData>>(playRaw).data

        val links = (play?.hls.orEmpty() + play?.streams.orEmpty())
            .mapNotNull { it.url?.takeIf { u -> u.startsWith("http") } to it.resolutions }

        links.forEach { (u, res) ->
            val q = when {
                (res ?: "").contains("1080") || u.contains("1080", true) -> Qualities.P1080.value
                (res ?: "").contains("720") || u.contains("720", true) -> Qualities.P720.value
                (res ?: "").contains("480") || u.contains("480", true) -> Qualities.P480.value
                (res ?: "").contains("360") || u.contains("360", true) -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }
            callback(
                newExtractorLink(name, "$name ${res ?: "Auto"}", u) {
                    quality = q
                    referer = "$mainUrl/"
                }
            )
        }

        return links.isNotEmpty()
    }
}
