package com.mycima

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*  // for Qualities, newExtractorLink, etc.
import org.jsoup.nodes.Element


class MyCimaProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://wecima.buzz"
    override var name = "MyCimaProvider"
    override var usesWebView = false
    override var hasMainPage = true
    override var supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)
    

    private fun String.getImageURL(): String? =
        this.replace("--im(age|g):url\\(|\\);".toRegex(), "")

    private fun String.getIntFromText(): Int? =
        Regex("""\d+""").find(this)?.value?.toIntOrNull()

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = select("div.Thumb--GridItem a").attr("href")
        val poster = select("span.BG--GridItem")?.attr("data-lazy-style")?.getImageURL()
        val year = select("div.GridItem span.year")?.text()?.getIntFromText()
        val title = select("div.Thumb--GridItem strong").text()
            .replace("$year", "", true)
            .replace("مشاهدة|فيلم|مسلسل|مترجم".toRegex(), "")
            .replace("( نسخة مدبلجة )", " ( نسخة مدبلجة ) ")

        return newMovieSearchResponse(title, href) {
            //apiName = this@MyCimaProvider.name
            posterUrl = poster
            this.year = year
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/movies/top/page/" to "Top Movies",
        "$mainUrl/movies/page/" to "New Movies",
        "$mainUrl/movies/recent/page/" to "Recently Added Movies",
        "$mainUrl/seriestv/top/page/" to "Top Series",
        "$mainUrl/seriestv/new/page/" to "New Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page).document
        val list = doc.select("div.Grid--WecimaPosts div.GridItem")
            .mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.replace(" ", "%20")
        return listOf(
            "$mainUrl/search/$q",
            "$mainUrl/search/$q/list/series/",
            "$mainUrl/search/$q/list/anime/"
        ).flatMap { url ->
            app.get(url).document.select("div.Grid--WecimaPosts div.GridItem")
                .mapNotNull { if (!it.text().contains("اعلان")) it.toSearchResponse() else null }
        }.distinctBy { it.url }.sortedBy { it.name }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val isMovie = doc.select("ol li:nth-child(3)").text().contains("افلام")
        val posterUrl = doc.select("wecima.separated--top")?.attr("data-lazy-style")?.getImageURL()
            ?: doc.select("meta[itemprop=\"thumbnailUrl\"]")?.attr("content")
            ?: doc.select("wecima.separated--top")?.attr("style")?.getImageURL()
        val year = doc.select("div.Title--Content--Single-begin h1 a.unline")?.text()?.getIntFromText()
        val title = doc.select("div.Title--Content--Single-begin h1").text()
            .replace("($year)", "")
            .replace("مشاهدة|فيلم|مسلسل|مترجم|انمي".toRegex(), "")
        val duration = doc.select("ul.Terms--Content--Single-begin li").firstOrNull {
            it.text().contains("المدة")
        }?.text()?.getIntFromText()
        val synopsis = doc.select("div.StoryMovieContent").text().ifEmpty {
            doc.select("div.PostItemContent").text()
        }
        val tags = doc.select("li:nth-child(3) > p > a").map { it.text() }
        val actors = doc.select("div.List--Teamwork > ul.Inner--List--Teamwork > li").mapNotNull {
            val actorName = it.selectFirst("a > div.ActorName > span")?.text() ?: return@mapNotNull null
            val img = it.attr("style")?.getImageURL() ?: return@mapNotNull null
            Actor(actorName, img)
        }
        val recommendations = doc.select("div.Grid--WecimaPosts div.GridItem").mapNotNull {
            it.toSearchResponse()
        }

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.year = year
                plot = synopsis
                this.tags = tags
                this.duration = duration
                this.recommendations = recommendations
                addActors(actors)
            }
        } else {
            val episodes = mutableListOf<Episode>()
            doc.select("div.Seasons--Episodes div.Episodes--Seasons--Episodes a").forEach {
                newEpisode(it.attr("href")) {
                    name = it.text()
                    episode = it.text().getIntFromText() ?: 0
                }.also { ep -> episodes.add(ep) }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.sortedBy { it.episode }) {
                this.duration = duration
                this.posterUrl = posterUrl
                this.tags = tags
                this.year = year
                plot = synopsis
                this.recommendations = recommendations
                addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        doc.select("ul.List--Download--Wecima--Single:nth-child(2) li").forEach { li ->
            li.select("a").forEach { link ->
                val url = link.attr("href")
                val quality = link.select("resolution").text().getIntFromText() ?: Qualities.Unknown.value

                callback(
                    newExtractorLink(
                        source = this@MyCimaProvider.name,
                        name = name,
                        url = url,
                    )
                )
            }
        }

        return true
    }
}