package com.example.amped3controller

/**
 * Every cabinet the app can load, in one list: Blackstar's 24 (each with its six microphones and
 * two axes, chosen after) and the shipped ones. Both kinds are described with the same facets, so
 * one set of filters finds either and neither is a second-class citizen at the bottom of the page.
 */
data class CatalogCab(
    /** "f:<cabinet index>" for a factory cabinet, "lib:<id>" for a shipped one. */
    val id: String,
    val title: String,
    val brand: String,
    val size: String,
    val speakers: List<String>,
    val mics: List<String>,
    val style: String,
    val source: String,
    val factory: Int? = null,
    val lib: LibraryCab? = null,
) {
    val searchText: String = (listOf(title, brand, size, style, source) + speakers + mics +
        listOfNotNull(lib?.cabinet, lib?.speaker, lib?.mic, lib?.pack, lib?.author)).joinToString(" ").lowercase()

    /** brand · size · speaker · mic, whatever is known. */
    val summary: String get() = listOf(brand.ifBlank { T("marca non indicata", "brand not stated") }, size,
        speakers.joinToString(" + "), if (factory != null) T("6 microfoni", "6 mics") else mics.joinToString(" + "))
        .filter { it.isNotBlank() }.joinToString(" · ")
}

/** What the browser is narrowed by; an empty field is no filter. */
data class CatalogFilter(
    val query: String = "", val brand: String = "", val size: String = "", val speaker: String = "",
    val mic: String = "", val style: String = "", val source: String = "", val favorites: Boolean = false,
) {
    val active: Int get() = listOf(brand, size, speaker, mic, style, source).count { it.isNotEmpty() } + (if (favorites) 1 else 0)
}

object CabCatalog {
    const val BLACKSTAR = "Blackstar"
    const val OTHER = "other"
    const val BASS = "bass"

    /** The factory microphones under the names the shipped cabinets use, by mic index. */
    val factoryMics = listOf("SM57", "MD421", "U67", "C414", "R121", "M160")

    /** The shipped styles in four groups a guitarist would pick from, plus bass. */
    fun styleGroup(style: String, bass: Boolean): String {
        val s = style.lowercase()
        return when {
            bass -> BASS
            s.contains("metal") -> "metal"
            s.startsWith("clean") -> "clean"
            s.contains("punk") || s.contains("alt") -> "punk"
            s.contains("classic") || s.contains("blues") -> "classic"
            else -> ""
        }
    }

    fun styleLabel(group: String): String = when (group) {
        "metal" -> T("Moderno / metal", "Modern / metal")
        "classic" -> T("Classic rock", "Classic rock")
        "clean" -> T("Clean / blues", "Clean / blues")
        "punk" -> T("Punk / alt", "Punk / alt")
        BASS -> T("Basso", "Bass")
        else -> group
    }

    fun sourceLabel(source: String): String = when (source) {
        BLACKSTAR -> "Blackstar"
        "Tone3000" -> "Tone3000"
        "CC0" -> "Jester · CC0"
        "Collezione" -> T("Collezione", "Collection")
        "Progettate" -> T("Progettate", "Designed")
        else -> source
    }

    fun build(library: List<LibraryCab>): List<CatalogCab> {
        val factory = AmpedProtocol.cabinetNames.mapIndexed { i, name ->
            CatalogCab("f:$i", name, BLACKSTAR, if (name == "DI") "DI" else Regex("^\\d+x\\d+").find(name)?.value ?: "",
                emptyList(), factoryMics, "", BLACKSTAR, factory = i)
        }
        val shipped = library.map { cab ->
            val f = cab.facets
            CatalogCab("lib:${cab.id}", cab.name, f?.brand ?: cab.brand.takeIf { it != OTHER }.orEmpty(), f?.size.orEmpty(),
                f?.speakers.orEmpty(), f?.mics.orEmpty(), styleGroup(cab.style, f?.bass == true),
                f?.source?.ifBlank { null } ?: if (cab.captured) "Collezione" else "Progettate", lib = cab)
        }
        // one alphabetical list by brand: Blackstar's own sit among the others, not on top
        return (factory + shipped).sortedWith(compareBy({ it.brand.ifBlank { "~" }.lowercase() }, { sizeOrder(it.size) }, { it.title.lowercase() }))
    }

    private fun sizeOrder(size: String): Int = SIZES.indexOf(size).let { if (it < 0) SIZES.size else it }
    val SIZES = listOf("DI", "1x10", "1x12", "2x10", "2x12", "4x10", "4x12", "2x15", "8x10")

    /** True when [favorites] (factory "cab:mic:axis" keys and "lib:<id>" keys) holds this cabinet. */
    fun isFavorite(cab: CatalogCab, favorites: Collection<String>): Boolean =
        if (cab.factory != null) favorites.any { it.substringBefore(':') == cab.factory.toString() } else cab.id in favorites

    /** Whether [cab] passes [f]; the facet named in [ignoring] is left out, for counting its own values. */
    fun matches(cab: CatalogCab, f: CatalogFilter, favorites: Collection<String>, ignoring: String? = null): Boolean {
        val q = f.query.trim().lowercase()
        return (q.isEmpty() || q.split(Regex("\\s+")).all { cab.searchText.contains(it) }) &&
            (ignoring == "brand" || f.brand.isEmpty() || brandKey(cab) == f.brand) &&
            (ignoring == "size" || f.size.isEmpty() || cab.size == f.size) &&
            (ignoring == "speaker" || f.speaker.isEmpty() || f.speaker in cab.speakers) &&
            (ignoring == "mic" || f.mic.isEmpty() || f.mic in cab.mics) &&
            (ignoring == "style" || f.style.isEmpty() || cab.style == f.style) &&
            (ignoring == "source" || f.source.isEmpty() || cab.source == f.source) &&
            (!f.favorites || isFavorite(cab, favorites))
    }

    fun brandKey(cab: CatalogCab): String = cab.brand.ifBlank { OTHER }

    /** The values of one facet with how many cabinets each would leave, given every other filter;
     *  most common first, values that would leave nothing dropped. */
    fun counts(all: List<CatalogCab>, f: CatalogFilter, favorites: Collection<String>, facet: String): List<Pair<String, Int>> {
        val values: (CatalogCab) -> List<String> = when (facet) {
            "brand" -> { c -> listOf(brandKey(c)) }
            "size" -> { c -> listOf(c.size) }
            "speaker" -> { c -> c.speakers }
            "mic" -> { c -> c.mics }
            "style" -> { c -> listOf(c.style) }
            "source" -> { c -> listOf(c.source) }
            else -> error("unknown facet $facet")
        }
        val n = HashMap<String, Int>()
        all.filter { matches(it, f, favorites, ignoring = facet) }.forEach { c -> values(c).filter { it.isNotBlank() }.distinct().forEach { n[it] = (n[it] ?: 0) + 1 } }
        val order = if (facet == "size") compareBy<Pair<String, Int>> { sizeOrder(it.first) } else compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first }
        return n.toList().sortedWith(order)
    }
}
