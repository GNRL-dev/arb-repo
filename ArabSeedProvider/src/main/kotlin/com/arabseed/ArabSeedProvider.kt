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
            this.quality = quality
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
        val doc = app.get(url, interceptor = CloudflareKiller()).document
        val items = doc.select("a.movie__block").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = doc.select(".page-numbers a").isNotEmpty())
    }

    // --- Fixed search using /find/ endpoint ---
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/find/?word=${query.replace(" ", "+")}&type="
        val doc = app.get(url, interceptor = CloudflareKiller()).document
        return doc.select("a.movie__block").mapNotNull { it.toSearchResponse() }
    }

    // --- Load detail page (movie or series) ---
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, interceptor = CloudflareKiller()).document
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
         val doc = app.get(data, interceptor = CloudflareKiller()).document
        val watchUrl = doc.selectFirst("a.watch__btn")?.attr("href")
            ?: doc.selectFirst("a[href*=\"/watch/\"]")?.attr("href")
            ?: return false

         val watchDoc = app.get(watchUrl, referer = mainUrl, interceptor = CloudflareKiller()).document
        val iframes = watchDoc.select("iframe[src]").map { it.attr("src") }

        for (iframe in iframes) {
            val iframeDoc = app.get(iframe, referer = watchUrl).document
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
                    ){
                        referer = mainUrl
                       // quality = media.text().getIntFromText() ?: Qualities.Unknown.value
                        this.headers = headers
                      //  extractorData = null,
                        type = ExtractorLinkType.VIDEO
                    }
                )
            }

            // hand off to built-in extractors too
            loadExtractor(iframe, watchUrl, subtitleCallback, callback)
        }
        return true
    }
}
