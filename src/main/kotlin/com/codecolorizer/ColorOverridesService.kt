package com.codecolorizer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.ColorUtil
import java.awt.Color
import java.awt.Font

/** Une personnalisation pour un élément (TextAttributesKey) dans un thème donné. */
class ColorOverride {
    var foreground: String? = null   // "#RRGGBB" ou null = pas de couleur
    var background: String? = null
    var effectColor: String? = null
    var effectType: String? = null   // nom de EffectType
    var bold: Boolean = false
    var italic: Boolean = false

    fun toTextAttributes(): TextAttributes {
        var fontType = Font.PLAIN
        if (bold) fontType = fontType or Font.BOLD
        if (italic) fontType = fontType or Font.ITALIC
        val effect = effectType?.let { runCatching { EffectType.valueOf(it) }.getOrNull() }
        return TextAttributes(
            foreground.toColor(),
            background.toColor(),
            effectColor.toColor(),
            effect,
            fontType
        )
    }

    fun copy(): ColorOverride = ColorOverride().also {
        it.foreground = foreground; it.background = background
        it.effectColor = effectColor; it.effectType = effectType
        it.bold = bold; it.italic = italic
    }

    fun isEmpty() = foreground == null && background == null && effectColor == null && !bold && !italic

    companion object {
        fun from(attrs: TextAttributes?): ColorOverride = ColorOverride().apply {
            if (attrs == null) return@apply
            foreground = attrs.foregroundColor.toHex()
            background = attrs.backgroundColor.toHex()
            effectColor = attrs.effectColor.toHex()
            effectType = attrs.effectType?.name
            bold = attrs.fontType and Font.BOLD != 0
            italic = attrs.fontType and Font.ITALIC != 0
        }
    }
}

fun Color?.toHex(): String? = this?.let { String.format("#%02X%02X%02X", it.red, it.green, it.blue) }
fun String?.toColor(): Color? = this?.let { runCatching { Color.decode(it) }.getOrNull() }

/**
 * Stocke les personnalisations (par thème d'IDE) et les applique au thème actif.
 * Elles sont sauvegardées dans codeColorizer.xml et ré-appliquées à chaque démarrage
 * et à chaque changement de thème, même pour les thèmes intégrés (Darcula, Light...).
 */
@Service(Service.Level.APP)
@State(name = "CodeColorizerSettings", storages = [Storage("codeColorizer.xml")])
class ColorOverridesService : PersistentStateComponent<ColorOverridesService.SettingsState> {

    class SettingsState {
        /** clé = "<nom du thème>||<nom de la TextAttributesKey>" */
        var overrides: MutableMap<String, ColorOverride> = LinkedHashMap()
        /** clé = "<nom du thème>||<nom de la ColorKey>" → "#RRGGBB" */
        var colorOverrides: MutableMap<String, String> = LinkedHashMap()
        /** Thèmes importés par l'utilisateur : nom → contenu JSON */
        var userThemes: MutableMap<String, String> = LinkedHashMap()
    }

    private var state = SettingsState()

    /** Valeurs d'origine capturées avant notre première modification (pour "Réinitialiser"). */
    private val originals = HashMap<String, TextAttributes?>()
    private val originalColors = HashMap<String, Color?>()

    private val listeners = mutableListOf<() -> Unit>()

    override fun getState(): SettingsState = state
    override fun loadState(s: SettingsState) { state = s }

    val scheme: EditorColorsScheme get() = EditorColorsManager.getInstance().globalScheme

    private fun prefix(s: EditorColorsScheme) = "${s.name}||"
    private fun id(s: EditorColorsScheme, key: TextAttributesKey) = prefix(s) + key.externalName
    private fun colorId(s: EditorColorsScheme, key: ColorKey) = prefix(s) + key.externalName

    fun hasOverride(key: TextAttributesKey): Boolean = state.overrides.containsKey(id(scheme, key))
    fun hasOverride(key: ColorKey): Boolean = state.colorOverrides.containsKey(colorId(scheme, key))
    fun hasOverride(e: ColorEntry): Boolean =
        e.key?.let { hasOverride(it) } ?: e.colorKey?.let { hasOverride(it) } ?: false

    /** Couleur actuelle d'une couleur de l'éditeur (numéros de ligne, marge...). */
    fun currentColor(key: ColorKey): Color? = scheme.getColor(key)

    /** Couleur représentative d'un élément (pour les pastilles). */
    fun swatch(e: ColorEntry): Color? {
        e.colorKey?.let { return currentColor(it) }
        val attrs = e.key?.let { currentAttributes(it) } ?: return null
        return attrs.foregroundColor ?: attrs.backgroundColor ?: attrs.effectColor
    }

    fun overrideCount(): Int {
        val p = prefix(scheme)
        return state.overrides.keys.count { it.startsWith(p) } + state.colorOverrides.keys.count { it.startsWith(p) }
    }

