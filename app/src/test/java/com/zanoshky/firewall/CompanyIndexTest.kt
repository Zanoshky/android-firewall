package com.zanoshky.firewall

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CompanyIndexTest {

    private val google = Company("Google", listOf("com.google."), listOf("doubleclick.net", "app-measurement.com"))
    private val meta = Company("Meta", listOf("com.facebook.", "com.whatsapp"), listOf("graph.facebook.com"))
    private val adjust = Company("Adjust", emptyList(), listOf("adjust.com"))
    private val index = CompanyIndex(listOf(google, meta, adjust))

    private fun row(pkg: String, domain: String, hits: Int, blocked: Int = 0, tracker: Int = 0) =
        DomainActivity(pkg, pkg, domain, hits, blocked, tracker, 0)

    @Test
    fun matchesTheDomainAndEverythingUnderIt() {
        assertEquals("Google", index.companyOf("doubleclick.net")?.name)
        assertEquals("Google", index.companyOf("googleads.g.doubleclick.net")?.name)
        assertEquals("Adjust", index.companyOf("APP.Adjust.com.")?.name)
    }

    @Test
    fun doesNotMatchLookalikesOrParents() {
        assertNull(index.companyOf("notdoubleclick.net"))
        assertNull(index.companyOf("facebook.com"))
        assertNull(index.companyOf("www.facebook.com"))
        assertNull(index.companyOf("net"))
        assertNull(index.companyOf(""))
    }

    @Test
    fun anAppsOwnMakerIsNotCountedAgainstIt() {
        assertNull(index.companyOf("graph.facebook.com", "com.facebook.katana"))
        assertNull(index.companyOf("graph.facebook.com", "com.whatsapp"))
        assertEquals("Meta", index.companyOf("graph.facebook.com", "com.whatsapp.w4b")?.name)
        assertEquals("Meta", index.companyOf("graph.facebook.com", "com.example.weather")?.name)
    }

    @Test
    fun reportFoldsRowsIntoCompaniesMostContactedFirst() {
        val report = index.report(
            listOf(
                row("com.example", "doubleclick.net", 3, blocked = 3, tracker = 3),
                row("com.example", "ssl.app-measurement.com", 2),
                row("com.example", "app.adjust.com", 9, blocked = 4, tracker = 4),
                row("com.example", "example.com", 50),
                row("com.example", "unknown-tracker.io", 5, blocked = 5, tracker = 5)
            )
        )
        assertEquals(listOf("Adjust", "Google"), report.companies.map { it.name })
        assertEquals(CompanyHit("Google", 2, 5, 3), report.companies[1])
        assertEquals(1, report.otherDomains)
        assertEquals(5, report.otherHits)
        assertEquals(19, report.trackerHits)
    }

    @Test
    fun aCompanyCountsOnceAcrossApps() {
        val report = index.report(
            listOf(
                row("com.a", "doubleclick.net", 1),
                row("com.b", "doubleclick.net", 2),
                row("com.facebook.katana", "graph.facebook.com", 7),
                row("com.b", "graph.facebook.com", 1)
            )
        )
        assertEquals(2, report.companies.size)
        assertEquals(CompanyHit("Google", 1, 3, 0), report.companies[0])
        assertEquals(CompanyHit("Meta", 1, 1, 0), report.companies[1])
    }

    @Test
    fun emptyReport() {
        assertTrue(index.report(emptyList()).isEmpty)
    }

    /** The shipped map parses, has no duplicate domains, and holds only bare lowercase names. */
    @Test
    fun shippedMapIsWellFormed() {
        val file = listOf("src/main/assets/tracker_companies.json", "app/src/main/assets/tracker_companies.json")
            .map(::File).first { it.exists() }
        val array = JSONArray(file.readText())
        val seen = HashSet<String>()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val domains = o.getJSONArray("domains")
            assertTrue(o.getString("name"), domains.length() > 0)
            for (j in 0 until domains.length()) {
                val d = domains.getString(j)
                assertEquals(d, d.lowercase().trim())
                assertTrue(d, d.contains('.') && !d.contains('/'))
                assertTrue("duplicate $d", seen.add(d))
            }
        }
    }
}
