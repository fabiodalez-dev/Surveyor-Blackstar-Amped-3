package com.example.amped3controller

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CabCatalogTest {
    private val lib = LibraryCab.parseList(File("src/main/assets/cab_library.json").readText())
    private val all = CabCatalog.build(lib)

    @Test fun everyCabinetIsInOneListAndBlackstarIsNotOnTop() {
        assertEquals(AmpedProtocol.cabinetNames.size + lib.size, all.size)
        assertEquals(all.size, all.map { it.id }.distinct().size)
        assertNotEquals(CabCatalog.BLACKSTAR, all.first().brand)
        assertTrue(all.zipWithNext().all { (a, b) -> a.brand.ifBlank { "~" }.lowercase() <= b.brand.ifBlank { "~" }.lowercase() })
    }

    @Test fun theAssetCarriesFacetsFromTheFixedVocabulary() {
        assertTrue(lib.all { it.facets != null })
        val sizes = lib.mapNotNull { it.facets?.size }.filter { it.isNotEmpty() }.toSet()
        assertTrue("sizes: $sizes", sizes.all { it in CabCatalog.SIZES })
        assertTrue(lib.count { it.facets!!.size.isNotEmpty() } > lib.size * 0.85)
        assertTrue(lib.count { it.facets!!.speakers.isNotEmpty() } > lib.size / 2)
        assertTrue(lib.count { it.facets!!.mics.isNotEmpty() } > lib.size / 2)
        assertEquals(setOf("Tone3000", "CC0", "Collezione", "Progettate"), lib.map { it.facets!!.source }.toSet())
        assertTrue(lib.filter { !it.captured }.all { it.facets!!.brand == CabCatalog.BLACKSTAR && it.facets.mics.isEmpty() })
    }

    @Test fun filtersCombineAndCountTheOthers() {
        val f = CatalogFilter(brand = "Marshall", size = "4x12")
        val hits = all.filter { CabCatalog.matches(it, f, emptyList()) }
        assertTrue(hits.isNotEmpty() && hits.all { it.brand == "Marshall" && it.size == "4x12" })
        // the brand counts ignore the brand filter itself, so other brands stay one tap away
        val brands = CabCatalog.counts(all, f, emptyList(), "brand").toMap()
        assertEquals(hits.size, brands["Marshall"])
        assertTrue((brands["Mesa"] ?: 0) > 0)
        assertEquals(all.count { it.size == "4x12" && it.brand == "Blackstar" }, brands["Blackstar"])
    }

    @Test fun aMicrophoneFindsBothFactoryAndShippedCabinets() {
        val r121 = all.filter { CabCatalog.matches(it, CatalogFilter(mic = "R121"), emptyList()) }
        assertEquals(AmpedProtocol.cabinetNames.size, r121.count { it.factory != null })
        assertTrue(r121.any { it.lib != null })
    }

    @Test fun searchMatchesEveryWordAnywhere() {
        val hits = all.filter { CabCatalog.matches(it, CatalogFilter(query = "greenback 1960"), emptyList()) }
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.searchText.contains("greenback") && it.searchText.contains("1960") })
    }

    @Test fun favouritesCoverFactoryKeysAndShippedIds() {
        val shipped = all.first { it.lib != null }
        val favs = listOf("20:3:1", shipped.id)
        val hits = all.filter { CabCatalog.matches(it, CatalogFilter(favorites = true), favs) }.map { it.id }.toSet()
        assertEquals(setOf("f:20", shipped.id), hits)
    }
}
