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
    )

    // =======================
    // Home Page
    // =======================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("/anime")) {
            if (request.data.contains("?"))
                "${request.data}&page=$page"
            else
                "${request.data}?page=$page"
        } else {
            request.data
        }

        val doc = app.get(url).document
        val list = mutableListOf<AnimeSearchResponse>()

        if (request.data.contains("/anime")) {
            doc.select("div.v-card.v-sheet").mapNotNullTo(list) { card ->
                toSearchResult(card)
            }
        } else {
            doc.select("div.row a").mapNotNullTo(list) { link ->
                val href = link.attr("href") ?: return@mapNotNullTo null
                val title = link.attr("title")?.ifBlank { link.text() } ?: return@mapNotNullTo null
                val poster = link.extractPoster() // robust helper
                newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
                    this.posterUrl = poster
                }
            }
        }

        val hasNext = if (request.data.contains("/anime")) {
            doc.select("button[aria-label=Next page]").isNotEmpty()
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
        val poster = card.extractPoster() // will check child <img> and background styles
        return newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
            this.posterUrl = poster
        }
    }

    // =======================
    // Search
    // =======================
    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = try {
            java.net.URLEncoder.encode(query, "utf-8")
        } catch (e: Exception) {
            query
        }

        val searchUrl = "$mainUrl/search?q=$encoded"
        val doc = app.get(searchUrl).document

        val results = doc.select("div.row a, div.some-search-container a").mapNotNull { link ->
            val href = link.attr("href").ifEmpty { return@mapNotNull null }
            val title = link.selectFirst("h3, span, .title")?.text()?.trim()
                ?: link.text()?.trim()
                ?: return@mapNotNull null
            val poster = link.extractPoster()
            newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
                this.posterUrl = poster
            }
        }
        return results
    }

    // =======================
    // Load Anime Details
    // =======================
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst(".mx-auto.text-center.ltr")?.text()?.trim() ?: "Unknown"
        // Look specifically for the v-image cover element (helper searches inside document)
        val poster = doc.extractPoster(".v-image__image--cover")
        val description = doc.selectFirst("p.text-justify")?.text()?.trim()
        val genres = doc.select("span.v-chip__content span").map { it.text() }
        val statusText = doc.select("div:contains(مكتمل), div:contains(مستمر)")?.text() ?: ""
        val showStatus = if (statusText.contains("مكتمل")) ShowStatus.Completed else ShowStatus.Ongoing

        val episodes = mutableListOf<Episode>()
        var page = 1
        while (true) {
            val pageUrl = if (url.contains("?")) "$url&page=$page" else "$url?page=$page"
            val pageDoc = app.get(pageUrl).document

            val epCards = pageDoc.select("a.card-link")
            if (epCards.isEmpty()) break

            epCards.forEachIndexed { _, ep ->
                val href = ep.attr("href")
                episodes.add(
                    newEpisode(fixUrl(href)) {
                        name = ep.text().ifBlank { "Episode ${(episodes.size) + 1}" }
                        episode = (episodes.size) + 1
                        posterUrl = poster
                    }
                )
            }

            val hasNext = pageDoc.select("button[aria-label=Next page]").isNotEmpty()
            if (!hasNext) break
            page++
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

    // =======================
    // Helpers
    // =======================
    private fun Element.extractPoster(selector: String = "img"): String? {
        // 1) Try common <img> attributes on this element (or its children)
        val img = this.selectFirst("img")
        val imgCandidates = listOf(
            img?.attr("data-src"),
            img?.attr("data-lazy-src"),
            img?.attr("src"),
            img?.attr("data-srcset"),
            img?.attr("srcset")
        )
        imgCandidates.firstOrNull { !it.isNullOrBlank() }?.let {
            return it.toAbsolutePosterUrl()
        }

        // 2) If the caller passed a selector (eg ".v-image__image--cover"), find that element
        val selElem = this.selectFirst(selector)

        // 3) Look for style attribute on the selected element, or any child with background-image
        val styleCandidates = mutableListOf<String?>()
        if (selElem != null) styleCandidates.add(selElem.attr("style"))
        // search anywhere inside 'this' for inline background-image style
        styleCandidates.add(this.selectFirst("[style*=background-image]")?.attr("style"))

        // 4) also check commonly used data attributes (fallbacks)
        val dataFallback = listOfNotNull(
            selElem?.attr("data-bg"),
            selElem?.attr("data-src"),
            selElem?.attr("data-background")
        ).firstOrNull()
        if (!dataFallback.isNullOrBlank()) return dataFallback.toAbsolutePosterUrl()

        // 5) Extract URL from style candidates
        for (style in styleCandidates) {
            if (style.isNullOrBlank()) continue
            if (!style.contains("url(")) continue
            val regex = Regex("url\\((.*?)\\)")
            val match = regex.find(style)?.groupValues?.get(1)
            val cleaned = match
                ?.replace("&quot;", "")
                ?.replace("\"", "")
                ?.replace("'", "")
                ?.trim()
            if (!cleaned.isNullOrBlank()) return cleaned.toAbsolutePosterUrl()
        }

        return null
    }

    private fun String.toAbsolutePosterUrl(): String {
        var p = this.replace("&quot;", "").replace("\"", "").replace("'", "").trim()
        if (p.startsWith("data:")) return p // keep data URIs
        if (p.startsWith("//")) p = "https:$p"
        if (p.startsWith("/")) return mainUrl.trimEnd('/') + p
        if (!p.startsWith("http")) return "$mainUrl/$p"
        return p
    }
}
