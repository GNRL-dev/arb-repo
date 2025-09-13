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
        "$mainUrl/anime" to "قائمة الانمي"
    )

    // =======================
    // Home Page
    // =======================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("/anime")) {
            // anime list supports pagination
            if (request.data.contains("?")) "${request.data}&page=$page" else "${request.data}?page=$page"
        } else {
            // homepage - no pagination (keeps old behavior)
            request.data
        }

        val doc = app.get(url).document
        val list = mutableListOf<AnimeSearchResponse>()

        if (request.data.contains("/anime")) {
            // قائمة الأنمي (anime list)
            doc.select("div.v-card.v-sheet").mapNotNullTo(list) { card ->
                toSearchResult(card)
            }
        } else {
            // الرئيسية (homepage) — same behavior as before, parse anchors in rows
            doc.select("div.row a").mapNotNullTo(list) { link ->
                val href = link.attr("href") ?: return@mapNotNullTo null
                val title = link.attr("title")?.ifBlank { link.text() } ?: return@mapNotNullTo null
                val poster = extractPoster(link.selectFirst(".v-image__image--cover")) ?: extractPoster(link)

                newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
                    this.posterUrl = poster
                }
            }
        }

        // Pagination only for anime list: detect "Next page" button
        val hasNext = if (request.data.contains("/anime")) {
            doc.select("button[aria-label=Next page]").isNotEmpty()
        } else false

        return newHomePageResponse(request.name, list, hasNext = hasNext)
    }

    private fun toSearchResult(card: Element): AnimeSearchResponse? {
        val href = card.selectFirst("a")?.attr("href") ?: return null
        val title = card.selectFirst(".anime_name")?.text()?.trim()
            ?: card.selectFirst(".v-card__title")?.text()?.trim()
            ?: return null
        // Use robust extractor but prefer the structured cover element first
        val poster = extractPoster(card.selectFirst(".v-image__image--cover")) ?: extractPoster(card)

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

        // Prefer the site search endpoint (works in your browser)
        val searchUrl = "$mainUrl/search?q=$encoded"
        val doc = app.get(searchUrl).document

        // Try to parse v-card results first, then fallback to anchors
        val cards = doc.select("div.v-card.v-sheet")
        if (cards.isNotEmpty()) {
            return cards.mapNotNull { toSearchResult(it) }
        }

        val anchors = doc.select("div.row a, a.card-link, .search-results a")
        return anchors.mapNotNull { el ->
            val href = (el.attr("href")).ifEmpty { el.selectFirst("a")?.attr("href") ?: return@mapNotNull null }
            val title = el.selectFirst(".anime_name, .v-card__title, h3, .title")?.text()?.trim()
                ?: el.text()?.trim()
                ?: return@mapNotNull null
            val poster = extractPoster(el.selectFirst(".v-image__image--cover")) ?: extractPoster(el)

            newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
                this.posterUrl = poster
            }
        }
    }

    // =======================
    // Load Anime Details (with episode pagination)
    // =======================
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst(".mx-auto.text-center.ltr")?.text()?.trim() ?: "Unknown"
        // prefer the v-image cover element; fallback to other img tags in page
        val poster = extractPoster(doc.selectFirst(".v-image__image--cover")) ?: extractPoster(doc)
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

            epCards.forEach { ep ->
                val href = ep.attr("href")
                // try to read episode title if present, otherwise fallback to text or constructed name
                val epName = ep.selectFirst(".episode-title, .v-card__title, .name")?.text()?.trim()
                    ?: ep.text()?.trim()
                    ?: "Episode ${episodes.size + 1}"
                // per-episode poster fallback chain
                val epPoster = extractPoster(ep.selectFirst(".v-image__image--cover"))
                    ?: extractPoster(ep)
                    ?: poster

                episodes.add(
                    newEpisode(fixUrl(href)) {
                        name = epName
                        episode = episodes.size + 1
                        posterUrl = epPoster
                    }
                )
            }

            // If there's a Next page button, continue; otherwise stop
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

        // Direct <video><source> cases
        player.select("video source").forEach { src ->
            val videoUrl = src.attr("src")
            val qualityInt = src.attr("size").toIntOrNull() ?: 0

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "${qualityInt}p",
                    url = videoUrl,
                    type = if (videoUrl.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.MP4
                ) {
                    this.quality = qualityInt
                    this.referer = mainUrl
                }
            )
        }

        // Fallback: look for file:"..." inside scripts (common pattern)
        val scriptText = player.select("script").joinToString("\n") { it.html() }
        Regex("""file\s*:\s*["']([^"']+)["']""").findAll(scriptText).forEach { match ->
            val videoUrl = match.groupValues[1]
            val qualityInt = 0
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "auto",
                    url = videoUrl,
                    type = if (videoUrl.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.MP4
                ) {
                    this.quality = qualityInt
                    this.referer = mainUrl
                }
            )
        }

        return true
    }

    // =======================
    // Helpers
    // =======================
    // Robust poster extractor that tries multiple places (cover style, img[data-src], img[src])
    private fun extractPoster(element: Element?): String? {
        if (element == null) return null

        // 1) background-image style
        val style = element.attr("style")
        if (style.contains("url(")) {
            val url = style.substringAfter("url(").substringBefore(")").replace("\"", "").trim()
            if (url.isNotBlank()) return url
        }

        // 2) lazy-loaded image data-src
        element.selectFirst("img[data-src]")?.attr("data-src")?.let {
            if (it.isNotBlank()) return it
        }

        // 3) normal <img src>
        element.selectFirst("img[src]")?.attr("src")?.let {
            if (it.isNotBlank()) return it
        }

        return null
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("http")) url else mainUrl.trimEnd('/') + if (url.startsWith("/")) url else "/$url"
    }
}
