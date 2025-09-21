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

/*override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val baseHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to mainUrl,
        "Accept-Language" to "ar,en;q=0.9"
    )

    println("=== [ArabSeed] loadLinks START ===")
    println("Movie page URL: $data")

    // 1. Load the main watch page
    val doc = app.get(data, headers = baseHeaders).document
    println("Fetched movie page. Title: ${doc.title()}")

    // 2. Get the post ID
    val postId = doc.selectFirst("ul.qualities__list li")?.attr("data-post")
    println("Extracted postId = $postId")
    if (postId == null) {
        println("!!! ERROR: Could not find data-post on page")
        return false
    }

    // 3. Define qualities
    val qualities = listOf("480", "720", "1080")

    for (q in qualities) {
        try {
            println("=== Trying quality: $q ===")

            // 4. Call the AJAX endpoint
            val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
            val body = mapOf(
                "action" to "getquality",
                "post" to postId,
                "server" to "0",
                "quality" to q
            )

            println("POST $ajaxUrl with body $body")

            val json = app.post(
                ajaxUrl,
                data = body,
                headers = baseHeaders
            ).parsed<Map<String, Any?>>()

            println("AJAX Response: $json")

            val iframeUrl = json["server"] as? String
            println("Extracted iframeUrl = $iframeUrl")
            if (iframeUrl == null) {
                println("!!! ERROR: No iframeUrl found for quality $q")
                continue
            }

            // 5. Open iframe
            val iframeDoc = app.get(iframeUrl, headers = mapOf("Referer" to data)).document
            println("Fetched iframe. Title: ${iframeDoc.title()}")

            // 6. Extract video source
            val videoUrl = iframeDoc.selectFirst("video > source")?.attr("src")
            println("Extracted videoUrl = $videoUrl")
            if (videoUrl == null) {
                println("!!! ERROR: No <video> source found in iframe for quality $q")
                continue
            }

            // 7. Callback
            println(">>> SUCCESS: Found video for $q → $videoUrl")
            callback.invoke(
                newExtractorLink(
                    source = "ArabSeed",
                    name = "ArabSeed $q",
                    url = videoUrl,
                    ){
                    referer = iframeUrl
                    quality = q.toIntOrNull() ?: 0
                 //   isM3u8 = videoUrl.endsWith(".m3u8")
                }
            )
        } catch (e: Exception) {
            println("!!! ERROR: Exception while fetching quality $q → ${e.message}")
        }
    }

    println("=== [ArabSeed] loadLinks END ===")
    return true
}*/
override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    // Adjust these constants to your provider
    val baseUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    val mainHost = mainUrl // your provider main URL, e.g. "https://a.asd.homes"

    fun log(s: String) = println("=== [Provider] $s")

    log("loadLinks START -> $data")
    val defaultHeaders = mapOf(
        "User-Agent" to baseUserAgent,
        "Referer" to mainHost,
        "Accept-Language" to "ar,en;q=0.9"
    )

    // 1) Fetch main watch page
    val mainDoc = try {
        app.get(data, headers = defaultHeaders).document
    } catch (e: Exception) {
        log("!!! ERROR: Failed to fetch main page: ${e.message}")
        return false
    }
    log("Fetched movie page. Title: ${mainDoc.title()}")

    // 2) Try multiple ways to extract postId (data-post)
    var postId: String? = null
    try {
        // Primary: from qualities list li[data-post]
        postId = mainDoc.selectFirst("ul.qualities__list li[data-post]")?.attr("data-post")
        if (!postId.isNullOrBlank()) {
            log("postId found (qualities list): $postId")
        }
    } catch (_: Exception) {}

    if (postId.isNullOrBlank()) {
        try {
            // Secondary: any element with data-post attribute
            postId = mainDoc.selectFirst("[data-post]")?.attr("data-post")
            if (!postId.isNullOrBlank()) {
                log("postId found (generic data-post): $postId")
            }
        } catch (_: Exception) {}
    }

    if (postId.isNullOrBlank()) {
        try {
            // Tertiary: check for JS variables inside scripts like post: '638352' or post_id = 638352
            val scripts = mainDoc.select("script").joinToString("\n") { it.data() ?: "" }
            val regexes = listOf(
                Regex("""['"]post['"]\s*[:=]\s*['"]?(\d{4,})['"]?"""),
                Regex("""['"]post_id['"]\s*[:=]\s*['"]?(\d{4,})['"]?"""),
                Regex("""post:\s*['"]?(\d{4,})['"]?""")
            )
            for (r in regexes) {
                val m = r.find(scripts)
                if (m != null) {
                    postId = m.groupValues[1]
                    log("postId found (script regex): $postId via ${r.pattern}")
                    break
                }
            }
        } catch (_: Exception) {}
    }

    if (postId.isNullOrBlank()) {
        try {
            // Fallback: sometimes the watch__btn contains data-id or data-post
            val watchBtn = mainDoc.selectFirst("a.watch__btn, button.watch__btn, .watch__btn")
            postId = watchBtn?.attr("data-post") ?: watchBtn?.attr("data-id")
            if (!postId.isNullOrBlank()) {
                log("postId found (watch__btn attr): $postId")
            }
        } catch (_: Exception) {}
    }

    if (postId.isNullOrBlank()) {
        log("!!! ERROR: Could not find postId on page. Dumping small HTML snippet for debug:")
        val snippet = try { mainDoc.select("ul.qualities__list").outerHtml().take(1200) } catch (_: Exception) { "no snippet" }
        log("QUALITIES SNIPPET: $snippet")
        log("=== [Provider] loadLinks END (no postId) ===")
        return false
    }

    // 3) Prepare qualities order (prefer highest first if you want best quality)
    val qualitiesToTry = listOf("1080", "720", "480")

    // 4) AJAX endpoint
    val ajaxEndpoint = "$mainHost/wp-admin/admin-ajax.php"

    // 5) We'll try servers in order. Many sites provide up to 5-6 servers; try 0..5 by default.
    val maxServersToTry = 6

    // 6) Helper to fetch iframe and extract video URL
    fun extractVideoFromIframe(iframeUrl: String, referer: String): String? {
        log("Fetching iframe: $iframeUrl (referer: $referer)")
        return try {
            val iframeResp = app.get(iframeUrl, headers = mapOf(
                "User-Agent" to baseUserAgent,
                "Referer" to referer,
                "Accept-Language" to "ar,en;q=0.9"
            )).document

            // 6.a First try: direct <video> tag
            val vTag = iframeResp.selectFirst("video#player_1_html5_api, video.vjs-tech, video")
            val srcFromVideo = vTag?.attr("src") ?: iframeResp.selectFirst("video > source")?.attr("src")
            if (!srcFromVideo.isNullOrBlank()) {
                log("Found video src via <video>/<source>: $srcFromVideo")
                return srcFromVideo
            }

            // 6.b Next: search for any direct .mp4 or .m3u8 link in the HTML (regex)
            val htmlText = iframeResp.outerHtml()
            // Common video patterns
            val mp4Regex = Regex("""https?://[^\s'"]+?\.(?:mp4|m3u8)(?:\?[^\s'"]*)?""")
            val mp4Match = mp4Regex.find(htmlText)
            if (mp4Match != null) {
                val found = mp4Match.value
                log("Found video via regex in iframe HTML: $found")
                return found
            }

            // 6.c If still not found, sometimes the iframe returns obfuscated JS that needs header tweaks.
            log("No direct video found in iframe HTML. (iframe HTML length = ${htmlText.length})")
            null
        } catch (e: Exception) {
            log("!!! ERROR: Failed to fetch/parse iframe: ${e.message}")
            null
        }
    }

    // 7) Main loop: for each quality, call AJAX to get server URL(s) then extract the real video link
    for (quality in qualitiesToTry) {
        log("=== Trying QUALITY $quality ===")
        var ajaxJson: Map<String, Any?>? = null

        // We'll try servers 0..(maxServersToTry-1) and also attempt server omitted in case the endpoint allows returning default.
        // But first do one POST with server=0 to get 'html' which often lists all servers (so we can know actual server count).
        try {
            val body = mapOf(
                "action" to "getquality",
                "post" to postId,
                "server" to "0",
                "quality" to quality
            )
            log("POST $ajaxEndpoint body=$body")
            val resp = app.post(ajaxEndpoint, data = body, headers = defaultHeaders)
            // Some app.post().parsed might already parse JSON; guard for both text or parsed
            ajaxJson = try {
                resp.parsed<Map<String, Any?>>()
            } catch (_: Exception) {
                // fallback: parse raw text as JSON using simple approach
                val txt = resp.text
                log("AJAX raw response length = ${txt.length}")
                // Try to decode with built-in parsed again, but if not available we attempt string contains
                // Cloudstream's Response.parsed should usually work; otherwise we'll attempt naive detection below
                null
            }
            log("AJAX response: ${ajaxJson?.keys ?: "parsed==null"}")
        } catch (e: Exception) {
            log("!!! ERROR: AJAX call failed for quality $quality: ${e.message}")
            ajaxJson = null
        }

        // If we couldn't parse JSON via parsed(), attempt to fetch response as text and parse server field manually
        if (ajaxJson == null) {
            try {
                val body = mapOf("action" to "getquality", "post" to postId, "server" to "0", "quality" to quality)
                val rawResp = app.post(ajaxEndpoint, data = body, headers = defaultHeaders).text
                log("AJAX raw text (first 600 chars): ${rawResp.take(600)}")
                // naive extraction of "server":"...url..."
                val serverRegex = Regex(""""server"\s*:\s*"([^"]+)"""")
                val match = serverRegex.find(rawResp)
                val serverUrl = match?.groups?.get(1)?.value
                val htmlRegex = Regex(""""html"\s*:\s*"((?:.|\\n)*?)"""")
                val htmlMatch = htmlRegex.find(rawResp)
                val htmlPart = htmlMatch?.groups?.get(1)?.value?.replace("\\r","")?.replace("\\n","")?.replace("\\t","")
                ajaxJson = mapOf("server" to serverUrl, "html" to htmlPart)
                log("Parsed fallback AJAX: server=$serverUrl")
            } catch (e: Exception) {
                log("!!! ERROR: fallback AJAX parse failed: ${e.message}")
                ajaxJson = null
            }
        }

        // If still null, skip this quality
        if (ajaxJson == null) {
            log("No AJAX JSON for quality $quality, moving to next quality")
            continue
        }

        // Extract server URL from AJAX response
        val serverUrlAny = ajaxJson["server"]
        var primaryIframeUrl: String? = (serverUrlAny as? String)?.takeIf { it.isNotBlank() }
        log("Primary iframe url from AJAX = $primaryIframeUrl")

        // Also inspect html part to find all servers (so we can iterate over data-server indices)
        val htmlPart = (ajaxJson["html"] as? String)?.replace("\\/", "/")
        val foundServersFromHtml = mutableListOf<Int>()
        if (!htmlPart.isNullOrBlank()) {
            // Look for data-server="X" occurrences
            val serverIndexRegex = Regex("""data-server=["']?(\d+)["']?""")
            serverIndexRegex.findAll(htmlPart).forEach { m ->
                val idx = m.groupValues[1].toIntOrNull()
                if (idx != null && !foundServersFromHtml.contains(idx)) foundServersFromHtml.add(idx)
            }
            log("Found server indices from AJAX html = $foundServersFromHtml")
        }

        // If primaryIframeUrl is null but html contains an <li data-post ...> maybe it includes server url in different field - fallback to parsing main page server list
        if (primaryIframeUrl.isNullOrBlank()) {
            // try reading a.href from mainDoc watch__btn or similar
            val watchHref = mainDoc.selectFirst("a.watch__btn")?.attr("href")
            if (!watchHref.isNullOrBlank()) {
                primaryIframeUrl = watchHref
                log("Fallback: using watch__btn href as iframeUrl = $primaryIframeUrl")
            }
        }

        // Build list of server indices to try
        val serversToTry = if (foundServersFromHtml.isNotEmpty()) foundServersFromHtml.sorted() else (0 until maxServersToTry).toList()

        // If AJAX returned a primary iframe URL, try it first
        val triedIframeUrls = mutableSetOf<String>()
        if (!primaryIframeUrl.isNullOrBlank()) {
            triedIframeUrls.add(primaryIframeUrl!!)
            val videoUrl = extractVideoFromIframe(primaryIframeUrl!!, data)
            if (!videoUrl.isNullOrBlank()) {
                log(">>> SUCCESS: quality=$quality (primary iframe) -> $videoUrl")
                callback.invoke(
                    ExtractorLink(
                        source = "ArabSeed",
                        name = "ArabSeed $quality",
                        url = videoUrl,
                        referer = primaryIframeUrl,
                        quality = quality.toIntOrNull() ?: 0,
                        isM3u8 = videoUrl.endsWith(".m3u8")
                    )
                )
                // If you want the first success only, return true here.
                // Otherwise comment out the next line to collect all qualities.
                // return true
            } else {
                log("Primary iframe did not yield video for quality $quality")
            }
        }

        // 8) Try servers by index using AJAX repeated calls (server param changes)
        var foundAnyForThisQuality = false
        for (serverIdx in serversToTry) {
            try {
                log("Trying server index = $serverIdx for quality $quality")
                val body = mapOf(
                    "action" to "getquality",
                    "post" to postId,
                    "server" to serverIdx.toString(),
                    "quality" to quality
                )
                val ajaxResp = app.post(ajaxEndpoint, data = body, headers = defaultHeaders)
                val parsed = try {
                    ajaxResp.parsed<Map<String, Any?>>()
                } catch (_: Exception) {
                    val txt = ajaxResp.text
                    val serverRegex = Regex(""""server"\s*:\s*"([^"]+)"""")
                    val match = serverRegex.find(txt)
                    mapOf("server" to match?.groups?.get(1)?.value, "html" to txt)
                }
                val iframeUrl = (parsed["server"] as? String)?.takeIf { it.isNotBlank() }
                log("AJAX(server=$serverIdx) returned iframe = $iframeUrl")
                if (iframeUrl.isNullOrBlank()) {
                    log("No iframeUrl from AJAX(server=$serverIdx)")
                    continue
                }
                if (triedIframeUrls.contains(iframeUrl)) {
                    log("Already tried this iframe url: $iframeUrl -> skipping")
                    continue
                }
                triedIframeUrls.add(iframeUrl)

                val videoUrl = extractVideoFromIframe(iframeUrl, data)
                if (!videoUrl.isNullOrBlank()) {
                    log(">>> SUCCESS: Found video for quality=$quality server=$serverIdx -> $videoUrl")
                    callback.invoke(
                        newExtractorLink(
                            source = "ArabSeed",
                            name = "ArabSeed $quality",
                            url = videoUrl,
                            ){
                            referer = iframeUrl
                            quality = quality.toIntOrNull() ?: 0
                          //  isM3u8 = videoUrl.endsWith(".m3u8")
                        }
                    )
                    foundAnyForThisQuality = true
                    // If you want only highest-success link per quality, break server loop
                    break
                } else {
                    log("iframe did not provide video for server=$serverIdx")
                }
            } catch (e: Exception) {
                log("Exception while trying server $serverIdx for quality $quality -> ${e.message}")
            }
        }

        if (foundAnyForThisQuality) {
            // If you want to stop after first successful quality (e.g., highest-first), uncomment:
            // return true
            log("Finished attempts for quality $quality (found at least one).")
        } else {
            log("No working server found for quality $quality, moving to next quality.")
        }
    }

    log("=== [Provider] loadLinks END ===")
    return true
}


}
