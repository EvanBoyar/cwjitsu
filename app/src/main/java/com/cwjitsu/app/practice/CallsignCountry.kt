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
)

data class CallsignCountry(
    val name: String,
    val region: String,
    val templates: List<CallsignTemplate>,
)

object CallsignRegistry {

    /** Helper: build a [CallsignCountry] from a list of prefix strings. */
    private fun c(name: String, region: String, vararg prefixes: String): CallsignCountry =
        CallsignCountry(name, region, prefixes.map { CallsignTemplate(it) })

    /**
     * Master list of countries / territories with amateur radio allocations.
     *
     * The list is intentionally broad (~130 entries) but does not try to
     * enumerate every obscure DXCC entity. The user can search the dialog
     * by country name or by prefix. ITU Generic (empty prefix) is kept
     * for "anything you might hear" warm-ups.
     */
    val countries: List<CallsignCountry> = listOf(
        // -------- North America --------
        c("United States", "North America",
            "K", "W", "N",
            "AA", "AB", "AC", "AD", "AE", "AF", "AG", "AH", "AI", "AJ", "AK", "AL"),
        c("Canada", "North America", "VE", "VA", "VO", "VY"),
        c("Mexico", "North America",
            "XE", "XF", "4A", "4B", "4C", "6D", "6E", "6F", "6G", "6H", "6I", "6J"),
        c("Greenland", "North America", "OX"),
        c("Panama", "North America", "HP", "H3", "H8", "H9"),
        c("Cuba", "North America", "CM", "CO", "T4"),
        c("Jamaica", "North America", "6Y"),
        c("Puerto Rico", "North America", "KP4", "WP4", "NP4"),
        c("US Virgin Islands", "North America", "KP2", "WP2", "NP2"),
        c("Dominican Republic", "North America", "HI"),

        // -------- Caribbean --------
        c("Bahamas", "Caribbean", "C6"),
        c("Bermuda", "Caribbean", "VP9"),
        c("Cayman Islands", "Caribbean", "ZF"),
        c("Trinidad & Tobago", "Caribbean", "9Y", "9Z"),
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
        c("Costa Rica", "Central America", "TI", "TE"),
        c("Guatemala", "Central America", "TG"),
        c("Honduras", "Central America", "HR", "HQ"),
        c("El Salvador", "Central America", "YS"),
        c("Nicaragua", "Central America", "YN", "H6", "H7"),
        c("Belize", "Central America", "V3"),

        // -------- South America --------
        c("Brazil", "South America",
            "PP", "PQ", "PR", "PS", "PT", "PU", "PV", "PW", "PX", "PY",
            "ZV", "ZW", "ZX", "ZY", "ZZ"),
        c("Argentina", "South America",
            "LU", "LO", "LP", "LQ", "LR", "LS", "LT", "LV", "LW",
            "AY", "AZ"),
        c("Chile", "South America", "CE", "CA", "CB", "CC", "CD", "XQ", "XR", "3G"),
        c("Colombia", "South America", "HK", "HJ", "5J", "5K"),
        c("Peru", "South America", "OA", "OB", "OC", "4T"),
        c("Venezuela", "South America", "YV", "YW", "YX", "YY", "4M"),
        c("Ecuador", "South America", "HC", "HD"),
        c("Uruguay", "South America", "CX", "CV", "CW"),
        c("Paraguay", "South America", "ZP"),
        c("Bolivia", "South America", "CP"),
        c("French Guiana", "South America", "FY"),
        c("Guyana", "South America", "8R"),
        c("Suriname", "South America", "PZ"),

        // -------- Europe --------
        c("United Kingdom", "Europe", "G", "M", "2E", "MM", "MW"),
        c("Germany", "Europe",
            "DA", "DB", "DC", "DD", "DE", "DF", "DG", "DH", "DI", "DJ",
            "DK", "DL", "DM", "DN", "DO", "DP", "DQ", "DR"),
        c("France", "Europe", "F", "TM"),
        c("Italy", "Europe", "I", "IK", "IN", "IW", "IZ"),
        c("Spain", "Europe",
            "EA", "EB", "EC", "ED", "EE", "EF", "EG", "EH", "AM", "AO"),
        c("Russia", "Europe",
            "R", "RA", "RC", "RD", "RN", "RU", "RV", "RW", "RX", "RZ",
            "UA", "UB", "UC", "UD", "UE", "UF", "UG", "UH", "UI"),
        c("Poland", "Europe", "SP", "SQ", "SR", "SO", "3Z"),
        c("Ukraine", "Europe",
            "UR", "US", "UT", "UU", "UV", "UW", "UX", "UY", "UZ",
            "EM", "EN", "EO"),
        c("Netherlands", "Europe",
            "PA", "PB", "PC", "PD", "PE", "PF", "PG", "PH", "PI"),
        c("Belgium", "Europe", "ON", "OO", "OP", "OQ", "OR", "OS", "OT"),
        c("Sweden", "Europe",
            "SM", "SA", "SB", "SC", "SD", "SE", "SF", "SG", "SH", "SI", "SJ", "SK", "SL"),
        c("Norway", "Europe",
            "LA", "LB", "LC", "LD", "LE", "LF", "LG", "LH", "LI", "LJ", "LN"),
        c("Finland", "Europe", "OH", "OG", "OF", "OI"),
        c("Switzerland", "Europe", "HB", "HE"),
        c("Austria", "Europe", "OE"),
        c("Greece", "Europe", "SV", "SZ", "SX", "SY", "J4"),
        c("Iceland", "Europe", "TF"),
        c("Portugal", "Europe", "CT", "CR", "CS", "CQ"),
        c("Denmark", "Europe", "OZ", "5P", "5Q", "OU", "OV", "XP"),
        c("Czech Republic", "Europe", "OK", "OL"),
        c("Slovakia", "Europe", "OM"),
        c("Hungary", "Europe", "HA", "HG"),
        c("Romania", "Europe", "YO", "YP", "YQ", "YR"),
        c("Bulgaria", "Europe", "LZ"),
        c("Serbia", "Europe", "YU", "YT"),
        c("Croatia", "Europe", "9A"),
        c("Slovenia", "Europe", "S5"),
        c("Bosnia & Herzegovina", "Europe", "E7"),
        c("Montenegro", "Europe", "4O"),
        c("North Macedonia", "Europe", "Z3"),
        c("Albania", "Europe", "ZA"),
        c("Moldova", "Europe", "ER"),
        c("Belarus", "Europe", "EU", "EV", "EW"),
        c("Lithuania", "Europe", "LY"),
        c("Latvia", "Europe", "YL"),
        c("Estonia", "Europe", "ES"),
        c("Ireland", "Europe", "EI", "EJ"),
        c("Luxembourg", "Europe", "LX"),
        c("Malta", "Europe", "9H"),
        c("Cyprus", "Europe", "5B", "H2", "P3"),
        c("Turkey", "Europe", "TA", "TB", "TC", "YM"),

        // -------- Asia --------
        c("Japan", "Asia",
            "JA", "JE", "JF", "JG", "JH", "JI", "JJ", "JK", "JL", "JM",
            "JN", "JO", "JP", "JQ", "JR", "JS"),
        c("China", "Asia", "BA", "BD", "BG", "BH", "BI", "BJ"),
        c("India", "Asia", "VU"),
        c("South Korea", "Asia",
            "HL", "HM", "DS", "6K", "6L", "6M", "6N", "D7", "D8", "D9"),
        c("Indonesia", "Asia",
            "YB", "YC", "YD", "YE", "YF", "YG", "YH",
            "7A", "7B", "7C", "7D", "7E", "7F", "7G", "7H", "7I",
            "8A", "8B", "8C", "8D", "8E", "8F", "8G", "8H", "8I"),
        c("Philippines", "Asia", "DU", "DV", "DW", "DX", "DY", "DZ", "4D", "4E", "4F", "4G", "4H", "4I"),
        c("Thailand", "Asia", "HS", "E2"),
        c("Vietnam", "Asia", "XV"),
        c("Israel", "Asia", "4X", "4Z"),
        c("Saudi Arabia", "Asia", "HZ", "7Z", "8Z"),
        c("United Arab Emirates", "Asia", "A6"),
        c("Iran", "Asia", "EP", "EQ", "9B", "9C", "9D"),
        c("Pakistan", "Asia", "AP", "6P", "6Q", "6R", "6S"),
        c("Bangladesh", "Asia", "S2", "S3"),
        c("Sri Lanka", "Asia", "4S", "4R", "4U"),
        c("Mongolia", "Asia", "JT", "JU", "JV"),
        c("Taiwan", "Asia", "BV", "BU", "BW", "BX", "BM", "BN", "BO", "BP", "BQ"),
        c("Hong Kong", "Asia", "VR"),
        c("Macau", "Asia", "XX"),
        c("Malaysia", "Asia", "9M", "9W"),
        c("Singapore", "Asia", "9V", "S6"),
        c("Cambodia", "Asia", "XU"),
        c("Nepal", "Asia", "9N"),
        c("Kuwait", "Asia", "9K"),
        c("Qatar", "Asia", "A7"),
        c("Bahrain", "Asia", "A9"),
        c("Oman", "Asia", "A4"),
        c("Jordan", "Asia", "JY"),
        c("Lebanon", "Asia", "OD"),
        c("Kazakhstan", "Asia", "UN", "UO", "UP", "UQ"),
        c("Uzbekistan", "Asia", "UJ", "UM"),
        c("Kyrgyzstan", "Asia", "EX"),
        c("Tajikistan", "Asia", "EY"),
        c("Armenia", "Asia", "EK"),
        c("Azerbaijan", "Asia", "4J", "4K"),
        c("Georgia", "Asia", "4L"),

        // -------- Oceania --------
        c("Australia", "Oceania", "VK"),
        c("New Zealand", "Oceania", "ZL"),
        c("Hawaii", "Oceania", "KH6", "NH6", "WH6"),
        c("Alaska", "Oceania", "KL", "WL", "NL"),
        c("Guam", "Oceania", "KH2", "NH2", "WH2"),
        c("American Samoa", "Oceania", "KH8", "NH8", "WH8"),
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
        c("Northern Mariana Islands", "Oceania", "KH0", "NH0", "WH0"),
        c("Cook Islands", "Oceania", "ZK"),
        c("Kiribati", "Oceania", "T3"),
        c("Tuvalu", "Oceania", "T2"),

        // -------- Africa --------
        c("South Africa", "Africa", "ZS", "ZR", "ZT", "ZU"),
        c("Egypt", "Africa", "SU"),
        c("Morocco", "Africa", "CN", "5C", "5D", "5E", "5F", "5G"),
        c("Canary Islands", "Africa", "EA8"),
        c("Tunisia", "Africa", "3V"),
        c("Algeria", "Africa", "7T", "7U", "7V", "7W", "7X", "7Y"),
        c("Libya", "Africa", "5A"),
        c("Kenya", "Africa", "5Z", "5Y"),
        c("Nigeria", "Africa", "5N", "5O"),
        c("Ghana", "Africa", "9G"),
        c("Senegal", "Africa", "6W", "6V"),
        c("Ethiopia", "Africa", "ET", "9E", "9F"),
        c("Tanzania", "Africa", "5H", "5I"),
        c("Uganda", "Africa", "5X"),
        c("Zimbabwe", "Africa", "Z2"),
        c("Zambia", "Africa", "9J"),
        c("Mauritius", "Africa", "3B8"),
        c("Reunion", "Africa", "FR"),
        c("Seychelles", "Africa", "S7"),
        c("Madagascar", "Africa", "5R", "5S", "6X"),
        c("Angola", "Africa", "D2", "D3"),
        c("Mozambique", "Africa", "C8", "C9"),
        c("Namibia", "Africa", "V5"),
        c("Botswana", "Africa", "A2", "8O"),
        c("Cameroon", "Africa", "TJ", "TK"),
        c("Ivory Coast", "Africa", "TU"),
        c("Sudan", "Africa", "ST", "6T", "6U"),
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
