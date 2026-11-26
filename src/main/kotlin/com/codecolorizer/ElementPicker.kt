package com.codecolorizer

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.awt.RelativePoint
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Point
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/** Retrouve les couleurs qui s'appliquent à un endroit de l'éditeur. */
object ElementFinder {

    /** Couleurs de code (syntaxique + sémantique) à cet offset, de la plus précise à la plus générale. */
    fun textKeysAt(project: Project?, editor: Editor, offsetIn: Int): List<TextAttributesKey> {
        val doc = editor.document
        if (doc.textLength == 0) return emptyList()
        val offset = offsetIn.coerceIn(0, doc.textLength - 1)
        val keys = LinkedHashSet<TextAttributesKey>()

        // 1) Coloration sémantique (méthodes, champs, paramètres...) : la plus précise, en premier
        val docMarkup = project?.let { DocumentMarkupModel.forDocument(doc, it, false) }
        val highlighters: List<RangeHighlighter> =
            docMarkup?.allHighlighters?.toList().orEmpty() + editor.markupModel.allHighlighters.toList()
        highlighters
            .filter { it.isValid && it.startOffset <= offset && offset < it.endOffset }
            .sortedBy { it.endOffset - it.startOffset }
            .mapNotNullTo(keys) { it.textAttributesKey }

        // 2) Coloration syntaxique (mots-clés, chaînes, commentaires, nombres...)
        val highlighter = (editor as? EditorEx)?.highlighter
        if (highlighter != null) {
            val it = highlighter.createIterator(offset)
            if (!it.atEnd()) it.textAttributesKeys.reversed().forEach { k -> keys += k }
        }

        // 3) Couleurs "par défaut" dont elles héritent (ex. Java Keyword → Language Defaults › Keyword)
        val withFallbacks = LinkedHashSet(keys)
        for (k in keys) {
            var f = k.fallbackAttributeKey
            while (f != null && withFallbacks.add(f)) f = f.fallbackAttributeKey
        }
        return withFallbacks.toList()
    }

    /** Couleurs de code sous le curseur texte (pour le clic droit). */
    fun textKeysAtCaret(project: Project?, editor: Editor): List<TextAttributesKey> {
        val doc = editor.document
        var offset = editor.caretModel.offset
        // Curseur juste après un mot : on regarde le caractère précédent
        if (offset > 0 && (offset >= doc.textLength || doc.charsSequence[offset].isWhitespace())) offset--
        return textKeysAt(project, editor, offset)
    }

    /** Tout ce qui colore le point cliqué dans la zone de texte. */
    fun entriesInText(project: Project?, editor: Editor, p: Point, catalog: List<ColorEntry>): List<ColorEntry> {
        val doc = editor.document
        val pos = editor.xyToLogicalPosition(p)
        val result = ArrayList<ColorEntry>()
        val text = TextAttributesKey.find("TEXT")

        if (pos.line < doc.lineCount) {
            val lineEnd = doc.getLineEndOffset(pos.line)
            val offset = editor.logicalPositionToOffset(pos)
            val endX = editor.offsetToXY(lineEnd).x
            val onChar = doc.textLength > 0 && p.x < endX && offset < lineEnd &&
                !doc.charsSequence[offset].isWhitespace()
            if (onChar) {
                textKeysAt(project, editor, offset).filter { it != text }
                    .mapTo(result) { ColorEntries.describe(it, catalog) }
            }
            val sel = editor.selectionModel
            if (sel.hasSelection() && offset >= sel.selectionStart && offset < sel.selectionEnd)
                result += ColorEntries.describe(EditorColors.SELECTION_BACKGROUND_COLOR, catalog)
            if (pos.line == editor.caretModel.logicalPosition.line)
                result += ColorEntries.describe(EditorColors.CARET_ROW_COLOR, catalog)
        }
        // Texte et fond par défaut de l'éditeur
        result += ColorEntries.describe(text, catalog)
        return result.distinct()
    }

