package com.arabseed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class ArabSeed : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://a.asd.homes"
    override var name = "ArabSeed"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    private val cfKiller = CloudflareKiller()

    // --- Convert card element into SearchResponse ---
    private fun Element.toSearchResponse(): SearchResponse? {
        val href = this.attr("href") ?: return null
        val title = selectFirst("h3")?.text() ?: this.attr("title") ?: return null
        val poster = selectFirst(".post__image img")?.attr("src")

        return newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
            this.posterUrl = poster
        }
    }

    // --- Home categories ---
    override val mainPage = mainPageOf(
        "$mainUrl/main0/" to "الرئيسية",
        "$mainUrl/category/foreign-movies-6/" to "افلام اجنبي",
        "$mainUrl/category/asian-movies/" to "افلام اسيوية",
        "$mainUrl/category/arabic-movies-5/" to "افلام عربي",
        "$mainUrl/category/foreign-series-2/" to "مسلسلات اجنبي",
        "$mainUrl/category/arabic-series-2/" to "مسلسلات عربي",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84-%d9%83%d9%88%d8%b1%d9%8a%d9%87/" to "مسلسلات كوريه",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d9%86%d9%8a%d9%85%d9%8a%d8%b4%d9%86/" to "افلام انيميشن",
        "$mainUrl/category/cartoon-series/" to "مسلسلات كرتون"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"

        var doc = app.get(url).document
        if (doc.select("title").text() == "Just a moment...") {
            doc = app.get(url, interceptor = cfKiller, timeout = 120).document
        }

        val items = doc.select("a.movie__block").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = doc.select(".page-numbers a").isNotEmpty())
    }

    // --- Fixed search using /find/ endpoint ---
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/find/?word=${query.replace(" ", "+")}&type="

        var doc = app.get(url).document
        if (doc.select("title").text() == "Just a moment...") {
            doc = app.get(url, interceptor = cfKiller, timeout = 120).document
        }

        return doc.select("a.movie__block").mapNotNull { it.toSearchResponse() }
    }

    // --- Load detail page (movie or series) ---
    override suspend fun load(url: String): LoadResponse {
        var doc = app.get(url).document
        if (doc.select("title").text() == "Just a moment...") {
            doc = app.get(url, interceptor = cfKiller, timeout = 120).document
        }

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("title")?.text().orEmpty()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
        val tags = doc.select("meta[property=article:tag]").map { it.attr("content") }

        val episodes = doc.select("a[href*=\"/watch/\"]").map {
            newEpisode(it.attr("href")) {
                this.name = it.text().ifBlank { "Episode" }
            }
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    var doc = app.get(data).document
    if (doc.select("title").text() == "Just a moment...") {
        doc = app.get(data, interceptor = cfKiller, timeout = 120).document
    }

    val watchUrl = doc.selectFirst("a.watch__btn")?.attr("href")
        ?: doc.selectFirst("a[href*=\"/watch/\"]")?.attr("href")
        ?: return false

    var watchDoc = app.get(watchUrl, referer = mainUrl).document
    if (watchDoc.select("title").text() == "Just a moment...") {
        watchDoc = app.get(watchUrl, referer = mainUrl, interceptor = cfKiller, timeout = 120).document
    }

    val iframes = watchDoc.select("iframe[src]").map { it.attr("src") }

    for (iframe in iframes) {
        var iframeDoc = app.get(iframe, referer = watchUrl).document
        if (iframeDoc.select("title").text() == "Just a moment...") {
            iframeDoc = app.get(iframe, referer = watchUrl, interceptor = cfKiller, timeout = 120).document
        }

        iframeDoc.select("source").forEach { sourceEl ->
            val src = sourceEl.attr("src")
            val quality = when {
                src.contains("1080") -> Qualities.P1080
                src.contains("720") -> Qualities.P720
                else -> Qualities.Unknown
            }

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "Direct",
                    url = src,
                    type = ExtractorLinkType.VIDEO
                ) {
                    referer = iframe
                    this.quality = quality
                    headers = mapOf()
                }
            )
        }

        // hand off to built-in extractors too
        loadExtractor(iframe, watchUrl, subtitleCallback, callback)
    }
    return true
}

}