    /** Attributs actuellement effectifs pour cette clé dans le thème actif. */
    fun currentAttributes(key: TextAttributesKey): TextAttributes? = scheme.getAttributes(key)

    // ---------------------------------------------------------------- éléments un par un

    fun setOverride(key: TextAttributesKey, override: ColorOverride) {
        putAttr(scheme, key, override)
        refresh()
    }

    fun reset(key: TextAttributesKey) {
        val s = scheme
        val id = id(s, key)
        if (state.overrides.remove(id) == null) return
        s.setAttributes(key, originalAttributes(s, key, id))
        originals.remove(id)
        refresh()
    }

    fun setColorOverride(key: ColorKey, c: Color) {
        putColor(scheme, key, c)
        refresh()
    }

    fun resetColor(key: ColorKey) {
        val s = scheme
        val cid = colorId(s, key)
        if (state.colorOverrides.remove(cid) == null) return
        s.setColor(key, originalColor(s, key, cid))
        originalColors.remove(cid)
        refresh()
    }

    fun reset(e: ColorEntry) {
        e.key?.let { reset(it) }
        e.colorKey?.let { resetColor(it) }
    }

    fun resetAll() {
        resetAllNoRefresh(scheme)
        refresh()
    }

    // ---------------------------------------------------------------- thèmes complets

    /**
     * Applique un thème (prêt à l'emploi ou importé).
     * @param replace true = efface d'abord les modifications existantes ; false = fusionne.
     */
    fun applyTheme(theme: ThemeData, replace: Boolean) {
        val s = scheme
        if (replace) resetAllNoRefresh(s)

        for ((name, hex) in theme.editorColors) {
            val c = hex.toColor() ?: continue
            putColor(s, ColorKey.find(name), c)
        }
        for ((name, o) in theme.attributes) {
            val k = TextAttributesKey.find(name)
            putAttr(s, k, merged(s, k, o, theme.present[name]))
        }

        // Les clés DEFAULT_* ne suffisent pas toujours : certains langages ont des couleurs
        // explicites dans le thème de l'IDE. On propage donc chaque couleur du thème à tous
        // les éléments qui en héritent (ex. DEFAULT_KEYWORD → JAVA_KEYWORD, KOTLIN_KEYWORD...).
        val declared = theme.attributes.keys
        for (entry in ColorEntries.all()) {
            val k = entry.key ?: continue
            if (k.externalName in declared) continue
            var f = k.fallbackAttributeKey
            var depth = 0
            while (f != null && depth++ < 10) {
                val o = theme.attributes[f.externalName]
                if (o != null) { putAttr(s, k, merged(s, k, o, theme.present[f.externalName])); break }
                f = f.fallbackAttributeKey
            }
        }
        refresh()
    }

    /**
     * Construit un thème exportable.
     * @param everything false = seulement mes modifications ; true = toutes les couleurs actuelles.
     */
    fun exportTheme(name: String, everything: Boolean): ThemeData {
        val s = scheme
        val p = prefix(s)
        val theme = ThemeData(name, ColorUtil.isDark(s.defaultBackground))
        if (everything) {
            val keys = LinkedHashSet<TextAttributesKey>()
            keys += TextAttributesKey.find("TEXT")
            ColorEntries.all().mapNotNullTo(keys) { it.key }
            for (k in keys) {
                val o = ColorOverride.from(s.getAttributes(k))
                if (!o.isEmpty()) theme.attributes[k.externalName] = o
            }
            for (keyName in EDITOR_COLOR_KEYS) {
                s.getColor(ColorKey.find(keyName))?.toHex()?.let { theme.editorColors[keyName] = it }
            }
        } else {
            for ((cid, hex) in state.colorOverrides) if (cid.startsWith(p)) theme.editorColors[cid.removePrefix(p)] = hex
            for ((aid, o) in state.overrides) if (aid.startsWith(p)) theme.attributes[aid.removePrefix(p)] = o.copy()
        }
        return theme
    }

    // ---------------------------------------------------------------- thèmes importés

    fun userThemes(): List<ThemeData> = state.userThemes.values.mapNotNull {
        runCatching { ThemeJson.read(it) }.getOrNull()
    }

    fun saveUserTheme(theme: ThemeData) {
        state.userThemes[theme.name] = ThemeJson.write(theme)
    }

    fun deleteUserTheme(name: String) {
        state.userThemes.remove(name)
    }

    // ---------------------------------------------------------------- démarrage / changement de thème

    /** Applique au thème actif toutes les personnalisations enregistrées pour ce thème. */
    fun applyAll(target: EditorColorsScheme = scheme) {
        val p = prefix(target)
        var changed = false
        for ((cid, hex) in state.colorOverrides) {
            if (!cid.startsWith(p)) continue
            val key = ColorKey.find(cid.removePrefix(p))
            if (!originalColors.containsKey(cid)) originalColors[cid] = target.getColor(key)
            target.setColor(key, hex.toColor())
            changed = true
        }
        for ((aid, override) in state.overrides) {
            if (!aid.startsWith(p)) continue
            val key = TextAttributesKey.find(aid.removePrefix(p))
            if (!originals.containsKey(aid)) originals[aid] = target.getAttributes(key)?.clone()
            target.setAttributes(key, safeAttributes(target, key, override, aid))
            changed = true
        }
        if (changed) refresh()
    }

