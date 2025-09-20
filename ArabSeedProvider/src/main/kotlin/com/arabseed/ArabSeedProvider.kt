package com.arabseed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
    println("ArabSeedProvider: 🚀 ENTERING loadLinks(data=$data)")

    var foundAny = false
    try {
        val doc = app.get(data).document

      val watchUrl = doc.selectFirst("a.watch__btn")?.attr("href")
          ?: doc.selectFirst("a.btn-watch")?.attr("href")   // new class?
          ?: doc.selectFirst("a[href*=\"/play/\"]")?.attr("href")
          ?: doc.selectFirst("a[href*=\"/video/\"]")?.attr("href")

        
        
      //  val watchUrl = doc.selectFirst("a.watch__btn")?.attr("href")
        //   ?: doc.selectFirst("a[href*=\"/watch/\"]")?.attr("href")
 
//if (watchUrl.isNullOrBlank()) {
 //   println("ArabSeedProvider: ⚠️ No watch URL found on page: $data")
//    return foundAny
//}
if (watchUrl.isNullOrBlank()) {
    println("ArabSeedProvider: ⚠️ No watch URL found. First 300 chars of page:\n" +
        doc.outerHtml().take(300))
    return foundAny
}


        
     //   val watchUrl = doc.selectFirst("a.watch__btn")?.attr("href")
        //    ?: doc.selectFirst("a[href*=\"/watch/\"]")?.attr("href")

     //   if (watchUrl.isNullOrBlank()) {
      //      println("ArabSeedProvider: ⚠️ No watch URL found")
       //     return false
     //   }

        println("ArabSeedProvider: 🎬 Found watch URL=$watchUrl")

        val watchDoc = app.get(watchUrl, referer = mainUrl).document
        val iframes = watchDoc.select("iframe[src]").map { it.attr("src") }

        println("ArabSeedProvider: ➡️ Found ${iframes.size} iframe(s) from watch page")

        for (iframe in iframes) {
            println("ArabSeedProvider: 🌐 Checking iframe=$iframe")
            val iframeDoc = app.get(iframe, referer = watchUrl).document

            val qualities = iframeDoc.select("ul.qualities__list li")
            val postId = iframeDoc.selectFirst("input[name=post_id]")?.attr("value")
            val csrfToken = iframeDoc.selectFirst("input[name=csrf_token]")?.attr("value")

            if (qualities.isEmpty() || postId.isNullOrBlank() || csrfToken.isNullOrBlank()) {
                println("ArabSeedProvider: ⚠️ No quality list or tokens found in iframe=$iframe")
                continue
            }

            println("ArabSeedProvider: 🎚️ Found quality switcher with ${qualities.size} option(s)")

            for (q in qualities) {
                val quality = q.attr("data-quality")
                if (quality.isBlank()) continue

                try {
                    println("ArabSeedProvider: 🔍 Requesting quality=$quality")

                    val resp = app.post(
                        url = "$mainUrl/get__quality__servers/",
                        data = mapOf(
                            "post_id" to postId,
                            "quality" to quality,
                            "csrf_token" to csrfToken
                        ),
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Accept" to "application/json, text/javascript, */*; q=0.01",
                            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                        ),
                        referer = iframe
                    )

                    val body = resp.text
                    if (body.isNotBlank() && body.trim().startsWith("{")) {
                        val json = JSONObject(body)
                        val embedUrl = json.optString("server", null)

                        println("ArabSeedProvider: 🖼️ iframeUrl=$embedUrl for quality=${quality}p")

                        if (!embedUrl.isNullOrBlank()) {
                            val embedDoc = app.get(embedUrl, referer = iframe).document
                            val src = embedDoc.selectFirst("video > source")?.attr("src")

                            if (!src.isNullOrBlank()) {
                                println("ArabSeedProvider: ✅ FINAL LINK FOUND -> $src Quality=${quality}p")
                                callback.invoke(
                                    newExtractorLink(
                                        source = this.name,
                                        name = "${quality}p Direct",
                                        url = src,
                                        type = ExtractorLinkType.VIDEO
                                    )
                                )
                                return true
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("ArabSeedProvider: ❌ Error loading quality=$quality → ${e.message}")
                }
            }
        }
    } catch (e: Exception) {
        println("ArabSeedProvider: ❌ Error in loadLinks → ${e.message}")
    }

    println("ArabSeedProvider: 🚫 No valid links extracted")
    return false
}


}
