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
- Video export to MP4 through FFmpeg with a fixed time step.
- Camera path shapes: Curve (through every keyframe), Bezier (rounded corners, smoothest) and Linear.
- Constant speed camera paths with optional smooth start and stop: only the first and last keyframe times matter.
- The camera path and its keyframes are drawn in the world while editing.
- Development self test (`./gradlew runClient -Pselftest`).

#### Fixed
- Signed player chat no longer breaks replays (expired keys, seeking back, manual recordings).
- Mod GUIs and disconnect requests sent by recorded packets are ignored during playback; the editor stays open across seeks.
- The camera stays a spectator even when the recorded tab list contains the viewer.
- Mid-session recordings keep paintings, item frames, falling blocks and modded entities (original spawn packets are reused).
- The recording player respawns correctly after death and stays seated in vehicles; singleplayer pauses are not recorded.
- Video export never freezes the game when FFmpeg stops, Esc cancels it, screens cannot appear in the video, and exports never overwrite an earlier file.
- Other mods' HUDs are hidden while watching a replay.
- Playback speeds x2, x4 and x8 now work.
- Replays recorded on a server keep the server's block, item and entity ids, so they show the right content.
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
- Export vidéo MP4 via FFmpeg avec un pas de temps fixe.
- Formes de chemin de caméra : Courbe (passe par chaque keyframe), Bézier (angles arrondis, le plus fluide) et Linéaire.
- Chemins de caméra à vitesse constante avec départ et arrêt en douceur : seuls les temps du premier et du dernier keyframe comptent.
- Le chemin de caméra et ses keyframes sont dessinés dans le monde pendant l'édition.
- Auto-test de développement (`./gradlew runClient -Pselftest`).

#### Corrigé
- Le chat signé des joueurs ne casse plus les replays (clés expirées, retour en arrière, enregistrements manuels).
- Les interfaces de mods et les demandes de déconnexion venant des paquets enregistrés sont ignorées pendant la lecture ; l'éditeur reste ouvert lors des sauts.
- La caméra reste en spectateur même si la liste des joueurs enregistrée contient le spectateur.
- Les enregistrements lancés en cours de partie gardent les tableaux, cadres, blocs qui tombent et entités moddées (les paquets d'apparition d'origine sont réutilisés).
- Le joueur enregistré réapparaît correctement après une mort et reste assis dans les véhicules ; les pauses en solo ne sont plus enregistrées.
- L'export vidéo ne fige plus le jeu si FFmpeg s'arrête, Échap l'annule, aucun écran ne peut apparaître dans la vidéo, et un export n'écrase jamais un fichier existant.
- Les HUD des autres mods sont masqués pendant la lecture d'un replay.
- Les vitesses x2, x4 et x8 fonctionnent.
- Les replays enregistrés sur un serveur gardent les identifiants de blocs, objets et entités du serveur, et affichent donc le bon contenu.
- Les enregistrements survivent à un crash du jeu (flux vidé et durée sauvegardée toutes les 10 secondes) ; un replay endommagé s'ouvre jusqu'à l'endroit abîmé.
- Éditeur : cliquer un keyframe ne le déplace plus, un clic sur un marqueur y saute, vider le chemin et supprimer un acteur demandent confirmation, les nouveaux acteurs apparaissent tout de suite dans la liste.
- Les acteurs joueurs ont leur skin, le champ de vision de la caméra correspond aux keyframes, et un échec d'ouverture de replay ne bloque plus les suivants.
