# 🎨 Code Colorizer

> Plugin pour les IDE JetBrains qui permet de changer la couleur de **n'importe quel élément du code**
> en quelques clics : mots-clés, chaînes, commentaires, méthodes, champs, numéros de ligne…

![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20Platform-2024.3%2B-000000?logo=intellijidea)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white)
![JDK](https://img.shields.io/badge/JDK-21-ED8B00?logo=openjdk&logoColor=white)

Fonctionne dans IntelliJ IDEA (Community et Ultimate), WebStorm, PyCharm, Rider, GoLand, PhpStorm…
et pour **tous les langages installés**.

---

## ✨ Fonctionnalités

### 🖱️ Cliquer sur un élément pour le recolorer
Dans la fenêtre **Code Colors**, active **« Cliquer sur un élément de l'IDE »**, puis clique n'importe où
dans l'éditeur : un mot du code, un numéro de ligne, la marge, la ligne du curseur, la sélection…
L'élément s'ouvre aussitôt dans la liste, prêt à être modifié.
Si plusieurs couleurs se superposent à cet endroit, un petit menu te laisse choisir laquelle changer.
Ce clic ne déplace pas le curseur, et `Échap` annule.

### 📋 Fenêtre « Code Colors »
- Tous les éléments colorables, **filtrables par langage** et par recherche.
- Pour chaque élément : couleur du **texte**, **fond**, **effet** (souligné, ondulé, barré, encadré…),
  **gras**, **italique**.
- Les **couleurs de l'éditeur** sont aussi modifiables : numéros de ligne, fond de la marge,
  ligne du curseur, sélection, guides d'indentation…
- Aperçu et application **en direct**.
- Filtre **« Modifiés seulement »**, plus les boutons **Réinitialiser cet élément** et **Tout réinitialiser**.

### 🔍 Clic droit → « Changer la couleur de cet élément »
Raccourci `Ctrl+Alt+Shift+C`. Détecte ce qui colore le code sous le curseur (coloration syntaxique
et sémantique) et ouvre directement l'élément.

### 🎭 7 thèmes prêts à l'emploi
| Sombres | Clairs |
|---------|--------|
| Minuit néon · Océan profond · Forêt · Braise · Contraste élevé | Papier · Pastel doux |

Ces thèmes ne changent **que les couleurs du code** : le fond de l'éditeur, la marge, la ligne du
curseur, la sélection et les numéros de ligne restent ceux de ton thème d'IDE.

### 💾 Import / export JSON
- **Exporter** toutes les couleurs actuelles (un thème complet) ou seulement tes modifications.
- **Importer** un thème `.json` : il est ajouté à la liste, et tu peux remplacer tes couleurs
  ou les fusionner avec celles du thème.

### 🔁 Sauvegarde par thème d'IDE
Tes réglages sont enregistrés **pour chaque thème d'IDE** (Darcula, Light…). Ils sont réappliqués
au démarrage et à chaque changement de thème.

---

## 🚀 Installation

### Prérequis
| Outil | Version | Remarque |
|-------|---------|----------|
| IDE JetBrains | **2024.3+** | IntelliJ IDEA, WebStorm, PyCharm, Rider… |
| JDK | **21** | Nécessaire seulement pour construire le plugin. |
| Gradle | — | Inutile de l'installer : le wrapper `gradlew` le télécharge. |

Pour installer le JDK 21 : depuis IntelliJ (*File › Project Structure › SDKs › + › Download JDK…*),
ou depuis [adoptium.net](https://adoptium.net). Vérifie ensuite avec `java -version`. Si ce n'est pas
la version 21 qui s'affiche, définis `JAVA_HOME` :

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.x.x-hotspot"   # Windows (PowerShell)
```
```bash
export JAVA_HOME=/chemin/vers/jdk-21                                       # macOS / Linux
```

### 1. Construire le plugin
```powershell
git clone <url-du-dépôt>
cd code-colorizer
.\gradlew.bat buildPlugin        # macOS / Linux : ./gradlew buildPlugin
```
Le premier build télécharge Gradle et IntelliJ 2024.3 (environ 1 Go) : il prend quelques minutes.
Le plugin est généré ici : `build/distributions/code-colorizer-1.0.0.zip`.

### 2. L'installer dans l'IDE
1. **Settings** (`Ctrl+Alt+S`) › **Plugins**.
2. **⚙** › **Install Plugin from Disk…**
3. Choisis le **zip** (sans le décompresser) → **Restart IDE**.

La fenêtre **Code Colors** apparaît alors dans la barre latérale droite.

### Mettre à jour / désinstaller
- **Mise à jour** : reconstruis le plugin, puis réinstalle le nouveau zip de la même façon.
  Tes couleurs et tes thèmes importés sont conservés : ils sont stockés dans `codeColorizer.xml`,
  dans la configuration de l'IDE.
- **Désinstallation** : clique d'abord sur **Tout réinitialiser** dans Code Colors pour retrouver
  tes couleurs d'origine, puis *Settings › Plugins › Code Colorizer › Uninstall*.

### Problèmes fréquents
| Symptôme | Solution |
|----------|----------|
| `Unsupported class file major version` / `requires Java 21` | Le build n'utilise pas le JDK 21 : définis `JAVA_HOME`. |
| `Could not resolve …` / délai dépassé | Problème de réseau ou de proxy : vérifie l'accès à `services.gradle.org` et `cache-redirector.jetbrains.com`. |
| *Plugin is incompatible* | Ton IDE est antérieur à la version 2024.3 : mets-le à jour. |
| `gradlew: Permission denied` | `chmod +x gradlew` (macOS / Linux) |
| Build incohérent | Supprime `build/` et `.gradle/` (ils seront régénérés), puis relance le build. |

---

## 🧭 Utilisation

1. Ouvre la fenêtre **Code Colors** (barre latérale droite).
2. Choisis un élément, au choix :
   - clique sur **« Cliquer sur un élément de l'IDE »**, puis sur le code ;
   - fais un clic droit sur le code → **Changer la couleur de cet élément** ;
   - cherche-le dans la liste (filtre par langage ou recherche).
3. Modifie sa couleur, son style ou son effet : le changement est appliqué immédiatement.
4. Tu peux aussi partir d'un thème prêt à l'emploi : choisis-le en haut de la fenêtre → **Appliquer**.

---

## 🧩 Format d'un thème (`.json`)

```json
{
  "name": "Mon thème",
  "dark": true,
  "editor": {
    "CARET_COLOR": "#FF4FA3",
    "LINE_NUMBERS_COLOR": "#5A5A7A"
  },
  "attributes": {
    "TEXT":            { "foreground": "#E4E4F4" },
    "DEFAULT_KEYWORD": { "foreground": "#FF4FA3", "bold": true },
    "DEFAULT_STRING":  { "foreground": "#7CFFB2" },
    "JAVA_KEYWORD":    { "foreground": "#FF0000", "effectType": "WAVE_UNDERSCORE", "effectColor": "#FF0000" }
  }
}
```

| Champ | Rôle |
|-------|------|
| `name`, `dark` | Nom du thème, et s'il est fait pour un thème d'IDE sombre ou clair. |
| `editor` | Couleurs de l'éditeur (`ColorKey`) : curseur, ligne du curseur, sélection, marge, numéros de ligne… |
| `attributes` | Couleurs du code (`TextAttributesKey`) : `foreground`, `background`, `effectType`, `effectColor`, `bold`, `italic`. |

- Les clés `DEFAULT_*` s'appliquent à **tous les langages** ; une clé précise (ex. `JAVA_KEYWORD`)
  ne vise qu'un seul langage. Le nom exact d'une clé s'affiche quand tu la cherches dans Code Colors.
- Une couleur absente du fichier garde la valeur du thème actuel.
- Exemples complets : [`src/main/resources/presets/`](src/main/resources/presets/).

---

## 🗂️ Structure du projet

```
src/main/
├── kotlin/com/codecolorizer/
│   ├── CodeColorsPanel.kt              # Fenêtre « Code Colors » (liste, éditeur, thèmes, pipette)
│   ├── CodeColorsToolWindowFactory.kt  # Déclaration de la fenêtre
│   ├── ColorEntries.kt                 # Catalogue des éléments colorables
│   ├── ColorOverridesService.kt        # Stockage et application des couleurs (par thème d'IDE)
│   ├── ElementPicker.kt                # Détection de l'élément cliqué + mode pipette
│   ├── ChangeColorAtCaretAction.kt     # Action du clic droit
│   ├── ThemeData.kt                    # Thèmes : lecture / écriture JSON, thèmes prêts à l'emploi
│   └── Lifecycle.kt                    # Réapplication au démarrage et au changement de thème
└── resources/
    ├── META-INF/plugin.xml
    └── presets/*.json                  # Thèmes prêts à l'emploi
```

---

## 🛠️ Développement

```powershell
.\gradlew.bat runIde        # lance un IntelliJ de test avec le plugin
.\gradlew.bat buildPlugin   # génère le zip dans build/distributions/
```

Pour ajouter un thème prêt à l'emploi : crée un fichier `.json` dans `src/main/resources/presets/`,
puis ajoute son nom à la liste `FILES` de `ThemePresets` (dans `ThemeData.kt`).

Les contributions sont bienvenues : ouvre une *issue* ou une *pull request*.
