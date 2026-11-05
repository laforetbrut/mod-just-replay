# Changelog

## [1.1.0] - 2026-11-05

### Added
- **Nine targets**: Just Replay now runs on Minecraft 1.20.1, 1.21.1 and 26.1.2, each on NeoForge, Forge and Fabric. The available integrations differ per target: see the table in `README.md` and the status in `docs/COMPATIBILITY.md`.
- **GeckoLib**: animated mobs, block entities, items and armor follow the replay clock. They hold while paused, follow the speed, and move by the exact time of each frame in an export. Animations triggered before a recording start or a seek target are not restored.
- **Curios**: worn items are written in snapshots and checkpoints, so they show in a recording started mid-session and after a seek.
- **Iron's Spellbooks**: the recording player's casts play on its actor, its cast state is kept in snapshots, and what only concerns the recording player (mana, cooldowns, cast bar, motion) no longer lands on the replay camera.
- **Iris**: shader time follows the replay clock, so water and wind stop on pause and a given tick gets the same shader time in every export. The result with real shader packs has not been looked at yet.
- **Entity Culling**: switched off while a replay is open and restored afterwards, so entities no longer pop in late in the free camera or differently in each export. Replays with many entities can run slower.
- **Exposure**: photographs are written in snapshots and checkpoints, so photos loaded before the recording show. A recorded capture, export or shader packet no longer acts on the viewer's game.
- **Accessories**: slot definitions and worn accessories are written in snapshots and checkpoints, also for a replay opened in a fresh game.
- **Jade**: the tooltip is hidden by the clean HUD and its requests stay out of the replay host. A recorded packet can no longer change the viewer's Jade settings.
- **Immersive Paintings**: the painting list and the images in use are written in snapshots and checkpoints, so custom paintings show after a game restart. A recorded deletion can no longer delete the viewer's own cached file.
- **Immersive Aircraft**: weapons, banners and dyed sails of an aircraft already in view at the start, or rebuilt by a seek, are written in snapshots and checkpoints. The self test loaded this mod with no aircraft in the scene, so this behaviour has not been run.

### Changed
- **Settings file**: settings move from `config/justreplay-client.toml` to `config/justreplay-client.json`. The old values are imported once and the old file stays on disk. A hand edit of the file needs a game restart. There is a new settings screen.
- **Jar name**: jars are now named `justreplay-<loader>-<minecraft version>-<version>.jar`, for example `justreplay-fabric-1.21.1-1.1.0.jar`.
- **Recordings per version**: a recording opens only on the Minecraft version it was made on. Another version is refused with a message instead of failing while playing. A recording from another loader opens with a warning, since some mod content may be missing.
- **Repository layout**: `main` holds `common/<minecraft version>/` and one Gradle project per target. There is no root build any more. `docs/BUILDING.md` and `CONTRIBUTING.md` are new.
- **Recorder log**: mod payloads that have no codec are counted in the log.

### Fixed
- **Replay closing right after opening**: a replay could close at once, now and then, with `Failed to decode packet`. Recorded registry ids were applied while packets of the replay host were still in flight. They are now applied once the host is silent and drained, and a channel fault is no longer taken for a recorded disconnect.
- **Failed replay starts**: a replay that failed before login left the session stuck until a game restart, and an error during the start left the loading screen up for good. The game now returns to the previous screen with a message and cleans up.
- **Damaged project.json**: an unreadable file was overwritten by an empty project at the next save. It is now kept aside as `project.json.bad-<time>` with a message, and null list entries are dropped on load.
- **Checkpoints**: under 40 fps a checkpoint could take minutes, since it built about one chunk every 2 seconds. It now defers once and then keeps progressing on slow frames. Chunks the player leaves while flying are built just before the client unloads them, which removes the holes they left.
- **Snapshots and seeks**: snapshots use the current chunk radius instead of the one at login, clear entity state that went back to default, and include maps. A backward seek clears tab list entries of later players, a recorded dimension change places the camera again instead of leaving it at the world origin, and differences in synced registries are reported at opening.
- **Disk failures**: a failing disk stopped a recording silently and the toast still said saved. It now stops the recording with a message, and a stop that did not finish reports the recording as incomplete. Failed saves no longer leave a partial clip, a clip being written is not listed, and file handles and native memory are released on time.
- **Mod packets in bundles**: one sub packet that fails to encode no longer loses the rest of its bundle, and the failure is logged under that sub packet.
- **Undo**: undo could get stuck and redo be wiped when the restored state was normalized, walk and run actions moved their destination on undo, and one drag of a world slider could fill the whole history. Selecting a camera is no longer an undo step.
- **Shortcuts**: editor shortcuts no longer fire while typing in a text field, where digits inserted cuts and Delete removed keyframes.
- **Editor details**: a follow camera keeps its mob or model actor after a respawn, the "Whole replay" hide button hides a visible entity, scaled-up actors no longer vanish at the screen edge, ripple edits keep fades inside the clip and drop hide ranges that were removed, and a cut placed past a manual end moves that end.
- **Export sound**: project clips were 50 ms ahead of the picture. Looping sounds now repeat, stopped sounds stop, records and loops started before the capture are kept, voices wait for their track to load, and the track is mixed in blocks so a long export does not hold it all in memory.
- **Export endings**: an internal error on the finisher thread left an export without a result, a hung FFmpeg was never killed, a failed or cancelled export kept its partial file and took the name, and an audio failure was reported as a plain success. Each case now ends properly, and an export without sound says so.
- **Mixed sound on Fabric**: speed-scaled and mixed sounds were silent on Fabric because its sound API calls a method these sound classes did not declare.
- **Replay stopping on 1.20.1**: the replay host timed the camera player out about 32 seconds into any replay on this new target, which ended the replay.

