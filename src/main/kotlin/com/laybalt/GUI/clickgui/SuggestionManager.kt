package com.laybalt.GUI.clickgui

/** Ranks features for the search bar: name prefix, name match, setting match, then description match. */
object SuggestionManager {
    fun getSuggestions(query: String, features: List<GuiFeature>): List<GuiFeature> {
        if (query.isBlank()) return features
        val cleanQuery = query.normalize()

        return features.mapNotNull { feature -> rank(feature, cleanQuery)?.let { feature to it } }
            .sortedWith(compareBy({ it.second }, { it.first.name }))
            .map { it.first }
    }

    private fun rank(feature: GuiFeature, query: String): Int? {
        val name = feature.name.normalize()
        if (name.startsWith(query)) return 0
        if (name.contains(query)) return 1

        var settingContains = false
        for (setting in feature.settings.filter { it.visibility() }) {
            val settingName = setting.name.normalize()
            if (settingName.startsWith(query)) return 2
            if (settingName.contains(query)) settingContains = true
        }
        if (settingContains) return 3

        if (feature.description?.normalize()?.contains(query) == true) return 5
        return null
    }

    private fun String.normalize() = filterNot(Char::isWhitespace).lowercase()
}
