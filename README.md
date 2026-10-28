<p align="center"><img src="Branding/source/previous/logo.png" alt="Just Replay" width="640"></p>

# Just Replay

Record, replay and edit your Minecraft sessions, with a cinematic editor and video export.
NeoForge 1.21.1, client side. Author: vyrriox. License: Apache-2.0.

## English

### Features

- **Recording**: every packet the client receives is saved, custom mod payloads included, so modded
  blocks, entities and effects come back in the replay. Start and stop with **F8** or from the pause menu,
  or enable auto recording in the config. The recording player is saved too (position, look, pose,
  equipment, swings).
- **Playback**: free spectator camera, play and pause, speed from x0.1 to x8, seek forward and backward,
  jump to the recording player's point of view. Pause and speed go through the vanilla tick rate, so every
  entity and modded renderer follows the replay clock.
- **Editor** (key **G**): timeline with markers, camera keyframes and actor keyframes, drag keyframes to
  retime them, smooth Catmull-Rom camera path with roll and field of view.
- **Simple and Advanced modes**: the editor opens in Simple mode, with big buttons and plain words for a
  first video (camera points, characters, markers, one-click export). Switch to Advanced in the top bar for
  every tool; both modes work on the same project.
- **Place actors in the world**: the actor follows the crosshair as a live preview, the wheel turns it and a
  click places it at the play head. Seat it on stairs, slabs, carpets or mod chairs, lay it in a bed, send it
  walking to a point, and click an actor in the world to select it.
- **Multi-camera editing**: static, path and follow cameras, a cut track with hard cuts or smooth blends,
  and live switching with keys 1 to 9 while the replay plays, like a multi-camera editor.
- **Actors**: add players (any skin, by name) or any entity, vanilla or modded, give them equipment and a
  pose, and animate them with keyframes. The recording is never modified, actors live in `project.json`.
- **Skin files**: drop PNG skins (64x64) in `.minecraft/justreplay/skins/` and give one to a
  player actor in the actor screen ("Skin file", with a "Slim arms" switch). No Mojang account is needed.
- **Actor actions**: place timed actions on an actor (walk, run, swing, attack, hurt, death, jump, sit,
  sleep, swim, elytra, use item, shield, bow, eat, wave, dance on the beat, look at, wander...) from the actor panel or the
  actor screen. They show on the timeline and replay identically after a seek.
- **Blockbench models**: drop `.bbmodel` files in `.minecraft/justreplay/models/` and add them as actors with
  **+ Model**. Cubes, groups, embedded or neighbouring PNG textures and animations are supported; pick a
  looping default animation and play others from the timeline with the *Model animation* action.
- **Video export**: renders the camera path frame by frame to MP4 through FFmpeg, every frame is exact
  whatever the speed of the machine.
- **Audio editor**: put OGG or WAV files in `.minecraft/justreplay/audio/` and place them on audio tracks
  from the **Audio** tab: move, trim, split (**S**), duplicate (**Ctrl + D**), fade, loop and change the speed
  of clips on the timeline, set track volume, mute and solo, and lower or mute the game sounds (with ducking
  under the music). Clips play with the replay and are mixed into exports. MP3 must be converted first.
  Overlapping clips of a track crossfade (equal power), and fades can be linear, equal power or exponential.
- **Video effects**: picture transitions on cuts (cross dissolve, fade through black or white, wipe, slide,
  zoom blur, circle iris) and a filter stack in the **Effects** tab (exposure, contrast, saturation,
  temperature and tint, hue, vignette, film grain, sharpen, blur, black and white, sepia, chromatic aberration,
  letterbox 2.39:1 / 1.85:1 / 2:1 / 4:3, LUT from `.minecraft/justreplay/luts/`), global or per camera, with
  keyframes on every parameter and ready-made looks (Cinematic, Vintage, Noir, Warm, Cold). Fade in and out at
  the start and end of the export. Rendered on the GPU after shader packs, in the preview and in exports.