### Ajouts
- **Neuf cibles** : Just Replay tourne maintenant sur Minecraft 1.20.1, 1.21.1 et 26.1.2, chacun sur NeoForge, Forge et Fabric. Les intégrations disponibles varient selon la cible : voir le tableau du `README.md` et l'état dans `docs/COMPATIBILITY.md`.
- **GeckoLib** : les mobs, blocs, objets et armures animés suivent l'horloge du replay. Ils se figent en pause, suivent la vitesse et avancent du temps exact de chaque image en export. Les animations déclenchées avant le début d'un enregistrement ou la cible d'un saut ne sont pas restaurées.
- **Curios** : les objets portés sont écrits dans les instantanés et points de reprise, ils s'affichent donc dans un enregistrement démarré en cours de partie et après un saut.
- **Iron's Spellbooks** : les sorts du joueur enregistré se jouent sur son acteur, son état d'incantation est gardé dans les instantanés, et ce qui ne concerne que lui (mana, temps de recharge, barre d'incantation, poussées) n'arrive plus sur la caméra du replay.
- **Iris** : le temps des shaders suit l'horloge du replay, l'eau et le vent s'arrêtent donc en pause et un même tick a le même temps de shader dans chaque export. Le résultat avec de vrais shader packs n'a pas encore été regardé.
- **Entity Culling** : coupé pendant un replay puis rétabli, les entités n'apparaissent donc plus en retard en caméra libre ni différemment à chaque export. Un replay avec beaucoup d'entités peut être plus lent.
- **Exposure** : les photos sont écrites dans les instantanés et points de reprise, celles chargées avant l'enregistrement s'affichent donc. Une capture, un export ou un paquet de shader enregistré n'agit plus sur le jeu de celui qui regarde.
- **Accessories** : les définitions d'emplacements et les accessoires portés sont écrits dans les instantanés et points de reprise, aussi pour un replay ouvert dans une partie neuve.
- **Jade** : l'infobulle est masquée par le HUD propre et ses demandes ne vont plus au serveur du replay. Un paquet enregistré ne peut plus changer les réglages Jade de celui qui regarde.
- **Immersive Paintings** : la liste des tableaux et les images utilisées sont écrites dans les instantanés et points de reprise, les tableaux personnalisés s'affichent donc après un redémarrage du jeu. Une suppression enregistrée ne peut plus effacer le fichier en cache de celui qui regarde.
- **Immersive Aircraft** : les armes, bannières et voiles teintes d'un appareil déjà visible au début, ou reconstruit par un saut, sont écrites dans les instantanés et points de reprise. Le test automatique a chargé ce mod sans appareil dans la scène : ce comportement n'a donc pas été exécuté.

### Modifications
- **Fichier de réglages** : les réglages passent de `config/justreplay-client.toml` à `config/justreplay-client.json`. Les anciennes valeurs sont importées une fois et l'ancien fichier reste sur le disque. Modifier le fichier à la main demande de redémarrer le jeu. Il y a un nouvel écran de réglages.
- **Nom du jar** : les jars s'appellent maintenant `justreplay-<loader>-<version de minecraft>-<version>.jar`, par exemple `justreplay-fabric-1.21.1-1.1.0.jar`.
- **Enregistrements par version** : un enregistrement ne s'ouvre que sur la version de Minecraft où il a été fait. Une autre version est refusée avec un message au lieu d'échouer pendant la lecture. Un enregistrement d'un autre loader s'ouvre avec un avertissement, car le contenu de certains mods peut manquer.
- **Organisation du dépôt** : `main` contient `common/<version de minecraft>/` et un projet Gradle par cible. Il n'y a plus de build à la racine. `docs/BUILDING.md` et `CONTRIBUTING.md` sont nouveaux.
- **Journal de l'enregistreur** : les paquets de mods sans codec sont comptés dans le journal.

### Correctifs
- **Replay fermé juste après l'ouverture** : un replay pouvait se fermer aussitôt, de temps en temps, avec `Failed to decode packet`. Les identifiants de registres enregistrés étaient appliqués alors que des paquets du serveur du replay circulaient encore. Ils sont maintenant appliqués une fois ce serveur silencieux et vidé, et une panne du canal n'est plus prise pour une déconnexion enregistrée.
- **Démarrages de replay ratés** : un replay qui échouait avant la connexion bloquait la session jusqu'au redémarrage du jeu, et une erreur au démarrage laissait l'écran de chargement affiché pour de bon. Le jeu revient maintenant à l'écran précédent avec un message et nettoie.
- **project.json abîmé** : un fichier illisible était écrasé par un projet vide à la sauvegarde suivante. Il est maintenant gardé de côté sous le nom `project.json.bad-<heure>` avec un message, et les entrées nulles des listes sont retirées au chargement.
- **Points de reprise** : sous 40 images par seconde, un point de reprise pouvait prendre plusieurs minutes, à environ un chunk toutes les 2 secondes. Il n'attend plus qu'une fois puis continue d'avancer sur les images lentes. Les chunks que le joueur quitte en volant sont construits juste avant que le client les décharge, ce qui retire les trous qu'ils laissaient.
- **Instantanés et sauts** : les instantanés utilisent le rayon de chunks actuel et non celui de la connexion, effacent l'état d'entité revenu à sa valeur par défaut et contiennent les cartes. Un saut en arrière retire de la liste des joueurs ceux arrivés plus tard, un changement de dimension enregistré replace la caméra au lieu de la laisser à l'origine du monde, et les différences de registres synchronisés sont signalées à l'ouverture.
- **Pannes de disque** : un disque en panne arrêtait un enregistrement sans rien dire et le message annonçait quand même la sauvegarde. L'enregistrement s'arrête maintenant avec un message, et un arrêt inachevé signale l'enregistrement comme incomplet. Les sauvegardes ratées ne laissent plus de clip partiel, un clip en cours d'écriture n'y apparaît pas, et les fichiers et la mémoire native sont libérés à temps.
- **Paquets de mods dans les groupes** : un sous-paquet impossible à encoder ne fait plus perdre le reste de son groupe, et l'échec est noté au nom de ce sous-paquet.
- **Annulation** : l'annulation pouvait rester bloquée et le rétablissement s'effacer quand l'état restauré était normalisé, les actions de marche et de course déplaçaient leur destination à l'annulation, et un seul glissement d'un curseur du monde pouvait remplir tout l'historique. Sélectionner une caméra n'est plus une étape d'annulation.
- **Raccourcis** : les raccourcis de l'éditeur ne se déclenchent plus pendant la saisie dans un champ de texte, où les chiffres inséraient des coupes et Suppr retirait des keyframes.
- **Détails de l'éditeur** : une caméra de suivi garde son acteur mob ou modèle après une réapparition, le bouton de masquage "Whole replay" masque une entité visible, les acteurs agrandis ne disparaissent plus au bord de l'écran, les éditions de temps gardent les fondus dans le clip et retirent les plages de masquage supprimées, et une coupe placée après une fin manuelle déplace cette fin.
- **Son des exports** : les clips du projet avaient 50 ms d'avance sur l'image. Les sons en boucle se répètent maintenant, les sons arrêtés s'arrêtent, les disques et boucles démarrés avant la capture sont gardés, les voix attendent le chargement de leur piste, et la piste est mixée par blocs pour qu'un long export ne la garde pas entière en mémoire.
- **Fins d'export** : une erreur interne sur le fil de fin laissait un export sans résultat, un FFmpeg bloqué n'était jamais arrêté, un export raté ou annulé gardait son fichier partiel et prenait le nom, et un échec audio était annoncé comme un simple succès. Chaque cas se termine maintenant proprement, et un export sans son le dit.
- **Son mixé sur Fabric** : les sons à vitesse modifiée et mixés étaient muets sur Fabric, car son API de son appelle une méthode que ces classes ne déclaraient pas.
- **Arrêt du replay en 1.20.1** : le serveur du replay déconnectait le joueur caméra environ 32 secondes après le début de tout replay sur cette nouvelle cible, ce qui terminait le replay.

---

## English

### [Unreleased]

### [1.0.11] - 2026-11-04

#### Fixed
- **Armourer's Workshop**: outfits still did not show in replays. Armourer's Workshop cuts its packets on a thread of
  its own, so the skins and wardrobes written at the start of a recording were handed back too late and never made it
  into the file, while the log counted them. They are now written straight away, in the same format. Recordings made
  with 1.0.9 or 1.0.10 have no outfits: record again.

### [1.0.10] - 2026-11-03

#### Fixed
- **Armourer's Workshop**: singleplayer replays now show outfits. In singleplayer the skins are read straight from the
  world's skin database and never cross the connection, so 1.0.9 recorded only the skins the client held when the
  recording started, and the replay world looked the others up in its own empty database. Skins are now recorded
  wherever they are loaded from, snapshots add every skin a recorded entity wears, and during a replay every lookup is
  served from the recording before it reaches the replay world's database. The log says how many skins each snapshot
  wrote and warns about a worn skin the recording does not have. Singleplayer recordings made with 1.0.9 may stay
  without some skins.

### [1.0.9] - 2026-11-03

#### Added
- **Epic Fight**: animations and battle mode of players and patched mobs replay as recorded, the recording player's
  included on its actor. A recording started mid-fight shows the attack in progress, seeks resume animations at
  their time, and animations hold while paused and follow the replay speed.
- **Armourer's Workshop**: outfits and skinned items of every player, the recording player's actor included, worn
  before or during the recording, after seeks and in a later session. The skins are saved in the recording.
- **Custom NPCs** (unofficial NeoForge port): NPCs replay with the look they had when recorded (model, skin, size,
  name, held items), also when they were there before the recording or changed during it. NPC dialogs and screens
  of the recording player do not open during a replay.
- **Pehkui**: entity scales, set before or during the recording, the recording player's own scale on its actor, and
  gradual resizes after seeks and at any speed.

#### Fixed
- Entity scale and the other synced attributes (vanilla `/attribute`, movement speed...) were lost: they are saved
  with every entity at the start of a recording and at checkpoints, and the recording player's own are followed.
- Joining a world while morphed with Morph, then recording, showed the recording player as a player.
- Simulated (Create Aeronautics) ropes attached before a recording or a checkpoint were missing from the replay.
- In a replay, players in spectator mode showed up (the replay camera is itself a spectator), and the inventory,
  containers and advancements could be opened, which belong to no one there.

### [1.0.8] - 2026-11-01

#### Added
- Duplicate actor: a Duplicate button in the actor panel copies the selected actor with its look, skin,
  model, scale, hidden parts, skin changes, keyframes and actions, one block aside, and selects the copy.
- Save still: a button in the export dialog renders the play head frame at the export size, with effects,
  letterbox and overlays, and saves it as `exports/<name>_still_<tick>.png` without overwriting older ones.
- Insert time and Remove time in the Scene tab: a ripple edit at the play head that pushes every later edit
  (camera keyframes, actor keyframes and actions, markers, cuts, audio clips, effect and overlay keyframes,
  overlay times, world keyframes, entity edits) later, or deletes a range and pulls the rest earlier. The
  recording itself does not move.
- **Camera shake**: every camera has a handheld shake amount (off to 100%) and a shake speed. The wobble of
  position (a few centimetres) and rotation (a couple of degrees) is a smooth function of the replay time seeded
  by the camera, so a frame is the same in the preview, in exports and after a seek.
- **Look-at target**: static and path cameras can aim at an actor or a recorded entity ("Look at", "Clear aim").
  Yaw and pitch then follow the target's eyes every frame, roll and FOV keep their keyframes; when the target is
  not found the keyframed rotation is used.
- **Morph V2 support**: players in a Morph form replay in that form, the recording player's actor included,
  with its transitions (morph and demorph). Recordings started while morphed and seeks show the right form, a
  transition cut by a seek resumes where it should, and the form holds while the replay is paused and follows its
  speed. Morph's own screens and HUD data of the recording player are not replayed.
- **Sable and Create Aeronautics**: airships and other Sable sub-levels now move in replays instead of standing
  frozen or showing up empty. Their poses, which skip the connection (a direct call in singleplayer, Sable's UDP
  channel in multiplayer), are recorded and handed to Sable's interpolation at their tick; snapshots and checkpoints
  write every sub-level, so recordings started mid-flight and seeks work; sub-level chunks are no longer refused as
  out of range; the replay world's own server can no longer send poses of its own. See `docs/COMPATIBILITY.md`.

#### Changed
- **EMOTE action**: takes a speed like *Custom emote* (`name|speed`), finds an emote by its file name as well as its
  display name or UUID (`palm` for Emotecraft's Face palm), and follows the replay clock frame by frame, so it stays
  in sync in slow motion, after a seek and in exports.
- **POINT action**: still points straight ahead along the actor's facing; with `camera` or an actor name as the
  parameter, the head and the arm aim at it.
- **DANCE action**: actors that are not humanoid (creepers, animals...) bounce on the beat and sway, and hop for the
  hopping styles, instead of standing still.
- **Scene jobs**: the `delay` of a `record` step counts server ticks from the moment the commands ran, so events
  scheduled by the commands land on the same replay tick in every take. `meta.json` gets `startGameTime` and
  `sceneCommandsTick` to line events up without measuring. `shots` play the second before each tick, so live mobs
  and particles show as they do in an export.

#### Fixed
- Emotecraft emotes played with *Custom emote* (JSON and `.emotecraft`) sank the legs under the floor and pulled
  the arms off the shoulders: their limb positions are where the part is, not how far it moved, and were counted
  twice (laforetbrut/mod-just-replay#2).
- An Ender Dragon actor never turned in place and never flapped its wings: its heading, neck and tail now follow
  the actor's yaw keys, and its wings beat, faster when it hovers.
- After the start of a replay or a seek, the replay camera stayed attached to the world from before, so mods that
  reach the world through the player (Sable creating its airships) acted on a world no longer shown.

### [1.0.7] - 2026-11-01

#### Fixed
- A replay could close right after it started in large modpacks (seen with KubeJS and about 440 mods): the
  registry ids saved with a recording left entries it did not list without an id, and the replay world's own
  server then failed to send the recipes. Entries missing from a recording now keep an id, and the replay world's
  server no longer sends recipes during a replay.

### [1.0.6] - 2026-10-28

#### Added
- **Action start and end**: the actor Actions tab sets a start and an end tick on the replay timeline for every
  action, with "Start = play head" and "End = play head" buttons; an empty end lasts until the next action. The list
  shows each action as start → end.
- **Emote clip**: custom emotes and Emotecraft emotes can play only part of their animation ("Emote from" and "Emote
  to", in emote ticks). A looping custom emote loops inside the clip; an Emotecraft emote starts at the clip start
  and stops at its end.
- **Timed skin changes**: a player actor or a recorded player can switch skin at a chosen tick ("Skin changes" in
  the actor screen and in the Entities tab, "Add at cursor"), for staged scenes such as the same skin with blood
  from tick 120. The change shows in playback and in video exports. "Save current skin" writes the skin shown now
  into the skins folder as a new PNG, ready to paint on in any image editor.
- **Skin overlay and body parts**: player actors now show the outer layer of their skin (hat, jacket, sleeves,
  trousers), with a switch to turn it off; recorded players get the same switch in the Entities tab. Each player
  actor or recorded player can also hide its head, body, left or right arm, left or right leg; a hidden arm takes
  its held item with it.
- **Edit recorded entities**: a new **Entities** tab (Simple and Advanced) lists the recorded players and mobs
  near the camera and picks one under the crosshair. Hide it for the whole replay, from the play head or until the
  play head (model, name tag, shadow, fire and held items, and its sounds); move it to a new spot at the play head
  with the crosshair (offset keyframes, turned with the wheel); set the volume of its own sounds from 0 to 400% or
  mute them. The selected entity is outlined while the editor is open. Edits apply in playback, previews and video
  exports; physics and recorded movement are untouched. A sound belongs to an entity when it follows it or starts
  within 0.75 block of it; hidden entities are also left out of the spectate pick.
- **Sound types** in the editor's Audio tab: the volume of each kind of game sound, from 0 to 400%, in the
  replay and in exports (footsteps, players, blocks, hostile mobs, friendly mobs, ambient, weather, jukebox and note
  blocks).
- **Custom emotes** for actors, without Emotecraft: drop Blockbench Bedrock animation files (every animation is an
  emote, linear, step and catmullrom keyframes) or Emotecraft legacy JSON emotes (bend and axis are not played) in
  `justreplay/emotes/`, then pick them in the new *Custom emote* action (optional "|speed"). Files reload when they
  change; a broken file is reported once. An example file with a wave and a bow is created on first use.
  Emotecraft binary emotes (`*.emotecraft`) are read too, with their stored name, and the Emotecraft `body` part now
  moves and turns the whole actor (a headspin plays upside down); new files show up in the picker while it is open,
  and broken files are listed with their reason.
- **Weather and time**: a new **World** tab in the editor (Simple and Advanced). Recorded, clear, rain or thunder
  weather, a fixed time of day with presets, keyframes for the time and the rain and thunder strengths, frozen
  time and the moon phase. Identical in previews and exports. Off by default.
- **ParCool and Better Combat**: the recording player's parkour actions (vault, roll, dodge, wall run, crawl,
  cling...) and its Better Combat attack animations now play on its actor in the replay, as other clients see
  them; other players' actions and attacks were already in the recorded packets. Checkpoints carry the lasting
  ParCool actions of every player, so a seek lands in the right action; attacks already over at a seek target are
  dropped. Both mods' requests are not sent to the replay host.
- Recorded packets of a mod that is not installed when the replay plays are now skipped quietly (one line in the
  log per payload), instead of a warning for every packet.
- **Instant replay button**: the pause menu has a "Save last N min" button next to "Start recording", which
  saves the instant replay buffer like its key.
- **Precise transform and scale**: the actor screen has a "Transform" section with X, Y, Z, yaw and pitch typed as
  numbers (with 0.1 block and 5 degree steps) for the keyframe at the play head, created when missing. The Entities
  tab gets the same fields for the offset of a recorded entity, with its resulting absolute position. Actors and
  recorded entities also get a scale (0.05 to 10), drawn around the feet, with the name tag and shadow following.
  For model actors it multiplies the model scale.

#### Fixed
- Footsteps were nearly silent in exported videos: the game plays a step at 15% of its volume and the export
  fades it with the distance to the camera, so from a camera a few blocks away it could not be heard. Footsteps now
  play at 200% by default in replays and exports, adjustable in Sound types.
- The recording player's own footsteps were still missing: the local player plays its entity sounds (steps,
  landing, swimming, eating) through its own path, which the recorder did not hear. They are now recorded too.
- Clicking a recorded player or mob in the editor's view selects it and opens the Entities tab on it.

### [1.0.5] - 2026-10-27

#### Fixed
- The recording player's own sounds are back in replays and video exports: footsteps, blocks placed and broken,
  eating, swings. The server never sends a player the sounds it causes (the game plays them locally), so the
  recorder now captures them on the client as the packets every other player received.
- Distant Horizons: the distant terrain (LODs) of the recorded world now shows in replays and exported videos
  instead of being turned off. Recordings note where DH keeps each visited dimension's LODs; opening the replay
  copies those databases once to a temporary folder that DH reads in read only mode, so the original LODs are never
  opened or changed, and the copy is deleted when the replay closes. Distant generation stays off. New options
  `playback.distantHorizonsLods` (on by default) and `playback.distantHorizonsMaxCopyMb`. Recordings made before
  this version play without LODs, as before.
- Distant Horizons could stay switched off after a replay that ended with a crash: Just Replay turned DH rendering
  off through its API during replays, and DH saves such values in its config file. Rendering is no longer touched;
  the distant generation setting changed during a replay is noted first and put back at the next start if the
  replay did not end normally. Players hit by this before can turn DH rendering back on in its settings.
- A short video export could fail at its very end: when the game rendered faster than FFmpeg encoded, the
  exporter took the full frame queue for a stuck encoder and stopped FFmpeg. It now waits for the last frames.

### [1.0.4] - 2026-10-16

#### Added
- Scene jobs for tools that film a mod: `-Djustreplay.job=<file.json>` (or `./gradlew runClient -Pjob=...`) runs
  a list of steps in one launch (record a world, open a replay, take screenshots, export, quit), each export with
  its own resolution or exact size, frame rate, quality and MP4 or PNG sequence output. Every step is logged
  with the `SCENE` prefix. The `justreplay.scene*` properties keep working as before. See the README, "Scene
  jobs".

#### Changed
- The mod list shows the Just Replay logo.

### [1.0.3] - 2026-10-15

#### Added
- Optional Drop the Beam support: its music, light desk cues and video screens follow the replay clock (pause,
  speed, seeks). A recording started mid-session carries the turntable, mix and show state. The music is silent
  while the replay is paused or away from x1, follows the game sound volume, mute and ducking of the project,
  and is part of the video export audio, placed relative to the camera. The tick where each song starts is
  logged and available to the editor.
- **Dance** actor action: humanoid actors dance on a beat grid ("style|bpm|beat tick") in ten styles
  (bounce, fist pump, hands up, wave, shuffle, headbang, jump, DJ, hakken, jumpstyle), smooth between ticks and on the beat in
  exports, with a slightly different energy and timing per actor so a crowd never moves as one body.
- Player actors can wear a skin file: drop PNG skins (64x64) in `.minecraft/justreplay/skins/`
  and pick one in the actor screen, with a "Slim arms" switch for the arm model. No Mojang lookup is made for
  such an actor. A missing or broken file logs one warning and the actor keeps the default skin.

#### Fixed
- With Drop the Beam's camera screens in view, a replay could crash on its first frame or show no terrain at all:
  the level renderer is now put back on its current section grid when another view left it on a released one.
- Colour grading no longer turns the darkest areas into bright noise when contrast or saturation and the
  temperature filter are combined.
- Player actors with "Show name" switched off no longer show their name tag.
- Projects with many named player actors no longer have their skin lookups refused by Mojang (HTTP 429): the
  lookups are queued, two per tick, and a failed one is retried once after five seconds. Actors wear the
  default skin until their turn.

### [1.0.2] - 2026-10-15

#### Fixed
- An actor seated or laid in a bed after a "Walk to" or a "Follow" no longer ends up beside the seat or the
  bed: a placement keyframe now ends the moves and the follow started before it, and a seated actor is no
  longer snapped onto a chair back, a table or a ceiling above the seat.

### [1.0.1] - 2026-10-15

#### Added
- Texts and images in the world: a new **Overlays** tab, in the Simple and Advanced modes, adds titles,
  labels and stickers anchored to an entity (the recording player, an actor or a recorded entity, followed
  smoothly and found again after seeks), a block face or a fixed position, placed with the crosshair. They
  face the camera, keep a fixed yaw, pitch and roll or lie flat on the block, show between two ticks with fade
  in and out, and can be drawn through walls. Texts: several lines, `&` colour and style codes, size, colour,
  rounded glass plate (colour, opacity, padding), shadow or outline, alignment. Images: PNG or JPG files (the
  first frame of a GIF) from `justreplay/images/`, width in blocks, aspect ratio kept or not, tint; each file
  is loaded once and released when the replay ends, and a broken file warns once. Offset, scale, rotation and
  opacity take keyframes at the play head. An overlays lane on the timeline shows each visible range as a bar
  that drags to move, with ends that drag to change the start and the end. Overlays render fullbright in the
  editor preview, during playback and in video exports, shader packs included.

### [1.0.0] - 2026-10-15

#### Added
- Recording of every clientbound play packet, mod payloads included, with the local player's movement,
  pose, equipment and swings. Manual start (F8, pause menu) with a snapshot of the current world, or
  automatic start on join.
- Playback in a throw-away void world: free spectator camera, pause, speeds x0.1 to x8, seek in both
  directions, jump to the recording player.
- Replay list from the title screen: open, rename, delete, open folder.
- Editor: timeline, markers, camera keyframes with roll and FOV, Catmull-Rom camera path, draggable
  keyframes.
- Actors: players with any skin and any entity type, equipment, pose, visibility, keyframed motion.
- Actor actions on the timeline: walk and run cycles, swings, attacks (mob attack animations included), hurt
  flash, death, jump, crouch, sit, sleep, swim, elytra, spin attack, item use, shield, bow, eating, gestures,
  look at an actor or the camera, look around, wander. Seeking shows the same pose as playing.
- Blockbench model actors: `.bbmodel` files from `justreplay/models/` with their textures and animations,
  drawn in the world (shaders and video export included). An example model is provided.
- Video export to MP4 through FFmpeg with a fixed time step, with an audio track: the sounds of the replay are logged during the export and mixed in stereo relative to the camera, in sync with every frame.
- Camera path shapes: Curve (through every keyframe), Bezier (rounded corners, smoothest) and Linear.
- Constant speed camera paths with optional smooth start and stop: only the first and last keyframe times matter.
- The camera path and its keyframes are drawn in the world while editing.
- Multi-camera editing: any number of named cameras, each static (one placement), path (keyframes with its own
  curve, timing and ease) or follow (tracks an entity from behind, the side, the front or in orbit, smoothed).
  A cut track says which camera is live from which tick, with hard cuts or smooth blends; keys 1 to 9 cut to
  camera 1 to 9 at the play head, also while playing. The preview (O) and the export go through the cut track.
  Older projects open with their camera path as the first camera.
- Development self test (`./gradlew runClient -Pselftest`).
- Chunks the client holds beyond the server view distance (for example cached by Bobby) are recorded too, a few per tick.
- Replays keep chunks loaded up to the playback view distance (32 by default) instead of unloading them when the recording player walked away.
- Audio editor: an Audio tab and audio lanes on the timeline for music clips and sound design. OGG and WAV
  files from `justreplay/audio/` go on any number of tracks (volume, mute, solo) as clips that can be moved,
  trimmed by their edges, split at the play head (S), duplicated (Ctrl + D), faded in and out, looped and sped
  up or down, with a waveform preview and snapping to markers, cuts, the play head and other clips. The clips
  play in sync with the replay (pause, speeds, seeks) and are mixed sample accurately into video exports, with
  a game sound volume, a mute switch for the game sounds and ducking under the clips.
- Simple Voice Chat support: voices heard and your own microphone are recorded into `voice.bin.gz`, played back at the speaker in sync with the replay clock and mixed into video exports; a Voices toggle in the editor turns them off.
- Glass interface: every screen, the HUD and the menu buttons are redrawn in a clear glass style with rounded
  corners, crisp procedural icons at any GUI scale and smooth animations. The editor gets a top bar (replay
  name, save state, settings, help), a collapsible sidebar with Cameras, Actors, Scene and Export tabs that
  scroll on small windows, and a bottom dock with icon transport controls, a speed selector and a laned
  timeline (cuts, camera keys, actor keys and actions, markers) that zooms with Ctrl + wheel and scrolls.
  F1 or ? shows the shortcuts, read from the current key bindings. The replay list shows cards with search
  and sorting by date, name or size; the export screen has resolution and frame rate selectors and a quality
  slider.
- Instant replay: with `recording.replayBuffer` on, the last minutes of play (`bufferMinutes`, 5 by default) stay in memory, compressed, and F7 saves them as a normal replay. Nothing is written to disk until you save.
- "Record next minutes" key (unbound by default): records for `recording.nextMinutes` and stops by itself.
- New settings `recording.checkpointMinutes` (5 by default) and `recording.extraChunkRadius` (16 by default).
- Simple and Advanced editor modes, switched in the editor top bar and remembered (`editor.mode`, Simple by
  default). Simple keeps the essentials with big buttons and plain words: play, seek and speed, "Add camera
  point here", camera path on or off, Curve or Bezier smoothness with constant speed, characters (a player skin
  or a mob) placed in the world that walk to a point, follow someone, sit, lie in bed or wave, markers, and a
  one-click export. Advanced is the full editor. Switching keeps all the work.
- In-world actor placement: adding or moving an actor shows it live under the crosshair (free camera, paused or
  not); the wheel turns it (Shift: fine, right click: quarter turn), Ctrl snaps to the block center, a click
  writes a keyframe at the play head and Esc cancels. "Sit here" seats it on the clicked block at the right
  height (stairs on their low step, slabs, carpets and mod chairs on top of their collision shape, full blocks
  on top), "Lie in bed" lays it in the clicked bed, "Walk to" picks a destination. "Move to crosshair" and
  "Face the camera" act on the selected actor, and clicking an actor in the world selects it while the editor
  is open. Works for players, mobs, modded entities and Blockbench model actors.
- Video effects: picture transitions on cuts (cross dissolve from the frozen last frame of the outgoing shot,
  fade through black or white, wipe and slide in four directions, zoom blur, circle iris) with a duration, set
  from the Cameras tab and marked on the cut lane; an Effects tab with a filter stack (exposure and brightness,
  contrast, saturation, temperature and tint, hue shift, vignette, film grain, sharpen, blur, black and white,
  sepia, chromatic aberration, letterbox, LUT from `justreplay/luts/`), global or bound to a camera, keyframes
  on every parameter at the play head, presets (Cinematic, Vintage, Noir, Warm, Cold), and fades at the start
  and end of the export range. Rendered on the GPU over the finished world (after shader packs) and before the
  HUD, so the preview and the export match.
- Audio crossfades: overlapping clips of a track crossfade with an equal power curve (switch per track), and
  clip fades can be linear, equal power or exponential.

#### Changed
- Recording costs less on the client thread: no allocation per tick for an idle player, chunk building on a
  small time budget that pauses while frames are slow, voice frames handed over in one batch per tick, one
  reused encoding buffer per thread, writer threads at low priority. A recording and the instant replay share
  one capture, so packets are encoded once.
- Fewer bytes written to the drive: checkpoints every 5 minutes at maximum compression, `meta.json` rewritten
  once a minute, stream flushed every 30 seconds only when something new was written, cached chunks written
  once per recording.

#### Fixed
- Signed player chat no longer breaks replays (expired keys, seeking back, manual recordings).
- Mod GUIs and disconnect requests sent by recorded packets are ignored during playback; the editor stays open across seeks.
- The camera stays a spectator even when the recorded tab list contains the viewer.
- Mid-session recordings keep paintings, item frames, falling blocks and modded entities (original spawn packets are reused).
- The recording player respawns correctly after death and stays seated in vehicles; singleplayer pauses are not recorded.
- Video export never freezes the game when FFmpeg stops, Esc cancels it, screens cannot appear in the video, and exports never overwrite an earlier file.
- Other mods' HUDs are hidden while watching a replay.
- Playback keys only act inside a replay, so they no longer clash with other mods' keys in a normal game.
- Modded entities used as actors no longer run their own AI; an actor that cannot exist client side is reported once instead of crashing.
- Settings screen in the mod list; replay sizes shown in KB or MB.
- Playback speeds x2, x4 and x8 now work.
- Replays recorded on a server keep the server's block, item and entity ids, so they show the right content.
- Long replays no longer load entirely into memory: recordings are stored in five second segments read on demand (older replays still open).
- Recordings survive a game crash (the stream is flushed and the duration saved every 10 seconds); damaged replays open up to the damage.
- Editor: clicking a keyframe no longer moves it, markers can be clicked to jump, clearing the path and deleting an actor ask for confirmation, new actors appear in the list at once.
- Player actors get their skin, the camera field of view matches the keyframes, and a failed replay start no longer blocks later ones.

## Français

### [Non publié]

### [1.0.11] - 2026-11-04

#### Corrigé
- **Armourer's Workshop** : les tenues ne s'affichaient toujours pas dans les replays. Armourer's Workshop découpe ses
  paquets sur un fil d'exécution à lui : les skins et garde-robes écrits au début d'un enregistrement revenaient trop
  tard et n'arrivaient jamais dans le fichier, alors que le journal les comptait. Ils sont maintenant écrits tout de
  suite, au même format. Les enregistrements faits en 1.0.9 ou 1.0.10 n'ont pas de tenues : enregistrez à nouveau.

### [1.0.10] - 2026-11-03

#### Corrigé
- **Armourer's Workshop** : les replays solo affichent désormais les tenues. En solo, les skins sont lus directement
  dans la base de skins du monde et ne passent jamais par la connexion : la 1.0.9 n'enregistrait que les skins
  détenus par le client au début de l'enregistrement, et le monde du replay cherchait les autres dans sa propre base
  vide. Les skins sont maintenant enregistrés quelle que soit leur source, les instantanés ajoutent chaque skin porté
  par une entité enregistrée, et pendant un replay chaque recherche est servie depuis l'enregistrement avant
  d'atteindre la base du monde du replay. Le journal indique combien de skins chaque instantané a écrits et avertit
  pour un skin porté absent de l'enregistrement. Les enregistrements solo faits avec la 1.0.9 peuvent rester sans
  certains skins.

### [1.0.9] - 2026-11-03

#### Ajouté
- **Epic Fight** : les animations et le mode combat des joueurs et des mobs pris en charge sont rejoués, ceux du
  joueur enregistré sur son acteur. Un enregistrement lancé en plein combat montre l'attaque en cours, les sauts dans
  le temps reprennent les animations au bon moment, et elles se figent en pause et suivent la vitesse du replay.
- **Armourer's Workshop** : les tenues et objets personnalisés de chaque joueur, acteur du joueur enregistré compris,
  portés avant ou pendant l'enregistrement, après un saut dans le temps et dans une session ultérieure. Les skins
  sont sauvegardés dans l'enregistrement.
- **Custom NPCs** (portage NeoForge non officiel) : les PNJ sont rejoués avec l'apparence qu'ils avaient (modèle,
  skin, taille, nom, objets tenus), même présents avant l'enregistrement ou modifiés pendant. Les dialogues et
  écrans de PNJ du joueur enregistré ne s'ouvrent pas pendant un replay.
- **Pehkui** : les tailles des entités, réglées avant ou pendant l'enregistrement, celle du joueur enregistré sur
  son acteur, et les changements progressifs après un saut dans le temps et à toute vitesse.

#### Corrigé
- La taille des entités et les autres attributs synchronisés (`/attribute` vanilla, vitesse de déplacement...)
  étaient perdus : ils sont sauvegardés avec chaque entité au début d'un enregistrement et aux points de reprise,
  et ceux du joueur enregistré sont suivis.
- Rejoindre un monde transformé avec Morph puis enregistrer montrait le joueur enregistré sous forme de joueur.
- Les cordes de Simulated (Create Aeronautics) attachées avant un enregistrement ou un point de reprise manquaient
  au replay.
- Pendant un replay, les joueurs en mode spectateur étaient visibles (la caméra du replay est elle-même spectatrice),
  et l'inventaire, les conteneurs et les progrès pouvaient s'ouvrir alors qu'ils n'appartiennent à personne.

### [1.0.8] - 2026-11-01

#### Ajouté
- Dupliquer un acteur : un bouton Dupliquer dans le panneau des acteurs copie l'acteur choisi avec son
  apparence, son skin, son modèle, son échelle, ses parties cachées, ses changements de skin, ses keyframes
  et ses actions, un bloc à côté, et sélectionne la copie.
- Image fixe : un bouton de la fenêtre d'export rend l'image de la tête de lecture à la taille d'export,
  avec effets, bandes noires et overlays, et l'enregistre en `exports/<nom>_still_<tick>.png` sans écraser
  les précédentes.
- Insérer du temps et Retirer du temps dans l'onglet Scène : un montage en décalage à la tête de lecture qui
  repousse tout ce qui suit (keyframes de caméra, keyframes et actions d'acteurs, marqueurs, coupes, clips
  audio, keyframes d'effets et d'overlays, temps des overlays, keyframes du monde, modifications d'entités),
  ou supprime une plage et ramène le reste plus tôt. L'enregistrement lui-même ne bouge pas.
- **Tremblement de caméra** : chaque caméra a une intensité de tremblement façon caméra à l'épaule (de désactivé
  à 100 %) et une vitesse. Le mouvement de la position (quelques centimètres) et de la rotation (un ou deux degrés)
  est une fonction douce du temps du replay propre à chaque caméra : une image est identique dans l'aperçu, dans
  les exports et après un saut.
- **Cible de visée** : les caméras fixes et les chemins peuvent viser un acteur ou une entité enregistrée
  ("Look at", "Clear aim"). Le lacet et le tangage suivent alors les yeux de la cible à chaque image, le roulis et
  le FOV gardent leurs keyframes ; si la cible est introuvable, la rotation des keyframes est utilisée.
- **Prise en charge de Morph V2** : les joueurs transformés avec Morph sont rejoués sous cette forme, acteur du
  joueur enregistré compris, avec leurs transformations et retours à la forme humaine. Un enregistrement démarré
  sous une forme et les sauts dans le temps montrent la bonne forme, une transformation coupée par un saut reprend
  au bon moment, et la forme reste figée quand le replay est en pause et suit sa vitesse. Les écrans de Morph et
  les données d'interface du joueur enregistré ne sont pas rejoués.
- **Sable et Create Aeronautics** : les dirigeables et autres sous-niveaux de Sable bougent maintenant dans les
  replays au lieu de rester figés ou d'être vides. Leurs poses, envoyées hors de la connexion (appel direct en solo,
  canal UDP en multijoueur), sont enregistrées et rendues à l'interpolation de Sable à leur tick ; les instantanés
  et points de reprise écrivent chaque sous-niveau, pour les enregistrements démarrés en plein vol et les sauts dans
  le temps ; les chunks des sous-niveaux ne sont plus refusés comme trop lointains ; le serveur du monde de replay
  ne peut plus envoyer ses propres poses. Voir `docs/COMPATIBILITY.md`.

