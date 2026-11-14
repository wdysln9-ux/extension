package com.wdysln9ux.sakunime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class SakunimeProvider : MainAPI() {
    override var mainUrl = "https://sakunime.com"
    override var name = "Sakunime"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val instantLinkLoading = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "$mainUrl/anime/" to "Semua Anime",
        "$mainUrl/anime/ongoing/" to "Ongoing",
        "$mainUrl/anime/completed/" to "Completed",
        "$mainUrl/anime/jadwal/" to "Jadwal Tayang"
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept-Language" to "id-ID,id;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/"
    )

    private fun absolute(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank() || value == "#") return null
        return when {
            value.startsWith("https://") || value.startsWith("http://") -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> mainUrl + value
            else -> "$mainUrl/$value"
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val anchor = if (tagName() == "a") this else selectFirst("a[href]") ?: return null
        val href = absolute(anchor.attr("href")) ?: return null
        if (!href.startsWith(mainUrl) || href == mainUrl ||
            href.contains("/genre/") || href.contains("/jadwal/") ||
            href.contains("/ongoing/") || href.contains("/completed/") ||
            href.contains("/search/")) return null

        val image = selectFirst("img") ?: anchor.selectFirst("img")
        val title = (selectFirst(".title, .anime-title, .entry-title, h2, h3")?.text()
            ?: anchor.attr("title").ifBlank { image?.attr("alt").orEmpty().ifBlank { anchor.text() } })
            .trim()
        if (title.isBlank() || title.length < 2) return null

        val poster = absolute(
            image?.attr("data-src")?.ifBlank {
                image.attr("data-lazy-src").ifBlank { image.attr("data-original").ifBlank { image.attr("src") } }
            }
        )
        val type = if (title.contains("movie", true)) TvType.AnimeMovie else TvType.Anime
        return newAnimeSearchResponse(title, href, type) { posterUrl = poster }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data
        val url = if (page <= 1) base else base + (if (base.contains("?")) "&" else "?") + "page=$page"
        val document = app.get(url, headers = headers).document
        val results = document.select(
            ".anime-card, .anime-item, .film-item, .flw-item, .post, article, .item, a[href*='/anime/']"
        ).mapNotNull { it.toSearchResponse() }.distinctBy { it.url }
        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        // Sakunime's current search route is /search/{query}/.
        // Keep older query-based routes as fallbacks for server-side variants.
        val urls = listOf(
            "$mainUrl/search/$encoded/",
            "$mainUrl/search/?q=$encoded",
            "$mainUrl/search/?keyword=$encoded",
            "$mainUrl/?s=$encoded"
        )
        for (url in urls) {
            try {
                val document = app.get(url, headers = headers).document
                val results = document.select(
                    ".anime-card, .anime-item, .film-item, .flw-item, .post, article, .item, a[href*='/anime/']"
                ).mapNotNull { it.toSearchResponse() }.distinctBy { it.url }
                if (results.isNotEmpty()) return results
            } catch (_: Exception) { }
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = absolute(url) ?: url
        val document = app.get(pageUrl, headers = headers).document
        val title = (
            document.selectFirst("h1, .anime-title, .entry-title, .film-name")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.title()
            ).substringBefore(" | ").substringBefore(" - Sakunime").trim()
        if (title.isBlank()) throw ErrorLoadingException("Judul anime tidak ditemukan")

        val poster = absolute(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst(".anime-poster img, .poster img, .thumb img")?.let {
                    it.attr("data-src").ifBlank { it.attr("data-lazy-src").ifBlank { it.attr("src") } }
                }
        )
        val plot = document.selectFirst(".synopsis, .description, .entry-content, .anime-description, .content")?.text()
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val episodes = document.select(
            "a[href*='/episode/'], a[href*='/watch/'], .episode-list a[href], .episodes a[href], .eps a[href]"
        ).mapNotNull { anchor ->
            val episodeUrl = absolute(anchor.attr("href")) ?: return@mapNotNull null
            val label = anchor.text().trim().ifBlank { anchor.attr("title").trim() }
            if (label.isBlank() || episodeUrl == pageUrl) return@mapNotNull null
            val number = Regex("(?i)(?:episode|eps?|ep)\\s*[-:#.]?\\s*(\\d+(?:\\.\\d+)?)")
                .find(label)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.toInt()
            newEpisode(episodeUrl) {
                name = label
                episode = number
            }
        }.distinctBy { it.data }.reversed()

        if (pageUrl.contains("movie", true) || episodes.isEmpty()) {
            return newMovieLoadResponse(title, pageUrl, TvType.AnimeMovie, pageUrl) {
                posterUrl = poster
                this.plot = plot
            }
        }
        return newTvSeriesLoadResponse(title, pageUrl, TvType.Anime, episodes) {
            posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = absolute(data) ?: data
        val document = app.get(episodeUrl, headers = headers).document
        val seenSubtitles = hashSetOf<String>()

        document.select("track[src], track[kind=subtitles][src], track[kind=captions][src]").forEach { track ->
            val subtitleUrl = absolute(track.attr("src")) ?: return@forEach
            if (!seenSubtitles.add(subtitleUrl)) return@forEach
            val language = track.attr("label").ifBlank {
                track.attr("srclang").ifBlank { "Subtitle" }
            }
            subtitleCallback(SubtitleFile(language, subtitleUrl))
        }

        document.select("a[href], source[src]").forEach { element ->
            val raw = element.attr("href").ifBlank { element.attr("src") }
            val subtitleUrl = absolute(raw) ?: return@forEach
            if (subtitleUrl.matches(Regex("(?i).+\\.(vtt|srt|ass|ssa)(\\?.*)?$")) &&
                seenSubtitles.add(subtitleUrl)) {
                subtitleCallback(SubtitleFile(element.text().ifBlank { "Subtitle" }, subtitleUrl))
            }
        }

        val frames = document.select("iframe[src], video[src], video source[src], source[src]")
            .mapNotNull { element ->
                val raw = element.attr("src")
                absolute(raw)?.takeIf { it.startsWith("http") }
            }.distinct()

        var found = false
        for (frame in frames) {
            try {
                if (frame.matches(Regex("(?i).+\\.(m3u8|mp4|mpd)(\\?.*)?$"))) {
                    callback(
                        newExtractorLink(name, "Sakunime", frame, INFER_TYPE) {
                            this.referer = episodeUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    found = true
                } else {
                    loadExtractor(frame, episodeUrl, subtitleCallback, callback)
                    found = true
                }
            } catch (_: Exception) { }
        }
        return found
    }
}