- **Texts and images in the world**: the **Overlays** tab (Simple and Advanced) adds titles, labels and
  stickers that live in the 3D world. Anchor them to the recording player, an actor or any recorded entity
  (they follow it smoothly), to a block face, or to a fixed position, by aiming with the crosshair. They face
  the camera, keep a fixed orientation or lie flat on the block, show between two ticks with fades, and can be
  drawn through walls. Texts are multi-line with Minecraft colour codes (`&6`, `&l`...), a size, a colour, a
  rounded glass plate, a shadow or an outline and an alignment; images are PNG or JPG files from
  `.minecraft/justreplay/images/` with a width in blocks, a tint and the aspect ratio kept. Offset, scale,
  rotation and opacity take keyframes, and each overlay is a bar on the timeline that drags to move or retime
  it. They render fullbright in the preview, during playback and in video exports, shader packs included.
- **Edit recorded entities**: the **Entities** tab hides a recorded player or mob (for the whole replay or
  before or after the play head), moves it to a new spot with the crosshair, and sets the volume of its sounds
  (0 to 400%, or muted), in playback and in video exports.
- **Glass interface**: a clear glass look with rounded corners and smooth animations. The editor has a
  collapsible sidebar (Cameras, Actors, Scene, Export), a bottom dock with the transport controls and a
  zoomable multi-lane timeline (Ctrl + wheel), and a shortcut overlay on **F1** or **?**.

### Keys

| Key | Action |
|---|---|
| F8 | Start / stop recording |
| F7 | Save the last minutes (instant replay, enable `replayBuffer` in the settings) |
| unbound | Record the next minutes, then stop |
| G | Replay editor |
| J | Play / pause |
| Left / Right | Seek backward / forward |
| Up / Down | Faster / slower |
| K | Add a keyframe to the selected path camera |
| O | Preview through the cut track (or the selected camera) |
| 1 to 9 | Cut to camera 1 to 9 at the play head |
| M | Add a marker |
| H | Show / hide the timeline |
| V | Jump to the recording player |

### Where are the files

- Replays: `.minecraft/justreplay/replays/<name>/` (`stream.bin.gz` + `index.bin`, `meta.json`, `project.json`, `registries.bin`)
- Videos: `.minecraft/justreplay/exports/`
- Blockbench models: `.minecraft/justreplay/models/`
- Audio files (OGG, WAV): `.minecraft/justreplay/audio/`
- LUT images (PNG, 1024x32 strip or 512x512 grid): `.minecraft/justreplay/luts/`
- Overlay images (PNG, JPG): `.minecraft/justreplay/images/`