#### Modifié
- **Action EMOTE** : accepte une vitesse comme *Custom emote* (`nom|vitesse`), trouve une emote par son nom de
  fichier en plus de son nom affiché ou de son UUID (`palm` pour le Face palm d'Emotecraft), et suit l'horloge du
  replay image par image : elle reste synchronisée au ralenti, après un saut dans le temps et dans les exports.
- **Action POINT** : pointe toujours droit devant, dans la direction de l'acteur ; avec `camera` ou un nom d'acteur
  en paramètre, la tête et le bras visent la cible.
- **Action DANCE** : les acteurs non humanoïdes (creepers, animaux...) rebondissent sur le temps et se balancent, et
  sautent pour les styles sautés, au lieu de rester immobiles.
- **Tâches de scène** : le `delay` d'une étape `record` compte les ticks serveur depuis l'exécution des commandes,
  donc les événements qu'elles programment tombent au même tick du replay à chaque prise. `meta.json` reçoit
  `startGameTime` et `sceneCommandsTick` pour caler les événements sans mesurer. Les `shots` jouent la seconde qui
  précède chaque tick, pour que les mobs vivants et les particules s'affichent comme dans un export.

#### Corrigé
- Les emotes Emotecraft jouées avec *Custom emote* (JSON et `.emotecraft`) enfonçaient les jambes dans le sol et
  détachaient les bras des épaules : leurs positions de membres indiquent où est la partie, pas de combien elle a
  bougé, et étaient comptées deux fois (laforetbrut/mod-just-replay#2).
- Un acteur Ender Dragon ne tournait jamais sur place et ne battait jamais des ailes : son cap, son cou et sa queue
  suivent maintenant les clés d'orientation de l'acteur, et ses ailes battent, plus vite quand il fait du surplace.
- Après le début d'un replay ou un saut dans le temps, la caméra du replay restait rattachée au monde d'avant : les
  mods qui passent par le joueur pour trouver le monde (Sable qui crée ses dirigeables) agissaient sur un monde qui
  n'était plus affiché.

### [1.0.7] - 2026-11-01

#### Corrigé
- Un replay pouvait se fermer juste après son ouverture dans les gros modpacks (vu avec KubeJS et environ 440
  mods) : les identifiants de registre enregistrés avec la capture laissaient sans identifiant les entrées qu'ils
  ne listaient pas, et le serveur du monde de replay n'arrivait plus à envoyer les recettes. Les entrées absentes
  d'un enregistrement gardent maintenant un identifiant, et le serveur du monde de replay n'envoie plus de recettes
  pendant un replay.

### [1.0.6] - 2026-10-28

#### Ajouté
- **Début et fin des actions** : l'onglet Actions de l'acteur règle un tick de début et un tick de fin sur la
  timeline pour chaque action, avec les boutons "Start = play head" et "End = play head" ; une fin vide dure jusqu'à
  l'action suivante. La liste affiche chaque action sous la forme début → fin.
- **Extrait d'emote** : les emotes personnalisées et les emotes Emotecraft peuvent ne jouer qu'une partie de leur
  animation ("Emote from" et "Emote to", en ticks d'emote). Une emote personnalisée en boucle boucle dans l'extrait ;
  une emote Emotecraft commence au début de l'extrait et s'arrête à sa fin.
- **Changements de skin datés** : un acteur joueur ou un joueur enregistré peut changer de skin à un tick choisi
  ("Skin changes" dans l'écran de l'acteur et dans l'onglet Entités, "Add at cursor"), pour des scènes mises en
  scène comme le même skin couvert de sang à partir du tick 120. Le changement apparaît en lecture et dans les
  exports vidéo. "Save current skin" enregistre le skin affiché dans le dossier des skins en un nouveau PNG, prêt à
  être peint dans n'importe quel éditeur d'image.
- **Surcouche du skin et parties du corps** : les acteurs joueurs affichent désormais la couche extérieure de leur
  skin (chapeau, veste, manches, pantalon), avec un interrupteur pour la retirer ; les joueurs enregistrés ont le
  même interrupteur dans l'onglet Entités. Chaque acteur joueur ou joueur enregistré peut aussi masquer sa tête, son
  corps, son bras ou sa jambe gauche ou droite ; un bras masqué emporte l'objet qu'il tient.
- **Modifier les entités enregistrées** : un nouvel onglet **Entités** (modes Simple et Avancé) liste les joueurs
  et les mobs enregistrés près de la caméra et en sélectionne un au viseur. Le masquer pour tout le replay, à partir
  de la tête de lecture ou jusqu'à elle (modèle, nom, ombre, feu, objets tenus, et ses sons) ; le déplacer à un
  nouvel endroit à la tête de lecture avec le viseur (images clés de décalage, tourné à la molette) ; régler le
  volume de ses propres sons de 0 à 400 % ou les couper. L'entité sélectionnée est surlignée tant que l'éditeur est
  ouvert. Les modifications s'appliquent à la lecture, aux aperçus et aux exports vidéo ; la physique et les
  mouvements enregistrés ne changent pas. Un son appartient à une entité quand il la suit ou démarre à moins de
  0,75 bloc d'elle ; les entités masquées ne sont plus sélectionnables pour le mode spectateur.
- **Types de sons** dans l'onglet Audio de l'éditeur : le volume de chaque type de son du jeu, de 0 à 400 %,
  dans le replay et dans les exports (pas, joueurs, blocs, monstres, animaux, ambiance, météo, jukebox et blocs
  musicaux).
- **Emotes personnalisées** pour les acteurs, sans Emotecraft : déposer des animations Blockbench Bedrock (chaque
  animation est une emote, images clés linéaires, en escalier et catmullrom) ou des emotes Emotecraft JSON (le pli
  et l'axe ne sont pas joués) dans `justreplay/emotes/`, puis les choisir dans la nouvelle action *Custom emote*
  ("|vitesse" en option). Les fichiers sont relus quand ils changent ; un fichier cassé est signalé une fois. Un
  fichier d'exemple avec un salut et une révérence est créé au premier usage.
  Les emotes Emotecraft binaires (`*.emotecraft`) sont lues aussi, avec leur nom, et la partie Emotecraft `body`
  déplace et tourne maintenant tout l'acteur (un headspin se joue tête en bas) ; les nouveaux fichiers apparaissent
  dans la liste pendant qu'elle est ouverte, et les fichiers cassés sont listés avec leur raison.
- **Météo et heure** : nouvel onglet **World** dans l'éditeur (modes Simple et Avancé). Météo enregistrée,
  dégagée, pluie ou orage, heure fixe avec préréglages, keyframes de l'heure et de la force de la pluie et de
  l'orage, heure figée et phase de la lune. Identique dans les aperçus et les exports. Désactivé par défaut.
- **ParCool et Better Combat** : les actions de parkour du joueur enregistré (saut d'obstacle, roulade, esquive,
  course sur les murs, ramper, s'agripper...) et ses animations d'attaque Better Combat sont jouées par son acteur
  dans le replay, comme les autres clients les voient ; celles des autres joueurs étaient déjà dans les paquets
  enregistrés. Les points de reprise contiennent les actions ParCool en cours de chaque joueur, un saut dans le
  temps tombe donc dans la bonne action ; une attaque déjà finie à la cible d'un saut est ignorée. Les requêtes
  des deux mods ne sont pas envoyées à l'hôte du replay.
- Les paquets enregistrés d'un mod absent au moment de la lecture sont ignorés sans bruit (une ligne dans le
  journal par type de paquet) au lieu d'un avertissement par paquet.
- **Bouton de replay instantané** : le menu pause a un bouton "Save last N min" à côté de "Start recording",
  qui enregistre la mémoire tampon du replay instantané comme sa touche.
- **Transformation précise et échelle** : l'écran d'acteur a une section "Transformation" avec X, Y, Z, lacet et
  tangage saisis en nombres (pas de 0,1 bloc et de 5 degrés) pour l'image clé à la tête de lecture, créée si besoin.
  L'onglet Entités reçoit les mêmes champs pour le décalage d'une entité enregistrée, avec sa position absolue.
  Acteurs et entités enregistrées ont aussi une échelle (0,05 à 10), appliquée depuis les pieds, avec l'étiquette
  de nom et l'ombre. Pour les acteurs modèles, elle multiplie l'échelle du modèle.

#### Corrigé
- Les pas étaient presque muets dans les vidéos exportées : le jeu joue un pas à 15 % de son volume et l'export
  l'atténue avec la distance à la caméra, si bien qu'avec une caméra à quelques blocs on ne l'entendait pas. Les
  pas sont maintenant joués à 200 % par défaut dans les replays et les exports, réglables dans Types de sons.
- Les pas du joueur enregistré manquaient encore : le joueur local joue ses sons d'entité (pas, atterrissage,
  nage, nourriture) par son propre chemin, que l'enregistreur n'entendait pas. Ils sont maintenant enregistrés.
- Un clic sur un joueur ou un mob enregistré dans la vue de l'éditeur le sélectionne et ouvre l'onglet Entités.

### [1.0.5] - 2026-10-27

#### Corrigé
- Les sons du joueur enregistré reviennent dans les replays et les exports vidéo : pas, blocs posés et cassés,
  nourriture, coups. Le serveur n'envoie jamais à un joueur les sons qu'il provoque (le jeu les joue en local),
  l'enregistreur les capte donc côté client, sous la forme des paquets reçus par tous les autres joueurs.
- Distant Horizons : le terrain lointain (LOD) du monde enregistré s'affiche maintenant dans les replays et les
  vidéos exportées au lieu d'être coupé. Les enregistrements notent où DH garde les LOD de chaque dimension
  visitée ; l'ouverture du replay copie une fois ces bases dans un dossier temporaire que DH lit en lecture seule,
  donc les LOD d'origine ne sont jamais ouverts ni modifiés, et la copie est supprimée à la fermeture du replay. La
  génération lointaine reste coupée. Nouvelles options `playback.distantHorizonsLods` (activée par défaut) et
  `playback.distantHorizonsMaxCopyMb`. Les enregistrements antérieurs se lisent sans LOD, comme avant.
- Distant Horizons pouvait rester désactivé après un replay terminé par un plantage : Just Replay coupait le rendu
  de DH par son API pendant les replays, et DH enregistre ces valeurs dans son fichier de configuration. Le rendu
  n'est plus touché ; le réglage de génération lointaine modifié pendant un replay est noté d'abord et remis au
  démarrage suivant si le replay ne s'est pas terminé normalement. Les joueurs déjà touchés peuvent réactiver le
  rendu de DH dans ses réglages.
- Un export vidéo court pouvait échouer à la toute fin : quand le jeu rendait plus vite que FFmpeg n'encodait,
  l'exporteur prenait la file d'images pleine pour un encodeur bloqué et arrêtait FFmpeg. Il attend maintenant les
  dernières images.

### [1.0.4] - 2026-10-16

#### Ajouté
- Tâches de scène pour les outils qui filment un mod : `-Djustreplay.job=<fichier.json>` (ou
  `./gradlew runClient -Pjob=...`) déroule une liste d'étapes en un seul lancement (enregistrer un monde, ouvrir
  un replay, prendre des captures, exporter, quitter), chaque export avec sa propre résolution ou taille exacte,
  ses images par seconde, sa qualité et une sortie MP4 ou séquence PNG. Chaque étape est écrite dans le log avec
  le préfixe `SCENE`. Les propriétés `justreplay.scene*` marchent comme avant. Voir le README, "Tâches de scène".

#### Modifié
- La liste des mods affiche le logo de Just Replay.

### [1.0.3] - 2026-10-15

#### Ajouté
- Compatibilité optionnelle avec Drop the Beam : la musique, les cues de la console lumière et les écrans vidéo
  suivent l'horloge du replay (pause, vitesse, sauts dans le temps). Un enregistrement démarré en cours de
  partie contient l'état des platines, des mixages et des shows. La musique se tait quand le replay est en pause
  ou hors x1, suit le volume des sons du jeu, leur coupure et l'atténuation du projet, et figure dans l'audio des
  exports vidéo, placée par rapport à la caméra. Le tick de début de chaque morceau est écrit dans le journal et
  disponible pour l'éditeur.
- Action d'acteur **Danse** : les acteurs humanoïdes dansent sur une grille de temps (« style|bpm|tick d'un
  temps ») dans dix styles (rebond, poing levé, mains en l'air, vague, shuffle, headbang, saut, DJ, hakken, jumpstyle), fluides
  entre les ticks et calés sur le temps dans les exports, avec une énergie et un timing un peu différents par
  acteur pour qu'une foule ne bouge jamais d'un seul bloc.
- Les acteurs joueurs peuvent porter un fichier de skin : déposer des skins PNG (64x64)
  dans `.minecraft/justreplay/skins/` et en choisir un dans l'écran de l'acteur, avec un interrupteur
  "Slim arms" pour le modèle des bras. Aucune requête Mojang n'est faite pour un tel acteur. Un fichier absent ou
  illisible écrit un seul avertissement et l'acteur garde le skin par défaut.

#### Corrigé
- Avec les écrans caméra de Drop the Beam en vue, un replay pouvait planter dès la première image ou n'afficher
  aucun terrain : le rendu du monde est remis sur sa grille de sections actuelle quand une autre vue l'a laissé
  sur une grille libérée.
- L'étalonnage ne transforme plus les zones les plus sombres en bruit vif quand le contraste ou la saturation
  sont combinés au filtre de température.
- Les acteurs joueurs dont « Afficher le nom » est désactivé n'affichent plus leur nom.
- Les projets avec beaucoup d'acteurs joueurs nommés ne voient plus leurs requêtes de skin refusées par Mojang
  (HTTP 429) : les requêtes passent dans une file, deux par tick, et une requête échouée est retentée une fois
  après cinq secondes. Les acteurs portent le skin par défaut en attendant leur tour.

### [1.0.2] - 2026-10-15

#### Corrigé
- Un acteur assis ou couché dans un lit après un « Marcher vers » ou un « Suivre » ne se retrouve plus à côté
  du siège ou du lit : un point clé de placement met fin aux déplacements et au suivi commencés avant lui, et
  un acteur assis n'est plus collé sur un dossier, une table ou un plafond au-dessus du siège.

### [1.0.1] - 2026-10-15

#### Ajouté
- Textes et images dans le monde : un nouvel onglet **Overlays**, en mode Simple et Avancé, ajoute des titres,
  des étiquettes et des stickers ancrés à une entité (le joueur enregistré, un acteur ou une entité
  enregistrée, suivie en douceur et retrouvée après un saut dans le temps), à la face d'un bloc ou à une
  position fixe, placés avec le viseur. Ils font face à la caméra, gardent un lacet, un tangage et un roulis
  fixes ou se posent à plat sur le bloc, s'affichent entre deux ticks avec fondu d'entrée et de sortie, et
  peuvent se voir à travers les murs. Textes : plusieurs lignes, codes `&` de couleur et de style, taille,
  couleur, plaque de verre arrondie (couleur, opacité, marge), ombre ou contour, alignement. Images : fichiers
  PNG ou JPG (la première image d'un GIF) de `justreplay/images/`, largeur en blocs, proportions conservées ou
  non, teinte ; chaque fichier est chargé une fois et libéré à la fin du replay, et un fichier illisible
  prévient une seule fois. Le décalage, l'échelle, la rotation et l'opacité prennent des images clés à la tête
  de lecture. Une piste Overlays sur la timeline montre chaque plage visible comme une barre qui se déplace à la
  souris, et dont les bords changent le début et la fin. Les overlays sont rendus en pleine lumière dans
  l'aperçu de l'éditeur, pendant la lecture et dans les exports vidéo, shader packs compris.

### [1.0.0] - 2026-10-15

#### Ajouté
- Enregistrement de tous les paquets reçus par le client, y compris ceux des mods, avec les mouvements,
  la posture, l'équipement et les coups du joueur local. Démarrage manuel (F8, menu pause) avec un
  instantané du monde, ou automatique à la connexion.
- Lecture dans un monde vide temporaire : caméra libre, pause, vitesses x0,1 à x8, saut dans les deux
  sens, retour au joueur enregistré.
- Liste des replays depuis l'écran titre : ouvrir, renommer, supprimer, ouvrir le dossier.
- Éditeur : timeline, marqueurs, keyframes de caméra avec roulis et FOV, chemin Catmull-Rom, keyframes
  déplaçables.
- Acteurs : joueurs avec n'importe quel skin et n'importe quel type d'entité, équipement, posture,
  visibilité, mouvement par keyframes.
- Actions d'acteurs sur la timeline : marche et course, coups, attaques (animations d'attaque des mobs
  comprises), dégâts, mort, saut, accroupi, assis, sommeil, nage, élytres, attaque tournoyante, utilisation
  d'objet, bouclier, arc, manger, gestes, regarder un acteur ou la caméra, regarder autour, se promener. Une
  recherche dans le temps montre la même pose que la lecture.
- Acteurs modèles Blockbench : fichiers `.bbmodel` du dossier `justreplay/models/` avec leurs textures et
  animations, dessinés dans le monde (shaders et export vidéo compris). Un modèle d'exemple est fourni.
- Export vidéo MP4 via FFmpeg avec un pas de temps fixe, avec une piste audio : les sons du replay sont relevés pendant l'export et mixés en stéréo selon la position de la caméra, synchronisés à chaque image.
- Formes de chemin de caméra : Courbe (passe par chaque keyframe), Bézier (angles arrondis, le plus fluide) et Linéaire.
- Chemins de caméra à vitesse constante avec départ et arrêt en douceur : seuls les temps du premier et du dernier keyframe comptent.
- Le chemin de caméra et ses keyframes sont dessinés dans le monde pendant l'édition.
- Montage multi-caméra : autant de caméras nommées que voulu, chacune fixe (un seul placement), chemin
  (keyframes avec sa propre courbe, son timing et son lissage) ou suivi (suit une entité de dos, de côté, de
  face ou en orbite, avec lissage). Une piste de coupes indique quelle caméra est active à partir de quel tick,
  en coupe franche ou en fondu de mouvement ; les touches 1 à 9 coupent vers la caméra 1 à 9 à la tête de
  lecture, même pendant la lecture. L'aperçu (O) et l'export suivent la piste de coupes. Les anciens projets
  s'ouvrent avec leur chemin de caméra comme première caméra.
- Auto-test de développement (`./gradlew runClient -Pselftest`).
- Les chunks que le client garde au-delà de la distance de vue du serveur (par exemple en cache avec Bobby) sont aussi enregistrés, quelques-uns par tick.
- Les replays gardent les chunks chargés jusqu'à la distance de vue de lecture (32 par défaut) au lieu de les décharger quand le joueur enregistré s'éloigne.
- Éditeur audio : un onglet Audio et des pistes audio sur la timeline pour les clips musicaux et le sound
  design. Les fichiers OGG et WAV de `justreplay/audio/` se placent sur autant de pistes que voulu (volume,
  muet, solo) sous forme de clips que l'on déplace, coupe par les bords, scinde à la tête de lecture (S),
  duplique (Ctrl + D), avec fondus d'entrée et de sortie, boucle et vitesse, aperçu de la forme d'onde et
  aimantation aux marqueurs, coupes, tête de lecture et autres clips. Les clips suivent la lecture (pause,
  vitesses, sauts) et sont mixés à l'échantillon près dans les exports vidéo, avec un volume des sons du jeu,
  un bouton pour les couper et un ducking sous les clips.
- Prise en charge de Simple Voice Chat : les voix entendues et votre micro sont enregistrés dans `voice.bin.gz`, rejoués à la position de la personne qui parle en suivant l'horloge du replay et mixés dans les exports vidéo ; un bouton Voix dans l'éditeur les coupe.
- Interface en verre : tous les écrans, le HUD et les boutons des menus sont redessinés dans un style de verre
  clair aux coins arrondis, avec des icônes nettes à toutes les échelles d'interface et des animations
  fluides. L'éditeur a une barre du haut (nom du replay, état de la sauvegarde, réglages, aide), un panneau
  latéral repliable avec les onglets Caméras, Acteurs, Scène et Export qui défilent sur les petites fenêtres,
  et un dock en bas avec les commandes de lecture en icônes, un choix de vitesse et une timeline à pistes
  (coupes, keyframes de caméra, keyframes et actions d'acteurs, marqueurs) qui zoome avec Ctrl + molette et
  défile. F1 ou ? affiche les raccourcis, lus depuis les touches configurées. La liste des replays montre des
  cartes avec recherche et tri par date, nom ou taille ; l'écran d'export a des sélecteurs de résolution et
  d'images par seconde et un curseur de qualité.
- Replay instantané : avec `recording.replayBuffer` activé, les dernières minutes de jeu (`bufferMinutes`, 5 par défaut) restent en mémoire, compressées, et F7 les enregistre comme un replay normal. Rien n'est écrit sur le disque avant la sauvegarde.
- Touche "Enregistrer les prochaines minutes" (sans touche par défaut) : enregistre pendant `recording.nextMinutes` puis s'arrête seule.
- Nouveaux réglages `recording.checkpointMinutes` (5 par défaut) et `recording.extraChunkRadius` (16 par défaut).
- Modes Simple et Avancé de l'éditeur, choisis dans la barre du haut et mémorisés (`editor.mode`, Simple par
  défaut). Simple garde l'essentiel avec de grands boutons et des mots simples : lecture, déplacement et
  vitesse, "Ajouter un point de caméra ici", trajet de caméra activé ou non, douceur Courbe ou Bézier à vitesse
  constante, personnages (un skin de joueur ou un mob) placés dans le monde qui marchent vers un point, suivent
  quelqu'un, s'assoient, se couchent dans un lit ou saluent, marqueurs, et un export en un clic. Avancé est
  l'éditeur complet. Changer de mode garde tout le travail.
- Placement des acteurs dans le monde : ajouter ou déplacer un acteur le montre en direct sous le viseur
  (caméra libre, en pause ou non) ; la molette le tourne (Maj : finement, clic droit : quart de tour), Ctrl le
  centre sur le bloc, un clic écrit une keyframe à la tête de lecture et Échap annule. "S'asseoir ici" l'assoit
  sur le bloc cliqué à la bonne hauteur (escaliers sur leur marche basse, dalles, tapis et chaises de mods sur
  le haut de leur forme de collision, blocs pleins sur le dessus), "Se coucher" le couche dans le lit cliqué,
  "Marcher vers" choisit une destination. "Aller au viseur" et "Regarder la caméra" agissent sur l'acteur
  sélectionné, et cliquer sur un acteur dans le monde le sélectionne quand l'éditeur est ouvert. Fonctionne
  pour les joueurs, les mobs, les entités de mods et les modèles Blockbench.
- Effets vidéo : transitions d'image sur les coupes (fondu enchaîné depuis la dernière image figée du plan
  sortant, fondu au noir ou au blanc, volet et glissement dans quatre directions, zoom flou, iris) avec une
  durée, réglées depuis l'onglet Caméras et marquées sur la piste des coupes ; un onglet Effets avec une pile
  de filtres (exposition et luminosité, contraste, saturation, température et teinte, rotation de teinte,
  vignette, grain, netteté, flou, noir et blanc, sépia, aberration chromatique, bandes, LUT depuis
  `justreplay/luts/`), globale ou liée à une caméra, des images clés sur chaque paramètre à la tête de lecture,
  des préréglages (Cinematic, Vintage, Noir, Warm, Cold), et des fondus au début et à la fin de l'export. Rendus
  sur le GPU sur le monde terminé (après les shader packs) et avant le HUD, l'aperçu et l'export sont identiques.
- Fondus enchaînés audio : les clips qui se chevauchent sur une piste font un fondu à puissance constante
  (réglable par piste), et les fondus des clips peuvent être linéaires, à puissance constante ou exponentiels.

#### Modifié
- L'enregistrement coûte moins sur le thread client : aucune allocation par tick pour un joueur immobile,
  construction des chunks sur un petit budget de temps mis en pause quand les images ralentissent, voix
  transmises en un seul lot par tick, un tampon d'encodage réutilisé par thread, threads d'écriture en basse
  priorité. Un enregistrement et le replay instantané partagent une seule capture : chaque paquet est encodé
  une fois.
- Moins d'octets écrits sur le disque : points de reprise toutes les 5 minutes en compression maximale,
  `meta.json` réécrit une fois par minute, flux vidé toutes les 30 secondes seulement s'il y a du nouveau,
  chunks en cache écrits une fois par enregistrement.

#### Corrigé
- Le chat signé des joueurs ne casse plus les replays (clés expirées, retour en arrière, enregistrements manuels).
- Les interfaces de mods et les demandes de déconnexion venant des paquets enregistrés sont ignorées pendant la lecture ; l'éditeur reste ouvert lors des sauts.
- La caméra reste en spectateur même si la liste des joueurs enregistrée contient le spectateur.
- Les enregistrements lancés en cours de partie gardent les tableaux, cadres, blocs qui tombent et entités moddées (les paquets d'apparition d'origine sont réutilisés).
- Le joueur enregistré réapparaît correctement après une mort et reste assis dans les véhicules ; les pauses en solo ne sont plus enregistrées.
- L'export vidéo ne fige plus le jeu si FFmpeg s'arrête, Échap l'annule, aucun écran ne peut apparaître dans la vidéo, et un export n'écrase jamais un fichier existant.
- Les HUD des autres mods sont masqués pendant la lecture d'un replay.
- Les touches de lecture n'agissent que dans un replay et n'entrent plus en conflit avec celles des autres mods en jeu normal.
- Les entités moddées utilisées comme acteurs n'exécutent plus leur propre IA ; un acteur impossible à créer côté client est signalé une fois au lieu de faire planter le jeu.
- Écran de réglages dans la liste des mods ; taille des replays affichée en Ko ou Mo.
- Les vitesses x2, x4 et x8 fonctionnent.
- Les replays enregistrés sur un serveur gardent les identifiants de blocs, objets et entités du serveur, et affichent donc le bon contenu.
- Les longs replays ne sont plus chargés entièrement en mémoire : les enregistrements sont découpés en segments de cinq secondes lus à la demande (les anciens replays s'ouvrent toujours).
- Les enregistrements survivent à un crash du jeu (flux vidé et durée sauvegardée toutes les 10 secondes) ; un replay endommagé s'ouvre jusqu'à l'endroit abîmé.
- Éditeur : cliquer un keyframe ne le déplace plus, un clic sur un marqueur y saute, vider le chemin et supprimer un acteur demandent confirmation, les nouveaux acteurs apparaissent tout de suite dans la liste.
- Les acteurs joueurs ont leur skin, le champ de vision de la caméra correspond aux keyframes, et un échec d'ouverture de replay ne bloque plus les suivants.
