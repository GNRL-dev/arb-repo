package com.arabseed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.net.Uri

class ArabSeed : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://a.asd.homes"
    override var name = "ArabSeed"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // --- Parse search items ---
    /*private fun Element.toSearchResponse(): SearchResponse? {
        val href = attr("href") ?: return null
        val title = selectFirst("h3")?.text() ?: attr("title") ?: return null
        val poster = selectFirst(".post__image img")?.attr("src")?.let { fixUrl(it) }?.let {
            Uri.encode(it, "@")
        }

        return newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
            this.posterUrl = poster
        }
    }*/
   private fun Element.toSearchResponse(): SearchResponse? {
    val href = this.attr("href") ?: return null
    val title = selectFirst("h3")?.text() ?: this.attr("title") ?: return null

    val posterUrl = selectFirst(".post__image img")?.attr("src")
        ?.replace("-304x450.webp", ".jpg")

    println("=== ArabSeed DEBUG fixed poster: $posterUrl")

    return newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
        this.posterUrl = posterUrl
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
        val doc = app.get(url).document
        val items = doc.select("a.movie__block").mapNotNull { it.toSearchResponse() }
        val hasNext = doc.select(".page-numbers a").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext)
    }

    // --- Search ---
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/find/?word=${query.replace(" ", "+")}&type="
        val doc = app.get(url).document
        return doc.select("a.movie__block").mapNotNull { it.toSearchResponse() }
    }
    private fun parseDuration(text: String): Int? {
    // Arabic: "120 دقيقة"
    Regex("(\\d+)\\s*دقيقة").find(text)?.let { return it.groupValues[1].toIntOrNull() }

    // Arabic: "2 ساعة"
    Regex("(\\d+)\\s*ساعة").find(text)?.let { return (it.groupValues[1].toIntOrNull() ?: 0) * 60 }

    // English: "2h 15m"
    Regex("(\\d+)h\\s*(\\d+)m").find(text)?.let {
        val h = it.groupValues[1].toIntOrNull() ?: 0
        val m = it.groupValues[2].toIntOrNull() ?: 0
        return h * 60 + m
    }

    // English: "90m"
    Regex("(\\d+)m").find(text)?.let { return it.groupValues[1].toIntOrNull() }

    return null
}


    // --- Load details ---
override suspend fun load(url: String): LoadResponse {
    val doc = app.get(url).document

    val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
        ?: doc.selectFirst("title")?.text().orEmpty()
    val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
    val plot = doc.selectFirst("div.post__story p")?.text()
        ?: doc.selectFirst("meta[name=description]")?.attr("content")

    val year = doc.selectFirst(".info__area li:contains(سنة العرض) a")?.text()?.toIntOrNull()
    val genres = doc.select(".info__area li:contains(نوع العرض) a").map { it.text() }
    val durationText = doc.selectFirst(".info__area li:contains(مدة العرض)")?.text()
    val duration = durationText?.let { parseDuration(it) }

    // Episodes or movie
    val episodes = doc.select("ul.episodes__list li a").map {
        newEpisode(it.attr("href")) {
            this.name = it.selectFirst(".epi__num")?.text()?.trim() ?: "Episode"
        }
    }.ifEmpty {
        doc.select("a.watch__btn").map {
            newEpisode(it.attr("href")) { this.name = "مشاهدة الان" }
        }
    }

    return if (episodes.size > 1) {
        newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            this.year = year
            this.duration = duration
        }
    } else {
        newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            this.year = year
            this.duration = duration
        }
    }
}

    // --- Extract links ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val watchUrl = doc.selectFirst("a.watch__btn")?.attr("href")
            ?: doc.selectFirst("a[href*=\"/watch/\"]")?.attr("href")
            ?: return false

        val watchDoc = app.get(watchUrl, referer = mainUrl).document
        val iframes = watchDoc.select("iframe[src]").map { it.attr("src") }

        for (iframe in iframes) {
            val iframeDoc = app.get(iframe, referer = watchUrl).document

            iframeDoc.select("source").forEach { sourceEl ->
                val src = sourceEl.attr("src")
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "Direct",
                        url = src,
                        type = ExtractorLinkType.VIDEO
                    )
                )
            }

            // hand off to extractors
            loadExtractor(iframe, watchUrl, subtitleCallback, callback)
        }
        return true
    }
}