Video export needs [FFmpeg](https://ffmpeg.org/download.html). Set its path in the export screen if it is
not on the `PATH`.

### Building

```bash
./gradlew build
```

The jar is written to `build/libs/`. `./gradlew runClient -Pselftest` runs an automated record, replay,
edit and export test in a development client.

### Scene jobs

A development client can run a job file unattended, for tools that make footage of a mod: several
recordings, replays, screenshots and exports in one launch, then the game quits.

```bash
./gradlew runClient -Pjob=/absolute/path/to/job.json
```

The `-Pjob` Gradle property sets the JVM property `justreplay.job`; a relative path is read from the game
folder (`run/`). The file lists steps run in order:

```json
{
  "steps": [
    { "type": "record", "world": "Showcase", "commands": ["time set noon", "weather clear"], "delay": 5, "seconds": 30 },
    { "type": "open", "delay": 5 },
    { "type": "shots", "ticks": [100, 300], "prefix": "showcase" },
    { "type": "export", "start": 0, "end": 400, "name": "orbit", "resolution": "1080p", "fps": 60, "crf": 16 },
    { "type": "export", "start": 200, "end": 260, "name": "loop", "width": 960, "height": 540, "fps": 30, "format": "png" },
    { "type": "quit" }
  ]
}
```

| Step | Fields |
|---|---|
| `record` | `world` (folder of `saves/`, required), `commands` (list, or one string split on `;`, run as an operator), `delay` (seconds before recording, 5), `seconds` (length, 60) |
| `open` | `replay` (folder of `justreplay/replays/`; left out, the last recording of this job), `delay` (seconds after it starts, 5) |
| `shots` | `ticks` (list, required), `prefix` (`scene`): one screenshot per tick in `screenshots/<prefix>-<tick>.png` |
| `export` | `start`, `end` (ticks, required), `name` (`scene`), `resolution` (`window`, `720p`, `1080p`, `1440p`, `2160p`; `1080p`) or `width` and `height`, `fps` (10 to 240, 60), `crf` (0 to 51, 16), `format` (`mp4` or `png`, `mp4`) |
| `quit` | none; the game also quits after the last step |

Durations are in seconds and can be fractional. Shots and exports follow the camera path of the replay's
project (`project.json`). An MP4 goes to `justreplay/exports/<name>.mp4`; a PNG sequence goes to the folder
`justreplay/exports/<name>/` (`frame_00000.png`, ... and `audio.wav` when there is sound). An existing export is
never overwritten: the new one gets `_2`, `_3`... Job exports never change the export settings of the config.

Every step is logged with the `SCENE` prefix, so a caller can follow the log: `SCENE step 2/6 open: ...`,
`SCENE replay folder: <name>`, `SCENE screenshot at tick 100: <file>`, `SCENE export finished: <file> (<n>
frames)`, `SCENE step 2/6 done`. A bad file or a failed step logs `SCENE job error ...` or `SCENE job failed at
step n/m: <reason>` and the game quits; a full run logs `SCENE job finished`. The last line is always
`SCENE done`.

### License

Apache License 2.0, see `LICENSE` and `NOTICE`. The jar bundles no third-party code. FFmpeg is an external
program you install yourself; Just Replay only starts it for video export. Optional integrations (Simple
Voice Chat, Distant Horizons, Emotecraft, WaterFrames, Create, Drop the Beam, Bobby, Iris) work through those mods'
classes and APIs and include none of their code. Not affiliated with Mojang or Microsoft.

## Français

### Fonctionnalités

- **Enregistrement** : chaque paquet reçu par le client est sauvegardé, y compris ceux des mods, donc les
  blocs, entités et effets moddés reviennent dans le replay. Démarrer et arrêter avec **F8** ou depuis le
  menu pause, ou activer l'enregistrement automatique dans la config. Le joueur qui enregistre est aussi
  sauvegardé (position, regard, posture, équipement, coups).
- **Lecture** : caméra libre en spectateur, lecture et pause, vitesse de x0,1 à x8, saut en avant et en
  arrière, retour au point de vue du joueur enregistré. La pause et la vitesse passent par le tick rate
  vanilla, donc toutes les entités et les rendus des mods suivent l'horloge du replay.
- **Éditeur** (touche **G**) : timeline avec marqueurs, keyframes de caméra et d'acteurs, keyframes
  déplaçables à la souris, chemin de caméra lissé (Catmull-Rom) avec roulis et champ de vision.
- **Modes Simple et Avancé** : l'éditeur s'ouvre en mode Simple, avec de grands boutons et des mots simples
  pour une première vidéo (points de caméra, personnages, marqueurs, export en un clic). Passer en Avancé
  dans la barre du haut pour tous les outils ; les deux modes travaillent sur le même projet.
- **Placer les acteurs dans le monde** : l'acteur suit le viseur en aperçu direct, la molette le tourne et un
  clic le place à la tête de lecture. L'asseoir sur un escalier, une dalle, un tapis ou une chaise de mod, le
  coucher dans un lit, l'envoyer marcher vers un point, et cliquer sur un acteur dans le monde pour le
  sélectionner.
- **Montage multi-caméra** : caméras fixes, chemins et caméras de suivi, piste de coupes en coupe franche ou
  en fondu de mouvement, et changement de caméra en direct avec les touches 1 à 9 pendant la lecture.
- **Acteurs** : ajouter des joueurs (n'importe quel skin, par pseudo) ou n'importe quelle entité, vanilla ou
  moddée, leur donner un équipement et une posture, et les animer par keyframes. L'enregistrement n'est
  jamais modifié, les acteurs sont stockés dans `project.json`.
- **Fichiers de skin** : déposer des skins PNG (64x64) dans
  `.minecraft/justreplay/skins/` et en donner un à un acteur joueur dans l'écran de l'acteur ("Skin file", avec
  un interrupteur "Slim arms"). Aucun compte Mojang n'est nécessaire.
- **Actions d'acteurs** : placer des actions datées sur un acteur (marcher, courir, frapper, attaquer,
  dégâts, mort, sauter, s'asseoir, dormir, nager, élytres, utiliser un objet, bouclier, arc, manger, saluer, danser en rythme,
  regarder, se promener...) depuis le panneau des acteurs ou l'écran de l'acteur. Elles apparaissent sur la
  timeline et se rejouent à l'identique après une recherche dans le temps.
- **Modèles Blockbench** : déposer des fichiers `.bbmodel` dans `.minecraft/justreplay/models/` et les
  ajouter comme acteurs avec **+ Model**. Cubes, groupes, textures PNG intégrées ou voisines et animations
  sont pris en charge ; choisir une animation par défaut en boucle et jouer les autres depuis la timeline
  avec l'action *Model animation*.
- **Export vidéo** : rend le chemin de caméra image par image en MP4 via FFmpeg, chaque image est exacte
  quelle que soit la puissance du PC.
- **Éditeur audio** : déposer des fichiers OGG ou WAV dans `.minecraft/justreplay/audio/` et les placer sur
  des pistes audio depuis l'onglet **Audio** : déplacer, rogner, scinder (**S**), dupliquer (**Ctrl + D**),
  ajouter des fondus, boucler et changer la vitesse des clips sur la timeline, régler le volume, le muet et le
  solo des pistes, et baisser ou couper les sons du jeu (avec ducking sous la musique). Les clips sont joués
  avec le replay et mixés dans les exports. Les MP3 doivent d'abord être convertis. Les clips qui se
  chevauchent sur une piste font un fondu enchaîné (puissance constante), et les fondus peuvent être
  linéaires, à puissance constante ou exponentiels.
- **Effets vidéo** : transitions d'image sur les coupes (fondu enchaîné, fondu au noir ou au blanc, volet,
  glissement, zoom flou, iris) et une pile de filtres dans l'onglet **Effets** (exposition, contraste,
  saturation, température et teinte, teinte chromatique, vignette, grain, netteté, flou, noir et blanc, sépia,
  aberration chromatique, bandes 2.39:1 / 1.85:1 / 2:1 / 4:3, LUT depuis `.minecraft/justreplay/luts/`),
  globale ou par caméra, avec des images clés sur chaque paramètre et des looks prêts à l'emploi (Cinematic,
  Vintage, Noir, Warm, Cold). Fondu d'ouverture et de fermeture de l'export. Rendus sur le GPU après les shader
  packs, dans l'aperçu comme dans les exports.
