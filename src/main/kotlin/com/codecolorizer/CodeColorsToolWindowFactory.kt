package com.codecolorizer

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory

class CodeColorsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = CodeColorsPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        const val ID = "Code Colors"

        /** Ouvre la fenêtre et sélectionne l'élément voulu. */
        fun openAndSelect(project: Project, entry: ColorEntry) {
            val tw = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
            tw.activate {
                val panel = tw.contentManager.contents.firstOrNull()?.component as? CodeColorsPanel
                panel?.select(entry)
            }
        }
    }
}
