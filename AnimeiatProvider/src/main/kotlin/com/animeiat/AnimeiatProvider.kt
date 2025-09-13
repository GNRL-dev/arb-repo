package com.animeiat

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class Animeiat : MainAPI() {
    override var mainUrl = "https://ww1.animeiat.tv"
    override var name = "Animeiat"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "$mainUrl/anime?status=completed&page=" to "مكتمل",
        "$mainUrl/anime?status=ongoing&page=" to "مستمر"
    )

    // =======================
    // Home Page
    // =======================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page).document
        val items = doc.select("div.v-card")
        val list = items.mapNotNull { item ->
            val title = item.selectFirst(".v-card__title")?.text()?.trim() ?: return@mapNotNull null
            val href = item.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val posterStyle = item.selectFirst(".v-image__image--cover")?.attr("style")
            val poster = posterStyle?.substringAfter("url(")?.substringBefore(")")?.replace("\"", "")

            newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse(request.name, list, hasNext = true)
    }

    // =======================
    // Search
    // =======================
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/anime?q=$query").document
        return doc.select("div.v-card").mapNotNull { item ->
            val title = item.selectFirst(".v-card__title")?.text()?.trim() ?: return@mapNotNull null
            val href = item.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val posterStyle = item.selectFirst(".v-image__image--cover")?.attr("style")
            val poster = posterStyle?.substringAfter("url(")?.substringBefore(")")?.replace("\"", "")

            newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
                this.posterUrl = poster
            }
        }
    }

    // =======================
    // Load Anime Details
    // =======================
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst(".mx-auto.text-center.ltr")?.text()?.trim() ?: "Unknown"
        val posterStyle = doc.selectFirst(".v-image__image--cover")?.attr("style")
        val poster = posterStyle?.substringAfter("url(")?.substringBefore(")")?.replace("\"", "")
        val description = doc.selectFirst("p.text-justify")?.text()?.trim()
        val genres = doc.select("span.v-chip__content span").map { it.text() }
        val statusText = doc.selectFirst("span")?.text()?.trim() ?: ""
        val showStatus =
            if (statusText.contains("مكتمل")) ShowStatus.Completed else ShowStatus.Ongoing

        val episodes = doc.select("a.card-link").mapIndexed { index, ep ->
            val href = ep.attr("href")
            newEpisode(fixUrl(href)) {
                name = "Episode ${index + 1}"
                episode = index + 1
                posterUrl = poster
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
            this.showStatus = showStatus
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // =======================
    // Extract Links
    // =======================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val iframe = doc.selectFirst("iframe")?.attr("src") ?: return false
        val player = app.get(fixUrl(iframe)).document

        player.select("video source").forEach { src ->
            val videoUrl = src.attr("src")
            val quality = src.attr("size").toIntOrNull() ?: Qualities.Unknown.value

            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    referer = mainUrl,
                    quality = quality,
                    type = ExtractorLinkType.M3U8
                )
            )
        }

        return true
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("http")) url else mainUrl + url
    }
}
