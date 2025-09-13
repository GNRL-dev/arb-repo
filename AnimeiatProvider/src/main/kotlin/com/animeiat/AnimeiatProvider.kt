package com.animeiat

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Animeiat : MainAPI() {
    override var mainUrl = "https://ww1.animeiat.tv"
    override var name = "Animeiat"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية",
        "$mainUrl/anime-list" to "قائمة الانمي",
        "$mainUrl/anime?status=completed&page=" to "مكتمل",
        "$mainUrl/anime?status=ongoing&page=" to "مستمر"
    )

    // =======================
    // Home Page
    // =======================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("/anime")) {
            // قائمة الأنمي → supports pagination
            if (request.data.contains("?"))
                "${request.data}&page=$page"
            else
                "${request.data}?page=$page"
        } else {
            // الرئيسية → no pagination
            request.data
        }

        val doc = app.get(url).document
        val list = mutableListOf<AnimeSearchResponse>()

        if (request.data.contains("/anime")) {
            // قائمة الأنمي
            doc.select("div.v-card.v-sheet").mapNotNullTo(list) { card ->
                toSearchResult(card)
            }
        } else {
            // الرئيسية
            doc.select("div.row a").mapNotNullTo(list) { link ->
                val href = link.attr("href") ?: return@mapNotNullTo null
                val title = link.attr("title")?.ifBlank { link.text() } ?: return@mapNotNullTo null
                val poster = link.selectFirst("img")?.attr("data-src")
                    ?: link.selectFirst("img")?.attr("src")

                newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
                    this.posterUrl = poster
                }
            }
        }

        // ✅ Pagination only for anime list
        val hasNext = if (request.data.contains("/anime")) {
            doc.select("ul.pagination li a").any { it.text() == (page + 1).toString() }
        } else {
            false
        }

        return newHomePageResponse(request.name, list, hasNext = hasNext)
    }

    private fun toSearchResult(card: Element): AnimeSearchResponse? {
        val href = card.selectFirst("a")?.attr("href") ?: return null
        val title = card.selectFirst(".anime_name")?.text()?.trim()
            ?: card.selectFirst(".v-card__title")?.text()?.trim()
            ?: return null
        val posterStyle = card.selectFirst(".v-image__image--cover")?.attr("style")
        val poster = posterStyle?.substringAfter("url(")?.substringBefore(")")?.replace("\"", "")

        return newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
            this.posterUrl = poster
        }
    }

    // =======================
    // Search
    // =======================
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/anime?q=$query").document
        return doc.select("div.v-card.v-sheet").mapNotNull { toSearchResult(it) }
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
        val statusText = doc.select("div:contains(مكتمل), div:contains(مستمر)")?.text() ?: ""
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

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "${quality}p",
                    url = videoUrl,
                    type = if (videoUrl.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.MP4
                ) {
                    this.quality = quality
                    this.referer = mainUrl
                }
            )
        }

        return true
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("http")) url else mainUrl + url
    }
}
