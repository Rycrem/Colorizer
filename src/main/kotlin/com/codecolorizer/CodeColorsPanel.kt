package com.codecolorizer

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColorPanel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.DocumentAdapter
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.*
import javax.swing.event.DocumentEvent

class CodeColorsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val service = ColorOverridesService.getInstance()

    private var catalog: List<ColorEntry> = ColorEntries.all()
    private val extraEntries = mutableListOf<ColorEntry>()

    // --- Thèmes ---
    private val themeCombo = ComboBox<ThemeData>()
    private val applyThemeButton = JButton("Appliquer", AllIcons.Actions.Execute)
    private val importButton = JButton("Importer…", AllIcons.ToolbarDecorator.Import)
    private val exportButton = JButton("Exporter…", AllIcons.ToolbarDecorator.Export)
    private val deleteThemeButton = JButton("Supprimer", AllIcons.General.Remove)

    // --- Filtres ---
    private val allGroupsLabel = "Tous les langages"
    private val groupCombo = ComboBox<String>()
    private val search = SearchTextField(false)
    private val onlyModified = JBCheckBox("Modifiés seulement")
    private val schemeLabel = JBLabel()

    // --- Pipette : cliquer sur un élément de l'éditeur ---
    private val pickButton = JToggleButton("Cliquer sur un élément de l'IDE", AllIcons.General.Locate).apply {
        toolTipText = "Puis clique sur du code, un numéro de ligne, la marge… dans un éditeur pour modifier sa couleur"
    }
    private val pickHint = JBLabel("Clique sur un élément dans l'éditeur (Échap pour annuler)").apply {
        foreground = JBColor.BLUE
        isVisible = false
    }
    private var picker: ElementPicker? = null

    // --- Liste ---
    private val listModel = CollectionListModel<ColorEntry>()
    private val list = JBList(listModel)

    // --- Éditeur ---
    private val titleLabel = JBLabel("Sélectionne un élément dans la liste").apply {
        font = font.deriveFont(Font.BOLD)
    }
    private val fgCheck = JBCheckBox("Texte")
    private val fgColor = ColorPanel()
    private val bgCheck = JBCheckBox("Fond")
    private val bgColor = ColorPanel()
    private val effectCheck = JBCheckBox("Effet")
    private val effectColor = ColorPanel()
    private val effectCombo = ComboBox(
        arrayOf(
            EffectType.LINE_UNDERSCORE, EffectType.WAVE_UNDERSCORE, EffectType.BOLD_LINE_UNDERSCORE,
            EffectType.BOLD_DOTTED_LINE, EffectType.STRIKEOUT, EffectType.BOXED, EffectType.ROUNDED_BOX
        )
    )
    private val boldCheck = JBCheckBox("Gras")
    private val italicCheck = JBCheckBox("Italique")
    private val preview = JBLabel("  exemple = maFonction(42); // aperçu  ").apply {
        isOpaque = true
        border = JBUI.Borders.empty(6)
    }
    private val resetButton = JButton("Réinitialiser cet élément")
    private val resetAllButton = JButton("Tout réinitialiser")
    private val bgRow = row(bgCheck, bgColor)
    private val effectRow = row(effectCheck, effectColor, effectCombo)
    private val styleRow = row(boldCheck, italicCheck)

    private var current: ColorEntry? = null
    private var loading = false
    private val serviceListener: () -> Unit = { refilter(); load(current); list.repaint(); updateSchemeLabel() }

    init {
        border = JBUI.Borders.empty(4)

        // Filtres
        groupCombo.addActionListener { refilter() }
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refilter()
        })
        onlyModified.addActionListener { refilter() }
        // Thèmes
        themeCombo.renderer = SimpleListCellRenderer.create("") { t: ThemeData ->
            t.name + "  —  " + (if (t.dark) "sombre" else "clair") + (if (t.builtIn) "" else " · importé")
        }
        themeCombo.addActionListener { updateThemeButtons() }
        // Largeur minimale réduite : la liste s'adapte à la fenêtre au lieu de pousser les boutons hors champ
        themeCombo.setMinimumAndPreferredWidth(120)
        groupCombo.setMinimumAndPreferredWidth(120)
        applyThemeButton.toolTipText = "Appliquer le thème sélectionné"
        applyThemeButton.addActionListener { applySelectedTheme() }
        importButton.addActionListener { importTheme() }
        exportButton.addActionListener { exportTheme() }
        deleteThemeButton.addActionListener { deleteSelectedTheme() }
        reloadThemes(null)
        pickButton.addActionListener { if (pickButton.isSelected) startPicking() else picker?.cancel() }

        val filters = FormBuilder.createFormBuilder()
            .addComponent(JBLabel("Thème prêt :"))
            .addComponent(themeCombo)
            .addComponent(grid(applyThemeButton, importButton, exportButton, deleteThemeButton))
            .addSeparator()
            .addComponent(pickButton)
            .addComponent(pickHint)
            .addSeparator()
            .addComponent(schemeLabel)
            .addLabeledComponent("Langage :", groupCombo)
            .addComponent(search)
            .addComponent(onlyModified)
            .panel

        // Liste
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = EntryRenderer()
        list.addListSelectionListener { if (!it.valueIsAdjusting) load(list.selectedValue) }

        val top = JPanel(BorderLayout()).apply {
            add(filters, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
        }

        // Éditeur
        effectCombo.renderer = SimpleListCellRenderer.create("") { effectName(it) }
        listOf(fgCheck, bgCheck, effectCheck, boldCheck, italicCheck).forEach { it.addActionListener { save() } }
        listOf(fgColor, bgColor, effectColor).forEach { panel ->
            panel.addActionListener {
                // Choisir une couleur coche automatiquement la case correspondante
                if (!loading) when (panel) {
                    fgColor -> fgCheck.isSelected = true
                    bgColor -> bgCheck.isSelected = true
                    effectColor -> effectCheck.isSelected = true
                }
                save()
            }
        }
        effectCombo.addActionListener { if (!loading) effectCheck.isSelected = true; save() }
        resetButton.addActionListener { current?.let { service.reset(it); load(it) } }
        resetAllButton.addActionListener {
            val n = service.overrideCount()
            if (n == 0) return@addActionListener
            val ok = JOptionPane.showConfirmDialog(
                this, "Réinitialiser les $n élément(s) modifié(s) du thème « ${service.scheme.name} » ?",
                "Code Colors", JOptionPane.YES_NO_OPTION
            )
            if (ok == JOptionPane.YES_OPTION) { service.resetAll(); load(current) }
        }

        val editor = FormBuilder.createFormBuilder()
            .addComponent(titleLabel)
            .addComponent(row(fgCheck, fgColor))
            .addComponent(bgRow)
            .addComponent(effectRow)
            .addComponent(styleRow)
            .addComponent(preview)
            .addComponent(row(resetButton, resetAllButton))
            .panel
            .apply { border = JBUI.Borders.emptyTop(6) }

        val splitter = JBSplitter(true, 0.62f).apply {
            firstComponent = top
            secondComponent = JBScrollPane(editor).apply { border = JBUI.Borders.empty() }
        }
        add(splitter, BorderLayout.CENTER)

        service.addChangeListener(serviceListener)
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(EditorColorsManager.TOPIC, EditorColorsListener {
                ApplicationManager.getApplication().invokeLater { load(current); list.repaint(); updateSchemeLabel() }
            })

        reloadGroups()
        refilter()
        updateSchemeLabel()
        load(null)
    }

    /** Sélectionne (et affiche) cet élément dans la liste. */
    fun select(wanted: ColorEntry) {
        fun same(e: ColorEntry) =
            (wanted.key != null && e.key == wanted.key) || (wanted.colorKey != null && e.colorKey == wanted.colorKey)
        val entry = catalog.firstOrNull(::same)
            ?: extraEntries.firstOrNull(::same)
            ?: wanted.also { extraEntries += it; reloadGroups() }
        groupCombo.selectedItem = allGroupsLabel
        search.text = ""
        onlyModified.isSelected = false
        refilter()
        list.setSelectedValue(entry, true)
        load(entry)
    }

    // ------------------------------------------------------------------ pipette

    private fun startPicking() {
        picker?.cancel()
        pickButton.isSelected = true
        pickHint.isVisible = true
        picker = ElementPicker.start(
            parent = this,
            onPicked = { entries, where ->
                when (entries.size) {
                    0 -> {}
                    1 -> CodeColorsToolWindowFactory.openAndSelect(project, entries[0])
                    else -> EntryPopup.create(entries) { CodeColorsToolWindowFactory.openAndSelect(project, it) }
                        .show(where)
                }
            },
            onEnd = {
                picker = null
                pickButton.isSelected = false
                pickHint.isVisible = false
            }
        )
    }

    // ------------------------------------------------------------------

    private fun reloadThemes(selectName: String?) {
        val themes = ThemePresets.all + service.userThemes()
        themeCombo.model = DefaultComboBoxModel(themes.toTypedArray())
        themes.firstOrNull { it.name == selectName }?.let { themeCombo.selectedItem = it }
        updateThemeButtons()
    }

    private fun updateThemeButtons() {
        val t = themeCombo.selectedItem as? ThemeData
        applyThemeButton.isEnabled = t != null
        deleteThemeButton.isEnabled = t != null && !t.builtIn
    }

    private fun applySelectedTheme() {
        val t = themeCombo.selectedItem as? ThemeData ?: return
        val n = service.overrideCount()
        val msg = StringBuilder("Appliquer le thème « ${t.name} » au thème d'IDE « ${service.scheme.name} » ?")
        if (n > 0) msg.append("\n\nTes $n modification(s) actuelles seront remplacées.\nAstuce : clique d'abord sur « Exporter… » pour les garder.")
        if (t.dark == JBColor.isBright()) {
            val ideKind = if (t.dark) "sombre (Darcula, Dark…)" else "clair (Light…)"
            msg.append("\n\nCe thème est ${if (t.dark) "sombre" else "clair"} : il rend mieux avec un thème d'IDE $ideKind.")
        }
        val ok = Messages.showOkCancelDialog(project, msg.toString(), "Code Colors", "Appliquer", "Annuler", Messages.getQuestionIcon())
        if (ok != Messages.OK) return
        service.applyTheme(t, replace = true)
    }

    private fun importTheme() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
            .withTitle("Importer un thème Code Colorizer")
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return
        val theme = try {
            ThemeJson.read(String(file.contentsToByteArray(), Charsets.UTF_8))
        } catch (ex: Exception) {
            Messages.showErrorDialog(project, "Impossible de lire ce thème :\n${ex.message}", "Code Colors")
            return
        }
        if (ThemePresets.all.any { it.name == theme.name }) theme.name = theme.name + " (importé)"
        service.saveUserTheme(theme)
        reloadThemes(theme.name)

        val count = theme.attributes.size + theme.editorColors.size
        val choice = Messages.showYesNoCancelDialog(
            project,
            "Thème « ${theme.name} » importé ($count couleurs). Il est maintenant dans la liste des thèmes.\n\nL'appliquer maintenant ?",
            "Code Colors", "Remplacer mes couleurs", "Fusionner avec mes couleurs", "Plus tard",
            Messages.getQuestionIcon()
        )
        when (choice) {
            Messages.YES -> service.applyTheme(theme, replace = true)
            Messages.NO -> service.applyTheme(theme, replace = false)
        }
    }

    private fun exportTheme() {
        val choice = Messages.showDialog(
            project,
            "Que veux-tu exporter ?\n\n• Toutes les couleurs : un thème complet, identique à ce que tu vois.\n• Mes modifications : seulement ce que tu as changé avec Code Colors.",
            "Exporter un thème",
            arrayOf("Toutes les couleurs", "Mes modifications", "Annuler"), 0, Messages.getQuestionIcon()
        )
        if (choice != 0 && choice != 1) return
        val everything = choice == 0
        if (!everything && service.overrideCount() == 0) {
            Messages.showInfoMessage(project, "Tu n'as encore rien modifié dans le thème « ${service.scheme.name} ».", "Code Colors")
            return
        }
        val name = Messages.showInputDialog(
            project, "Nom du thème :", "Exporter un thème", null, "Mon thème", null
        )?.trim()?.takeIf { it.isNotEmpty() } ?: return

        val theme = service.exportTheme(name, everything)
        val fileName = name.replace(Regex("[\\\\/:*?\"<>|]+"), "-") + ".json"
        val wrapper = FileChooserFactory.getInstance()
            .createSaveFileDialog(FileSaverDescriptor("Exporter le thème", "Fichier JSON du thème", "json"), project)
            .save(null as VirtualFile?, fileName) ?: return
        try {
            wrapper.file.writeText(ThemeJson.write(theme), Charsets.UTF_8)
            Messages.showInfoMessage(project, "Thème exporté (${theme.attributes.size + theme.editorColors.size} couleurs) :\n${wrapper.file.path}", "Code Colors")
        } catch (ex: Exception) {
            Messages.showErrorDialog(project, "Impossible d'écrire le fichier :\n${ex.message}", "Code Colors")
        }
    }

    private fun deleteSelectedTheme() {
        val t = themeCombo.selectedItem as? ThemeData ?: return
        if (t.builtIn) return
        val ok = Messages.showYesNoDialog(project, "Retirer « ${t.name} » de la liste des thèmes ?\n(Les couleurs déjà appliquées ne changent pas.)", "Code Colors", Messages.getQuestionIcon())
        if (ok != Messages.YES) return
        service.deleteUserTheme(t.name)
        reloadThemes(null)
    }

    private fun reloadGroups() {
        val selected = groupCombo.selectedItem
        val groups = (catalog + extraEntries).map { it.group }.distinct()
        groupCombo.model = DefaultComboBoxModel((listOf(allGroupsLabel) + groups).toTypedArray())
        groupCombo.selectedItem = selected ?: allGroupsLabel
    }

    private fun refilter() {
        val group = groupCombo.selectedItem as? String ?: allGroupsLabel
        val query = search.text.trim().lowercase()
        val items = (catalog + extraEntries).filter { e ->
            (group == allGroupsLabel || e.group == group) &&
                (query.isEmpty() || e.label.lowercase().contains(query) || e.externalName.lowercase().contains(query)) &&
                (!onlyModified.isSelected || service.hasOverride(e))
        }
        val keep = list.selectedValue
        listModel.replaceAll(items)
        if (keep != null && keep in items) list.setSelectedValue(keep, true)
    }

    private fun updateSchemeLabel() {
        val n = service.overrideCount()
        schemeLabel.text = "Thème : ${service.scheme.name}" + if (n > 0) "  ($n modifié${if (n > 1) "s" else ""})" else ""
    }

    private fun load(entry: ColorEntry?) {
        loading = true
        try {
            current = entry
            val enabled = entry != null
            listOf(fgCheck, fgColor, bgCheck, bgColor, effectCheck, effectColor, effectCombo, boldCheck, italicCheck, resetButton)
                .forEach { it.isEnabled = enabled }
            val editorColor = entry?.colorKey
            fgCheck.text = if (editorColor != null) "Couleur" else "Texte"
            listOf(bgRow, effectRow, styleRow).forEach { it.isVisible = editorColor == null }
            if (entry == null) {
                titleLabel.text = "Sélectionne un élément dans la liste"
                updatePreview(null)
                return
            }
            titleLabel.text = entry.label
            if (editorColor != null) {
                // Couleur de l'éditeur (numéros de ligne, marge...) : une seule couleur
                val c = service.currentColor(editorColor)
                fgCheck.isSelected = true
                fgCheck.isEnabled = false
                fgColor.selectedColor = c ?: if (entry.isBackground) service.scheme.defaultBackground else service.scheme.defaultForeground
                resetButton.isEnabled = service.hasOverride(entry)
                updateEditorColorPreview(entry, fgColor.selectedColor)
                return
            }
            val key = entry.key ?: return
            val attrs = service.currentAttributes(key)
            val fg = attrs?.foregroundColor
            val bg = attrs?.backgroundColor
            val ef = attrs?.effectColor
            fgCheck.isSelected = fg != null; fgColor.selectedColor = fg ?: service.scheme.defaultForeground
            bgCheck.isSelected = bg != null; bgColor.selectedColor = bg ?: service.scheme.defaultBackground
            effectCheck.isSelected = ef != null && attrs?.effectType != null
            effectColor.selectedColor = ef ?: service.scheme.defaultForeground
            effectCombo.selectedItem = attrs?.effectType ?: EffectType.LINE_UNDERSCORE
            boldCheck.isSelected = attrs != null && attrs.fontType and Font.BOLD != 0
            italicCheck.isSelected = attrs != null && attrs.fontType and Font.ITALIC != 0
            resetButton.isEnabled = service.hasOverride(key)
            updatePreview(attrs)
        } finally {
            loading = false
        }
    }

    private fun save() {
        if (loading) return
        val entry = current ?: return
        entry.colorKey?.let { ck ->
            val c = fgColor.selectedColor ?: return
            service.setColorOverride(ck, c)
            resetButton.isEnabled = true
            updateEditorColorPreview(entry, c)
            list.repaint()
            updateSchemeLabel()
            return
        }
        val key = entry.key ?: return
        val o = ColorOverride().apply {
            foreground = if (fgCheck.isSelected) fgColor.selectedColor.toHex() else null
            background = if (bgCheck.isSelected) bgColor.selectedColor.toHex() else null
            if (effectCheck.isSelected) {
                effectColor = this@CodeColorsPanel.effectColor.selectedColor.toHex()
                effectType = (effectCombo.selectedItem as? EffectType)?.name
            }
            bold = boldCheck.isSelected
            italic = italicCheck.isSelected
        }
        service.setOverride(key, o)
        resetButton.isEnabled = true
        updatePreview(o.toTextAttributes())
        list.repaint()
        updateSchemeLabel()
    }

    private fun updatePreview(attrs: com.intellij.openapi.editor.markup.TextAttributes?) {
        val scheme = service.scheme
        preview.background = attrs?.backgroundColor ?: scheme.defaultBackground
        preview.foreground = attrs?.foregroundColor ?: scheme.defaultForeground
        var style = Font.PLAIN
        if (attrs != null) style = attrs.fontType
        preview.font = scheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN).deriveFont(style)
    }

    private fun updateEditorColorPreview(entry: ColorEntry, c: java.awt.Color?) {
        val scheme = service.scheme
        preview.font = scheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN)
        if (entry.isBackground) {
            preview.background = c ?: scheme.defaultBackground
            preview.foreground = scheme.defaultForeground
        } else {
            preview.background = scheme.defaultBackground
            preview.foreground = c ?: scheme.defaultForeground
        }
    }

    /** Boutons sur 2 colonnes : ils restent visibles même si la fenêtre est étroite. */
    private fun grid(vararg c: JComponent) = JPanel(java.awt.GridLayout(0, 2, 4, 4)).apply { c.forEach { add(it) } }

    private fun row(vararg c: JComponent) = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply { c.forEach { add(it) } }

    private fun effectName(t: EffectType?): String = when (t) {
        EffectType.LINE_UNDERSCORE -> "Souligné"
        EffectType.WAVE_UNDERSCORE -> "Souligné ondulé"
        EffectType.BOLD_LINE_UNDERSCORE -> "Souligné épais"
        EffectType.BOLD_DOTTED_LINE -> "Pointillés"
        EffectType.STRIKEOUT -> "Barré"
        EffectType.BOXED -> "Encadré"
        EffectType.ROUNDED_BOX -> "Encadré arrondi"
        else -> t?.name ?: ""
    }

    override fun dispose() {
        picker?.cancel()
        service.removeChangeListener(serviceListener)
    }

    /** Affiche chaque élément avec sa couleur actuelle + une pastille. */
    private inner class EntryRenderer : ColoredListCellRenderer<ColorEntry>() {
        override fun customizeCellRenderer(
            list: JList<out ColorEntry>, value: ColorEntry?, index: Int, selected: Boolean, hasFocus: Boolean
        ) {
            if (value == null) return
            val attrs = value.key?.let { service.currentAttributes(it) }
            val swatch = service.swatch(value)
            icon = ColorIcon(12, swatch ?: service.scheme.defaultForeground)
            val style = if (attrs != null && !selected) SimpleTextAttributes.fromTextAttributes(attrs)
            else SimpleTextAttributes.REGULAR_ATTRIBUTES
            append(value.name, style)
            append("   ${value.group}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            if (service.hasOverride(value)) append("  ●", SimpleTextAttributes.LINK_ATTRIBUTES)
        }
    }
}
