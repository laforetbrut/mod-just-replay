# Changelog

## English

### [Unreleased]

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
