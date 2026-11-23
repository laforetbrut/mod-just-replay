# MCP server

Just Replay 1.2.0 (mod id `justreplay`), author / auteur : vyrriox.

- [English](#english)
- [Français](#français)

---

## English

Just Replay can run a small MCP server (Model Context Protocol) inside your game, so an AI assistant on your
computer can see what the mod is doing: the recording, the replay you watch or edit, your saved replays, the edit
of a replay (cameras, actors, actions, audio, effects, overlays) and its markers with their notes. It can also look
at the replay itself: any frame as a picture, every entity of any tick, the world and the blocks. And it can drive
it: open and play a replay, edit everything the editor edits, place markers, record, export. It does so through the
same calls as the editor, the keys and the replay list, so a change made by the assistant is saved, undone and shown
exactly like one you make by hand. Two settings keep it in check: **Read only**, and deleting files, off by default.

### Switching it on

The server is off by default. Open the Just Replay settings (the gear of the editor, or the Config button of the
mod in the Mods list on NeoForge and Forge), section **MCP server (AI assistants)**, switch on
**Start the MCP server** and press **Done**: it starts at once and a toast gives its address,
`http://127.0.0.1:24464/mcp`. Switching it off stops it at once. The same options are in the `mcp` section of
`.minecraft/config/justreplay-client.json`; a change made by hand there applies at the next start.

| Option | Default | What it does |
|---|---|---|
| `enabled` | `false` | Starts the server. |
| `port` | `24464` | Port on 127.0.0.1. The address is `http://127.0.0.1:<port>/mcp`. |
| `token` | empty | Access token, written here the first time the server starts. Empty it to get a new one. |
| `timeoutSeconds` | `10` | Longest wait for the game to answer one request (a world loading, for instance). A frame may take up to 60 seconds and a jump through the replay up to 30, whatever this says. |
| `allowedOrigins` | empty | Web pages allowed to call the server from a browser, such as `http://localhost:6274` (the MCP Inspector). Desktop and command line clients need nothing here. |
| `readOnly` | `false` | **Read only**: every tool that changes something is refused, the reading and observation tools keep working. |
| `allowDestructive` | `false` | **Allow deleting files**: lets a client delete saved replays and exported videos for good. Off, such calls are refused and nothing is deleted. |

While the server listens, an **MCP** badge sits in the top left corner, in the world and on the title screen.
Its dot turns green when a client asks something. It is never in your videos. When the port is taken by another
program, a toast says so and the server waits until you pick another port.

### Security

- It listens on 127.0.0.1 only: other computers cannot reach it, not even on your network.
- Every request must carry the token, as `Authorization: Bearer <token>`. Without it the answer is 401.
- Web pages are turned away: a request whose `Origin` is not allowed, or whose `Host` is not 127.0.0.1 or
  localhost, gets 403. A website cannot reach the server through your browser, DNS rebinding included.
- Every tool reads the game on the game thread, through a queue with a time limit: a client can neither freeze the
  game nor touch it from another thread. Answers name replays by their id (folder name), never by a path of your
  computer.
- The token is in your settings file: keep it to yourself, and leave it out when you share that file.
- What a tool may do is checked before it runs, whatever it asks: with **Read only** on every change is refused, and
  tools that delete files (`delete_replay`, `delete_export`) need **Allow deleting files**. Each tool says in its
  annotations whether it only reads, adds or may overwrite and remove (`readOnlyHint`, `destructiveHint`).
- Changes of the edit stay undoable (`undo`, or Ctrl+Z in the editor); an export never overwrites a file and a rename
  never goes over another replay.

### Connecting a client

Copy the token from the settings (or from `justreplay-client.json`), then add the server to your client. With
Claude Code:

```bash
claude mcp add --transport http just-replay http://127.0.0.1:24464/mcp --header "Authorization: Bearer <token>"
```

Clients that read an `mcpServers` file, such as the `.mcp.json` of a Claude Code project, take this form
(`${JUST_REPLAY_MCP_TOKEN}` is read from the environment):

```json
{
  "mcpServers": {
    "just-replay": {
      "type": "http",
      "url": "http://127.0.0.1:24464/mcp",
      "headers": { "Authorization": "Bearer ${JUST_REPLAY_MCP_TOKEN}" }
    }
  }
}
```

A client that only starts local programs (Claude Desktop, for one) can reach it through the `mcp-remote` bridge
(Node.js), in `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "just-replay": {
      "command": "npx",
      "args": ["mcp-remote", "http://127.0.0.1:24464/mcp", "--header", "Authorization:${JUST_REPLAY_AUTH_HEADER}"],
      "env": { "JUST_REPLAY_AUTH_HEADER": "Bearer <token>" }
    }
  }
}
```

A quick check from a terminal, with the game running:

```bash
curl -s http://127.0.0.1:24464/mcp -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_state"}}'
```

### What it offers

Reading tools, which change nothing. Times are in ticks (20 per second) and also as text such as `1:02.5`.

| Tool | Arguments | Answer |
|---|---|---|
| `get_state` | none | What the mod is doing: recording, instant replay buffer, the open replay (play head, length, speed, camera path, spectated entity), the editor, the export in progress, the screen and the versions. |
| `list_replays` | `query`, `limit` (1 to 500, 50), `offset` | Saved replays, newest first: id, name, server or singleplayer, date, length, versions, size, whether it has a project, which one is open. |
| `get_project` | `replay`, `sections`, `detail` (`summary` or `full`) | The edit of a replay, with the field names of `project.json`: `overview`, `cameras`, `cuts`, `actors`, `actions`, `audio`, `effects`, `overlays`, `world`, `speed`, `entities`. A summary shortens keyframe lists to their count and first and last tick. |
| `list_markers` | `replay`, `type`, `query` | The markers in time order with their notes (see [MARKERS.md](MARKERS.md)). |
| `get_marker` | `id`, `replay` | One marker: full note, times, colour, spot or entity, the markers before and after it. |

Without `replay`, a tool reads the replay open in the game, edits not saved yet included; with the id of a saved
replay it reads its files, which stay as they are.

Resources: `justreplay://state`, `justreplay://replays`, `justreplay://project` (summary of the open project),
`justreplay://markers` and the template `justreplay://markers/{id}`, all JSON.

### Seeing the replay

These tools look at the replay open in the game, frame by frame and entity by entity. Without `tick` they look at
the play head. Another tick is reached by jumping there and back within one game tick: you never see the jump, but
going back rebuilds the world from a checkpoint, so chunks may redraw for a moment. Nothing is changed or saved.

| Tool | Arguments | Answer |
|---|---|---|
| `render_frame` | `tick`, `camera` (`auto`, `project`, `current`, `free`) with `x`, `y`, `z`, `yaw`, `pitch`, `roll`, `fov`, then `width`, `height`, `format` (`jpeg` or `png`), `quality` (1 to 100, 80), `ui` | The frame as a picture (MCP image content) and the camera it was drawn from. By default a clean video frame, drawn by the video export itself (effects, letterbox, overlays, no editor) from the project's camera path, or from the current view when the project has none. `ui=true` gives the window as you see it, editor and HUD included, from your current view unless a camera is asked for. 640 pixels wide by default, at most 1280 a side and 1280x720 in all: a bigger size is scaled down. |
| `list_entities` | `tick`, `types`, `source` (`recorded` or `actor`), `query`, a centre (`x`, `y`, `z`, or `marker`, or `near`) with `radius` (32), `limit` (1 to 200, 50), `offset`, `detail` (`summary` or `full`) | The entities of a tick: id, ref, UUID, type, name, recorded entity or editor actor, position, rotation, velocity in blocks per second, health, equipment, vehicle and passengers. Nearest first around a centre, by id otherwise; `nextOffset` gives the next page. `full` adds size, eyes, pose and item names, 50 entities a page at most. |
| `get_entity` | `id`, `tick` | One entity in full, with what it rides, what rides it and the markers pinned to it. |
| `get_entity_track` | `id`, `from`, `to`, `step` or `samples` (2 to 500, 100) | Where one entity is over time: rows `[tick, x, y, z, yaw, pitch]`, the distance travelled, its top speed and the box it moved in. At most 10 minutes (12000 ticks) and 500 samples a call. |
| `get_world` | `tick` | The replay's clock (length, play head, speed, paused) and the world: dimension, time of day shown and recorded, weather, difficulty, counts of entities, players and chunks, the project's time and weather settings, the camera of the window. |
| `get_block` | `x`, `y`, `z`, `tick` | The block there: id, state properties, fluid, block entity, biome and light, or that its chunk is not loaded. |
| `list_players` | `tick`, `limit` (1 to 200, 100) | Who recorded the replay, the players in the world with their position, and the tab list. |
| `project_to_screen` | `points` (each `[x, y, z]`), `markers` (ids, or `all`), `entities`, `tick`, the camera arguments, `width`, `height`, `ui` | Where each one falls on a frame drawn by `render_frame` with the same arguments: pixels from the left and from the top, depth in front of the camera, whether it is in front and on the picture. An entity gives its centre, feet and head. |

- An entity's `ref` is how markers and cameras name it: `recorder` for the player who recorded, `actor:<id>` for an
  actor of the editor, else its UUID. `id`, `ref` and UUID all work as `id`; prefer `ref` across ticks, an actor's
  network id changes when the editor rebuilds it.
- Positions are those the recording gives at the tick, in blocks. The game draws entities a little behind them, as it
  eases from one recorded position to the next, so a frame may show them up to a few tenths of a block away.
- A camera gives the position of its eye. Yaw 0 looks south (+Z), 90 west (-X), 180 north; a positive pitch looks
  down; `fov` is the vertical field of view in degrees. The camera keyframes of `get_project` store the position of
  the feet, 1.62 blocks lower.
- A frame takes about a second, longer while chunks load (60 seconds at most). A video frame plays the second of the
  replay before it, under the usual export veil, then everything is put back: play head, speed, pause, screen and
  camera. While a frame or an export is drawn, the tools only look at the play head.

### Driving Just Replay

These tools change things, through the same calls as the editor, the keys, the export screen and the replay list:

- A change of the edit is saved at once, as in the editor, and is one step of the editor's undo history: `undo` (or
  Ctrl+Z) takes it back. When the editor is open, it opens again on the new state, as after Ctrl+Z.
- Every argument is checked before anything changes. A wrong one gets a message saying what to fix (the range, the
  values it takes, the ids to use), and a call that fails changes nothing. An argument that does not apply to the
  action asked is refused too, so a mistyped call never half works.
- The edit tools take an `action` (`add`, `update`, `remove`...) and find what they change by id, or by exact name
  when only one thing has it. Fields left out keep their value; a new element takes the editor's defaults.
- Cameras are placed by their eye, as in `render_frame`; actors by their feet; markers and overlays in the world.
  Entities are named as `list_entities` names them (`recorder`, `actor:<id>`, a UUID or an id).

Playback and view:

| Tool | Arguments | What it does |
|---|---|---|
| `open_replay` | `replay`, `wait` | Opens a saved replay as the replay list does (one already open closes first, its edit saved) and waits until it is shown. Refused while you are in a world of your own (leave it first), during a recording or an export. If the replay was recorded with other mods, the game asks you first and the answer says so. |
| `close_replay` | none | Back to the title screen, the edit saved. Refused during an export, and while the replay's resource packs are still loading (just after it opens): leaving then would leave the loading screen up. |
| `set_playback` | `playing`, `speed` (0.1, 0.25, 0.5, 1, 2, 4 or 8), `until` | Play, pause, the playback speed; `until` plays to a tick and pauses there. |
| `seek` | one of `tick`, `time` (such as `1:02.5`), `marker` (id or name), `by` | Moves the play head, as the timeline does. |
| `set_camera` | `view` (`free`, `path`, `entity`), `entity`, `x`, `y`, `z`, `yaw`, `pitch`, `roll`, `fov`, `lookAt` (`[x, y, z]`), `lookAtEntity` | The view: the free camera placed and aimed, with a roll and a field of view held until the replay closes; the project's camera path; or an entity viewed through. It adds nothing to the edit. |

The edit:

| Tool | Arguments | What it does |
|---|---|---|
| `edit_camera` | `action` (`add`, `update`, `remove`, `select`), `camera`, `type` (`PATH`, `STATIC`, `FOLLOW`), `name`, `curve`, `timing`, `ease`, a placement (`x`, `y`, `z`, `yaw`, `pitch`, `fromView`), `roll`, `fov`, `target`, `followMode`, `distance`, `height`, `orbitSpeed`, `smoothing`, `lookAt`, `shake`, `shakeSpeed`, `select` | Cameras. The selected camera is the one exported when there are no cuts; removing one removes its cuts and the effects bound to it. |
| `edit_camera_keyframe` | `action` (`add`, `update`, `remove`), `camera`, `tick`, `newTick`, `x`, `y`, `z`, `yaw`, `pitch`, `roll`, `fov`, `lookAt` | Keyframes of a PATH camera. What is left out of a new keyframe comes from the current view, and it replaces the keyframe at its tick, as the keyframe button does. |
| `edit_cut` | `action` (`add`, `update`, `remove`, `end`), `tick`, `newTick`, `camera`, `blend`, `transition` (`CUT`, `DISSOLVE`, `FADE_BLACK`, `FADE_WHITE`, `WIPE`, `SLIDE`, `ZOOM_BLUR`, `IRIS`), `transitionTicks`, `transitionDir` | The cut track and its transitions. |
| `edit_speed` | `action` (`set_key`, `remove_key`, `add_timelapse`, `remove_timelapse`, `add_freeze`, `remove_freeze`, `set_sounds`, `clear`), `tick`, `speed`, `ramp`, `from`, `to`, `seconds`, `soundMin`, `soundMax` | Speed keys, timelapses, freezes, and the speeds at which game sounds play. |
| `edit_actor` | `action` (`add`, `update`, `remove`, `add_skin_change`, `remove_skin_change`), `actor`, `type` (`player`, `model` or an entity id), `name`, `skin`, `slim`, `model`, `modelAnimation`, `modelScale`, `scale`, `hiddenParts`, `hiddenArmor`, `mainHand` to `feet`, `pose`, `visible`, `showName`, `groundSnap`, `tick`, `x`, `y`, `z`, `yaw` | Actors. A new one stands at its feet position, or where the view looks, facing the camera. |
| `edit_actor_keyframe` | `action`, `actor`, `tick`, `newTick`, `x`, `y`, `z`, `yaw`, `pitch` | Where an actor stands over time; without a position, under the camera, as the keyframe button does. |
| `edit_actor_action` | `action`, `actor`, `tick`, `type`, `newTick`, `duration`, `param`, `target`, `clipFrom`, `clipTo` | Timed actions: swings, sitting, waving, walking to a spot, following, emotes, dances and the others of the editor. |
| `edit_entity` | `action` (`update`, `reset`, `set_offset`, `remove_offset`, `add_skin_change`, `remove_skin_change`, `hide_type`, `show_type`), `entity` or `type`, `hidden`, `hideFrom`, `hideTo`, `soundGain`, `muted`, `scale`, `glow`, `hideSkinLayers`, `hiddenParts`, `hiddenArmor` | Recorded entities: hidden, moved, scaled, muted, glowing, a player's skin; a whole entity type hidden. |
| `edit_audio_track` | `action`, `track`, `name`, `volume`, `mute`, `solo`, `crossfade` | Audio tracks. |
| `edit_audio_clip` | `action` (`add`, `update`, `move`, `remove`), `clip`, `track`, `file`, `start`, `in`, `out`, `volume`, `fadeIn`, `fadeOut`, `fadeCurve`, `loop`, `length`, `offset`, `speed` | Clips of the files of the audio folder, decoded first. |
| `set_audio_mix` | `gameVolume`, `soundGains`, `muteGame`, `voices`, `ducking`, `duckAmount` | The mix of the recorded sounds and voices under the tracks. |
| `edit_effect` | `action` (`add`, `update`, `move`, `remove`, `set_key`, `remove_key`), `effect`, `type`, `camera`, `enabled`, `values`, `file`, `index`, `param`, `tick`, `value`, `ease` | The effect stack and the keyframes of its parameters. |
| `set_effects` | `effectsEnabled`, `fadeIn`, `fadeOut`, `fadeWhite` | The switch of the whole effect chain, and the fades of the export. |
| `edit_overlay` | `action` (`add`, `update`, `remove`, `set_key`, `remove_key`), `overlay`, `type` (`TEXT`, `IMAGE`), the text and picture settings, `anchor`, `x`, `y`, `z`, `block`, `entity`, `start`, `end`, `values`, `param`, `tick`, `value` | Text and picture overlays. |
| `set_world` | `action` (`set`, `set_key`, `remove_key`, `clear_keys`), `timeMode`, `time`, `moonPhase`, `weather`, `rain`, `thunder`, `nightVision`, `fog`, `particles`, `hiddenParticles`, `key`, `tick`, `value` | Time, weather, fog and particles over the recording. |
| `edit_marker` | `action` (`add`, `update`, `remove`), `marker`, `type`, `name`, `note`, `tick`, `end`, `x`, `y`, `z`, `dimension`, `entity`, `color` | Markers (see [MARKERS.md](MARKERS.md)). The assistant can place its own and read yours; it should change yours only when you ask. |
| `save_project` | none | Writes `project.json` once more and says what the edit holds (every change is already saved). |
| `undo`, `redo` | `steps` (1 to 50) | The editor's history, shared with you: the last 100 changes since the replay opened, lost when it closes. |
| `list_files` | `kind` (`skins`, `models`, `audio`, `images`, `luts`) | The files the edit can use, from the `justreplay` folders of the game. |

Recordings and exports:

| Tool | Arguments | What it does |
|---|---|---|
| `start_recording` | `minutes` | Records the world you are in, as the record key does; with `minutes` it stops by itself. |
| `stop_recording` | `name` | Stops and saves the recording; `name` names it at once, else the naming window comes up as with the key. |
| `save_instant_replay` | `name` | Saves the last minutes kept in memory (the instant replay setting must be on). |
| `rename_replay` | `replay`, `name` | Renames a saved replay; its id may change and the answer gives the new one. |
| `delete_replay` | `replay` | Deletes a saved replay for good. Needs **Allow deleting files**. |
| `start_export` | `from`, `to`, `name`, `fps` (10 to 240), `quality` (CRF, 0 to 51), `resolution` (`WINDOW`, `HD_720`, `FULL_HD_1080`, `QHD_1440`, `UHD_2160`) or `width` and `height`, `format` (`mp4` or `png`), `rangeInName` | Starts a video export as the export screen does: the cut track, else the keyframes of the selected camera, unless `from` and `to` are given. Your export settings fill what is left out and are never changed. |
| `get_export` | none | How the export goes: state, frames, percent, time spent and left, and how the last one ended. |
| `cancel_export` | none | Stops the export as Escape does; its unfinished file is removed. |
| `list_exports` | `limit`, `offset` | The files of the export folder, newest first, with size and date. |
| `delete_export` | `file` | Deletes an exported file for good. Needs **Allow deleting files**. |

### Example: from a marker to a video

What an assistant does when you ask "make a 5 second shot of the moment I marked". Each line is one tool call,
arguments as JSON; answers are cut short.

1. `list_replays {"query": "boss"}` gives `{"replays": [{"id": "Boss fight", ...}]}`, then
   `open_replay {"replay": "Boss fight"}` gives `{"open": true, ...}`.
2. `list_markers {}` gives `{"markers": [{"id": "4c1e...", "name": "Wide shot", "note": "the whole arena", "tick": 1200}]}`.
3. `seek {"marker": "4c1e..."}` puts the play head at tick 1200; `get_entity {"id": "recorder"}` gives where the
   player stands, say `{"x": 10, "y": 64, "z": -3}`.
4. `set_camera {"x": 4, "y": 70, "z": -15, "lookAtEntity": "recorder", "fov": 60}` places the view, and
   `render_frame {}` shows it.
5. `edit_camera_keyframe {"action": "add"}` turns the view into a keyframe at tick 1200; `seek {"by": 100}`,
   `set_camera {"x": 16, "y": 70, "z": -12, "lookAtEntity": "recorder"}`, then `edit_camera_keyframe {"action": "add"}`
   again.
6. `edit_marker {"action": "add", "name": "Shot done", "note": "orbit from the left", "tick": 1200, "end": 1300}`
   leaves a note for you.
7. `start_export {"name": "Boss fight wide"}` exports the 100 ticks of the camera path and answers with the file name;
   `get_export {}` until `state` is `written`.

If you do not like it, `undo {"steps": 3}` takes the keyframes and the marker back.

### Protocol

- Streamable HTTP on one endpoint, `/mcp`: one JSON-RPC message per POST, answered with JSON (no event stream: GET
  and DELETE get 405), no session.
- Protocol versions: `2026-07-28` (no handshake: the version goes in `_meta` and in the `MCP-Protocol-Version`,
  `Mcp-Method` and `Mcp-Name` headers, `server/discover` tells what the server supports), and the handshake versions
  `2025-11-25`, `2025-06-18` and `2025-03-26` (`initialize`, then plain requests).
- Errors: an unknown tool or resource is a JSON-RPC error; a wrong argument, a tool that fails or a game that does
  not answer in time gives a tool result with `isError` and a message the assistant can read.

---

## Français

Just Replay peut faire tourner un petit serveur MCP (Model Context Protocol) dans ton jeu, pour qu'un assistant IA
de ton ordinateur voie ce que fait le mod : l'enregistrement, le replay que tu regardes ou montes, tes replays
sauvés, le montage d'un replay (caméras, acteurs, actions, audio, effets, overlays) et ses repères avec leurs
notes. Il peut aussi regarder le replay lui-même : n'importe quelle frame en image, toutes les entités de n'importe
quel tick, le monde et les blocs. Et il peut le piloter : ouvrir et lire un replay, monter tout ce que l'éditeur
monte, poser des repères, enregistrer, exporter. Il passe pour cela par les mêmes appels que l'éditeur, les touches et
la liste des replays : une modification faite par l'assistant est sauvée, annulée et affichée exactement comme une
modification faite à la main. Deux réglages le tiennent : **Read only** (lecture seule), et la suppression de
fichiers, coupée par défaut.

### L'activer

Le serveur est coupé par défaut. Ouvre les réglages de Just Replay (l'engrenage de l'éditeur, ou le bouton Config
du mod dans la liste des mods sur NeoForge et Forge), section **MCP server (AI assistants)**, active
**Start the MCP server** puis **Done** : il démarre aussitôt et un toast donne son adresse,
`http://127.0.0.1:24464/mcp`. Le couper l'arrête aussitôt. Les mêmes options sont dans la section `mcp` de
`.minecraft/config/justreplay-client.json` ; une modification à la main s'applique au démarrage suivant.

| Option | Défaut | Rôle |
|---|---|---|
| `enabled` | `false` | Démarre le serveur. |
| `port` | `24464` | Port sur 127.0.0.1. L'adresse est `http://127.0.0.1:<port>/mcp`. |
| `token` | vide | Jeton d'accès, écrit ici au premier démarrage du serveur. Vide-le pour en obtenir un nouveau. |
| `timeoutSeconds` | `10` | Attente la plus longue d'une réponse du jeu à une requête (pendant le chargement d'un monde, par exemple). Une frame peut prendre jusqu'à 60 secondes et un saut dans le replay jusqu'à 30, quoi qu'il en dise. |
| `allowedOrigins` | vide | Pages web autorisées à appeler le serveur depuis un navigateur, comme `http://localhost:6274` (le MCP Inspector). Les clients de bureau et en ligne de commande n'ont besoin de rien ici. |
| `readOnly` | `false` | **Read only** (lecture seule) : tout outil qui change quelque chose est refusé, les outils de lecture et d'observation marchent toujours. |
| `allowDestructive` | `false` | **Allow deleting files** (autoriser la suppression) : un client peut supprimer pour de bon des replays sauvés et des vidéos exportées. Coupé, ces appels sont refusés et rien n'est supprimé. |

