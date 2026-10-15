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
- **Multi-camera editing**: static, path and follow cameras, a cut track with hard cuts or smooth blends,
  and live switching with keys 1 to 9 while the replay plays, like a multi-camera editor.
- **Actors**: add players (any skin, by name) or any entity, vanilla or modded, give them equipment and a
  pose, and animate them with keyframes. The recording is never modified, actors live in `project.json`.
- **Actor actions**: place timed actions on an actor (walk, run, swing, attack, hurt, death, jump, sit,
  sleep, swim, elytra, use item, shield, bow, eat, wave, look at, wander...) from the actor panel or the
  actor screen. They show on the timeline and replay identically after a seek.
- **Blockbench models**: drop `.bbmodel` files in `.minecraft/justreplay/models/` and add them as actors with
  **+ Model**. Cubes, groups, embedded or neighbouring PNG textures and animations are supported; pick a
  looping default animation and play others from the timeline with the *Model animation* action.
- **Video export**: renders the camera path frame by frame to MP4 through FFmpeg, every frame is exact
  whatever the speed of the machine.

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

Video export needs [FFmpeg](https://ffmpeg.org/download.html). Set its path in the export screen if it is
not on the `PATH`.

### Building

```bash
./gradlew build
```

The jar is written to `build/libs/`. `./gradlew runClient -Pselftest` runs an automated record, replay,
edit and export test in a development client.

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
- **Montage multi-caméra** : caméras fixes, chemins et caméras de suivi, piste de coupes en coupe franche ou
  en fondu de mouvement, et changement de caméra en direct avec les touches 1 à 9 pendant la lecture.
- **Acteurs** : ajouter des joueurs (n'importe quel skin, par pseudo) ou n'importe quelle entité, vanilla ou
  moddée, leur donner un équipement et une posture, et les animer par keyframes. L'enregistrement n'est
  jamais modifié, les acteurs sont stockés dans `project.json`.
- **Actions d'acteurs** : placer des actions datées sur un acteur (marcher, courir, frapper, attaquer,
  dégâts, mort, sauter, s'asseoir, dormir, nager, élytres, utiliser un objet, bouclier, arc, manger, saluer,
  regarder, se promener...) depuis le panneau des acteurs ou l'écran de l'acteur. Elles apparaissent sur la
  timeline et se rejouent à l'identique après une recherche dans le temps.
- **Modèles Blockbench** : déposer des fichiers `.bbmodel` dans `.minecraft/justreplay/models/` et les
  ajouter comme acteurs avec **+ Model**. Cubes, groupes, textures PNG intégrées ou voisines et animations
  sont pris en charge ; choisir une animation par défaut en boucle et jouer les autres depuis la timeline
  avec l'action *Model animation*.
- **Export vidéo** : rend le chemin de caméra image par image en MP4 via FFmpeg, chaque image est exacte
  quelle que soit la puissance du PC.

### Touches

Voir le tableau de la section anglaise. Toutes les touches se changent dans Options, Contrôles, Just Replay.

### Fichiers

- Replays : `.minecraft/justreplay/replays/<nom>/`
- Vidéos : `.minecraft/justreplay/exports/`
- Modèles Blockbench : `.minecraft/justreplay/models/`

L'export vidéo demande [FFmpeg](https://ffmpeg.org/download.html). Indiquer son chemin dans l'écran
d'export s'il n'est pas dans le `PATH`.

### Compilation

```bash
./gradlew build
```

Le jar est créé dans `build/libs/`. `./gradlew runClient -Pselftest` lance un test automatique complet
(enregistrement, lecture, édition, export) dans un client de développement.
