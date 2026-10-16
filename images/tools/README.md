# Page images

Everything the project page (`curseforge_page.md`) shows lives in `images/`, and everything that
generates it lives here, in `images/tools/`.

| Folder | Holds |
|---|---|
| `images/` | Animated banners, title ribbons, badges, language labels, the divider |
| `images/icons/` | The small icons shown next to the features |
| `images/screenshots/` | Pictures for the CurseForge gallery (uploaded by hand) |
| `images/preview/` | Pictures of the page as CurseForge will show it (not committed) |
| `images/tools/` | The scripts and the files below |

| File | Role |
|---|---|
| `page.toml` | The page itself: links, badges, sections, texts in every language |
| `theme.json` | The colors, the ornament and the shape shared by every image, taken from the brand kit in `Branding/` (`theme.py brand`) |
| `make_banners.py` | The animated banners, made from a clip filmed with the mod itself |
| `build_page.py` | Writes `curseforge_page.md` from `page.toml` |
| `titles.py`, `export_icons.py`, `shots.py` | Title ribbons, badges, language labels, divider, feature icons, gallery pictures |
| `check_page.py`, `preview.py` | Check the page and show it the way CurseForge will |
| `kit.py`, `iso.py`, `pixelfont.py`, `icons.py`, `theme.py`, `gifsheet.py`, `heat.py` | The drawing, animation and encoding library behind the others |

Requirements: Python 3.11 or newer with Pillow and numpy, and ffmpeg on the PATH for the banners.

## Rebuilding

```
python images/tools/export_icons.py images/tools/page.toml     # images/icons
python images/tools/titles.py images/tools/page.toml           # images/title-*.gif, lang-*.gif, divider.gif, badge-*.png
python images/tools/make_banners.py                            # images/*.gif (needs the footage)
python images/tools/build_page.py images/tools/page.toml       # curseforge_page.md
python images/tools/check_page.py curseforge_page.md
python images/tools/preview.py curseforge_page.md
```

To change a text, edit `page.toml` and run `build_page.py`. Then paste the new `curseforge_page.md`
into the description of the project on CurseForge.

## Why the page looks the way it does

CurseForge filters the HTML of a description: it removes `align="center"` and most style
properties, and shows headings as small plain text. It keeps `text-align`, `color`, `font-size`,
`font-weight`, `display`, `margin-left`, `margin-right` and the width of images. So the page is
centered by wrappers that carry `text-align`, section titles are images, and feature names are
colored with `span` tags. `preview.py` imitates that filter and CurseForge's dark stylesheet.

Every image weighs less than 2 MB, the limit of CurseForge for an uploaded image, and banners are
800 pixels wide, the width of its description column.

## Footage

The top banner shows the logo of `Branding/` (its reverse horizontal lockup) on a still of the clip, finished in
EffectCraft (`fx.branded_hero`); every banner gets a light grade and vignette (`kit.LOOK`).


The banners are made from a clip filmed with Just Replay itself: a Drop the Beam festival recorded
in a replay, edited and exported at 1080p, then cut into 1600 x 900 stills with ffmpeg
(`still_NNN.png` at second NNN, `hero_*.png` 3.4 s from 0:50 at 20 fps). The editor stills are
captures of the editor open on each tab over that replay, at GUI scale 2, taken with the
development `SceneRecorder` (`-PsceneReplay=... -PsceneEditor=tick|ADVANCED|tab,tab`). The frames
are not in the repository; `make_banners.py` reads them from `run-capture/screenshots/`.

## Credits

Author: vyrriox

---

# Images de la page (Version Française)

Tout ce que montre la page du projet (`curseforge_page.md`) se trouve dans `images/`, et tout ce
qui le génère se trouve ici, dans `images/tools/`.

| Dossier | Contenu |
|---|---|
| `images/` | Bannières animées, rubans de titre, badges, étiquettes de langue, séparateur |
| `images/icons/` | Les petites icônes affichées à côté des fonctionnalités |
| `images/screenshots/` | Les images pour la galerie CurseForge (à envoyer à la main) |
| `images/preview/` | Des images de la page telle que CurseForge l'affichera (non versionnées) |
| `images/tools/` | Les scripts et les fichiers ci-dessous |

| Fichier | Rôle |
|---|---|
| `page.toml` | La page elle-même : liens, badges, sections, textes dans chaque langue |
| `theme.json` | Les couleurs, l'ornement et la forme communs à toutes les images, repris du kit de marque de `Branding/` (`theme.py brand`) |
| `make_banners.py` | Les bannières animées, faites à partir d'un clip filmé avec le mod lui-même |
| `build_page.py` | Écrit `curseforge_page.md` à partir de `page.toml` |
| `titles.py`, `export_icons.py`, `shots.py` | Rubans de titre, badges, étiquettes de langue, séparateur, icônes, images de galerie |
| `check_page.py`, `preview.py` | Vérifient la page et la montrent comme CurseForge l'affichera |
| `kit.py`, `iso.py`, `pixelfont.py`, `icons.py`, `theme.py`, `gifsheet.py`, `heat.py` | La bibliothèque de dessin, d'animation et d'encodage utilisée par les autres |

Prérequis : Python 3.11 ou plus récent avec Pillow et numpy, et ffmpeg dans le PATH pour les bannières.

## Reconstruire

```
python images/tools/export_icons.py images/tools/page.toml     # images/icons
python images/tools/titles.py images/tools/page.toml           # images/title-*.gif, lang-*.gif, divider.gif, badge-*.png
python images/tools/make_banners.py                            # images/*.gif (demande les séquences)
python images/tools/build_page.py images/tools/page.toml       # curseforge_page.md
python images/tools/check_page.py curseforge_page.md
python images/tools/preview.py curseforge_page.md
```

Pour changer un texte, modifiez `page.toml` puis lancez `build_page.py`. Collez ensuite le nouveau
`curseforge_page.md` dans la description du projet sur CurseForge.

## Pourquoi la page est faite ainsi

CurseForge filtre le HTML d'une description : il retire `align="center"` et la plupart des
propriétés de style, et affiche les titres comme du petit texte ordinaire. Il conserve
`text-align`, `color`, `font-size`, `font-weight`, `display`, `margin-left`, `margin-right` et la
largeur des images. La page est donc centrée par des blocs qui portent `text-align`, les titres de
section sont des images, et les noms des fonctionnalités sont colorés avec des balises `span`.
`preview.py` imite ce filtre et la feuille de style sombre de CurseForge.

Chaque image pèse moins de 2 Mo, la limite de CurseForge pour une image envoyée, et les bannières
font 800 pixels de large, la largeur de la colonne de description.

## Séquences

La bannière du haut montre le logo de `Branding/` (sa version horizontale inversée) sur une image du clip,
finie dans EffectCraft (`fx.branded_hero`) ; chaque bannière reçoit un léger étalonnage et une vignette (`kit.LOOK`).


Les bannières viennent d'un clip filmé avec Just Replay lui-même : un festival Drop the Beam
enregistré dans un replay, monté et exporté en 1080p, puis découpé en images 1600 x 900 avec ffmpeg
(`still_NNN.png` à la seconde NNN, `hero_*.png` 3,4 s à partir de 0:50 à 20 images par seconde).
Les images de l'éditeur sont des captures de l'éditeur ouvert sur chaque onglet sur ce replay, en
échelle d'interface 2, prises avec le `SceneRecorder` de développement
(`-PsceneReplay=... -PsceneEditor=tick|ADVANCED|onglet,onglet`). Ces images ne sont pas dans le
dépôt ; `make_banners.py` les lit dans `run-capture/screenshots/`.

## Credits

Author: vyrriox