Tant que le serveur écoute, un badge **MCP** s'affiche en haut à gauche, dans le monde et sur l'écran titre. Son
point passe au vert quand un client demande quelque chose. Il n'est jamais dans tes vidéos. Si le port est pris
par un autre programme, un toast le dit et le serveur attend que tu choisisses un autre port.

### Sécurité

- Il n'écoute que sur 127.0.0.1 : les autres ordinateurs ne peuvent pas l'atteindre, même sur ton réseau.
- Chaque requête doit porter le jeton, sous la forme `Authorization: Bearer <jeton>`. Sans lui, la réponse est 401.
- Les pages web sont refusées : une requête dont l'`Origin` n'est pas autorisée, ou dont le `Host` n'est ni
  127.0.0.1 ni localhost, reçoit 403. Un site ne peut pas atteindre le serveur à travers ton navigateur, DNS
  rebinding compris.
- Chaque outil lit le jeu sur le thread du jeu, par une file avec un délai maximal : un client ne peut ni figer le
  jeu ni y toucher depuis un autre thread. Les réponses désignent les replays par leur id (nom du dossier), jamais
  par un chemin de ton ordinateur.
- Le jeton est dans ton fichier de réglages : garde-le pour toi, et retire-le quand tu partages ce fichier.
- Ce qu'un outil a le droit de faire est vérifié avant qu'il tourne, quoi qu'il demande : avec **Read only** toute
  modification est refusée, et les outils qui suppriment des fichiers (`delete_replay`, `delete_export`) ont besoin
  de **Allow deleting files**. Chaque outil dit dans ses annotations s'il ne fait que lire, s'il ajoute ou s'il peut
  écraser et retirer (`readOnlyHint`, `destructiveHint`).