- **Textes et images dans le monde** : l'onglet **Overlays** (modes Simple et Avancé) ajoute des titres, des
  étiquettes et des stickers qui vivent dans le monde en 3D. Les ancrer au joueur enregistré, à un acteur ou à
  n'importe quelle entité enregistrée (ils la suivent en douceur), à la face d'un bloc ou à une position fixe,
  en visant avec le viseur. Ils font face à la caméra, gardent une orientation fixe ou se posent à plat sur le
  bloc, s'affichent entre deux ticks avec des fondus et peuvent se voir à travers les murs. Les textes sont
  multi-lignes avec les codes couleur de Minecraft (`&6`, `&l`...), une taille, une couleur, une plaque de verre
  arrondie, une ombre ou un contour et un alignement ; les images sont des fichiers PNG ou JPG de
  `.minecraft/justreplay/images/` avec une largeur en blocs, une teinte et les proportions conservées. Le
  décalage, l'échelle, la rotation et l'opacité prennent des images clés, et chaque overlay est une barre sur
  la timeline qui se déplace ou se redimensionne à la souris. Ils sont rendus en pleine lumière dans l'aperçu,
  pendant la lecture et dans les exports vidéo, shader packs compris.
- **Modifier les entités enregistrées** : l'onglet **Entités** masque un joueur ou un mob enregistré (pour tout
  le replay, ou avant ou après la tête de lecture), le déplace au viseur et règle le volume de ses sons (0 à 400 %,
  ou coupés), en lecture et dans les exports vidéo.
- **Interface en verre** : un style de verre clair aux coins arrondis et aux animations fluides. L'éditeur a
  un panneau latéral repliable (Caméras, Acteurs, Scène, Export), un dock en bas avec les commandes de
  lecture et une timeline à pistes zoomable (Ctrl + molette), et la liste des raccourcis sur **F1** ou **?**.

