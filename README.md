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
- **Actors**: add players (any skin, by name) or any entity, vanilla or modded, give them equipment and a
  pose, and animate them with keyframes. The recording is never modified, actors live in `project.json`.
- **Video export**: renders the camera path frame by frame to MP4 through FFmpeg, every frame is exact
  whatever the speed of the machine.

### Keys

| Key | Action |
|---|---|
| F8 | Start / stop recording |
| G | Replay editor |
| J | Play / pause |
| Left / Right | Seek backward / forward |
| Up / Down | Faster / slower |
| K | Add a camera keyframe |
| O | Play the camera path |
| M | Add a marker |
| H | Show / hide the timeline |
| V | Jump to the recording player |

### Where are the files

- Replays: `.minecraft/justreplay/replays/<name>/` (`stream.bin.gz` + `index.bin`, `meta.json`, `project.json`, `registries.bin`)
- Videos: `.minecraft/justreplay/exports/`

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
- **Acteurs** : ajouter des joueurs (n'importe quel skin, par pseudo) ou n'importe quelle entité, vanilla ou
  moddée, leur donner un équipement et une posture, et les animer par keyframes. L'enregistrement n'est
  jamais modifié, les acteurs sont stockés dans `project.json`.
- **Export vidéo** : rend le chemin de caméra image par image en MP4 via FFmpeg, chaque image est exacte
  quelle que soit la puissance du PC.

### Touches

Voir le tableau de la section anglaise. Toutes les touches se changent dans Options, Contrôles, Just Replay.

### Fichiers

- Replays : `.minecraft/justreplay/replays/<nom>/`
- Vidéos : `.minecraft/justreplay/exports/`

L'export vidéo demande [FFmpeg](https://ffmpeg.org/download.html). Indiquer son chemin dans l'écran
d'export s'il n'est pas dans le `PATH`.

### Compilation

```bash
./gradlew build
```

Le jar est créé dans `build/libs/`. `./gradlew runClient -Pselftest` lance un test automatique complet
(enregistrement, lecture, édition, export) dans un client de développement.