- Les modifications du montage restent annulables (`undo`, ou Ctrl+Z dans l'éditeur) ; un export n'écrase jamais un
  fichier et un renommage ne passe jamais par-dessus un autre replay.

### Brancher un client

Copie le jeton depuis les réglages (ou depuis `justreplay-client.json`), puis ajoute le serveur à ton client.
Avec Claude Code :

```bash
claude mcp add --transport http just-replay http://127.0.0.1:24464/mcp --header "Authorization: Bearer <jeton>"
```

Les clients qui lisent un fichier `mcpServers`, comme le `.mcp.json` d'un projet Claude Code, prennent cette forme
(`${JUST_REPLAY_MCP_TOKEN}` est lu dans l'environnement) :

```json
{
  "mcpServers": {
    "just-replay": {
      "type": "http",
      "url": "http://127.0.0.1:24464/mcp",
      "headers": { "Authorization": "Bearer ${JUST_REPLAY_MCP_TOKEN}" }
    }
  }
}
```

Un client qui ne lance que des programmes locaux (Claude Desktop, par exemple) passe par le pont `mcp-remote`
(Node.js), dans `claude_desktop_config.json` :

```json
{
  "mcpServers": {
    "just-replay": {
      "command": "npx",
      "args": ["mcp-remote", "http://127.0.0.1:24464/mcp", "--header", "Authorization:${JUST_REPLAY_AUTH_HEADER}"],
      "env": { "JUST_REPLAY_AUTH_HEADER": "Bearer <jeton>" }
    }
  }
}
```

Un essai rapide depuis un terminal, jeu lancé :

```bash
curl -s http://127.0.0.1:24464/mcp -H "Authorization: Bearer <jeton>" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_state"}}'
```

### Ce qu'il propose

Des outils de lecture, qui ne changent rien. Les temps sont en ticks (20 par seconde) et aussi en texte comme `1:02.5`.

| Outil | Arguments | Réponse |
|---|---|---|
| `get_state` | aucun | Ce que fait le mod : enregistrement, tampon de replay instantané, replay ouvert (tête de lecture, durée, vitesse, trajectoire de caméra, entité suivie), éditeur, export en cours, écran affiché et versions. |
| `list_replays` | `query`, `limit` (1 à 500, 50), `offset` | Les replays sauvés, du plus récent au plus ancien : id, nom, serveur ou solo, date, durée, versions, taille, présence d'un projet, celui qui est ouvert. |
| `get_project` | `replay`, `sections`, `detail` (`summary` ou `full`) | Le montage d'un replay, avec les noms de champs de `project.json` : `overview`, `cameras`, `cuts`, `actors`, `actions`, `audio`, `effects`, `overlays`, `world`, `speed`, `entities`. Un résumé réduit les listes de keyframes à leur nombre et à leurs premier et dernier ticks. |
| `list_markers` | `replay`, `type`, `query` | Les repères dans l'ordre du temps avec leurs notes (voir [MARKERS.md](MARKERS.md)). |
| `get_marker` | `id`, `replay` | Un repère : note complète, temps, couleur, point ou entité, repères d'avant et d'après. |

Sans `replay`, un outil lit le replay ouvert dans le jeu, modifications pas encore sauvées comprises ; avec l'id
d'un replay sauvé il lit ses fichiers, qui restent tels quels.

Ressources : `justreplay://state`, `justreplay://replays`, `justreplay://project` (résumé du projet ouvert),
`justreplay://markers` et le modèle `justreplay://markers/{id}`, toutes en JSON.

### Voir le replay

Ces outils regardent le replay ouvert dans le jeu, frame par frame et entité par entité. Sans `tick` ils regardent la
tête de lecture. Un autre tick est atteint par un saut aller-retour dans le même tick du jeu : tu ne vois jamais le
saut, mais revenir en arrière reconstruit le monde depuis un point de reprise, donc des chunks peuvent se redessiner
un instant. Rien n'est modifié ni sauvé.

| Outil | Arguments | Réponse |
|---|---|---|
| `render_frame` | `tick`, `camera` (`auto`, `project`, `current`, `free`) avec `x`, `y`, `z`, `yaw`, `pitch`, `roll`, `fov`, puis `width`, `height`, `format` (`jpeg` ou `png`), `quality` (1 à 100, 80), `ui` | La frame en image (contenu image MCP) et la caméra qui l'a prise. Par défaut une frame vidéo propre, rendue par l'export vidéo lui-même (effets, bandes noires, overlays, sans l'éditeur) depuis la trajectoire de caméra du projet, ou depuis la vue actuelle si le projet n'en a pas. `ui=true` donne la fenêtre telle que tu la vois, éditeur et HUD compris, depuis ta vue actuelle sauf si une caméra est demandée. 640 pixels de large par défaut, au plus 1280 par côté et 1280x720 en tout : une taille plus grande est réduite. |
| `list_entities` | `tick`, `types`, `source` (`recorded` ou `actor`), `query`, un centre (`x`, `y`, `z`, ou `marker`, ou `near`) avec `radius` (32), `limit` (1 à 200, 50), `offset`, `detail` (`summary` ou `full`) | Les entités d'un tick : id, ref, UUID, type, nom, entité enregistrée ou acteur de l'éditeur, position, rotation, vitesse en blocs par seconde, vie, équipement, monture et passagers. Les plus proches d'abord autour d'un centre, par id sinon ; `nextOffset` donne la page suivante. `full` ajoute taille, yeux, posture et noms des objets, 50 entités par page au plus. |
| `get_entity` | `id`, `tick` | Une entité en détail, avec ce qu'elle monte, ce qui la monte et les repères épinglés sur elle. |
| `get_entity_track` | `id`, `from`, `to`, `step` ou `samples` (2 à 500, 100) | Où est une entité au fil du temps : lignes `[tick, x, y, z, yaw, pitch]`, distance parcourue, vitesse maximale et boîte où elle a bougé. Au plus 10 minutes (12000 ticks) et 500 échantillons par appel. |
| `get_world` | `tick` | L'horloge du replay (durée, tête de lecture, vitesse, pause) et le monde : dimension, heure affichée et enregistrée, météo, difficulté, nombre d'entités, de joueurs et de chunks, réglages d'heure et de météo du projet, caméra de la fenêtre. |
| `get_block` | `x`, `y`, `z`, `tick` | Le bloc à cet endroit : id, propriétés d'état, fluide, bloc-entité, biome et lumière, ou que son chunk n'est pas chargé. |
| `list_players` | `tick`, `limit` (1 à 200, 100) | Qui a enregistré le replay, les joueurs présents dans le monde avec leur position, et la liste des joueurs (tab). |
| `project_to_screen` | `points` (chacun `[x, y, z]`), `markers` (ids, ou `all`), `entities`, `tick`, les arguments de caméra, `width`, `height`, `ui` | Où chacun tombe sur une frame rendue par `render_frame` avec les mêmes arguments : pixels depuis la gauche et depuis le haut, profondeur devant la caméra, devant ou non, sur l'image ou non. Une entité donne son centre, ses pieds et sa tête. |

- Le `ref` d'une entité est le nom que lui donnent les repères et les caméras : `recorder` pour le joueur qui a
  enregistré, `actor:<id>` pour un acteur de l'éditeur, sinon son UUID. `id`, `ref` et UUID marchent tous comme `id` ;
  préfère `ref` d'un tick à l'autre, l'id réseau d'un acteur change quand l'éditeur le reconstruit.
- Les positions sont celles que l'enregistrement donne au tick, en blocs. Le jeu dessine les entités un peu en
  retard, en lissant d'une position enregistrée à la suivante : une frame peut les montrer à quelques dixièmes de bloc.
- Une caméra donne la position de son œil. Yaw 0 regarde au sud (+Z), 90 à l'ouest (-X), 180 au nord ; un pitch
  positif regarde vers le bas ; `fov` est le champ de vision vertical en degrés. Les keyframes de caméra de
  `get_project` gardent la position des pieds, 1,62 bloc plus bas.
- Une frame prend environ une seconde, plus pendant le chargement des chunks (60 secondes au plus). Une frame vidéo
  joue la seconde du replay qui la précède, sous le voile habituel de l'export, puis tout est remis : tête de
  lecture, vitesse, pause, écran et caméra. Pendant le rendu d'une frame ou d'un export, les outils ne regardent que
  la tête de lecture.

### Piloter Just Replay

Ces outils changent des choses, par les mêmes appels que l'éditeur, les touches, l'écran d'export et la liste des
replays :

- Une modification du montage est sauvée aussitôt, comme dans l'éditeur, et compte pour un pas de son historique :
  `undo` (ou Ctrl+Z) la reprend. Quand l'éditeur est ouvert, il se rouvre sur le nouvel état, comme après Ctrl+Z.
- Chaque argument est vérifié avant que quoi que ce soit change. Un argument faux reçoit un message qui dit quoi
  corriger (la plage, les valeurs possibles, les ids à utiliser), et un appel qui échoue ne change rien. Un argument
  qui ne s'applique pas à l'action demandée est refusé lui aussi : un appel mal tapé ne marche jamais à moitié.
- Les outils de montage prennent une `action` (`add`, `update`, `remove`...) et trouvent ce qu'ils changent par id,
  ou par nom exact quand une seule chose le porte. Un champ absent garde sa valeur ; un nouvel élément prend les
  valeurs par défaut de l'éditeur.
- Les caméras se placent par leur œil, comme dans `render_frame` ; les acteurs par leurs pieds ; repères et overlays
  dans le monde. Les entités se nomment comme `list_entities` les nomme (`recorder`, `actor:<id>`, un UUID ou un id).

Lecture et vue :

| Outil | Arguments | Ce qu'il fait |
|---|---|---|
| `open_replay` | `replay`, `wait` | Ouvre un replay sauvé comme la liste des replays (celui qui est ouvert se ferme d'abord, montage sauvé) et attend qu'il s'affiche. Refusé quand tu es dans un monde à toi (quitte-le d'abord), pendant un enregistrement ou un export. Si le replay a été enregistré avec d'autres mods, le jeu te pose d'abord la question et la réponse le dit. |
| `close_replay` | aucun | Retour à l'écran titre, montage sauvé. Refusé pendant un export, et tant que les packs de ressources du replay chargent encore (juste après son ouverture) : quitter à ce moment laisserait l'écran de chargement affiché. |
| `set_playback` | `playing`, `speed` (0.1, 0.25, 0.5, 1, 2, 4 ou 8), `until` | Lecture, pause, vitesse de lecture ; `until` lit jusqu'à un tick et s'y met en pause. |
| `seek` | un seul de `tick`, `time` (comme `1:02.5`), `marker` (id ou nom), `by` | Déplace la tête de lecture, comme la timeline. |
| `set_camera` | `view` (`free`, `path`, `entity`), `entity`, `x`, `y`, `z`, `yaw`, `pitch`, `roll`, `fov`, `lookAt` (`[x, y, z]`), `lookAtEntity` | La vue : la caméra libre placée et visée, avec un roulis et un champ de vision gardés jusqu'à la fermeture du replay ; la trajectoire de caméra du projet ; ou une entité à travers laquelle regarder. N'ajoute rien au montage. |

Le montage :

| Outil | Arguments | Ce qu'il fait |
|---|---|---|
| `edit_camera` | `action` (`add`, `update`, `remove`, `select`), `camera`, `type` (`PATH`, `STATIC`, `FOLLOW`), `name`, `curve`, `timing`, `ease`, un placement (`x`, `y`, `z`, `yaw`, `pitch`, `fromView`), `roll`, `fov`, `target`, `followMode`, `distance`, `height`, `orbitSpeed`, `smoothing`, `lookAt`, `shake`, `shakeSpeed`, `select` | Les caméras. La caméra sélectionnée est celle exportée quand il n'y a pas de coupes ; en retirer une retire ses coupes et les effets qui lui sont liés. |
| `edit_camera_keyframe` | `action` (`add`, `update`, `remove`), `camera`, `tick`, `newTick`, `x`, `y`, `z`, `yaw`, `pitch`, `roll`, `fov`, `lookAt` | Les keyframes d'une caméra PATH. Ce qui manque à une nouvelle keyframe vient de la vue actuelle, et elle remplace celle de son tick, comme le bouton keyframe. |
| `edit_cut` | `action` (`add`, `update`, `remove`, `end`), `tick`, `newTick`, `camera`, `blend`, `transition` (`CUT`, `DISSOLVE`, `FADE_BLACK`, `FADE_WHITE`, `WIPE`, `SLIDE`, `ZOOM_BLUR`, `IRIS`), `transitionTicks`, `transitionDir` | La piste de coupes et ses transitions. |
| `edit_speed` | `action` (`set_key`, `remove_key`, `add_timelapse`, `remove_timelapse`, `add_freeze`, `remove_freeze`, `set_sounds`, `clear`), `tick`, `speed`, `ramp`, `from`, `to`, `seconds`, `soundMin`, `soundMax` | Clés de vitesse, timelapses, arrêts sur image, et vitesses auxquelles on entend le jeu. |
| `edit_actor` | `action` (`add`, `update`, `remove`, `add_skin_change`, `remove_skin_change`), `actor`, `type` (`player`, `model` ou un id d'entité), `name`, `skin`, `slim`, `model`, `modelAnimation`, `modelScale`, `scale`, `hiddenParts`, `hiddenArmor`, `mainHand` à `feet`, `pose`, `visible`, `showName`, `groundSnap`, `tick`, `x`, `y`, `z`, `yaw` | Les acteurs. Un nouveau se tient à la position de ses pieds, ou là où regarde la vue, tourné vers la caméra. |
| `edit_actor_keyframe` | `action`, `actor`, `tick`, `newTick`, `x`, `y`, `z`, `yaw`, `pitch` | Où se tient un acteur au fil du temps ; sans position, sous la caméra, comme le bouton keyframe. |
| `edit_actor_action` | `action`, `actor`, `tick`, `type`, `newTick`, `duration`, `param`, `target`, `clipFrom`, `clipTo` | Les actions dans le temps : coups, s'asseoir, saluer, marcher vers un point, suivre, emotes, danses et les autres de l'éditeur. |
| `edit_entity` | `action` (`update`, `reset`, `set_offset`, `remove_offset`, `add_skin_change`, `remove_skin_change`, `hide_type`, `show_type`), `entity` ou `type`, `hidden`, `hideFrom`, `hideTo`, `soundGain`, `muted`, `scale`, `glow`, `hideSkinLayers`, `hiddenParts`, `hiddenArmor` | Les entités enregistrées : cachées, déplacées, agrandies, muettes, en surbrillance, le skin d'un joueur ; tout un type d'entité caché. |
| `edit_audio_track` | `action`, `track`, `name`, `volume`, `mute`, `solo`, `crossfade` | Les pistes audio. |
| `edit_audio_clip` | `action` (`add`, `update`, `move`, `remove`), `clip`, `track`, `file`, `start`, `in`, `out`, `volume`, `fadeIn`, `fadeOut`, `fadeCurve`, `loop`, `length`, `offset`, `speed` | Les clips des fichiers du dossier audio, décodés d'abord. |
| `set_audio_mix` | `gameVolume`, `soundGains`, `muteGame`, `voices`, `ducking`, `duckAmount` | Le mixage des sons et des voix enregistrés sous les pistes. |
| `edit_effect` | `action` (`add`, `update`, `move`, `remove`, `set_key`, `remove_key`), `effect`, `type`, `camera`, `enabled`, `values`, `file`, `index`, `param`, `tick`, `value`, `ease` | La pile d'effets et les keyframes de leurs paramètres. |
| `set_effects` | `effectsEnabled`, `fadeIn`, `fadeOut`, `fadeWhite` | L'interrupteur de toute la chaîne d'effets, et les fondus de l'export. |
| `edit_overlay` | `action` (`add`, `update`, `remove`, `set_key`, `remove_key`), `overlay`, `type` (`TEXT`, `IMAGE`), les réglages du texte et de l'image, `anchor`, `x`, `y`, `z`, `block`, `entity`, `start`, `end`, `values`, `param`, `tick`, `value` | Les overlays texte et image. |
| `set_world` | `action` (`set`, `set_key`, `remove_key`, `clear_keys`), `timeMode`, `time`, `moonPhase`, `weather`, `rain`, `thunder`, `nightVision`, `fog`, `particles`, `hiddenParticles`, `key`, `tick`, `value` | Heure, météo, brouillard et particules par-dessus l'enregistrement. |
| `edit_marker` | `action` (`add`, `update`, `remove`), `marker`, `type`, `name`, `note`, `tick`, `end`, `x`, `y`, `z`, `dimension`, `entity`, `color` | Les repères (voir [MARKERS.md](MARKERS.md)). L'assistant peut poser les siens et lire les tiens ; il ne devrait toucher aux tiens que si tu le demandes. |
| `save_project` | aucun | Réécrit `project.json` et dit ce que contient le montage (chaque modification est déjà sauvée). |
| `undo`, `redo` | `steps` (1 à 50) | L'historique de l'éditeur, partagé avec toi : les 100 dernières modifications depuis l'ouverture du replay, perdues à sa fermeture. |
| `list_files` | `kind` (`skins`, `models`, `audio`, `images`, `luts`) | Les fichiers que le montage peut utiliser, dans les dossiers `justreplay` du jeu. |

Enregistrements et exports :

| Outil | Arguments | Ce qu'il fait |
|---|---|---|
| `start_recording` | `minutes` | Enregistre le monde où tu es, comme la touche d'enregistrement ; avec `minutes` il s'arrête tout seul. |
| `stop_recording` | `name` | Arrête et sauve l'enregistrement ; `name` le nomme tout de suite, sinon la fenêtre de nommage s'ouvre comme avec la touche. |
| `save_instant_replay` | `name` | Sauve les dernières minutes gardées en mémoire (le réglage du replay instantané doit être actif). |
| `rename_replay` | `replay`, `name` | Renomme un replay sauvé ; son id peut changer et la réponse donne le nouveau. |
| `delete_replay` | `replay` | Supprime pour de bon un replay sauvé. Demande **Allow deleting files**. |
| `start_export` | `from`, `to`, `name`, `fps` (10 à 240), `quality` (CRF, 0 à 51), `resolution` (`WINDOW`, `HD_720`, `FULL_HD_1080`, `QHD_1440`, `UHD_2160`) ou `width` et `height`, `format` (`mp4` ou `png`), `rangeInName` | Lance un export vidéo comme l'écran d'export : la piste de coupes, sinon les keyframes de la caméra sélectionnée, sauf si `from` et `to` sont donnés. Tes réglages d'export complètent ce qui manque et ne sont jamais modifiés. |
| `get_export` | aucun | Où en est l'export : état, frames, pourcentage, temps passé et restant, et comment le dernier s'est fini. |
| `cancel_export` | aucun | Arrête l'export comme Échap ; son fichier inachevé est retiré. |
| `list_exports` | `limit`, `offset` | Les fichiers du dossier d'export, du plus récent au plus ancien, avec taille et date. |
| `delete_export` | `file` | Supprime pour de bon un fichier exporté. Demande **Allow deleting files**. |

### Exemple : d'un repère à une vidéo

Ce que fait un assistant quand tu lui demandes « fais un plan de 5 secondes du moment que j'ai marqué ». Chaque
ligne est un appel d'outil, arguments en JSON ; les réponses sont raccourcies.

1. `list_replays {"query": "boss"}` donne `{"replays": [{"id": "Boss fight", ...}]}`, puis
   `open_replay {"replay": "Boss fight"}` donne `{"open": true, ...}`.
2. `list_markers {}` donne `{"markers": [{"id": "4c1e...", "name": "Plan large", "note": "toute l'arène", "tick": 1200}]}`.
3. `seek {"marker": "4c1e..."}` met la tête de lecture au tick 1200 ; `get_entity {"id": "recorder"}` donne où se
   tient le joueur, disons `{"x": 10, "y": 64, "z": -3}`.
4. `set_camera {"x": 4, "y": 70, "z": -15, "lookAtEntity": "recorder", "fov": 60}` place la vue, et
   `render_frame {}` la montre.
5. `edit_camera_keyframe {"action": "add"}` fait de la vue une keyframe au tick 1200 ; `seek {"by": 100}`,
   `set_camera {"x": 16, "y": 70, "z": -12, "lookAtEntity": "recorder"}`, puis encore
   `edit_camera_keyframe {"action": "add"}`.
6. `edit_marker {"action": "add", "name": "Plan fait", "note": "orbite par la gauche", "tick": 1200, "end": 1300}`
   te laisse une note.
7. `start_export {"name": "Boss fight large"}` exporte les 100 ticks de la trajectoire et répond avec le nom du
   fichier ; `get_export {}` jusqu'à ce que `state` vaille `written`.

Si le résultat ne te plaît pas, `undo {"steps": 3}` reprend les keyframes et le repère.

### Protocole

- Streamable HTTP sur un seul point d'accès, `/mcp` : un message JSON-RPC par POST, avec une réponse JSON (pas de
  flux d'événements : GET et DELETE reçoivent 405), sans session.
- Versions du protocole : `2026-07-28` (sans poignée de main : la version passe dans `_meta` et dans les en-têtes
  `MCP-Protocol-Version`, `Mcp-Method` et `Mcp-Name`, `server/discover` dit ce que le serveur prend en charge), et les
  versions à poignée de main `2025-11-25`, `2025-06-18` et `2025-03-26` (`initialize`, puis des requêtes simples).
- Erreurs : un outil ou une ressource inconnus donnent une erreur JSON-RPC ; un argument faux, un outil qui échoue
  ou un jeu qui ne répond pas à temps donnent un résultat d'outil avec `isError` et un message que l'assistant peut
  lire.