### Touches

Voir le tableau de la section anglaise. Toutes les touches se changent dans Options, Contrôles, Just Replay.

### Fichiers

- Replays : `.minecraft/justreplay/replays/<nom>/`
- Vidéos : `.minecraft/justreplay/exports/`
- Modèles Blockbench : `.minecraft/justreplay/models/`
- Fichiers audio (OGG, WAV) : `.minecraft/justreplay/audio/`
- Images LUT (PNG, bande 1024x32 ou grille 512x512) : `.minecraft/justreplay/luts/`
- Images des overlays (PNG, JPG) : `.minecraft/justreplay/images/`

L'export vidéo demande [FFmpeg](https://ffmpeg.org/download.html). Indiquer son chemin dans l'écran
d'export s'il n'est pas dans le `PATH`.

### Compilation

```bash
./gradlew build
```

Le jar est créé dans `build/libs/`. `./gradlew runClient -Pselftest` lance un test automatique complet
(enregistrement, lecture, édition, export) dans un client de développement.

### Tâches de scène

Un client de développement peut dérouler seul un fichier de tâche, pour les outils qui filment un mod :
plusieurs enregistrements, replays, captures et exports en un seul lancement, puis le jeu se ferme.

```bash
./gradlew runClient -Pjob=/chemin/absolu/vers/job.json
```

La propriété Gradle `-Pjob` règle la propriété JVM `justreplay.job` ; un chemin relatif part du dossier du jeu
(`run/`). Le fichier liste des étapes exécutées dans l'ordre (exemple complet dans la section anglaise) :

| Étape | Champs |
|---|---|
| `record` | `world` (dossier de `saves/`, obligatoire), `commands` (liste, ou une chaîne coupée sur `;`, lancées en opérateur), `delay` (secondes avant l'enregistrement, 5), `seconds` (durée, 60) |
| `open` | `replay` (dossier de `justreplay/replays/` ; absent, le dernier enregistrement de la tâche), `delay` (secondes après le démarrage, 5) |
| `shots` | `ticks` (liste, obligatoire), `prefix` (`scene`) : une capture par tick dans `screenshots/<prefix>-<tick>.png` |
| `export` | `start`, `end` (ticks, obligatoires), `name` (`scene`), `resolution` (`window`, `720p`, `1080p`, `1440p`, `2160p` ; `1080p`) ou `width` et `height`, `fps` (10 à 240, 60), `crf` (0 à 51, 16), `format` (`mp4` ou `png`, `mp4`) |
| `quit` | aucun ; le jeu se ferme aussi après la dernière étape |

Les durées sont en secondes et peuvent être décimales. Les captures et les exports suivent le chemin de caméra
du projet du replay (`project.json`). Un MP4 va dans `justreplay/exports/<name>.mp4` ; une séquence PNG va dans
le dossier `justreplay/exports/<name>/` (`frame_00000.png`, ... et `audio.wav` s'il y a du son). Un export
existant n'est jamais écrasé : le nouveau prend `_2`, `_3`... Les exports d'une tâche ne touchent jamais aux
réglages d'export de la config.

Chaque étape est écrite dans le log avec le préfixe `SCENE`, pour qu'un outil suive l'avancement :
`SCENE step 2/6 open: ...`, `SCENE replay folder: <nom>`, `SCENE screenshot at tick 100: <fichier>`,
`SCENE export finished: <fichier> (<n> frames)`, `SCENE step 2/6 done`. Un fichier invalide ou une étape ratée
écrit `SCENE job error ...` ou `SCENE job failed at step n/m: <raison>` et le jeu se ferme ; une tâche complète
écrit `SCENE job finished`. La dernière ligne est toujours `SCENE done`.

### Licence

Licence Apache 2.0, voir `LICENSE` et `NOTICE`. Le jar n'embarque aucun code tiers. FFmpeg est un programme
externe que tu installes toi-même ; Just Replay ne fait que le lancer pour l'export vidéo. Les intégrations
optionnelles (Simple Voice Chat, Distant Horizons, Emotecraft, WaterFrames, Create, Drop the Beam, Bobby, Iris) passent par les
classes et API de ces mods et n'incluent aucun de leur code. Projet non affilié à Mojang ou Microsoft.