    /** Ce qui colore la marge (numéros de ligne, fond de la marge). */
    fun entriesInGutter(editor: Editor, y: Int, catalog: List<ColorEntry>): List<ColorEntry> {
        val line = editor.xyToLogicalPosition(Point(0, y)).line
        val keys = ArrayList<ColorKey>()
        if (line < editor.document.lineCount) {
            if (line == editor.caretModel.logicalPosition.line) keys += EditorColors.LINE_NUMBER_ON_CARET_ROW
            keys += EditorColors.LINE_NUMBERS_COLOR
        }
        keys += EditorColors.GUTTER_BACKGROUND
        return keys.map { ColorEntries.describe(it, catalog) }.distinct()
    }
}

/**
 * Mode "pipette" : le prochain clic dans un éditeur (texte ou marge) ne déplace pas le curseur,
 * il désigne l'élément dont on veut changer la couleur. Échap annule.
 */
class ElementPicker private constructor(
    private val onPicked: (List<ColorEntry>, RelativePoint) -> Unit,
    private val onEnd: () -> Unit,
) : Disposable {

    private var swallowUntilRelease = false
    private var ended = false

    private val dispatcher = IdeEventQueue.EventDispatcher { e -> handle(e) }

    private fun handle(e: AWTEvent): Boolean {
        if (!ended && e is KeyEvent && e.id == KeyEvent.KEY_PRESSED && e.keyCode == KeyEvent.VK_ESCAPE) {
            cancel(); return true
        }
        if (e !is MouseEvent) return false
        if (swallowUntilRelease) {
            // On avale aussi le relâchement / clic qui suivent, pour que l'éditeur ne bouge pas
            if (e.id == MouseEvent.MOUSE_RELEASED || e.id == MouseEvent.MOUSE_CLICKED) {
                if (e.id == MouseEvent.MOUSE_CLICKED) swallowUntilRelease = false
                return true
            }
            if (e.id == MouseEvent.MOUSE_DRAGGED) return true
        }
        if (e.id != MouseEvent.MOUSE_PRESSED || ended) return false
        val src = e.component ?: return false
        val hit = findEditor(src) ?: return false   // clic hors éditeur : comportement normal
        val (editor, inGutter) = hit
        val catalog = ColorEntries.all()
        val project = editor.project
        val entries = if (inGutter) {
            val p = SwingUtilities.convertPoint(src, e.point, editor.contentComponent)
            ElementFinder.entriesInGutter(editor, p.y, catalog)
        } else {
            val p = SwingUtilities.convertPoint(src, e.point, editor.contentComponent)
            ElementFinder.entriesInText(project, editor, p, catalog)
        }
        swallowUntilRelease = true
        val where = RelativePoint(src, e.point)
        finish()
        ApplicationManager.getApplication().invokeLater { onPicked(entries, where) }
        return true
    }

    private fun findEditor(c: Component): Pair<Editor, Boolean>? {
        for (editor in EditorFactory.getInstance().allEditors) {
            if (SwingUtilities.isDescendingFrom(c, editor.contentComponent)) return editor to false
            val gutter = (editor as? EditorEx)?.gutterComponentEx ?: editor.gutter as? Component
            if (gutter != null && SwingUtilities.isDescendingFrom(c, gutter)) return editor to true
        }
        return null
    }

    /** Arrête le mode sans rien choisir. */
    fun cancel() {
        swallowUntilRelease = false
        finish()
    }

    private fun finish() {
        if (ended) return
        ended = true
        onEnd()
        // On garde le dispatcher un instant pour avaler le relâchement du clic, puis on le retire.
        if (!swallowUntilRelease) Disposer.dispose(this)
        else javax.swing.Timer(1500) { Disposer.dispose(this) }.apply { isRepeats = false; start() }
    }

    override fun dispose() { ended = true }

    companion object {
        /** Démarre le mode. [parent] : arrête tout si la fenêtre Code Colors est fermée. */
        fun start(
            parent: Disposable,
            onPicked: (List<ColorEntry>, RelativePoint) -> Unit,
            onEnd: () -> Unit,
        ): ElementPicker {
            val picker = ElementPicker(onPicked, onEnd)
            Disposer.register(parent, picker)
            IdeEventQueue.getInstance().addDispatcher(picker.dispatcher, picker)
            return picker
        }
    }
}
