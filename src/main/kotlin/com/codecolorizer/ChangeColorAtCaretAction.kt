package com.codecolorizer

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.ColorIcon
import javax.swing.JList

/**
 * Clic droit → "Changer la couleur de cet élément".
 * Trouve toutes les couleurs qui s'appliquent au code sous le curseur
 * (coloration syntaxique + coloration sémantique), puis ouvre la fenêtre Code Colors dessus.
 */
class ChangeColorAtCaretAction : AnAction(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val catalog = ColorEntries.all()
        val entries = ElementFinder.textKeysAtCaret(project, editor).map { ColorEntries.describe(it, catalog) }

        when (entries.size) {
            0 -> Messages.showInfoMessage(project, "Aucune couleur trouvée à cet endroit.", "Code Colors")
            1 -> CodeColorsToolWindowFactory.openAndSelect(project, entries[0])
            else -> EntryPopup.create(entries) { CodeColorsToolWindowFactory.openAndSelect(project, it) }
                .showInBestPositionFor(editor)
        }
    }
}

/** Petit menu "Quelle couleur modifier ?" avec une pastille de la couleur actuelle. */
object EntryPopup {
    fun create(entries: List<ColorEntry>, onChosen: (ColorEntry) -> Unit): JBPopup =
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(entries)
            .setTitle("Quelle couleur modifier ?")
            .setRenderer(Renderer())
            .setItemChosenCallback { onChosen(it) }
            .createPopup()

    private class Renderer : ColoredListCellRenderer<ColorEntry>() {
        override fun customizeCellRenderer(
            list: JList<out ColorEntry>, value: ColorEntry?, index: Int, selected: Boolean, hasFocus: Boolean
        ) {
            if (value == null) return
            val service = ColorOverridesService.getInstance()
            icon = ColorIcon(12, service.swatch(value) ?: service.scheme.defaultForeground)
            append(value.name)
            append("   ${value.group}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }
}
