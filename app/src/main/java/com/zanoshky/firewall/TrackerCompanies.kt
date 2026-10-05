package com.zanoshky.firewall

import android.content.Context
import org.json.JSONArray

/**
 * Who is behind a tracker domain.
 *
 * "Instagram looked up 412 names" says nothing. "Instagram reached 6 tracker
 * companies" does, so the log is turned into companies here. The map lives in
 * assets/tracker_companies.json and is hand-written: every entry is a domain
 * used only for ads, analytics or telemetry, never a company's ordinary
 * service, so a hit really is tracking.
 *
 * An app talking to its own maker is not counted against that maker. Instagram
 * reaching graph.facebook.com is Instagram using its own API; a weather app
 * reaching it is the Facebook SDK reporting home.
 */
object TrackerCompanies {

    @Volatile private var index: CompanyIndex? = null

    fun get(context: Context): CompanyIndex {
        index?.let { return it }
        synchronized(this) {
            index?.let { return it }
            val loaded = try {
                val json = context.assets.open("tracker_companies.json")
                    .bufferedReader().use { it.readText() }
                CompanyIndex(parse(json))
            } catch (_: Exception) {
                CompanyIndex(emptyList())
            }
            index = loaded
            return loaded
        }
    }

    private fun parse(json: String): List<Company> {
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Company(
                name = o.getString("name"),
                packages = strings(o.optJSONArray("packages")),
                domains = strings(o.optJSONArray("domains"))
            )
        }
    }

    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).map { array.getString(it) }
}

data class Company(
    val name: String,
    /** Package name prefixes of the company's own apps. */
    val packages: List<String>,
    /** Tracker domains; each also covers every name under it. */
    val domains: List<String>
)

/** One company an app reached, with how often and how much of it was stopped. */
data class CompanyHit(
    val name: String,
    val domains: Int,
    val hits: Int,
    val blocked: Int
)

/** What one app, or every app together, did with trackers. */
data class TrackerReport(
    /** Most contacted first. */
    val companies: List<CompanyHit>,
    /** Names a tracker list stopped that the company map does not know. */
    val otherDomains: Int,
    val otherHits: Int
) {
    val trackerHits: Int get() = companies.sumOf { it.hits } + otherHits
    val isEmpty: Boolean get() = companies.isEmpty() && otherDomains == 0
}

/**
 * The lookup side of [TrackerCompanies], kept free of Android so it can be
 * tested on its own.
 */
class CompanyIndex(companies: List<Company>) {

    private val byDomain: Map<String, Company> = buildMap {
        for (company in companies) for (domain in company.domains) put(domain.lowercase(), company)
    }

    val size: Int get() = byDomain.size

    /**
     * The company behind [domain], or null. Walks from the full name up through
     * its parents, so "ib.adnxs.com" finds "adnxs.com". When [packageName] is
     * one of the company's own apps the answer is null: that is first party.
     */
    fun companyOf(domain: String, packageName: String? = null): Company? {
        var d = domain.lowercase().trimEnd('.')
        while (true) {
            val company = byDomain[d]
            if (company != null) {
                return if (packageName != null && isOwnApp(company, packageName)) null else company
            }
            val dot = d.indexOf('.')
            if (dot < 0) return null
            d = d.substring(dot + 1)
            if (!d.contains('.')) return null
        }
    }

    private fun isOwnApp(company: Company, packageName: String): Boolean =
        company.packages.any { prefix ->
            if (prefix.endsWith('.')) packageName.startsWith(prefix) else packageName == prefix
        }

    /**
     * Fold [rows] into companies. A row whose domain maps to a company counts
     * for it whether or not it was stopped, which is what makes "reached" and
     * "stopped" comparable. A row the tracker list stopped but the map does not
     * know goes under "other". Rows from several apps can be mixed; each is
     * judged against its own app, and a company counts once however many apps
     * reached it.
     */
    fun report(rows: List<DomainActivity>): TrackerReport {
        class Acc(val name: String) {
            val domains = HashSet<String>()
            var hits = 0
            var blocked = 0
        }
        val acc = LinkedHashMap<String, Acc>()
        val otherDomains = HashSet<String>()
        var otherHits = 0

        for (row in rows) {
            if (row.domain.isEmpty()) continue
            val company = companyOf(row.domain, row.packageName)
            when {
                company != null -> {
                    val a = acc.getOrPut(company.name) { Acc(company.name) }
                    a.domains.add(row.domain)
                    a.hits += row.hits
                    a.blocked += row.blocked
                }
                row.trackerHits > 0 -> {
                    otherDomains.add(row.domain)
                    otherHits += row.trackerHits
                }
            }
        }

        val companies = acc.values
            .map { CompanyHit(it.name, it.domains.size, it.hits, it.blocked) }
            .sortedWith(compareByDescending<CompanyHit> { it.hits }.thenBy { it.name })
        return TrackerReport(companies, otherDomains.size, otherHits)
    }
}
