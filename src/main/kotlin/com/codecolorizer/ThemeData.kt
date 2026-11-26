package com.codecolorizer

/**
 * Un thème Colorizer, tel qu'il est importé / exporté en JSON :
 *
 * {
 *   "name": "Mon thème",
 *   "dark": true,
 *   "editor":     { "CARET_ROW_COLOR": "#202030", ... },
 *   "attributes": { "DEFAULT_KEYWORD": { "foreground": "#FF79C6", "bold": true }, ... }
 * }
 *
 * - "attributes" : clés de couleur du code (TextAttributesKey). Les clés DEFAULT_* s'appliquent
 *   à tous les langages ; on peut aussi viser une clé précise (ex. JAVA_KEYWORD, KOTLIN_FUNCTION_CALL).
 *   "TEXT" règle le texte et le fond de l'éditeur.
 * - Une couleur absente garde la valeur du thème actuel ; "background": null l'enlève.
 *   "TEXT" garde toujours un fond et un texte (sinon l'éditeur deviendrait blanc / noir).
 * - "editor" : couleurs de l'éditeur (ligne du curseur, sélection, numéros de ligne...).
 * - Les thèmes prêts à l'emploi (resources/presets) ne touchent ni aux fonds (éditeur, marge,
 *   ligne du curseur, sélection) ni aux numéros de ligne : ils gardent ceux du thème de l'IDE.
 */
class ThemeData(
    var name: String,
    var dark: Boolean = true,
    val attributes: LinkedHashMap<String, ColorOverride> = LinkedHashMap(),
    val editorColors: LinkedHashMap<String, String> = LinkedHashMap(),
    var builtIn: Boolean = false,
    /** Champs réellement écrits dans le JSON pour chaque clé (une couleur absente = inchangée). */
    val present: HashMap<String, Set<String>> = HashMap(),
) {
    override fun toString() = name
}

object ThemeJson {

    fun write(t: ThemeData): String = buildString {
        append("{\n")
        append("  \"name\": ").append(str(t.name)).append(",\n")
        append("  \"dark\": ").append(t.dark).append(",\n")
        append("  \"editor\": {")
        append(t.editorColors.entries.joinToString(",") { "\n    ${str(it.key)}: ${str(it.value)}" })
        append(if (t.editorColors.isEmpty()) "},\n" else "\n  },\n")
        append("  \"attributes\": {")
        append(t.attributes.entries.joinToString(",") { (k, o) ->
            val fields = buildList {
                o.foreground?.let { add("\"foreground\": ${str(it)}") }
                o.background?.let { add("\"background\": ${str(it)}") }
                o.effectColor?.let { add("\"effectColor\": ${str(it)}") }
                o.effectType?.let { add("\"effectType\": ${str(it)}") }
                if (o.bold) add("\"bold\": true")
                if (o.italic) add("\"italic\": true")
            }
            "\n    ${str(k)}: { ${fields.joinToString(", ")} }"
        })
        append(if (t.attributes.isEmpty()) "}\n" else "\n  }\n")
        append("}\n")
    }

    /** Lit un thème. Lève IllegalArgumentException si le fichier n'est pas valide. */
    fun read(text: String): ThemeData {
        val root = Parser(text).parseRoot() as? Map<*, *>
            ?: throw IllegalArgumentException("The file does not contain a JSON object.")
        val name = root["name"] as? String ?: "Imported theme"
        val dark = root["dark"] as? Boolean ?: true
        val theme = ThemeData(name, dark)
        (root["editor"] as? Map<*, *>)?.forEach { (k, v) ->
            if (k is String && v is String && v.toColor() != null) theme.editorColors[k] = v
        }
        (root["attributes"] as? Map<*, *>)?.forEach { (k, v) ->
            if (k !is String || v !is Map<*, *>) return@forEach
            theme.present[k] = v.keys.filterIsInstance<String>().toSet()
            theme.attributes[k] = ColorOverride().apply {
                foreground = (v["foreground"] as? String)?.takeIf { it.toColor() != null }
                background = (v["background"] as? String)?.takeIf { it.toColor() != null }
                effectColor = (v["effectColor"] as? String)?.takeIf { it.toColor() != null }
                effectType = v["effectType"] as? String
                bold = v["bold"] == true
                italic = v["italic"] == true
            }
        }
        if (theme.attributes.isEmpty() && theme.editorColors.isEmpty())
            throw IllegalArgumentException("No colors found in this file.")
        return theme
    }

    private fun str(s: String) = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\")
            '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
        }
        append('"')
    }

    /** Mini parseur JSON (objets, tableaux, chaînes, nombres, booléens, null). */
    private class Parser(val s: String) {
        var i = 0

        fun parseRoot(): Any? { val v = value(); ws(); if (i != s.length) fail("unexpected content"); return v }

        fun value(): Any? {
            ws()
            if (i >= s.length) fail("unexpected end of file")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) number() else fail("unexpected character '$c'")
            }
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++; ws()
            if (peek('}')) { i++; return m }
            while (true) {
                ws(); val k = string(); ws(); expect(':')
                m[k] = value(); ws()
                if (peek(',')) { i++; continue }
                expect('}'); return m
            }
        }

        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++; ws()
            if (peek(']')) { i++; return l }
            while (true) {
                l += value(); ws()
                if (peek(',')) { i++; continue }
                expect(']'); return l
            }
        }

        fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDoubleOrNull() ?: fail("invalid number")
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("unexpected word")
            i += word.length; return v
        }

        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun peek(c: Char) = i < s.length && s[i] == c
        fun expect(c: Char) { if (!peek(c)) fail("'$c' expected"); i++ }
        fun fail(msg: String): Nothing {
            val line = s.substring(0, minOf(i, s.length)).count { it == '\n' } + 1
            throw IllegalArgumentException("Invalid JSON (line $line): $msg")
        }
    }
}

/** Thèmes prêts à l'emploi, livrés dans le plugin (dossier resources/presets, fichiers .json). */
object ThemePresets {
    private val FILES = listOf(
        "one-dark", "rose-fluo-menthe", "bleu-turquoise", "ambre-sauge", "orange-or", "jaune-vert-fluo", "bordeaux-vert-clair", "magenta-bleu-clair"
    )

    val all: List<ThemeData> by lazy {
        FILES.mapNotNull { f ->
            runCatching {
                val text = ThemePresets::class.java.getResourceAsStream("/presets/$f.json")!!
                    .use { it.readBytes().toString(Charsets.UTF_8) }
                ThemeJson.read(text).also { it.builtIn = true }
            }.getOrNull()
        }
    }
}
