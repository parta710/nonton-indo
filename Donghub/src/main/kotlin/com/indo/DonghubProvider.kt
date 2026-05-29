package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DonghubProvider : MainAPI() {
    override var mainUrl = "https://donghub.vip"
    override var name = "Donghub"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.OVA)

    override val mainPage = mainPageOf(
        "$mainUrl/anime/?order=update&page=" to "Latest Update",
        "$mainUrl/anime/?order=popular&page=" to "Popular",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page).document
        val items = doc.select("article.hentry").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = selectFirst("h2 a")?.text() ?: return null
        val href = selectFirst("h2 a")?.attr("href") ?: return null
        val poster = selectFirst("img")?.attr("src")
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("article.hentry").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text() ?: ""
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val synopsis = doc.selectFirst(".entry-content p")?.text()
        val episodes = doc.select("li a[href*=episode]").mapNotNull {
            val epNum = it.attr("href")
                .substringAfterLast("episode-")
                .substringBefore("-")
                .toIntOrNull()
            Episode(it.attr("href"), episode = epNum)
        }
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = synopsis
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val dmUrl = doc.select("iframe[src*=dailymotion]").attr("src")
        if (dmUrl.isNotEmpty()) {
            val videoId = dmUrl.substringAfterLast("video=").substringBefore("&")
            loadExtractor(
                "https://www.dailymotion.com/video/$videoId",
                data, subtitleCallback, callback
            )
        }
        val okruUrl = doc.select("iframe[src*=ok.ru]").attr("src")
        if (okruUrl.isNotEmpty()) {
            loadExtractor(okruUrl, data, subtitleCallback, callback)
        }
        return true
    }
}
