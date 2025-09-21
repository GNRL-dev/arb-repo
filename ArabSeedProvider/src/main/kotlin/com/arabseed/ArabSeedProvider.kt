package com.arabseed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
//import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import android.net.Uri
import org.json.JSONObject

class ArabSeed : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://a.asd.homes"
    override var name = "ArabSeed"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // --- Parse search items ---
    private fun Element.toSearchResponse(): SearchResponse? {
        val href = this.attr("href") ?: return null
        val title = selectFirst("h3")?.text() ?: this.attr("title") ?: return null

      val posterUrl = selectFirst("img")?.attr("data-src")
    ?: selectFirst(".post__image img")?.attr("src")
    

        println("ArabSeedProvider: Parsed search item -> title=$title, posterUrl=$posterUrl")

        return newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    // --- Home categories ---
    override val mainPage = mainPageOf(
        "$mainUrl/main0/" to "الرئيسية",
        "$mainUrl/category/foreign-movies-6/" to "أفلام اجنبي",
        "$mainUrl/category/asian-movies/" to "افلام آسيوية",
        "$mainUrl/category/arabic-movies-5/" to "افلام عربي",
        "$mainUrl/category/foreign-series-2/" to "مسلسلات اجنبي",
        "$mainUrl/category/arabic-series-2/" to "مسلسلات عربي",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84-%d9%83%d9%88%d8%b1%d9%8a%d9%87/" to "مسلسلات كورية",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d9%86%d9%8a%d9%85%d9%8a%d8%b4%d9%86/" to "أفلام انيميشن",
        "$mainUrl/category/cartoon-series/" to " أنمي"
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


        val episodes = doc.select("ul.episodes__list li a").map {
            val rawName = it.selectFirst(".epi__num")?.text()?.trim() ?: "Episode"
            val cleanName = rawName.replace(Regex("(?<=\\D)(?=\\d)"), " ")

            newEpisode(it.attr("href")) {
                this.name = cleanName
            }
        }.ifEmpty {
            doc.select("a.watch__btn").map {
                newEpisode(it.attr("href")) {
                    this.name = "مشاهدة الان"
                }
            }
        }

        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.year = year
            }
        }
    }

override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    println("=== [ArabSeed] loadLinks START ===")
    println("Movie page: $data")

    val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mainUrl)

    // 1. Fetch movie page
    val doc = app.get(data, headers = headers).document
    println("Fetched movie page. Title: ${doc.title()}")

    // 2. Extract post_id
    val postId = doc.selectFirst("ul.qualities__list li[data-post]")?.attr("data-post")
    println("Extracted postId = $postId")

    // 3. Extract csrf_token from JS
    val csrf = Regex("csrf__token\\s*:\\s*\"(\\w+)\"")
        .find(doc.html())?.groupValues?.get(1)
    println("Extracted csrf_token = $csrf")

    if (postId.isNullOrBlank() || csrf.isNullOrBlank()) {
        println("!!! ERROR: Missing postId or csrf_token")
        return false
    }

    // 4. Loop qualities
    val qualities = listOf("480", "720", "1080")
    val ajaxUrl = "$mainUrl/get__quality__servers/"

    for (q in qualities) {
        try {
            println("=== Trying quality $q ===")

            val body = mapOf(
                "post_id" to postId,
                "quality" to q,
                "csrf_token" to csrf
            )
            println("POST $ajaxUrl with $body")

            val json = app.post(ajaxUrl, data = body, headers = headers).parsed<Map<String, Any?>>()
            val iframeUrl = json["server"] as? String
            println("AJAX returned iframeUrl = $iframeUrl")

            if (iframeUrl.isNullOrBlank()) continue

            // 5. Fetch iframe
            val iframeDoc = app.get(iframeUrl, headers = mapOf("Referer" to data)).document
            val videoUrl = iframeDoc.selectFirst("video > source")?.attr("src")
                ?: iframeDoc.selectFirst("video")?.attr("src")
            println("Extracted videoUrl = $videoUrl")

            if (videoUrl.isNullOrBlank()) {
                println("!!! ERROR: No video found for $q")
                continue
            }

            // 6. Return link
            callback.invoke(
                newExtractorLink(
                    source = "ArabSeed",
                    name = "ArabSeed ${q}p",
                    url = videoUrl,
                    ){
                    referer = iframeUrl
                  //  quality = q.toInt(),
                  //  isM3u8 = videoUrl.endsWith(".m3u8")
                }
            )
            println(">>> SUCCESS: $q → $videoUrl")
        } catch (e: Exception) {
            println("!!! ERROR: Failed $q → ${e.message}")
        }
    }

    println("=== [ArabSeed] loadLinks END ===")
    return true
}



}
