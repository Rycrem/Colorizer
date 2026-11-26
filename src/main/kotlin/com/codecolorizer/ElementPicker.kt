package com.codecolorizer

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.awt.RelativePoint
import java.awt.Point
import java.awt.event.KeyEvent
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
            if (!it.atEnd()) {
                // API stable : on demande au coloriseur du langage les couleurs du jeton
                val file = FileDocumentManager.getInstance().getFile(doc)
                val syntax = file?.let { f -> SyntaxHighlighterFactory.getSyntaxHighlighter(f.fileType, project, f) }
                syntax?.getTokenHighlights(it.tokenType)?.reversed()?.forEach { k -> keys += k }
            }
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
            if (line == editor.caretModel.logicalPosition.line) keys += EditorColors.LINE_NUMBER_ON_CARET_ROW_COLOR
            keys += EditorColors.LINE_NUMBERS_COLOR
        }
        keys += EditorColors.GUTTER_BACKGROUND
        return keys.map { ColorEntries.describe(it, catalog) }.distinct()
    }
}

/**
 * Mode "pipette" : le prochain clic dans un éditeur (texte ou marge) ne déplace pas le curseur,
 * il désigne l'élément dont on veut changer la couleur. Échap annule.
 *
 * Les clics sont captés via l'API officielle des éditeurs (EditorMouseListener), qui reçoit les clics
 * du texte comme de la marge et permet de les "consommer" pour que l'éditeur ne bouge pas.
 */
class ElementPicker private constructor(
    private val onPicked: (List<ColorEntry>, RelativePoint) -> Unit,
    private val onEnd: () -> Unit,
) : Disposable {

    /** Vrai entre le clic choisi et son relâchement : on avale la suite du clic. */
    private var swallowing = false
    private var ended = false

    private val mouseListener = object : EditorMouseListener {
        override fun mousePressed(event: EditorMouseEvent) {
            if (ended) return
            val editor = event.editor
            val e = event.mouseEvent
            val src = e.component ?: return
            val p = SwingUtilities.convertPoint(src, e.point, editor.contentComponent)
            val catalog = ColorEntries.all()
            val entries = if (event.area == EditorMouseEventArea.EDITING_AREA)
                ElementFinder.entriesInText(editor.project, editor, p, catalog)
            else
                ElementFinder.entriesInGutter(editor, p.y, catalog)
            event.consume()
            e.consume()
            swallowing = true
            val where = RelativePoint(src, e.point)
            finish()
            ApplicationManager.getApplication().invokeLater { onPicked(entries, where) }
        }

        override fun mouseReleased(event: EditorMouseEvent) {
            if (swallowing) { event.consume(); event.mouseEvent.consume(); swallowing = false }
        }

        override fun mouseClicked(event: EditorMouseEvent) {
            if (pendingClick) {
                pendingClick = false
                event.consume(); event.mouseEvent.consume()
            }
        }
    }

    /** Le MOUSE_CLICKED qui suit le relâchement du clic choisi, à avaler aussi. */
    private var pendingClick = false

    /** Échap annule (écouteur AWT standard, retiré à la fin du mode). */
    private val escListener = java.awt.event.AWTEventListener { e ->
        if (!ended && e is KeyEvent && e.id == KeyEvent.KEY_PRESSED && e.keyCode == KeyEvent.VK_ESCAPE) cancel()
    }

    /** Arrête le mode sans rien choisir. */
    fun cancel() {
        swallowing = false
        finish()
    }

    private fun finish() {
        if (ended) return
        ended = true
        pendingClick = swallowing
        onEnd()
        // On garde l'écoute un instant pour avaler le relâchement du clic, puis on la retire.
        if (!swallowing) Disposer.dispose(this)
        else javax.swing.Timer(1000) { Disposer.dispose(this) }.apply { isRepeats = false; start() }
    }

    override fun dispose() { ended = true }

    companion object {
        /** Démarre le mode. [parent] : arrête tout si la fenêtre Colorizer est fermée. */
        fun start(
            parent: Disposable,
            onPicked: (List<ColorEntry>, RelativePoint) -> Unit,
            onEnd: () -> Unit,
        ): ElementPicker {
            val picker = ElementPicker(onPicked, onEnd)
            Disposer.register(parent, picker)
            EditorFactory.getInstance().eventMulticaster.addEditorMouseListener(picker.mouseListener, picker)
            val toolkit = java.awt.Toolkit.getDefaultToolkit()
            toolkit.addAWTEventListener(picker.escListener, java.awt.AWTEvent.KEY_EVENT_MASK)
            Disposer.register(picker) { toolkit.removeAWTEventListener(picker.escListener) }
            return picker
        }
    }
}
