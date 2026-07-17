package com.cwjitsu.app.practice

/**
 * Per-country callsign model.
 *
 * A real callsign is: PREFIX + DIGITS + SUFFIX where
 *   - PREFIX is 1..3 literal characters from the country's allocation
 *   - DIGITS is exactly one digit (US/Canada/UK conventions)
 *   - SUFFIX is a random sequence of 1..3 uppercase letters
 *
 * [region] is the broad geographic grouping used to organise the
 * country-picker dialog (e.g. "Europe", "Asia", "Oceania"). It is not
 * part of the callsign itself; it only drives the UI.
 *
 * This model is straightforward, accurate, and easy to expand.
 */
data class CallsignTemplate(
    val prefix: String,
    val suffixRange: IntRange = 1..3,
    /**
     * Relative sampling weight against the country's other templates.
     * Defaults to 1 (uniform). Countries where the real-world prefix
     * distribution is far from uniform (e.g. the US, where K/W/N calls
     * vastly outnumber the AA-AL Extra-class block) override this so
     * generated traffic sounds like the air instead of like the
     * allocation table.
     */
    val weight: Int = 1,
)

data class CallsignCountry(
    val name: String,
    val region: String,
    val templates: List<CallsignTemplate>,
)

object CallsignRegistry {

    /** Helper: build a [CallsignCountry] from a list of prefix strings (uniform weights). */
    private fun c(name: String, region: String, vararg prefixes: String): CallsignCountry =
        CallsignCountry(name, region, prefixes.map { CallsignTemplate(it) })

    /**
     * Helper: build a [CallsignCountry] from weighted prefixes, e.g.
     * `cw("France", "Europe", "F" to 30, "TM" to 1)`. Weights are relative
     * within the country only.
     */
    private fun cw(name: String, region: String, vararg prefixes: Pair<String, Int>): CallsignCountry =
        CallsignCountry(name, region, prefixes.map { (p, w) -> CallsignTemplate(p, weight = w) })

