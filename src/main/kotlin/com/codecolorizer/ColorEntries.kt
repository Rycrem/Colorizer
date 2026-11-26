package com.codecolorizer

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.options.colors.ColorSettingsPage

/**
 * Un élément colorable :
 *  - soit une couleur de code (TextAttributesKey) : ex. "Java › Mots-clés" → JAVA_KEYWORD ;
 *  - soit une couleur de l'éditeur (ColorKey) : ex. "Général › Numéros de ligne" → LINE_NUMBERS_COLOR.
 */
data class ColorEntry(
    val group: String,
    val name: String,
    val key: TextAttributesKey? = null,
    val colorKey: ColorKey? = null,
    /** Pour une ColorKey : true si c'est une couleur de fond (ligne du curseur, marge...). */
    val isBackground: Boolean = false,
) {
    val label: String get() = "$group › $name"
    val externalName: String get() = key?.externalName ?: colorKey?.externalName ?: ""
    val isEditorColor: Boolean get() = colorKey != null
}

object ColorEntries {
    const val OTHER_GROUP = "Other"

    /** Tous les éléments déclarés par les langages installés (même liste que Settings › Color Scheme). */
    fun all(): List<ColorEntry> {
        val result = ArrayList<ColorEntry>()
        val seen = HashSet<String>()
        for (page in ColorSettingsPage.EP_NAME.extensionList) {
            val group = runCatching { page.displayName }.getOrNull() ?: continue
            runCatching { page.attributeDescriptors }.getOrNull()?.forEach { d ->
                val key = d.key ?: return@forEach
                if (seen.add("$group|A|${key.externalName}"))
                    result += ColorEntry(group, d.displayName.replace("//", " › "), key = key)
            }
            runCatching { page.colorDescriptors }.getOrNull()?.forEach { d ->
                val key = d.key ?: return@forEach
                if (seen.add("$group|C|${key.externalName}"))
                    result += ColorEntry(
                        group, d.displayName.replace("//", " › "), colorKey = key,
                        isBackground = d.kind.name.startsWith("BACKGROUND")
                    )
            }
        }
        return result.sortedWith(compareBy({ it.group.lowercase() }, { it.name.lowercase() }))
    }

    /** Nom lisible pour une clé de code trouvée dans l'éditeur. */
    fun describe(key: TextAttributesKey, catalog: List<ColorEntry>): ColorEntry =
        catalog.firstOrNull { it.key == key }
            ?: ColorEntry(OTHER_GROUP, key.externalName, key = key)

    /** Nom lisible pour une couleur de l'éditeur. */
    fun describe(key: ColorKey, catalog: List<ColorEntry>): ColorEntry =
        catalog.firstOrNull { it.colorKey == key }
            ?: ColorEntry(
                "Editor", EDITOR_NAMES[key.externalName] ?: key.externalName, colorKey = key,
                isBackground = key.externalName.contains("BACKGROUND") || key.externalName.endsWith("ROW_COLOR")
            )

    private val EDITOR_NAMES = mapOf(
        "LINE_NUMBERS_COLOR" to "Line numbers",
        "LINE_NUMBER_ON_CARET_ROW_COLOR" to "Caret row line number",
        "GUTTER_BACKGROUND" to "Gutter background",
        "CARET_ROW_COLOR" to "Caret row",
        "SELECTION_BACKGROUND" to "Selection background",
        "SELECTION_FOREGROUND" to "Selected text",
        "CARET_COLOR" to "Caret",
        "INDENT_GUIDE" to "Indent guides",
        "RIGHT_MARGIN_COLOR" to "Right margin",
    )
}
