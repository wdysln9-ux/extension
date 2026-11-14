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
        val anchor = if (tagName() == "a") this else selectFirst("a[href*='/anime/']") ?: return null
        val href = absolute(anchor.attr("href")) ?: return null
        val path = try { java.net.URI(href).path.trimEnd('/') } catch (_: Exception) { return null }
        val slug = path.substringAfterLast("/")
        if (!path.matches(Regex("/anime/[^/]+")) ||
            slug in setOf("ongoing", "completed", "genre", "jadwal")) return null

        // Sakunime's cards can place the image and title beside the anime anchor,
        // so inspect a few parent levels when the anchor itself has no poster.
        var card: Element? = anchor
        var image: Element? = anchor.selectFirst("img")
        var title = anchor.attr("title").trim().ifBlank { anchor.text().trim() }
        repeat(4) {
            if (card == null || (image != null && title.isNotBlank())) return@repeat
            card = card?.parent()
            if (image == null) image = card?.selectFirst("img")
            if (title.isBlank()) {
                title = card?.selectFirst("h2, h3, .title, .anime-title")?.text()?.trim().orEmpty()
            }
        }
        if (title.isBlank()) title = image?.attr("alt").orEmpty().trim()
        if (title.isBlank()) return null

        val poster = image?.let { img ->
            val raw = listOf("data-src", "data-lazy-src", "data-original", "src")
                .asSequence()
                .map { img.attr(it).trim() }
                .firstOrNull { it.isNotBlank() }
            absolute(raw)
        }
        val type = if (title.contains("movie", true) || slug.contains("movie", true))
            TvType.AnimeMovie else TvType.Anime
        return newAnimeSearchResponse(title, href, type) { posterUrl = poster }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data
        val url = if (page <= 1) base else base + (if (base.contains("?")) "&" else "?") + "page=$page"
        val document = app.get(url, headers = headers).document
        val results = document.select("a[href*='/anime/']")
            .mapNotNull { it.toSearchResponse() }.distinctBy { it.url }
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
                val results = document.select("a[href*='/anime/']")
                    .mapNotNull { it.toSearchResponse() }.distinctBy { it.url }
                if (results.isNotEmpty()) return results
            } catch (_: Exception) { }
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = absolute(url) ?: url
        val document = app.get(pageUrl, headers = headers).document
        val title = (
            document.selectFirst("h1, .pg-bar-title, .anime-title, .entry-title, .film-name")?.text()
                ?: document.selectFirst("#playerShell")?.attr("data-ep-title")
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
            "a.watch-eprow[href], .episode-list a[href*='/watch/'], a[href*='/episode/'], a[href*='/watch/']"
        ).mapNotNull { anchor ->
            val episodeUrl = absolute(anchor.attr("href")) ?: return@mapNotNull null
            val label = anchor.attr("title").trim().ifBlank { anchor.text().trim() }.ifBlank {
                Regex("(?i)(?:episode|ep|eps)\\s*[-:#.]?\\s*\\d+(?:\\.\\d+)?")
                    .find(episodeUrl)?.value.orEmpty()
            }
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

        val defaultPlayer = document.selectFirst("#playerShell")?.attr("data-default")
        val defaultPlayerUrl = absolute(defaultPlayer)?.takeIf { it.startsWith("http") }
        val frames = (listOfNotNull(defaultPlayerUrl) + document.select("iframe[src], video[src], video source[src], source[src]")
            .mapNotNull { element ->
                val raw = element.attr("src")
                absolute(raw)?.takeIf { it.startsWith("http") }
            }).distinct()

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