    /**
     * Master list of countries / territories with amateur radio allocations.
     *
     * The list is intentionally broad (~130 entries) but does not try to
     * enumerate every obscure DXCC entity. The user can search the dialog
     * by country name or by prefix. ITU Generic (empty prefix) is kept
     * for "anything you might hear" warm-ups.
     *
     * Countries built with [cw] carry per-prefix weights approximating
     * how often each prefix is actually heard on the air (standard
     * allocations heavy; secondary series light; special-event, contest
     * and club blocks ~1). Countries built with [c] are uniform, either
     * because they have a single prefix or because their allocations are
     * genuinely evenly used.
     */
    val countries: List<CallsignCountry> = listOf(
        // -------- North America --------
        // K/W/N dominate real US activity; the AA-AL block is issued only
        // to Extra-class licensees and is a small slice of what's heard
        // (~12% of generated calls start with A at these weights).
        cw("United States", "North America",
            "K" to 30, "W" to 30, "N" to 25,
            "AA" to 1, "AB" to 1, "AC" to 1, "AD" to 1, "AE" to 1, "AF" to 1,
            "AG" to 1, "AH" to 1, "AI" to 1, "AJ" to 1, "AK" to 1, "AL" to 1),
        cw("Canada", "North America", "VE" to 10, "VA" to 7, "VO" to 2, "VY" to 1),
        // Nearly all Mexican amateurs are XE; 4A-4C and 6D-6J are
        // special-event blocks, XF is island territories.
        cw("Mexico", "North America",
            "XE" to 40, "XF" to 2,
            "4A" to 1, "4B" to 1, "4C" to 1,
            "6D" to 1, "6E" to 1, "6F" to 1, "6G" to 1, "6H" to 1, "6I" to 1, "6J" to 1),
        c("Greenland", "North America", "OX"),
        cw("Panama", "North America", "HP" to 15, "H3" to 2, "H8" to 1, "H9" to 1),
        cw("Cuba", "North America", "CM" to 8, "CO" to 8, "T4" to 1),
        c("Jamaica", "North America", "6Y"),
        cw("Puerto Rico", "North America", "KP4" to 10, "WP4" to 5, "NP4" to 3),
        cw("US Virgin Islands", "North America", "KP2" to 8, "WP2" to 3, "NP2" to 2),
        c("Dominican Republic", "North America", "HI"),

        // -------- Caribbean --------
        c("Bahamas", "Caribbean", "C6"),
        c("Bermuda", "Caribbean", "VP9"),
        c("Cayman Islands", "Caribbean", "ZF"),
        cw("Trinidad & Tobago", "Caribbean", "9Y" to 10, "9Z" to 1),
        c("Barbados", "Caribbean", "8P"),
        c("Haiti", "Caribbean", "HH"),
        c("Aruba", "Caribbean", "P4"),
        c("Curacao", "Caribbean", "PJ2"),
        c("Saint Lucia", "Caribbean", "J6"),
        c("Dominica", "Caribbean", "J7"),
        c("Grenada", "Caribbean", "J3"),
        c("Saint Martin", "Caribbean", "FS", "PJ7"),
        c("Antigua & Barbuda", "Caribbean", "V2"),

        // -------- Central America --------
        cw("Costa Rica", "Central America", "TI" to 12, "TE" to 1),
        c("Guatemala", "Central America", "TG"),
        cw("Honduras", "Central America", "HR" to 12, "HQ" to 1),
        c("El Salvador", "Central America", "YS"),
        cw("Nicaragua", "Central America", "YN" to 12, "H6" to 1, "H7" to 1),
        c("Belize", "Central America", "V3"),

        // -------- South America --------
        // PY dominates; PU is the entry class, PT/PP/PR/PS real but
        // smaller, PV-PX and the Z-series are special/contest blocks.
        cw("Brazil", "South America",
            "PY" to 40, "PU" to 15, "PT" to 8, "PP" to 5, "PR" to 4, "PS" to 3,
            "PQ" to 2, "PV" to 1, "PW" to 1, "PX" to 1,
            "ZV" to 1, "ZW" to 1, "ZX" to 1, "ZY" to 1, "ZZ" to 1),
        cw("Argentina", "South America",
            "LU" to 40, "LW" to 10, "LR" to 2,
            "LO" to 1, "LP" to 1, "LQ" to 1, "LS" to 1, "LT" to 1, "LV" to 1,
            "AY" to 1, "AZ" to 1),
        cw("Chile", "South America",
            "CE" to 30, "CA" to 8, "XQ" to 4, "CB" to 3, "CC" to 2, "CD" to 2,
            "XR" to 1, "3G" to 1),
        cw("Colombia", "South America", "HK" to 20, "HJ" to 2, "5J" to 1, "5K" to 1),
        cw("Peru", "South America", "OA" to 20, "OB" to 2, "OC" to 2, "4T" to 1),
        cw("Venezuela", "South America",
            "YV" to 20, "YW" to 1, "YX" to 1, "YY" to 1, "4M" to 1),
        cw("Ecuador", "South America", "HC" to 12, "HD" to 1),
        cw("Uruguay", "South America", "CX" to 15, "CV" to 1, "CW" to 1),
        c("Paraguay", "South America", "ZP"),
        c("Bolivia", "South America", "CP"),
        c("French Guiana", "South America", "FY"),
        c("Guyana", "South America", "8R"),
        c("Suriname", "South America", "PZ"),

        // -------- Europe --------
        // M-series (post-1996) is now the biggest UK block, G the large
        // legacy population; 2E is intermediate licence, MM/MW are the
        // Scottish/Welsh regional variants (~10% of UK population).
        cw("United Kingdom", "Europe",
            "M" to 40, "G" to 35, "2E" to 12, "MM" to 4, "MW" to 3),
        // DL is the standard German prefix by a wide margin; DO is the
        // entry class, DA/DE/DN/DP/DQ/DR are special, training or club
        // blocks.
        cw("Germany", "Europe",
            "DA" to 1, "DB" to 4, "DC" to 4, "DD" to 3, "DE" to 1, "DF" to 6,
            "DG" to 6, "DH" to 4, "DI" to 1, "DJ" to 8, "DK" to 8, "DL" to 40,
            "DM" to 5, "DN" to 1, "DO" to 6, "DP" to 1, "DQ" to 1, "DR" to 2),
        // TM is an event-only prefix.
        cw("France", "Europe", "F" to 30, "TM" to 1),
        cw("Italy", "Europe",
            "IZ" to 12, "IK" to 10, "I" to 8, "IW" to 6, "IN" to 2),
        cw("Spain", "Europe",
            "EA" to 30, "EB" to 4, "EC" to 2,
            "ED" to 1, "EE" to 1, "EF" to 1, "EG" to 1, "EH" to 1,
            "AM" to 1, "AO" to 1),
        // UA is the classic Russian prefix; RA and the newer bare-R
        // series are common, the rest of the R/U blocks trail off.
        cw("Russia", "Europe",
            "UA" to 20, "RA" to 10, "R" to 8, "RV" to 5, "RW" to 5,
            "RX" to 4, "RZ" to 4, "RN" to 3, "RU" to 3, "RC" to 2, "RD" to 2,
            "UB" to 2, "UC" to 1, "UD" to 1, "UE" to 1, "UF" to 1,
            "UG" to 1, "UH" to 1, "UI" to 1),
        cw("Poland", "Europe",
            "SP" to 25, "SQ" to 12, "SO" to 2, "SR" to 1, "3Z" to 1),
        cw("Ukraine", "Europe",
            "UR" to 15, "UT" to 12, "US" to 10, "UX" to 4, "UY" to 4,
            "UW" to 3, "UU" to 2, "UV" to 2, "UZ" to 2,
            "EM" to 1, "EN" to 1, "EO" to 1),
        // PA full licence, PD/PE novice+intermediate, PI clubs/repeaters.
        cw("Netherlands", "Europe",
            "PA" to 25, "PD" to 8, "PE" to 8, "PB" to 3, "PC" to 3,
            "PG" to 2, "PH" to 2, "PF" to 1, "PI" to 1),
        cw("Belgium", "Europe",
            "ON" to 20, "OT" to 2,
            "OO" to 1, "OP" to 1, "OQ" to 1, "OR" to 1, "OS" to 1),
        // SM classic, SA the newer series, SK clubs.
        cw("Sweden", "Europe",
            "SM" to 20, "SA" to 10, "SK" to 3,
            "SB" to 1, "SC" to 1, "SD" to 1, "SE" to 1, "SF" to 1,
            "SG" to 1, "SH" to 1, "SI" to 1, "SJ" to 1, "SL" to 1),
        cw("Norway", "Europe",
            "LA" to 20, "LB" to 8,
            "LC" to 1, "LD" to 1, "LE" to 1, "LF" to 1, "LG" to 1,
            "LH" to 1, "LI" to 1, "LJ" to 1, "LN" to 1),
        cw("Finland", "Europe", "OH" to 20, "OG" to 3, "OF" to 1, "OI" to 1),
        cw("Switzerland", "Europe", "HB" to 15, "HE" to 1),
        c("Austria", "Europe", "OE"),
        cw("Greece", "Europe",
            "SV" to 20, "SZ" to 2, "SX" to 1, "SY" to 1, "J4" to 1),
        c("Iceland", "Europe", "TF"),
        cw("Portugal", "Europe", "CT" to 15, "CS" to 3, "CR" to 1, "CQ" to 1),
        cw("Denmark", "Europe",
            "OZ" to 20, "OU" to 2, "OV" to 2, "5P" to 2, "5Q" to 1, "XP" to 1),
        cw("Czech Republic", "Europe", "OK" to 15, "OL" to 2),
        c("Slovakia", "Europe", "OM"),
        cw("Hungary", "Europe", "HA" to 12, "HG" to 6),
        cw("Romania", "Europe", "YO" to 15, "YR" to 2, "YP" to 1, "YQ" to 1),
        c("Bulgaria", "Europe", "LZ"),
        cw("Serbia", "Europe", "YU" to 10, "YT" to 6),
        c("Croatia", "Europe", "9A"),
        c("Slovenia", "Europe", "S5"),
        c("Bosnia & Herzegovina", "Europe", "E7"),
        c("Montenegro", "Europe", "4O"),
        c("North Macedonia", "Europe", "Z3"),
        c("Albania", "Europe", "ZA"),
        c("Moldova", "Europe", "ER"),
        cw("Belarus", "Europe", "EW" to 8, "EU" to 5, "EV" to 2),
        c("Lithuania", "Europe", "LY"),
        c("Latvia", "Europe", "YL"),
        c("Estonia", "Europe", "ES"),
        cw("Ireland", "Europe", "EI" to 12, "EJ" to 1),
        c("Luxembourg", "Europe", "LX"),
        c("Malta", "Europe", "9H"),
        cw("Cyprus", "Europe", "5B" to 12, "P3" to 2, "H2" to 1),
        cw("Turkey", "Europe", "TA" to 15, "TB" to 3, "TC" to 2, "YM" to 1),

        // -------- Asia --------
        // JA is the oldest and largest Japanese block; JH/JR next, then
        // the rest of the JE-JS series in roughly issue order.
        cw("Japan", "Asia",
            "JA" to 20, "JH" to 8, "JR" to 8,
            "JE" to 5, "JF" to 5, "JG" to 5,
            "JI" to 4, "JJ" to 4, "JK" to 4, "JL" to 4,
            "JM" to 3, "JN" to 3, "JO" to 3, "JP" to 3,
            "JQ" to 2, "JS" to 2),
        cw("China", "Asia",
            "BG" to 12, "BH" to 10, "BD" to 8, "BA" to 3, "BI" to 2, "BJ" to 1),
        c("India", "Asia", "VU"),
        // HL legacy, DS the newer common series; HM is allocated but
        // essentially unused on the air.
        cw("South Korea", "Asia",
            "HL" to 15, "DS" to 10, "6K" to 3, "6L" to 3, "6M" to 2, "6N" to 2,
            "D7" to 2, "D8" to 1, "D9" to 1, "HM" to 1),
        // YB (full class) dominates HF; YC next; the 7x/8x blocks are
        // special allocations rarely heard.
        cw("Indonesia", "Asia",
            "YB" to 20, "YC" to 10, "YD" to 5, "YE" to 2, "YF" to 2,
            "YG" to 1, "YH" to 1,
            "7A" to 1, "7B" to 1, "7C" to 1, "7D" to 1, "7E" to 1,
            "7F" to 1, "7G" to 1, "7H" to 1, "7I" to 1,
            "8A" to 1, "8B" to 1, "8C" to 1, "8D" to 1, "8E" to 1,
            "8F" to 1, "8G" to 1, "8H" to 1, "8I" to 1),
        cw("Philippines", "Asia",
            "DU" to 12, "DV" to 6, "DW" to 3, "DX" to 2, "DY" to 2, "DZ" to 1,
            "4D" to 1, "4E" to 1, "4F" to 1, "4G" to 1, "4H" to 1, "4I" to 1),
        cw("Thailand", "Asia", "HS" to 12, "E2" to 4),
        // 3W is the standard amateur prefix; XV is reserved for
        // commercial and special-event use.
        cw("Vietnam", "Asia", "3W" to 10, "XV" to 1),
        cw("Israel", "Asia", "4X" to 10, "4Z" to 8),
        cw("Saudi Arabia", "Asia", "HZ" to 10, "7Z" to 2, "8Z" to 1),
        c("United Arab Emirates", "Asia", "A6"),
        cw("Iran", "Asia",
            "EP" to 12, "EQ" to 2, "9B" to 1, "9C" to 1, "9D" to 1),
        cw("Pakistan", "Asia",
            "AP" to 12, "6P" to 1, "6Q" to 1, "6R" to 1, "6S" to 1),
        cw("Bangladesh", "Asia", "S2" to 8, "S3" to 2),
        // 4P-4S is the Sri Lankan block; 4S is what's actually issued.
        // (4U belongs to the United Nations, not Sri Lanka.)
        cw("Sri Lanka", "Asia", "4S" to 10, "4R" to 2),
        cw("Mongolia", "Asia", "JT" to 10, "JU" to 2, "JV" to 1),
        cw("Taiwan", "Asia",
            "BV" to 10, "BM" to 5, "BU" to 4, "BX" to 3,
            "BN" to 1, "BO" to 1, "BP" to 1, "BQ" to 1, "BW" to 1),
        c("Hong Kong", "Asia", "VR"),
        c("Macau", "Asia", "XX"),
        cw("Malaysia", "Asia", "9M" to 10, "9W" to 5),
        cw("Singapore", "Asia", "9V" to 10, "S6" to 1),
        c("Cambodia", "Asia", "XU"),
        c("Nepal", "Asia", "9N"),
        c("Kuwait", "Asia", "9K"),
        c("Qatar", "Asia", "A7"),
        c("Bahrain", "Asia", "A9"),
        c("Oman", "Asia", "A4"),
        c("Jordan", "Asia", "JY"),
        c("Lebanon", "Asia", "OD"),
        cw("Kazakhstan", "Asia", "UN" to 10, "UP" to 3, "UO" to 1, "UQ" to 1),
        // Uzbekistan's block is UJA-UMZ but the issued calls are UK7-UK9;
        // UJ/UL/UM are reserved and essentially unheard.
        cw("Uzbekistan", "Asia", "UK" to 10, "UJ" to 1, "UM" to 1),
        c("Kyrgyzstan", "Asia", "EX"),
        c("Tajikistan", "Asia", "EY"),
        c("Armenia", "Asia", "EK"),
        cw("Azerbaijan", "Asia", "4J" to 8, "4K" to 6),
        c("Georgia", "Asia", "4L"),

        // -------- Oceania --------
        c("Australia", "Oceania", "VK"),
        c("New Zealand", "Oceania", "ZL"),
        cw("Hawaii", "Oceania", "KH6" to 10, "WH6" to 5, "NH6" to 2),
        cw("Alaska", "Oceania", "KL" to 10, "WL" to 4, "NL" to 2),
        cw("Guam", "Oceania", "KH2" to 10, "WH2" to 3, "NH2" to 2),
        cw("American Samoa", "Oceania", "KH8" to 8, "WH8" to 2, "NH8" to 1),
        c("Fiji", "Oceania", "3D2"),
        c("Papua New Guinea", "Oceania", "P2"),
        c("New Caledonia", "Oceania", "FK"),
        c("French Polynesia", "Oceania", "FO"),
        c("Tonga", "Oceania", "A3"),
        c("Samoa", "Oceania", "5W"),
        c("Vanuatu", "Oceania", "YJ"),
        c("Solomon Islands", "Oceania", "H4"),
        c("Marshall Islands", "Oceania", "V7"),
        c("Micronesia", "Oceania", "V6"),
        c("Palau", "Oceania", "T8"),
        cw("Northern Mariana Islands", "Oceania", "KH0" to 8, "WH0" to 2, "NH0" to 1),
        // E5 since 2006/07; the old ZK1 calls were withdrawn and all
        // licensees reissued under E5, so ZK1 is never heard today.
        c("Cook Islands", "Oceania", "E5"),
        c("Kiribati", "Oceania", "T3"),
        c("Tuvalu", "Oceania", "T2"),

        // -------- Africa --------
        cw("South Africa", "Africa", "ZS" to 12, "ZR" to 4, "ZT" to 1, "ZU" to 1),
        c("Egypt", "Africa", "SU"),
        cw("Morocco", "Africa",
            "CN" to 12, "5C" to 1, "5D" to 1, "5E" to 1, "5F" to 1, "5G" to 1),
        c("Canary Islands", "Africa", "EA8"),
        c("Tunisia", "Africa", "3V"),
        cw("Algeria", "Africa",
            "7X" to 10, "7T" to 1, "7U" to 1, "7V" to 1, "7W" to 1, "7Y" to 1),
        c("Libya", "Africa", "5A"),
        cw("Kenya", "Africa", "5Z" to 8, "5Y" to 2),
        cw("Nigeria", "Africa", "5N" to 8, "5O" to 1),
        c("Ghana", "Africa", "9G"),
        cw("Senegal", "Africa", "6W" to 8, "6V" to 1),
        cw("Ethiopia", "Africa", "ET" to 8, "9E" to 1, "9F" to 1),
        cw("Tanzania", "Africa", "5H" to 8, "5I" to 2),
        c("Uganda", "Africa", "5X"),
        c("Zimbabwe", "Africa", "Z2"),
        c("Zambia", "Africa", "9J"),
        c("Mauritius", "Africa", "3B8"),
        c("Reunion", "Africa", "FR"),
        c("Seychelles", "Africa", "S7"),
        cw("Madagascar", "Africa", "5R" to 8, "5S" to 1, "6X" to 1),
        cw("Angola", "Africa", "D2" to 6, "D3" to 1),
        cw("Mozambique", "Africa", "C9" to 8, "C8" to 2),
        c("Namibia", "Africa", "V5"),
        cw("Botswana", "Africa", "A2" to 8, "8O" to 1),
        c("Cameroon", "Africa", "TJ"),
        c("Ivory Coast", "Africa", "TU"),
        cw("Sudan", "Africa", "ST" to 8, "6T" to 1, "6U" to 1),
        c("Somalia", "Africa", "6O", "T5"),

        // -------- Antarctica --------
        c("Antarctica", "Antarctica", "KC4", "VP8", "DP0"),

        // -------- Generic --------
        c("ITU Generic", "ITU Generic", ""),
    )

    fun names(): List<String> = countries.map { it.name }
    fun byName(name: String): CallsignCountry? = countries.firstOrNull { it.name == name }

    /**
     * Group the registry by region, preserving the declaration order
     * within each region. Used by the country-picker dialog.
     */
    fun namesByRegion(): LinkedHashMap<String, List<CallsignCountry>> {
        val out = LinkedHashMap<String, MutableList<CallsignCountry>>()
        for (country in countries) {
            out.getOrPut(country.region) { mutableListOf() }.add(country)
        }
        return out as LinkedHashMap<String, List<CallsignCountry>>
    }
}
