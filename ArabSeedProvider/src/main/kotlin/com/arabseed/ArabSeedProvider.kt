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
    println("▶️ loadLinks started for $data")

    val doc = app.get(data).document

    // 1) Extract postId + csrf
    val postId = doc.select("li[data-post]").attr("data-post")
    val csrf = doc.select("meta[name=csrf-token]").attr("content")
    println("Step 1: postId=$postId | csrf=$csrf")

    if (postId.isBlank() || csrf.isBlank()) {
        println("❌ Missing postId or csrf_token")
        return false
    }

    val qualities = listOf("480", "720", "1080")
    var foundAny = false

    for (quality in qualities) {
        try {
            println("------ Trying quality $quality ------")

            // 2) Ajax call
            val ajaxResp = app.post(
                "https://a.asd.homes/get__quality__servers/",
                data = mapOf(
                    "post_id" to postId,
                    "quality" to quality,
                    "csrf_token" to csrf
                )
            ).text
            println("Step 2: Ajax resp length=${ajaxResp.length}")

            // 3) Extract iframe url
            val serverUrl = Regex(""""server"\s*:\s*"([^"]+)"""")
                .find(ajaxResp)?.groupValues?.get(1)
            println("Step 3: iframe=$serverUrl")

            if (serverUrl.isNullOrBlank()) continue

            // 4) Open iframe
            val iframeDoc = app.get(serverUrl).document
            val videoUrl = iframeDoc.select("video source").attr("src")
            println("Step 4: video=$videoUrl")

            if (videoUrl.isNotBlank()) {
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        "${this.name} - ${quality}p",
                        videoUrl,
                        ){
                        referer = serverUrl
                      //  quality = quality.toInt(),
                       // isM3u8 = videoUrl.contains(".m3u8")
                    }
                )
                println("✅ Added $quality → $videoUrl")
                foundAny = true
            } else {
                println("❌ No video tag for $quality")
            }
        } catch (e: Exception) {
            println("⚠️ Error $quality → ${e.message}")
        }
    }

    println("▶️ loadLinks finished. foundAny=$foundAny")
    return foundAny
}

}
