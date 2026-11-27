# Markers

Just Replay 1.2.0 (mod id `justreplay`), author / auteur : vyrriox.

- [English](#english)
- [Français](#français)

---

## English

A marker pins a note to a moment, a stretch of time, a spot of the world or an entity of a replay. It says what
happens where, and what to do there, for whoever edits the replay next: you, a friend, or a tool that drives
the editor.

### Placing markers

Open the editor (**G**), then the **Markers** tab (Simple and Advanced modes):

- **Moment**: a marker at the play head, after asking for its name. The flag button of the transport bar and
  the **M** key do the same.
- **Stretch**: five seconds from the play head; drag its ends on the timeline.
- **Spot**: the tool follows the crosshair, a click pins the marker on the block under it, at the play head.
- **Entity**: the same on the entity under the crosshair: a recorded player or mob, or an actor.

Markers dropped with **F6** while recording arrive as moments the first time the replay opens.

### Editing

Select a marker in the list or on the timeline, then rename it, write its note (`\n` starts a new line), move
it to the play head (a stretch keeps its length), set its start or its end at the play head, pin it on a spot
(the crosshair or the camera position), an entity, an actor or the recording player, unpin it, pick its
colour, duplicate it or delete it (the Delete key works on the tab). On the timeline, drag a marker to move it
and drag the ends of a stretch to resize it. A stretch that would start before the replay, end past it, or end
before its start is refused, and the marker stays as it was.

### In the world

A spot shows as a post topped with a diamond, an entity gets a box in the marker's colour, both with the
marker's name; the selected marker shows its note too. Markers away from the play head are dimmed. They are
drawn while you edit only: never in exported videos, nor while previewing the cameras or with F1. **Show
markers in the world** turns them off.

### In project.json

Markers are saved in the `markers` list of the replay's `project.json`, sorted by tick. Projects saved before
these fields existed open as before: their markers become moments, with an id made from their place in the
file until the next save. Each marker holds:

| Field | Meaning |
|---|---|
| `id` | stable id, kept by every edit |
| `name`, `note` | name (128 characters at most) and free note (2000 at most) |
| `type` | `INSTANT`, `RANGE`, `POINT` or `ENTITY` |
| `tick`, `end` | first and last tick, both included, 20 ticks a second; `end` is -1 for a single moment |
| `x`, `y`, `z`, `dimension` | spot of a `POINT`; `dimension` like `minecraft:overworld`, empty for any |
| `target`, `targetName` | entity of an `ENTITY`: a UUID, `recorder`, or `actor:<id>` for a project actor |
| `color` | RGB colour, as a number |

A `POINT` or an `ENTITY` may cover a stretch too (an `end` after its `tick`). `showMarkers` (true by default)
switches the drawing in the world.

## Français

Un repère attache une note à un moment, à une plage de temps, à un point du monde ou à une entité d'un replay.
Il dit ce qui se passe où, et ce qu'il faut y faire, pour la personne qui monte le replay ensuite : toi, un
ami, ou un outil qui pilote l'éditeur.

### Poser des repères

Ouvre l'éditeur (**G**), puis l'onglet **Markers** (modes simple et avancé) :

- **Moment** : un repère à la tête de lecture, après avoir demandé son nom. Le bouton drapeau de la barre de
  lecture et la touche **M** font pareil.
- **Stretch** : cinq secondes à partir de la tête de lecture ; ses bords se tirent sur la timeline.
- **Spot** : l'outil suit le viseur, un clic pose le repère sur le bloc visé, à la tête de lecture.
- **Entity** : pareil sur l'entité visée : un joueur ou un mob enregistré, ou un acteur.

Les repères posés avec **F6** pendant un enregistrement arrivent comme des moments à la première ouverture du
replay.

### Modifier

Choisis un repère dans la liste ou sur la timeline, puis renomme-le, écris sa note (`\n` passe à la ligne),
amène-le à la tête de lecture (une plage garde sa longueur), mets son début ou sa fin à la tête de lecture,
épingle-le sur un point (le viseur ou la position de la caméra), une entité, un acteur ou le joueur qui
enregistrait, détache-le, choisis sa couleur, duplique-le ou supprime-le (la touche Suppr marche sur
l'onglet). Sur la timeline, tire un repère pour le déplacer et tire les bords d'une plage pour la changer. Une
plage qui commencerait avant le replay, finirait après lui ou finirait avant son début est refusée, et le
repère reste comme il était.

### Dans le monde

Un point s'affiche comme un poteau surmonté d'un losange, une entité reçoit une boîte de la couleur du repère,
avec le nom du repère ; le repère choisi montre aussi sa note. Les repères loin de la tête de lecture sont
atténués. Ils ne sont dessinés que pendant le montage : jamais dans les vidéos exportées, ni pendant l'aperçu
des caméras ou avec F1. **Show markers in the world** les masque.

### Dans project.json

Les repères sont sauvés dans la liste `markers` du `project.json` du replay, triés par tick. Les projets
sauvés avant ces champs s'ouvrent comme avant : leurs repères deviennent des moments, avec un identifiant
tiré de leur place dans le fichier jusqu'à la prochaine sauvegarde. Chaque repère contient :

| Champ | Sens |
|---|---|
| `id` | identifiant stable, gardé par toutes les modifications |
| `name`, `note` | nom (128 caractères au plus) et note libre (2000 au plus) |
| `type` | `INSTANT`, `RANGE`, `POINT` ou `ENTITY` |
| `tick`, `end` | premier et dernier tick, inclus, 20 ticks par seconde ; `end` vaut -1 pour un simple moment |
| `x`, `y`, `z`, `dimension` | point d'un `POINT` ; `dimension` comme `minecraft:overworld`, vide pour toutes |
| `target`, `targetName` | entité d'un `ENTITY` : un UUID, `recorder`, ou `actor:<id>` pour un acteur du projet |
| `color` | couleur RVB, en nombre |

Un `POINT` ou un `ENTITY` peut aussi couvrir une plage (un `end` après son `tick`). `showMarkers` (vrai par
défaut) règle le dessin dans le monde.
