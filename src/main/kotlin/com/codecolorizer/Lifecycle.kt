package com.codecolorizer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.util.concurrent.atomic.AtomicBoolean

/** Ré-applique les couleurs enregistrées à l'ouverture du premier projet. */
class ApplyOverridesOnStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (applied.compareAndSet(false, true)) {
            ApplicationManager.getApplication().invokeLater {
                ColorOverridesService.getInstance().applyAll()
            }
        }
    }

    companion object {
        private val applied = AtomicBoolean(false)
    }
}

/** Quand l'utilisateur change de thème, on applique les personnalisations de ce thème. */
class SchemeSwitchListener : EditorColorsListener {
    override fun globalSchemeChange(scheme: EditorColorsScheme?) {
        if (scheme == null) return
        ApplicationManager.getApplication().invokeLater {
            ColorOverridesService.getInstance().applyAll(scheme)
        }
    }
}
