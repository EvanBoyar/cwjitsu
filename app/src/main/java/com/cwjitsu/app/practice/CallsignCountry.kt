package com.cwjitsu.app.practice

/**
 * Per-country callsign model.
 *
 * A real callsign is: PREFIX + DIGITS + SUFFIX where
 *   - PREFIX is 1..3 literal characters from the country's allocation
 *   - DIGITS is exactly one digit (US/Canada/UK conventions)
 *   - SUFFIX is a random sequence of 1..3 uppercase letters
 *
 * This model is straightforward, accurate, and easy to expand.
 */
data class CallsignTemplate(
    val prefix: String,
    val suffixRange: IntRange = 1..3,
)

data class CallsignCountry(
    val name: String,
    val templates: List<CallsignTemplate>,
)

object CallsignRegistry {

    val USA = listOf(
        CallsignTemplate("K"), CallsignTemplate("W"), CallsignTemplate("N"),
        CallsignTemplate("AA"), CallsignTemplate("AB"), CallsignTemplate("AC"),
        CallsignTemplate("AD"), CallsignTemplate("AE"), CallsignTemplate("AF"),
        CallsignTemplate("AG"), CallsignTemplate("AH"), CallsignTemplate("AI"),
        CallsignTemplate("AJ"), CallsignTemplate("AK"), CallsignTemplate("AL"),
    )

    val Canada = listOf(
        CallsignTemplate("VE"), CallsignTemplate("VA"), CallsignTemplate("VO"), CallsignTemplate("VY"),
    )

    val UK = listOf(
        CallsignTemplate("G"), CallsignTemplate("M"),
        CallsignTemplate("2E"),
    )

    val Germany = listOf(
        CallsignTemplate("DK"), CallsignTemplate("DL"), CallsignTemplate("DM"),
        CallsignTemplate("DN"), CallsignTemplate("DO"), CallsignTemplate("DP"),
        CallsignTemplate("DR"),
    )

    val France = listOf(
        CallsignTemplate("F"),
    )

    val Italy = listOf(
        CallsignTemplate("I"), CallsignTemplate("IZ"),
    )

    val Japan = listOf(
        CallsignTemplate("JA"), CallsignTemplate("JE"), CallsignTemplate("JF"),
        CallsignTemplate("JG"), CallsignTemplate("JH"), CallsignTemplate("JI"),
        CallsignTemplate("JJ"), CallsignTemplate("JK"), CallsignTemplate("JL"),
        CallsignTemplate("JM"), CallsignTemplate("JN"), CallsignTemplate("JO"),
        CallsignTemplate("JP"), CallsignTemplate("JQ"), CallsignTemplate("JR"),
        CallsignTemplate("JS"),
    )

    val Australia = listOf(
        CallsignTemplate("VK"),
    )

    val Brazil = listOf(
        CallsignTemplate("PP"), CallsignTemplate("PQ"), CallsignTemplate("PR"),
        CallsignTemplate("PS"), CallsignTemplate("PT"), CallsignTemplate("PU"),
        CallsignTemplate("PV"), CallsignTemplate("PW"), CallsignTemplate("PX"),
        CallsignTemplate("PY"), CallsignTemplate("ZV"), CallsignTemplate("ZW"),
        CallsignTemplate("ZX"), CallsignTemplate("ZY"), CallsignTemplate("ZZ"),
    )

    val Russia = listOf(
        CallsignTemplate("R"), CallsignTemplate("UA"), CallsignTemplate("UB"),
        CallsignTemplate("UC"), CallsignTemplate("UD"), CallsignTemplate("UE"),
        CallsignTemplate("UF"), CallsignTemplate("UG"), CallsignTemplate("UH"),
        CallsignTemplate("UI"),
    )

    val Iceland = listOf(
        CallsignTemplate("TF"),
    )

    val countries: List<CallsignCountry> = listOf(
        CallsignCountry("United States", USA),
        CallsignCountry("Canada", Canada),
        CallsignCountry("United Kingdom", UK),
        CallsignCountry("Germany", Germany),
        CallsignCountry("France", France),
        CallsignCountry("Italy", Italy),
        CallsignCountry("Japan", Japan),
        CallsignCountry("Australia", Australia),
        CallsignCountry("Brazil", Brazil),
        CallsignCountry("Russia", Russia),
        CallsignCountry("Iceland", Iceland),
        CallsignCountry("ITU Generic", listOf(CallsignTemplate(""))),
    )

    fun names(): List<String> = countries.map { it.name }
    fun byName(name: String): CallsignCountry? = countries.firstOrNull { it.name == name }
}
