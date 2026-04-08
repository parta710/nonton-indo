package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class MovieBox : MainAPI() {
    override var mainUrl = "https://themoviebox.org"
    override var name = "MovieBox"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Trending"
    )

    private fun extractNuxtData(html: String): String? {
        val rgx = Regex("""<script[^>]*id=\"__NUXT_DATA__\"[^>]*>([\s\S]*?)</script>""")
        return rgx.find(html)?.groupValues?.getOrNull(1)
    }

    private fun extractStrings(nuxtData: String): List<String> {
        // Ambil string literal sederhana dari payload __NUXT_DATA__
        val out = mutableListOf<String>()
        Regex("\"([^\"\\n\\r]{2,})\"").findAll(nuxtData).forEach { m ->
            out.add(m.groupValues[1])
        }
        return out
    }

    private fun detailPathFromUrl(url: String): String {
        return url.substringAfterLast("/").substringBefore("?")
    }

    private fun titlePrefix(detailPath: String): String {
        // one-piece-CTqWaizwOp3 -> one-piece
        return detailPath.substringBeforeLast("-")
    }

    private fun parseCardTitle(rawTitle: String?, cardText: String?, detailPath: String): String {
        val fromTitleAttr = rawTitle
            ?.replace(Regex("^go to\\s+", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("\\s+detail page$", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.ifBlank { null }

        val fromCard = cardText?.trim()?.ifBlank { null }

        return fromCard
            ?: fromTitleAttr
            ?: detailPath.substringBeforeLast("-").replace('-', ' ')
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document

        val anchors = document
            .select("a.movie-card[href*=/moviesDetail/]")
            .ifEmpty { document.select("a[href*=/moviesDetail/]") }

        val items = anchors.mapNotNull { a ->
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val fixed = fixUrl(href)
            val path = detailPathFromUrl(fixed)
            if (path.isBlank()) return@mapNotNull null

            val cardTitle = a.selectFirst("p")?.text()
            val title = parseCardTitle(a.attr("title"), cardTitle, path)

            val poster = a.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { null }
                    ?: img.attr("src").ifBlank { null }
                    ?: img.attr("data-original").ifBlank { null }
            }

            newMovieSearchResponse(title, "$mainUrl/moviesDetail/$path", TvType.Movie) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // MovieBox web tidak expose search HTML stabil, jadi pakai kumpulan card homepage lalu filter judul
        val home = app.get(mainUrl).document
        val anchors = home
            .select("a.movie-card[href*=/moviesDetail/]")
            .ifEmpty { home.select("a[href*=/moviesDetail/]") }

        return anchors.mapNotNull { a ->
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val fixed = fixUrl(href)
            val path = detailPathFromUrl(fixed)
            if (path.isBlank()) return@mapNotNull null

            val cardTitle = a.selectFirst("p")?.text()
            val title = parseCardTitle(a.attr("title"), cardTitle, path)
            if (!title.contains(query, ignoreCase = true)) return@mapNotNull null

            val poster = a.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { null }
                    ?: img.attr("src").ifBlank { null }
            }

            newMovieSearchResponse(title, "$mainUrl/moviesDetail/$path", TvType.Movie) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val detailUrl = if (url.contains("/moviesDetail/")) url else "$mainUrl/moviesDetail/${detailPathFromUrl(url)}"
        val detailPath = detailPathFromUrl(detailUrl)

        val doc = app.get(detailUrl).document
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("title")?.text()?.ifBlank { null }
            ?: detailPath.substringBeforeLast("-").replace('-', ' ')

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
        val desc = doc.selectFirst("meta[name=description]")?.attr("content")?.ifBlank { null }

        val html = doc.outerHtml()
        val nuxt = extractNuxtData(html).orEmpty()
        val strings = extractStrings(nuxt)

        val basePrefix = titlePrefix(detailPath)
        val slugRegex = Regex("[a-z0-9-]+-[A-Za-z0-9]{8,}")

        val candidateSlugs = strings
            .asSequence()
            .flatMap { s -> slugRegex.findAll(s).map { it.value } }
            .filter { it.startsWith(basePrefix) }
            .distinct()
            .toList()

        // Cari slug yang kemungkinan playable (episode/variant)
        val playableSlugs = candidateSlugs.filter { it != detailPath }

        return if (playableSlugs.isNotEmpty()) {
            val episodes = playableSlugs.mapIndexed { idx, slug ->
                newEpisode("$mainUrl/movies/$slug") {
                    this.name = "Episode ${idx + 1}"
                    this.episode = idx + 1
                }
            }

            newTvSeriesLoadResponse(title, detailUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = desc
            }
        } else {
            // fallback single movie
            newMovieLoadResponse(title, detailUrl, TvType.Movie, "$mainUrl/movies/$detailPath") {
                this.posterUrl = poster
                this.plot = desc
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playUrl = if (data.contains("/movies/")) data else "$mainUrl/movies/${detailPathFromUrl(data)}"
        val html = app.get(playUrl).text
        val nuxt = extractNuxtData(html) ?: return false
        val strings = extractStrings(nuxt)

        // direct media urls (mp4 / m3u8)
        val mediaUrls = strings.filter {
            it.startsWith("http") && (
                it.contains(".mp4", ignoreCase = true) ||
                    it.contains(".m3u8", ignoreCase = true) ||
                    it.contains("macdn.aoneroom.com/media") ||
                    it.contains("hdfullcdn.cc")
                )
        }.distinct()

        mediaUrls.forEach { raw ->
            val q = when {
                raw.contains("1080", true) -> Qualities.P1080.value
                raw.contains("720", true) -> Qualities.P720.value
                raw.contains("480", true) -> Qualities.P480.value
                raw.contains("360", true) -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }

            callback.invoke(
                newExtractorLink(name, name, raw) {
                    this.quality = q
                    this.referer = "$mainUrl/"
                }
            )
        }

        return mediaUrls.isNotEmpty()
    }
}
