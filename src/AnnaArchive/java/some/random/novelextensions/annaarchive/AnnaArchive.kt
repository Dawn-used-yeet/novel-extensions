package some.random.novelextensions.annaarchive

import ani.dantotsu.parsers.Book
import ani.dantotsu.parsers.NovelInterface
import ani.dantotsu.parsers.ShowResponse
import com.lagradost.nicehttp.Requests
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AnnaArchive : NovelInterface {

    val name = "Anna's Archive"
    val saveName = "anna"
    val hostUrl = "https://annas-archive.org"
    val volumeRegex = Regex("vol\\.? (\\d+(\\.\\d+)?)|volume (\\d+(\\.\\d+)?)", RegexOption.IGNORE_CASE)
    val defaultImage = "https://s4.anilist.co/file/anilistcdn/media/manga/cover/medium/default.jpg"

    private fun parseShowResponse(element: Element?): ShowResponse? {
        element ?: return null
        if (!element.select("div[class~=lg:text-xs]").text().contains("epub", ignoreCase = true)) {
            return null
        }
        
        val name = element.selectFirst("h3")?.text() ?: ""
        var img = element.selectFirst("img")?.attr("src") ?: defaultImage
        
        val extra = mapOf(
            "0" to element.select("div.italic").text(),
            "1" to element.select("div[class~=max-lg:text-xs]").text(),
            "2" to element.select("div[class~=lg:text-xs]").text()
        )
        
        return ShowResponse(name, "$hostUrl${element.attr("href")}", img, extra = extra)
    }

    override suspend fun search(query: String, client: Requests): List<ShowResponse> {
        val formattedQuery = query.substringAfter("!$").replace("-", " ")
        val responseElements = client.get("$hostUrl/search?ext=epub&q=$formattedQuery")
            .document.getElementsByAttributeValueContaining("class", "h-[125]")
        
        return responseElements.mapNotNull { div ->
            val a = div.selectFirst("a") ?: Jsoup.parse(div.data())
            parseShowResponse(a.selectFirst("a"))
        }.let { results ->
            if (query.startsWith("!$")) results.sortByVolume(formattedQuery) else results
        }
    }

    override suspend fun loadBook(link: String, extra: Map<String, String>?, client: Requests): Book {
        return client.get(link).document.selectFirst("main")!!.let { mainElement ->
            val name = mainElement.selectFirst("div.text-3xl")!!.text().substringBefore("\uD83D\uDD0D")
            var img = mainElement.selectFirst("img")?.attr("src") ?: defaultImage
            
            val description = mainElement.selectFirst("div.js-md5-top-box-description")?.text()
            val links = mainElement.select("a.js-download-link")
                .filter { !it.text().contains("Fast") && !it.attr("href").containsAny(listOf("onion", "/datasets", "1lib", "slow_download")) }
                .reversed()
                .flatMap { LinkExtractor(it.attr("href"), client).extractLink() ?: emptyList() }

            Book(name, img, description, links)
        }
    }

    class LinkExtractor(private val url: String, private val client: Requests) {
        suspend fun extractLink(): List<String>? {
            return when {
                isLibgenUrl(url) || isLibraryLolUrl(url) -> LibgenExtractor(url).extract()
                isSlowDownload(url) -> fetchSlowDownloadLinks()
                else -> listOf(url)
            }
        }

        private fun isLibgenUrl(url: String) = url.contains("libgen")
        private fun isLibraryLolUrl(url: String) = url.contains("library.lol")
        private fun isSlowDownload(url: String) = url.contains("slow_download")

        private suspend fun fetchSlowDownloadLinks(): List<String>? {
            return try {
                val response = client.get("$hostUrl$url")
                response.document.select("a").mapNotNull { it.attr("href") }.takeWhile { !it.contains("localhost") }
            } catch (e: Exception) {
                null // Handle exceptions as needed
            }
        }

        private suspend fun LibgenExtractor(url: String): List<String>? {
            return try {
                when {
                    url.contains("ads.php") -> extractFromAdsPage(url)
                    else -> extractFromDownloadPage(url)
                }
            } catch (e: Exception) {
                null // Handle exceptions as needed
            }
        }

        private suspend fun extractFromAdsPage(url: String): List<String>? {
            val response = client.get(url)
            val link = response.document.selectFirst("table#main a[href]")?.attr("href")
            return if (link != null && (link.startsWith("/ads.php") || link.startsWith("get.php"))) {
                listOf(url.substringBefore("ads.php") + link)
            } else {
                listOf(link ?: "")
            }
        }

        private suspend fun extractFromDownloadPage(url: String): List<String>? {
            val response = client.get(url)
            return response.document.selectFirst("div#download a[href]")?.mapNotNull { it.attr("href") }
                ?.takeWhile { !it.contains("localhost") }
        }
    }

    private fun List<ShowResponse>.sortByVolume(query: String): List<ShowResponse> {
        val groupedByVolume = groupBy { res ->
            volumeRegex.find(res.name)?.groupValues?.firstOrNull { it.isNotEmpty() }?.substringAfter(" ")?.toDoubleOrNull() ?: Double.MAX_VALUE
        }.toSortedMap().values

        val volumes = groupedByVolume.map { showList ->
            showList.filter { it.coverUrl.url != defaultImage }.firstOrNull { it.name.contains(query) } ?: showList.first()
        }

        return volumes + groupedByVolume.flatten() - volumes.toSet()
    }
}

fun logger(msg: String) {
    println(msg)
}

private fun String.containsAny(substrings: List<String>): Boolean {
    return substrings.any { this.contains(it) }
}



