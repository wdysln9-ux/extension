package com.wdysln9ux.sakunime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
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

    override val mainPage = mainPageOf(
        "$mainUrl/anime/" to "Anime Terbaru"
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    private val blockedSlugs = setOf("ongoing", "completed", "genre", "jadwal", "page", "search")

    private fun absolute(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank() || value == "#" || value.startsWith("javascript:", true) || value.startsWith("data:", true)) return null
        return try {
            val resolved = URI(mainUrl).resolve(value)
            if (resolved.scheme != "http" && resolved.scheme != "https") null else resolved.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun animeUrl(raw: String?): String? {
        val url = absolute(raw) ?: return null
        return try {
            val uri = URI(url)
            if (!uri.host.equals(URI(mainUrl).host, ignoreCase = true)) return null
            val parts = uri.path.split("/").filter { it.isNotBlank() }
            if (parts.size != 2 || !parts[0].equals("anime", true)) return null
            val slug = parts[1].substringBefore("?")
            if (slug.isBlank() || slug.lowercase() in blockedSlugs) return null
            "$mainUrl/anime/$slug/"
        } catch (_: Exception) {
            null
        }
    }

    private fun watchUrl(raw: String?): String? {
        val url = absolute(raw) ?: return null
        return try {
            val uri = URI(url)
            if (!uri.host.equals(URI(mainUrl).host, ignoreCase = true)) return null
            val parts = uri.path.split("/").filter { it.isNotBlank() }
            if (parts.size == 2 && parts[0].equals("watch", true)) url else null
        } catch (_: Exception) {
            null
        }
    }

    private fun imageUrl(element: Element?): String? {
        if (element == null) return null
        val raw = listOf(
            "data-src", "data-lazy-src", "data-original", "data-original-src",
            "data-lazy", "src"
        ).asSequence().map { element.attr(it).trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
        if (!raw.isNullOrBlank()) return absolute(raw)

        val style = element.attr("style")
        val background = Regex("""url\(['"]?([^'")]+)['"]?\)""", RegexOption.IGNORE_CASE)
            .find(style)?.groupValues?.getOrNull(1)
        return absolute(background)
    }

    private fun cleanTitle(value: String?): String {
        return value.orEmpty()
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""(?i)\s*(subtitle indonesia|sub indo)\s*$"""), "")
            .trim()
    }

    private fun titleFrom(anchor: Element, card: Element?): String {
        val candidates = mutableListOf<String>()
        candidates += anchor.attr("title")
        candidates += anchor.attr("aria-label")
        candidates += anchor.attr("data-title")
        candidates += anchor.attr("data-name")
        candidates += anchor.text()
        anchor.selectFirst("img")?.let { candidates += it.attr("alt") }

        card?.let { parent ->
            candidates += parent.attr("data-title")
            candidates += parent.attr("data-name")
            parent.select("h1, h2, h3, h4, .title, .anime-title, [class*=title], [title]").forEach {
                candidates += it.text()
                candidates += it.attr("title")
            }
            parent.select("img[alt]").forEach { candidates += it.attr("alt") }
        }

        return candidates.asSequence().map(::cleanTitle)
            .firstOrNull { it.isNotBlank() && it.length > 1 }
            .orEmpty()
    }

    private fun cardFor(anchor: Element): Element {
        var current = anchor
        repeat(7) {
            val parent = current.parent() ?: return current
            val hasAnimeLink = parent.select("a[href]").any { animeUrl(it.attr("href")) != null }
            val hasTitle = parent.select("h1, h2, h3, h4, .title, .anime-title, img[alt]").isNotEmpty()
            if (hasAnimeLink && (hasTitle || parent.select("img").isNotEmpty())) return parent
            current = parent
        }
        return current
    }

    private fun parseCard(anchor: Element): SearchResponse? {
        val detailUrl = animeUrl(anchor.attr("href")) ?: return null
        val card = cardFor(anchor)
        val title = titleFrom(anchor, card)
        if (title.isBlank()) return null

        val image = anchor.selectFirst("img") ?: card.selectFirst("img")
        val poster = imageUrl(image)
        val type = if (title.contains("movie", true) || detailUrl.contains("-movie", true)) {
            TvType.AnimeMovie
        } else {
            TvType.Anime
        }

        return newAnimeSearchResponse(title, detailUrl, type) {
            posterUrl = poster
        }
    }

    private fun parseListing(document: Document): List<SearchResponse> {
        val found = linkedMapOf<String, SearchResponse>()
        // Kartu seri Sakunime menggunakan tautan /anime/{slug}; jangan mengambil tautan watch.
        document.select("a[href]").forEach { anchor ->
            val result = parseCard(anchor) ?: return@forEach
            found.putIfAbsent(result.url, result)
        }

        // Sebagian template meletakkan URL detail pada data-href, bukan href.
        document.select("[data-href], [data-url]").forEach { element ->
            val raw = element.attr("data-href").ifBlank { element.attr("data-url") }
            val detailUrl = animeUrl(raw) ?: return@forEach
            val title = titleFrom(element, element)
            if (title.isBlank()) return@forEach
            val poster = imageUrl(element.selectFirst("img"))
            found.putIfAbsent(detailUrl, newAnimeSearchResponse(title, detailUrl, TvType.Anime) {
                posterUrl = poster
            })
        }

        return found.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) "$mainUrl/anime/" else "$mainUrl/anime/page/$page/"
        val first = app.get(pageUrl, headers = headers)
        var results = parseListing(first.document)

        // Halaman utama kadang memuat feed episode terbaru dengan tautan seri.
        if (results.isEmpty() && page == 1) {
            val home = app.get("$mainUrl/", headers = headers).document
            results = parseListing(home)
        }

        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/search/$encoded/", headers = headers).document
        return parseListing(document)
    }

    private fun episodeNumber(label: String, url: String): Int? {
        val pattern = Regex("""(?i)(?:episode|eps?|ep)\s*[-:#.]?\s*(\d+(?:\.\d+)?)""")
        return (pattern.find(label)?.groupValues?.getOrNull(1)
            ?: pattern.find(url)?.groupValues?.getOrNull(1))
            ?.toDoubleOrNull()?.toInt()
    }

    private fun parseEpisodes(document: Document): List<Episode> {
        return document.select("a.watch-eprow[href], .episode-list a[href], a[href*='/watch/']")
            .mapNotNull { anchor ->
                val url = watchUrl(anchor.attr("href")) ?: return@mapNotNull null
                val label = cleanTitle(anchor.attr("title").ifBlank { anchor.text() })
                val number = episodeNumber(label, url)
                if (number == null) return@mapNotNull null
                newEpisode(url) {
                    name = label.ifBlank { "Episode $number" }
                    episode = number
                }
            }
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = animeUrl(url) ?: absolute(url) ?: throw ErrorLoadingException("URL Sakunime tidak valid")
        val document = app.get(pageUrl, headers = headers).document

        val title = cleanTitle(
            document.selectFirst("h1")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.title().substringBefore("|").substringBefore("- Sakunime")
        )
        if (title.isBlank()) throw ErrorLoadingException("Judul anime tidak ditemukan")

        val poster = absolute(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("meta[name=twitter:image]")?.attr("content")
        ) ?: imageUrl(document.selectFirst(".anime-poster img, .poster img, img"))

        val plot = document.selectFirst(".synopsis, .description, .entry-content, .anime-description, [class*=synopsis]")?.text()
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val episodes = parseEpisodes(document)
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
        val episodeUrl = watchUrl(data) ?: absolute(data) ?: return false
        val document = app.get(episodeUrl, headers = headers).document
        var found = false

        document.select("track[src]").forEach { track ->
            val subtitle = absolute(track.attr("src")) ?: return@forEach
            val language = track.attr("label").ifBlank {
                track.attr("srclang").ifBlank { "Subtitle" }
            }
            subtitleCallback(SubtitleFile(language, subtitle))
        }

        val defaultPlayer = absolute(document.selectFirst("#playerShell")?.attr("data-default"))
        val candidates = (listOfNotNull(defaultPlayer) +
            document.select("iframe[src], video[src], video source[src], source[src]")
                .mapNotNull { absolute(it.attr("src")) })
            .distinct()

        candidates.forEach { candidate ->
            try {
                if (candidate.matches(Regex("""(?i).+\.(m3u8|mp4|mpd)(\?.*)?$"""))) {
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
                // Lanjutkan ke embed berikutnya jika satu server gagal.
            }
        }
        return found
    }
}
