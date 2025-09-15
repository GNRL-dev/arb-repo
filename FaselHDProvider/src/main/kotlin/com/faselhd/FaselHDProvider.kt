package com.faselhd

import com.lagradost.cloudstream3.*
import com.lagradost.nicehttp.requestCreator
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element

class FaselHD : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://faselhds.life"
    private val alternativeUrl = "https://www.faselhds.life"
    override var name = "FaselHD"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama, TvType.Anime)
    private val cfKiller = CloudflareKiller()

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val url = select("div.postDiv a").attr("href") ?: return null
        val posterUrl = select("div.postDiv a div img").attr("data-src")
            ?: select("div.postDiv a div img").attr("src")
        val title = select("div.postDiv a div img").attr("alt")
        val quality = select(".quality").first()?.text()?.replace("1080p |-".toRegex(), "")
        val type = if (title.contains("فيلم")) TvType.Movie else TvType.TvSeries

        return newMovieSearchResponse(
            title.replace("الموسم الأول|برنامج|فيلم|مترجم|اون لاين|مسلسل|مشاهدة|انمي|أنمي".toRegex(), ""),
            url,
            type
        ) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(quality)
            this.posterHeaders = cfKiller.getCookieHeaders(alternativeUrl).toMap()
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/all-movies/page/" to "جميع الافلام",
        "$mainUrl/movies_top_views/page/" to "الافلام الاعلي مشاهدة",
        "$mainUrl/dubbed-movies/page/" to "الأفلام المدبلجة",
        "$mainUrl/movies_top_imdb/page/" to "الافلام الاعلي تقييما IMDB",
        "$mainUrl/series/page/" to "مسلسلات",
        "$mainUrl/recent_series/page/" to "المضاف حديثا",
        "$mainUrl/anime/page/" to "الأنمي",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        var doc = app.get(request.data + page).document
        if (doc.select("title").text() == "Just a moment...") {
            doc = app.get(
                request.data.replace(mainUrl, alternativeUrl) + page,
                interceptor = cfKiller,
                timeout = 120
            ).document
        }
        val list = doc.select("div[id=\"postList\"] div[class=\"col-xl-2 col-lg-2 col-md-3 col-sm-3\"]")
            .mapNotNull { element ->
                element.toSearchResponse()
            }
        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.replace(" ", "+")
        val results = mutableListOf<SearchResponse>()
        var page = 1

        while (true) {
            val url = "$mainUrl/page/$page?s=$q"
            var d = app.get(url).document

            if (d.select("title").text().contains("Just a moment...")) {
                d = app.get("$alternativeUrl/page/$page?s=$q", interceptor = cfKiller, timeout = 120).document
            }

            val items = d.select("div[id=\"postList\"] div[class=\"col-xl-2 col-lg-2 col-md-3 col-sm-3\"]")
                .mapNotNull { it.toSearchResponse() }

            if (items.isEmpty()) break

            results += items
            page++
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        var doc = app.get(url).document
        if (doc.select("title").text() == "Just a moment...") {
            doc = app.get(url, interceptor = cfKiller, timeout = 120).document
        }
        val isMovie = doc.select("div.epAll").isEmpty()
        val posterUrl = doc.select("div.posterImg img").attr("src")
            .ifEmpty { doc.select("div.seasonDiv.active img").attr("data-src") }

        val year = doc.select("div[id=\"singleList\"] div[class=\"col-xl-6 col-lg-6 col-md-6 col-sm-6\"]").firstOrNull {
            it.text().contains("سنة|موعد".toRegex())
        }?.text()?.getIntFromText()

        val title =
            doc.select("title").text().replace(" - فاصل إعلاني", "")
                .replace("الموسم الأول|برنامج|فيلم|مترجم|اون لاين|مسلسل|مشاهدة|انمي|أنمي|$year".toRegex(), "")

        val duration = doc.select("div[id=\"singleList\"] div[class=\"col-xl-6 col-lg-6 col-md-6 col-sm-6\"]").firstOrNull {
            it.text().contains("مدة|توقيت".toRegex())
        }?.text()?.getIntFromText()

        val tags = doc.select("div[id=\"singleList\"] div[class=\"col-xl-6 col-lg-6 col-md-6 col-sm-6\"]:contains(تصنيف الفيلم) a").map {
            it.text()
        }

        val recommendations = doc.select("div#postList div.postDiv").mapNotNull {
            it.toSearchResponse()
        }

        val synopsis = doc.select("div.singleDesc p").text()

        return if (isMovie) {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = synopsis
                this.duration = duration
                this.tags = tags
                this.recommendations = recommendations
                this.posterHeaders = cfKiller.getCookieHeaders(alternativeUrl).toMap()
            }
        } else {
            val episodes = ArrayList<Episode>()

            doc.select("div.epAll a").map {
                episodes.add(
                    newEpisode(it.attr("href")) {
                        this.name = it.text()
                        this.season = doc.select("div.seasonDiv.active div.title").text().getIntFromText() ?: 1
                        this.episode = it.text().getIntFromText()
                    }
                )
            }

            doc.select("div[id=\"seasonList\"] div[class=\"col-xl-2 col-lg-3 col-md-6\"] div.seasonDiv")
                .not(".active").apmap { it ->
                    val id = it.attr("onclick").replace(".*\\/\\?p=|'".toRegex(), "")
                    var s = app.get("$mainUrl/?p=" + id).document
                    if (s.select("title").text() == "Just a moment...") {
                        s = app.get("$alternativeUrl/?p=" + id, interceptor = cfKiller).document
                    }
                    s.select("div.epAll a").map {
                        episodes.add(
                            newEpisode(it.attr("href")) {
                                this.name = it.text()
                                this.season = s.select("div.seasonDiv.active div.title").text().getIntFromText()
                                this.episode = it.text().getIntFromText()
                            }
                        )
                    }
                }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinct().sortedBy { it.episode }) {
                this.duration = duration
                this.posterUrl = posterUrl
                this.year = year
                this.plot = synopsis
                this.tags = tags
                this.recommendations = recommendations
                this.posterHeaders = cfKiller.getCookieHeaders(alternativeUrl).toMap()
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
    if (doc.text().contains("Just a moment", ignoreCase = true)) {
        doc = app.get(data, interceptor = cfKiller).document
    }

    // 🔹 Only look for iframe (no download candidates)
    val iframeCandidate = doc.selectFirst("iframe[name=\"player_iframe\"]")
        ?.attr("src")
        ?.takeIf { it.isNotBlank() }

    println("FaselHD → Iframe candidate = $iframeCandidate")

    iframeCandidate?.let { url ->
        println("FaselHD → Iframe URL = $url")

        val regex = Regex("""https?://[^"]+\.m3u8""")

        // 1️⃣ Try with WebViewResolver
        val result = WebViewResolver(regex).resolveUsingWebView(
            requestCreator("GET", url, referer = mainUrl)
        )
        var m3u8Url = result?.toString()

        if (m3u8Url.isNullOrBlank()) {
            // 2️⃣ Fallback: scan raw HTML
            println("FaselHD → No .m3u8 from WebView. Scanning raw HTML...")

            val iframeDoc = app.get(
                url,
                referer = mainUrl,
                interceptor = cfKiller,
                timeout = 120
            ).document

            val html = iframeDoc.outerHtml()
            m3u8Url = regex.find(html)?.value
        }

        if (!m3u8Url.isNullOrBlank()) {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                "Origin" to mainUrl,
                "Referer" to mainUrl
            )

            println("✅ FaselHD → Sending HLS to player:")
            println("URL = $m3u8Url")
            println("Headers = $headers")

            callback(
                newExtractorLink(
                    source = name,
                    name = "$name HLS",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = mainUrl
                    quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
        } else {
            println("❌ FaselHD → Still no .m3u8 found in iframe.")
        }
    }

    return true
}


}
