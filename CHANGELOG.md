# Changelog

## English

### [Unreleased]

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