    fun addChangeListener(l: () -> Unit) { listeners += l }
    fun removeChangeListener(l: () -> Unit) { listeners -= l }

    // ---------------------------------------------------------------- interne

    private fun putAttr(s: EditorColorsScheme, key: TextAttributesKey, o: ColorOverride) {
        val aid = id(s, key)
        if (!originals.containsKey(aid)) originals[aid] = s.getAttributes(key)?.clone()
        state.overrides[aid] = o
        s.setAttributes(key, safeAttributes(s, key, o, aid))
    }

    /**
     * Le texte par défaut (TEXT) doit toujours avoir un fond et une couleur : sans fond,
     * IntelliJ affiche l'éditeur en blanc. Si on n'en donne pas, on garde ceux d'origine.
     */
    private fun safeAttributes(s: EditorColorsScheme, key: TextAttributesKey, o: ColorOverride, aid: String): TextAttributes {
        val attrs = o.toTextAttributes()
        if (key.externalName == "TEXT") {
            val orig = originals[aid] ?: parentScheme(s)?.getAttributes(key)
            if (attrs.backgroundColor == null) attrs.backgroundColor = orig?.backgroundColor
            if (attrs.foregroundColor == null) attrs.foregroundColor = orig?.foregroundColor
        }
        return attrs
    }

    /** Couleurs absentes du JSON = on garde celles du thème actuel pour cette clé. */
    private fun merged(s: EditorColorsScheme, key: TextAttributesKey, o: ColorOverride, present: Set<String>?): ColorOverride {
        val r = o.copy()
        if (present == null) return r
        val cur = ColorOverride.from(s.getAttributes(key))
        if ("foreground" !in present) r.foreground = cur.foreground
        if ("background" !in present) r.background = cur.background
        if ("effectColor" !in present && "effectType" !in present) {
            r.effectColor = cur.effectColor
            r.effectType = cur.effectType
        }
        return r
    }

    private fun putColor(s: EditorColorsScheme, key: ColorKey, c: Color) {
        val cid = colorId(s, key)
        if (!originalColors.containsKey(cid)) originalColors[cid] = s.getColor(key)
        state.colorOverrides[cid] = c.toHex()!!
        s.setColor(key, c)
    }

    private fun resetAllNoRefresh(s: EditorColorsScheme) {
        val p = prefix(s)
        for (aid in state.overrides.keys.filter { it.startsWith(p) }) {
            val key = TextAttributesKey.find(aid.removePrefix(p))
            state.overrides.remove(aid)
            s.setAttributes(key, originalAttributes(s, key, aid))
            originals.remove(aid)
        }
        for (cid in state.colorOverrides.keys.filter { it.startsWith(p) }) {
            val key = ColorKey.find(cid.removePrefix(p))
            state.colorOverrides.remove(cid)
            s.setColor(key, originalColor(s, key, cid))
            originalColors.remove(cid)
        }
    }

    private fun parentScheme(s: EditorColorsScheme): EditorColorsScheme? = runCatching {
        s.javaClass.getMethod("getParentScheme").invoke(s) as? EditorColorsScheme
    }.getOrNull()

    private fun originalAttributes(s: EditorColorsScheme, key: TextAttributesKey, aid: String): TextAttributes {
        originals[aid]?.let { return it }
        parentScheme(s)?.getAttributes(key)?.let { return it.clone() }
        return inheritedMarker()
    }

    private fun originalColor(s: EditorColorsScheme, key: ColorKey, cid: String): Color? {
        if (originalColors.containsKey(cid)) return originalColors[cid]
        return parentScheme(s)?.getColor(key)
    }

    private fun inheritedMarker(): TextAttributes = runCatching {
        TextAttributes::class.java.getField("USE_INHERITED_MARKER").get(null) as TextAttributes
    }.getOrElse { TextAttributes() }

    private fun refresh() {
        ApplicationManager.getApplication().invokeLater {
            EditorFactory.getInstance().refreshAllEditors()
            listeners.toList().forEach { it() }
        }
    }

    companion object {
        /** Couleurs de l'éditeur incluses dans les thèmes (fond de la marge, ligne du curseur...). */
        val EDITOR_COLOR_KEYS = listOf(
            "CARET_COLOR", "CARET_ROW_COLOR", "SELECTION_BACKGROUND", "GUTTER_BACKGROUND",
            "LINE_NUMBERS_COLOR", "LINE_NUMBER_ON_CARET_ROW_COLOR", "INDENT_GUIDE", "RIGHT_MARGIN_COLOR"
        )

        fun getInstance(): ColorOverridesService =
            ApplicationManager.getApplication().getService(ColorOverridesService::class.java)
    }
}
