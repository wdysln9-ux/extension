package com.wdysln9ux.sakunime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class SakunimeProvider : MainAPI() {
    override var mainUrl = "https://sakunime.com"
    override var name = "Sakunime"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val instantLinkLoading = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // Feed anime terbaru yang dipakai situs berada di halaman /anime/.
    override val mainPage = mainPageOf(
        "$mainUrl/anime/" to "Anime Terbaru"
    )

    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    private fun absolute(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank() || raw == "#" || raw.startsWith("javascript:", true)) return null
        return try {
            val uri = URI(raw)
            when {
                uri.isAbsolute -> raw
                raw.startsWith("//") -> "https:$raw"
                else -> URI(mainUrl).resolve(raw).toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun animePath(url: String): String? {
        return try {
            val uri = URI(url)
            if (!uri.host.equals(URI(mainUrl).host, true)) return null
            val parts = uri.path.trim('/').split("/")
            if (parts.size != 2 || parts[0] != "anime") return null
            val slug = parts[1]
            if (slug.isBlank() || slug in setOf("ongoing", "completed", "genre", "jadwal", "page")) null
            else "/anime/$slug"
        } catch (_: Exception) {
            null
        }
    }

    private fun posterFrom(img: Element?): String? {
        if (img == null) return null
        val raw = listOf("data-src", "data-lazy-src", "data-original", "data-original-src", "src")
            .firstNotNullOfOrNull { key -> img.attr(key).trim().takeIf { it.isNotBlank() && !it.startsWith("data:") } }
        return absolute(raw)
    }

    private fun Element.toAnimeResult(): SearchResponse? {
        val anchor = if (tagName() == "a") this else selectFirst("a[href]") ?: return null
        val href = absolute(anchor.attr("href")) ?: return null
        val path = animePath(href) ?: return null

        var context: Element? = anchor
        var image: Element? = anchor.selectFirst("img")
        var title = anchor.attr("title").trim().ifBlank { anchor.text().trim() }
        repeat(5) {
            val current = context ?: return@repeat
            if (image == null) image = current.selectFirst("img")
            if (title.isBlank()) {
                title = current.selectFirst("h1, h2, h3, h4, .title, .anime-title, [class*=title]")?.text()?.trim().orEmpty()
            }
            if (image != null && title.isNotBlank()) return@repeat
            context = current.parent()
        }
        if (title.isBlank()) title = image?.attr("alt")?.trim().orEmpty()
        if (title.isBlank()) return null

        val poster = posterFrom(image)
        val type = if (title.contains("movie", true) || path.contains("-movie", true)) TvType.AnimeMovie else TvType.Anime
        return newAnimeSearchResponse(title, path, type) {
            posterUrl = poster
        }
    }

    private fun parseAnimeCards(document: org.jsoup.nodes.Document): List<SearchResponse> {
        // Jangan mengandalkan satu nama class kartu. Tautan seri adalah identitas stabilnya.
        return document.select("a[href], [data-href]")
            .mapNotNull { element ->
                if (element.tagName() == "a") element.toAnimeResult()
                else {
                    val target = element.attr("data-href").ifBlank { element.attr("data-url") }
                    val url = absolute(target) ?: return@mapNotNull null
                    val path = animePath(url) ?: return@mapNotNull null
                    val title = element.selectFirst("h2, h3, h4, .title, [class*=title]")?.text()?.trim()
                        .orEmpty().ifBlank { element.selectFirst("img")?.attr("alt").orEmpty().trim() }
                    if (title.isBlank()) null else newAnimeSearchResponse(title, path, TvType.Anime) {
                        posterUrl = posterFrom(element.selectFirst("img"))
                    }
                }
            }
            .distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val target = if (page <= 1) "$mainUrl/anime/" else "$mainUrl/anime/?page=$page"
        val document = app.get(target, headers = requestHeaders).document
        val results = parseAnimeCards(document)
        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/search/$encoded/", headers = requestHeaders).document
        return parseAnimeCards(document)
    }

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = absolute(url) ?: throw ErrorLoadingException("URL anime tidak valid")
        val document = app.get(pageUrl, headers = requestHeaders).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.title().substringBefore(" | ").substringBefore(" - Sakunime").trim()
        if (title.isBlank()) throw ErrorLoadingException("Judul anime tidak ditemukan")

        val poster = absolute(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("meta[name=twitter:image]")?.attr("content")
                ?: posterFrom(document.selectFirst(".anime-poster img, .poster img, img"))
        )
        val plot = document.selectFirst(".synopsis, .description, .entry-content, .anime-description")?.text()
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val episodes = document.select("a.watch-eprow[href]").mapNotNull { anchor ->
            val episodeUrl = absolute(anchor.attr("href")) ?: return@mapNotNull null
            val label = anchor.attr("title").trim().ifBlank { anchor.text().trim() }
            if (label.isBlank()) return@mapNotNull null
            val number = Regex("(?i)(?:episode|eps?|ep)\\s*[-:#.]?\\s*(\\d+(?:\\.\\d+)?)")
                .find(label)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            newEpisode(episodeUrl) {
                name = label
                episode = number?.toInt()
            }
        }.distinctBy { it.data }.sortedBy { it.episode ?: Int.MAX_VALUE }

        if (episodes.isEmpty()) {
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
        val document = app.get(episodeUrl, headers = requestHeaders).document
        val shell = document.selectFirst("#playerShell")
        val defaultUrl = absolute(shell?.attr("data-default"))
        var found = false

        document.select("track[src]").forEach { track ->
            val subtitleUrl = absolute(track.attr("src")) ?: return@forEach
            subtitleCallback(SubtitleFile(
                track.attr("label").ifBlank { track.attr("srclang").ifBlank { "Subtitle" } },
                subtitleUrl
            ))
        }

        val candidates = (listOfNotNull(defaultUrl) +
            document.select("iframe[src], video[src], video source[src], source[src]")
                .mapNotNull { absolute(it.attr("src")) })
            .distinct()

        for (candidate in candidates) {
            try {
                if (candidate.matches(Regex("(?i).+\\.(m3u8|mp4|mpd)(\\?.*)?$"))) {
                    callback(newExtractorLink(name, "Sakunime", candidate, INFER_TYPE) {
                        this.referer = episodeUrl
                        this.quality = Qualities.Unknown.value
                    })
                    found = true
                } else {
                    loadExtractor(candidate, episodeUrl, subtitleCallback, callback)
                    found = true
                }
            } catch (_: Exception) {
                // Server lain tetap dicoba jika satu embed gagal.
            }
        }
        return found
    }
}
