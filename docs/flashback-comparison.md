# Flashback et Just Replay : comparaison des fonctionnalités

## English

Feature comparison between Just Replay 1.1.2 and Flashback 0.43.6 (another replay mod), written from
Flashback's public README, Modrinth page, release notes and public issues only, never its source code. The full
document is in French below: feature table with sources, compatibility problems reported against Flashback and
Just Replay's exposure to them, prioritised gaps, and the gaps picked for the next update.

## Français

### Méthode et sources

- **Flashback** : [README GitHub](https://github.com/Moulberry/Flashback), [page Modrinth](https://modrinth.com/mod/flashback)
  et sa [galerie](https://modrinth.com/mod/flashback/gallery), les notes de version 0.1.0 à 0.43.6
  ([liste](https://modrinth.com/mod/flashback/changelog) : 137 versions, 63 notes distinctes), les 69 pull requests
  publiques du dépôt (le dépôt n'a pas d'issues : le support passe par Discord) et une cinquantaine d'issues
  d'autres mods qui citent Flashback. État relevé le 2026-11-19. Une note de version est citée sous la forme
  [0.33.0](https://modrinth.com/mod/flashback/version/0.33.0) (lien vers la page de la version).
- **Just Replay** : branche `develop` au commit `25a58de` (version 1.1.2) : `README.md`, `CHANGELOG.md`,
  `docs/COMPATIBILITY.md` et le code. `JR/` veut dire `common/<mc>/src/main/java/com/vyrriox/justreplay/` : le même
  fichier existe pour 1.20.1, 1.21.1 et 26.1.2 sauf mention contraire. Le travail prêt sur des branches locales
  pas encore fusionnées est signalé « (branche `x`, non fusionnée) ».
- **Salle blanche** : Flashback est sous licence propriétaire (« Do not reupload or redistribute », README). Aucun
  fichier de code de Flashback ni de ReplayMod n'a été ouvert ; ce document ne décrit que des comportements visibles.
  Les corps de certaines pull requests contiennent des extraits de code : ils n'ont pas été recopiés et ne doivent
  pas servir de modèle.
- **Verdicts** : « JR mieux », « égal », « JR moins bien », « absent de JR », « absent de Flashback » (la fonction
  n'apparaît dans aucune note de version 0.1.0 à 0.43.6 ni sur la page Modrinth).

### En bref

- Just Replay a nettement plus d'outils de montage : acteurs (joueurs, entités, modèles Blockbench, émotes, actions),
  multi-caméra avec piste de coupes et transitions, effets vidéo et LUT, textes et images dans le monde, éditeur
  audio multipiste, annuler/rétablir, mode Simple, tampon « dernières minutes », tâches de scène automatisées.
- Just Replay couvre NeoForge et Forge en natif et Minecraft 1.20.1 ; Flashback est Fabric seul, 1.21 à 26.3.
- Flashback a plus d'options autour de la lecture et de l'export : vitesse et timelapse en keyframes, gel, filtres
  de rendu (particules, types d'entités), vision nocturne et brouillard, formats d'export (GIF, WebM, WebP, PNG,
  EXR, profondeur), suréchantillonnage, fond transparent, projections 360° et orthographique, encodeurs matériels,
  scènes multiples, fusion de replays, repères pendant l'enregistrement, pack de ressources du serveur, une douzaine
  de langues.

### Tableau des fonctionnalités

#### Enregistrement

| Fonction | Flashback (source) | Just Replay (source) | Verdict |
|---|---|---|---|
| Enregistrer côté client tout ce que le serveur envoie | oui, [0.1.0](https://modrinth.com/mod/flashback/version/0.1.0) et [README](https://github.com/Moulberry/Flashback) | oui, charges utiles des mods comprises : `JR/record/Recorder.java`, `JR/format/ReplayWriter.java` | égal |
| Démarrer et arrêter (touche, menu pause) | boutons du menu pause, [0.36.0](https://modrinth.com/mod/flashback/version/0.36.0) | F8 et menu pause : `JR/client/JRKeys.java`, `JR/client/JustReplayClient.java` | égal |
| Enregistrement automatique en solo et en multijoueur | non documenté ([notes](https://modrinth.com/mod/flashback/changelog)) | `autoRecordSingleplayer`, `autoRecordMultiplayer` : `JR/config/JRConfig.java` | absent de Flashback (non documenté) |
| Sauver les dernières minutes après coup (tampon en mémoire) | absent ([notes](https://modrinth.com/mod/flashback/changelog)) | F7, 1 à 120 min : `JR/format/ReplayBuffer.java`, `JR/config/JRConfig.java` (`replayBuffer`) | absent de Flashback |
| Enregistrer les N prochaines minutes puis s'arrêter | absent ([notes](https://modrinth.com/mod/flashback/changelog)) | touche `record_next` : `JR/client/JRKeys.java`, `nextMinutes` | absent de Flashback |
| Chunks mis en cache par Bobby | inclus [0.3.0](https://modrinth.com/mod/flashback/version/0.3.0), écrits dans le replay [0.39.2](https://modrinth.com/mod/flashback/version/0.39.2) | `captureExtraChunks` : `JR/record/ChunkCapture.java` ; intégration `JR/mixin/compat/BobbyMixin.java` activée en NeoForge et Fabric 1.21.1 (README) | égal |
| Distant Horizons | [0.10.0](https://modrinth.com/mod/flashback/version/0.10.0) (DH 2.3.0+) | copie temporaire de la base LOD : `JR/compat/DistantHorizonsCompat.java`, NeoForge et Fabric 1.21.1 (README) | égal |
| Simple Voice Chat enregistré | [0.4.0](https://modrinth.com/mod/flashback/version/0.4.0) | `JR/voice/VoiceRecorder.java`, `JR/voice/plugin/JustReplayVoicechatPlugin.java` (API publique seule) | égal |
| Repères posés pendant l'enregistrement | `/flashback mark` [0.4.0](https://modrinth.com/mod/flashback/version/0.4.0), 4 touches réglables [0.37.0](https://modrinth.com/mod/flashback/version/0.37.0) | repères seulement dans le replay (touche M) : `JR/client/JustReplayClient.java` (`JRKeys.MARKER`) | JR moins bien |
| Position du joueur enregistrée plus souvent que 20 fois par seconde (vue à la première personne fluide) | [0.4.0](https://modrinth.com/mod/flashback/version/0.4.0), [0.7.0](https://modrinth.com/mod/flashback/version/0.7.0) | une pose par tick, aucune capture par image dans `JR/record/Recorder.java` | absent de JR |
| Barre d'objets du joueur enregistrée et affichée en vue subjective | [0.4.0](https://modrinth.com/mod/flashback/version/0.4.0), [0.7.0](https://modrinth.com/mod/flashback/version/0.7.0) | HUD masqué pendant la lecture (`cleanHud`) : `JR/config/JRConfig.java` | absent de JR |
| Récupération après un plantage | [0.7.0](https://modrinth.com/mod/flashback/version/0.7.0) | flux vidé toutes les 30 s, `meta.json` chaque minute, flux tronqué relu jusqu'au dégât : `JR/record/Recorder.java`, `JR/format/ReplayStorage.java` (`readStream`) | égal |
| Refuser d'enregistrer ou avertir avec un mod connu pour casser | [0.5.0](https://modrinth.com/mod/flashback/version/0.5.0), [0.15.0](https://modrinth.com/mod/flashback/version/0.15.0) (Farsight) | pas de liste noire ; une intégration qui échoue se coupe (branche `fix/harden-compat-layer-crashes`, non fusionnée) | égal (autre approche) |
| Avertir quand les mods ou registres ont changé depuis l'enregistrement | [0.36.0](https://modrinth.com/mod/flashback/version/0.36.0), liste des mods changés au journal [0.39.9](https://modrinth.com/mod/flashback/version/0.39.9) | la liste `modid@version` est écrite (`JR/format/ReplayMeta.java` `mods`, `JR/record/Recorder.java`) mais jamais comparée ; seuls la version du jeu et le chargeur sont vérifiés : `JR/replay/VersionCheck.java` | JR moins bien |
| Ignorer des charges utiles choisies | option avancée [0.39.6](https://modrinth.com/mod/flashback/version/0.39.6) | filtre interne fixe `JR/replay/PacketFilter.java` ; paquet illisible sauté et journalisé : `JR/replay/Playback.java` | JR moins bien (pas réglable) |

#### Gestion des replays

| Fonction | Flashback (source) | Just Replay (source) | Verdict |
|---|---|---|---|
| Bouton sur l'écran titre | [0.2.0](https://modrinth.com/mod/flashback/version/0.2.0) | bouton « Replays » : `JR/client/JustReplayClient.java` (`onScreenInit`), `JR/gui/ReplayListScreen.java` | égal |
| Nommer à l'arrêt, renommer | nom à l'enregistrement [0.17.0](https://modrinth.com/mod/flashback/version/0.17.0), renommer [0.7.0](https://modrinth.com/mod/flashback/version/0.7.0) | renommer dans la liste : `JR/gui/ReplayListScreen.java` ; fenêtre de nom à l'arrêt (branche `feat/auto-name-replay-window`, non fusionnée) | égal |
| Trier, chercher | tri [0.13.0](https://modrinth.com/mod/flashback/version/0.13.0) | tri date, nom, taille et recherche : `JR/gui/ReplayListScreen.java` | JR mieux |
| Serveur ou monde d'origine affiché | [0.13.0](https://modrinth.com/mod/flashback/version/0.13.0) | serveur ou « Singleplayer » et nombre de mods : `JR/gui/ReplayListScreen.java` | égal |
| Supprimer, ouvrir le dossier | non documenté ([notes](https://modrinth.com/mod/flashback/changelog)) | supprimer avec confirmation, ouvrir le dossier : `JR/gui/ReplayListScreen.java` | égal |
| Dossiers dans la liste | [0.13.0](https://modrinth.com/mod/flashback/version/0.13.0) | non : `JR/gui/ReplayListScreen.java`, `JR/format/ReplayStorage.java` | absent de JR |
| Ouvrir un replay depuis un fichier, récents, menu Fichier | [0.8.0](https://modrinth.com/mod/flashback/version/0.8.0) | non : `JR/gui/ReplayListScreen.java` | absent de JR |
| Fusionner deux replays | [0.10.0](https://modrinth.com/mod/flashback/version/0.10.0) | non : `JR/format/ReplayStorage.java` | absent de JR |

#### Lecture et visuels

| Fonction | Flashback (source) | Just Replay (source) | Verdict |
|---|---|---|---|
| Caméra libre, lecture, pause, vitesse | [galerie](https://modrinth.com/mod/flashback/gallery) | x0.1 à x8 par le gestionnaire de tick du jeu : `JR/replay/Playback.java` | égal |
| Reculer avec un éclairage juste | [0.2.0](https://modrinth.com/mod/flashback/version/0.2.0) | points de reprise et réparation des sections : `JR/format/Checkpoints.java`, `JR/replay/SectionRepair.java` | égal |
| Vue depuis un joueur enregistré | [0.15.0](https://modrinth.com/mod/flashback/version/0.15.0), balancement [0.30.0](https://modrinth.com/mod/flashback/version/0.30.0) | n'importe quelle entité visée : `JR/replay/SpectateCamera.java` | égal |
| Réglages de déplacement de la caméra libre | fenêtre « Movement » [0.5.0](https://modrinth.com/mod/flashback/version/0.5.0) | vol spectateur du jeu, sans réglage propre : `JR/replay/Playback.java` | JR moins bien |
| Météo forcée | [0.11.0](https://modrinth.com/mod/flashback/version/0.11.0) | onglet Monde, avec keyframes de fondu : `JR/gui/WorldPanel.java`, `JR/replay/WorldOverride.java` | JR mieux |
| Heure du jour | keyframe [0.32.0](https://modrinth.com/mod/flashback/version/0.32.0) | keyframes, gel de l'heure, phase de lune : `JR/editor/WorldSettings.java`, `JR/gui/WorldPanel.java` | JR mieux |
| Brouillard (couleur, coupure), ciel coupé ou d'une couleur | [0.5.0](https://modrinth.com/mod/flashback/version/0.5.0), [0.6.0](https://modrinth.com/mod/flashback/version/0.6.0) | non : `JR/gui/WorldPanel.java`, `JR/editor/WorldSettings.java` (météo et heure seulement) | absent de JR |
| Vision nocturne forcée | [0.8.0](https://modrinth.com/mod/flashback/version/0.8.0) | non : `JR/gui/WorldPanel.java` | absent de JR |
| Filtre de rendu par type d'entité ou de particule, rayon de balise | [0.18.0](https://modrinth.com/mod/flashback/version/0.18.0), [0.39.1](https://modrinth.com/mod/flashback/version/0.39.1) | masquage entité par entité seulement : `JR/gui/EntitiesPanel.java` | absent de JR |
| Pack de ressources du serveur dans le replay | appliqué, option pour le couper [0.36.0](https://modrinth.com/mod/flashback/version/0.36.0) | paquets de pack jetés à la lecture : `JR/replay/PacketFilter.java` (`DROPPED`), dans les trois versions | JR moins bien |
| Blocs modifiés à la main pendant une pause, gardés dans une piste | [0.33.0](https://modrinth.com/mod/flashback/version/0.33.0) | non : `JR/editor/ReplayProject.java` (aucune piste de blocs) | absent de JR |
| Graine aléatoire remise à zéro pour des rendus identiques | [0.35.2](https://modrinth.com/mod/flashback/version/0.35.2) | non trouvé dans `JR/replay/Playback.java` | absent de JR |
| Commandes dans le replay (/hide, /show, /tp, /flashback pause) | [0.30.0](https://modrinth.com/mod/flashback/version/0.30.0), [0.35.2](https://modrinth.com/mod/flashback/version/0.35.2) | touches et panneaux seulement : `JR/client/JRKeys.java` | absent de JR (faible intérêt) |

#### Entités et joueurs enregistrés

| Fonction | Flashback (source) | Just Replay (source) | Verdict |
|---|---|---|---|
| Liste des joueurs : aller à, masquer, tout masquer, PNJ, spectateurs | [0.3.0](https://modrinth.com/mod/flashback/version/0.3.0), [0.16.0](https://modrinth.com/mod/flashback/version/0.16.0), [0.39.6](https://modrinth.com/mod/flashback/version/0.39.6) | liste des entités proches, choix au viseur, masquage : `JR/gui/EntitiesPanel.java` ; pas de « aller à » ni de « tout masquer » | JR moins bien |
| Masquer une entité à l'export | [0.3.0](https://modrinth.com/mod/flashback/version/0.3.0) | tout le replay, avant ou après la tête de lecture : `JR/gui/EntitiesPanel.java`, `JR/editor/EntityEdits.java` | JR mieux |
| Déplacer une entité enregistrée | absent ([notes](https://modrinth.com/mod/flashback/changelog)) | au viseur : `JR/gui/EntitiesPanel.java` (`entities.move`) | absent de Flashback |
| Volume des sons d'une entité | absent ([notes](https://modrinth.com/mod/flashback/changelog)) | 0 à 400 % ou muet : `JR/gui/EntitiesPanel.java` | absent de Flashback |
| Couper la voix d'un joueur | [0.39.1](https://modrinth.com/mod/flashback/version/0.39.1) | toutes les voix d'un coup (`voices`) : `JR/editor/ReplayProject.java` | JR moins bien |
| Entendre depuis une entité (source audio) | [0.3.0](https://modrinth.com/mod/flashback/version/0.3.0) | non : aucun réglage d'écoute dans `JR/gui/EntitiesPanel.java` ni `JR/editor/audio/AudioMixer.java` | absent de JR |
| Contour lumineux forcé à l'export | [0.20.0](https://modrinth.com/mod/flashback/version/0.20.0) | seulement l'entité sélectionnée dans l'éditeur : `JR/mixin/EntityEditGlowMixin.java` | absent de JR |
| Masquer l'équipement | [0.39.1](https://modrinth.com/mod/flashback/version/0.39.1) | pièce par pièce, élytres comprises : `JR/editor/ArmorSlots.java` (1.21.1 ; 1.20.1 et 26.1.2 sur la branche `feat/port-hide-armor-option`, non fusionnée) | égal |
| Masquer des parties du modèle (cape, couches de skin) | [0.33.0](https://modrinth.com/mod/flashback/version/0.33.0), [0.39.1](https://modrinth.com/mod/flashback/version/0.39.1) | tête, corps, bras, jambes, couches de skin : `JR/editor/BodyParts.java`, `JR/mixin/PlayerBodyPartsMixin.java` ; cape non trouvée | égal |
| Changer le skin, skin depuis un fichier | [0.9.0](https://modrinth.com/mod/flashback/version/0.9.0), [0.17.0](https://modrinth.com/mod/flashback/version/0.17.0) | fichiers PNG, changements datés, sauver le skin affiché : `JR/editor/SkinLibrary.java`, `JR/gui/SkinChangesSection.java`, `JR/editor/SkinSaver.java` | JR mieux |
| Changer le nom affiché, masquer préfixe d'équipe et texte sous le nom | [0.9.0](https://modrinth.com/mod/flashback/version/0.9.0), [0.17.0](https://modrinth.com/mod/flashback/version/0.17.0), [0.33.0](https://modrinth.com/mod/flashback/version/0.33.0) | nom affiché ou non sur les acteurs (`showName`) ; rien pour un joueur enregistré | JR moins bien |
| Copier l'UUID | [0.32.0](https://modrinth.com/mod/flashback/version/0.32.0) | non : `JR/gui/EntitiesPanel.java` | absent de JR (faible intérêt) |

#### Caméra et keyframes

| Fonction | Flashback (source) | Just Replay (source) | Verdict |
|---|---|---|---|
| Keyframes de caméra et chemin visible en 3D | [galerie](https://modrinth.com/mod/flashback/gallery) | `JR/editor/CameraPath.java`, `JR/editor/PathRenderer.java` | égal |
| Plusieurs caméras (fixe, chemin, suivi), piste de coupes, transitions, changement en direct 1 à 9 | pistes de keyframes et sélecteur de scènes ([0.13.0](https://modrinth.com/mod/flashback/version/0.13.0)) ; ni coupes ni transitions dans les [notes](https://modrinth.com/mod/flashback/changelog) | `JR/editor/ReplayProject.java` (`Camera`, `Cut`), `JR/editor/fx/TransitionType.java`, `JR/client/JRKeys.java` | JR mieux |
| Interpolation | lisse, linéaire, Hermite [0.16.0](https://modrinth.com/mod/flashback/version/0.16.0), en temps réel [0.33.0](https://modrinth.com/mod/flashback/version/0.33.0) | par les clés, Bézier, linéaire, vitesse constante ou temps des clés, adoucissement : `JR/editor/CameraPath.java` | égal |
| Suivre une entité | keyframe « Track Entity » [0.32.0](https://modrinth.com/mod/flashback/version/0.32.0) | caméra de suivi (derrière, côté, face, orbite, lissage) et visée d'une entité : `JR/editor/CameraRig.java`, `JR/gui/CameraPanel.java` | JR mieux |
| Orbite autour d'un point | keyframe d'orbite [0.30.0](https://modrinth.com/mod/flashback/version/0.30.0) | orbite autour d'une entité suivie seulement : `JR/editor/CameraRig.java` | JR moins bien |
| Tremblement de caméra | [0.13.0](https://modrinth.com/mod/flashback/version/0.13.0) | intensité et vitesse par caméra : `JR/editor/CameraRig.java` | égal |
| Champ de vision et roulis | [galerie](https://modrinth.com/mod/flashback/gallery), touches de roulis [0.40.0](https://modrinth.com/mod/flashback/version/0.40.0) | par keyframe : `JR/editor/ReplayProject.java` (`CameraKeyframe.fov`), README | égal |
| Vitesse, ralenti, accéléré (keyframes) et timelapse | [0.2.0](https://modrinth.com/mod/flashback/version/0.2.0), [0.3.0](https://modrinth.com/mod/flashback/version/0.3.0) | vitesse en lecture seulement, aucune piste de vitesse dans `JR/editor/ReplayProject.java` ni dans l'export | absent de JR |
| Gel de l'image (keyframe Freeze) | [0.14.0](https://modrinth.com/mod/flashback/version/0.14.0) | gel de l'heure du jour seulement : `JR/gui/WorldPanel.java` | absent de JR |
| Plusieurs scènes (plans) dans un même replay | [0.13.0](https://modrinth.com/mod/flashback/version/0.13.0) | un seul `project.json` par replay : `JR/editor/ReplayProject.java` | absent de JR |
| Pistes renommées, rangées, colorées | [0.13.0](https://modrinth.com/mod/flashback/version/0.13.0) | caméras et pistes audio nommées : `JR/gui/CameraPanel.java`, `JR/gui/AudioPanel.java` ; pas de couleur | JR moins bien |
| Projection orthographique, cube, équirectangulaire (360°) | [0.40.0](https://modrinth.com/mod/flashback/version/0.40.0) | non : `JR/editor/CameraController.java` | absent de JR |
| Touches réglables | [0.40.0](https://modrinth.com/mod/flashback/version/0.40.0) | toutes les touches dans les contrôles du jeu : `JR/client/JRKeys.java` | égal |
| Appliquer ou copier une position de caméra | [0.3.0](https://modrinth.com/mod/flashback/version/0.3.0), [0.33.0](https://modrinth.com/mod/flashback/version/0.33.0) | champs de transformation : `JR/gui/TransformFields.java` | égal |

#### Éditeur (montage)

| Fonction | Flashback (source) | Just Replay (source) | Verdict |
|---|---|---|---|
| Timeline : zoom, glisser, sélection multiple, aimantation | [0.3.0](https://modrinth.com/mod/flashback/version/0.3.0), [0.8.0](https://modrinth.com/mod/flashback/version/0.8.0), [0.34.0](https://modrinth.com/mod/flashback/version/0.34.0) | zoom Ctrl + molette, glisser les keyframes : `JR/gui/EditorTimeline.java`, `JR/gui/Timeline.java` | égal |
| Annuler et rétablir | absent ([notes](https://modrinth.com/mod/flashback/changelog)) | Ctrl + Z, Ctrl + Y : `JR/gui/EditorScreen.java` | absent de Flashback |
| Insérer ou retirer du temps à la tête de lecture | absent ([notes](https://modrinth.com/mod/flashback/changelog)) | `JR/editor/TimeShift.java` | absent de Flashback |
| Acteurs : joueurs, entités, modèles Blockbench, émotes, actions datées, placement au viseur | absent, seulement des réglages sur les joueurs enregistrés ([notes](https://modrinth.com/mod/flashback/changelog)) | `JR/editor/ActorManager.java`, `JR/editor/ActionType.java`, `JR/editor/model/BbModel.java`, `JR/editor/EmoteLibrary.java`, `JR/gui/PlacementTool.java` | absent de Flashback |
| Effets vidéo, LUT, looks prêts, fondus d'ouverture et de fin | absent ([notes](https://modrinth.com/mod/flashback/changelog)) | `JR/editor/fx/FxType.java`, `JR/editor/fx/LutLibrary.java`, `JR/editor/fx/FxPresets.java` | absent de Flashback |
| Textes et images ancrés dans le monde | absent (seule une surcouche « RTC » [0.42.0](https://modrinth.com/mod/flashback/version/0.42.0)) | onglet Overlays : `JR/editor/overlay/Overlay.java`, `JR/gui/OverlaysPanel.java` | absent de Flashback |
| Mode Simple et mode Avancé | absent ; interface jugée intimidante ([OneConfig #864](https://github.com/Polyfrost/OneConfig/issues/864)) | `JR/config/JRConfig.java` (`EditorMode`), README | absent de Flashback |
| Tâches de scène sans surveillance (enregistrer, captures, export, quitter) | absent ([notes](https://modrinth.com/mod/flashback/changelog)) | `JR/client/SceneJob.java`, README « Scene jobs » | absent de Flashback |
| Astuce du jour, aide | [0.31.0](https://modrinth.com/mod/flashback/version/0.31.0) | aide des raccourcis sur F1 : README « Glass interface » | égal |

#### Export

| Fonction | Flashback (source) | Just Replay (source) | Verdict |
|---|---|---|---|
| Vidéo image par image, résolution indépendante de la fenêtre | [galerie](https://modrinth.com/mod/flashback/gallery) | `JR/export/VideoExporter.java`, `JR/export/ExportRenderSize.java` | égal |
| FFmpeg fourni avec le mod | fourni, FFmpeg du système en option [0.42.2](https://modrinth.com/mod/flashback/version/0.42.2) | à installer soi-même (`ffmpegPath`) : `JR/config/JRConfig.java`, README | JR moins bien (choix de licence) |
| Choix et test des encodeurs, encodeurs matériels (AMD AMF...) | [0.2.0](https://modrinth.com/mod/flashback/version/0.2.0), [0.43.3](https://modrinth.com/mod/flashback/version/0.43.3) | libx264 seul : `JR/export/VideoExporter.java` | absent de JR |
| Formats WebM, WebP, GIF | [0.5.0](https://modrinth.com/mod/flashback/version/0.5.0) | MP4 seul dans l'écran d'export : `JR/gui/ExportScreen.java` | absent de JR |
| Suite d'images PNG (nom réglable), EXR | [0.6.0](https://modrinth.com/mod/flashback/version/0.6.0), [0.30.0](https://modrinth.com/mod/flashback/version/0.30.0), [0.43.3](https://modrinth.com/mod/flashback/version/0.43.3) | PNG seulement par une tâche de scène (`"format": "png"`) : `JR/export/VideoExporter.java`, `JR/client/SceneJob.java` | JR moins bien |
| Carte de profondeur | [0.43.3](https://modrinth.com/mod/flashback/version/0.43.3) | non : `JR/export/VideoExporter.java` | absent de JR |
| Ciel transparent | [0.3.0](https://modrinth.com/mod/flashback/version/0.3.0) | non : `JR/export/VideoExporter.java` | absent de JR |
| Suréchantillonnage (SSAA) | [0.3.0](https://modrinth.com/mod/flashback/version/0.3.0) | non : `JR/export/ExportRenderSize.java` | absent de JR |
| Audio stéréo, choix du codec | [0.5.0](https://modrinth.com/mod/flashback/version/0.5.0), [0.43.1](https://modrinth.com/mod/flashback/version/0.43.1) | AAC 192 kb/s : `JR/export/VideoExporter.java`, `JR/export/ExportAudio.java` | JR moins bien (un codec) |
| Export sans interface | [0.7.0](https://modrinth.com/mod/flashback/version/0.7.0) | toujours sans HUD : `JR/export/VideoExporter.java` | égal |
| Capture d'une image fixe | [0.8.0](https://modrinth.com/mod/flashback/version/0.8.0) | PNG à la taille d'export avec effets et overlays : `JR/gui/ExportScreen.java` (`export.still`) | JR mieux |
| Points d'entrée et de sortie | [0.8.0](https://modrinth.com/mod/flashback/version/0.8.0), [0.31.0](https://modrinth.com/mod/flashback/version/0.31.0) | plage déduite du chemin ou de la piste de coupes : `JR/gui/ExportScreen.java` | JR moins bien |
| Modèle de nom de fichier | [0.8.0](https://modrinth.com/mod/flashback/version/0.8.0), [0.39.1](https://modrinth.com/mod/flashback/version/0.39.1) | nom libre, jamais d'écrasement (`_2`) ; plage dans le nom sur la branche `feat/export-time-range-display`, non fusionnée | égal |
| Fin d'export : son, barre des tâches, ouvrir la vidéo, partage | [0.9.0](https://modrinth.com/mod/flashback/version/0.9.0), [0.14.0](https://modrinth.com/mod/flashback/version/0.14.0), [0.33.0](https://modrinth.com/mod/flashback/version/0.33.0), [0.38.0](https://modrinth.com/mod/flashback/version/0.38.0) | message et bouton « dossier » : `JR/gui/ExportScreen.java`, `JR/gui/ui/GlassToast.java` | JR moins bien |
| Réglages d'export gardés, images de chauffe | [0.17.0](https://modrinth.com/mod/flashback/version/0.17.0), [0.34.0](https://modrinth.com/mod/flashback/version/0.34.0) | `fps`, `crf`, `resolution`, `warmupFrames` (attend aussi les chunks) : `JR/config/JRConfig.java` | égal |
| Horloge d'Iris suivie à l'export | [0.4.0](https://modrinth.com/mod/flashback/version/0.4.0) | `JR/mixin/compat/IrisTimerMixin.java`, Iris activé en 1.21.1 et 26.1.2 (NeoForge, Fabric) | égal |

#### Audio

| Fonction | Flashback (source) | Just Replay (source) | Verdict |
|---|---|---|---|
| Fichiers audio sur la timeline | une piste audio [0.38.0](https://modrinth.com/mod/flashback/version/0.38.0) | pistes multiples, découpe, fondus, boucle, vitesse, solo, muet, fondu enchaîné : `JR/editor/audio/AudioTrack.java`, `JR/gui/AudioPanel.java` | JR mieux |
| Volume du jeu, atténuation sous la musique | absent ([notes](https://modrinth.com/mod/flashback/changelog)) | `JR/editor/ReplayProject.java` (`gameVolume`, `ducking`) | absent de Flashback |
| Pas et casse de blocs du joueur enregistré | [0.7.0](https://modrinth.com/mod/flashback/version/0.7.0) | `JR/mixin/LocalPlayerSoundsMixin.java`, `JR/mixin/ClientLevelLocalEffectsMixin.java` | égal |
| Voix de Simple Voice Chat à la lecture et à l'export | [0.4.0](https://modrinth.com/mod/flashback/version/0.4.0) | `JR/voice/VoicePlayer.java`, `JR/voice/VoiceTrack.java` | égal |

#### Chargeurs, versions, licence, interface, performance

| Fonction | Flashback (source) | Just Replay (source) | Verdict |
|---|---|---|---|
| Chargeurs | Fabric seul ([page Modrinth](https://modrinth.com/mod/flashback)) ; Forge par Sinytra Connector « sans support » [0.9.0](https://modrinth.com/mod/flashback/version/0.9.0) | NeoForge, Forge et Fabric en natif : README « Supported targets » | JR mieux |
| Versions de Minecraft | 1.21, 1.21.1, 1.21.4 à 1.21.11, 26.1 à 26.3 ([page Modrinth](https://modrinth.com/mod/flashback)) | 1.20.1, 1.21.1, 26.1.2 : README | JR mieux pour 1.20.1, moins bien pour 1.21.4 à 1.21.11 et 26.2 à 26.3 |
| Licence, contributions | propriétaire, pas de contributions extérieures ([README](https://github.com/Moulberry/Flashback)) | Apache 2.0 : `LICENSE`, `CONTRIBUTING.md` | JR mieux |
| Langues | une douzaine, dont le français : [0.37.0](https://modrinth.com/mod/flashback/version/0.37.0), [0.38.0](https://modrinth.com/mod/flashback/version/0.38.0), [0.38.1](https://modrinth.com/mod/flashback/version/0.38.1), [0.38.2](https://modrinth.com/mod/flashback/version/0.38.2), [0.39.3](https://modrinth.com/mod/flashback/version/0.39.3) | anglais seul : `common/<mc>/src/main/resources/assets/justreplay/lang/en_us.json` | JR moins bien |
| Fenêtre de préférences | [0.8.0](https://modrinth.com/mod/flashback/version/0.8.0), [0.18.0](https://modrinth.com/mod/flashback/version/0.18.0) | `JR/gui/JRConfigScreen.java` | égal |
| Rendu Vulkan | [0.41.1](https://modrinth.com/mod/flashback/version/0.41.1) (Minecraft 26.2) | sans objet, pas de cible 26.2 : README « Supported targets » | sans objet |
| Mémoire et disque | système de fichiers en mémoire [0.2.0](https://modrinth.com/mod/flashback/version/0.2.0), protections contre le manque de mémoire [0.38.0](https://modrinth.com/mod/flashback/version/0.38.0) | blocs de 5 s, points de reprise, tampon plafonné : `JR/format/SegmentedStream.java`, `JR/format/Checkpoints.java`, `JR/config/JRConfig.java` | égal |
| Interface | fenêtres Dear ImGui ([0.38.1](https://modrinth.com/mod/flashback/version/0.38.1)) dont la disposition est gardée ([PR #58](https://github.com/Moulberry/Flashback/pull/58)) | interface verre, barre latérale repliable : README « Glass interface », `JR/gui/ui/` | égal |

### Problèmes de compatibilité relevés chez Flashback, et Just Replay

Exposition : **non exposé** (l'architecture de Just Replay évite le cas), **protégé** (le cas arrive mais un
garde-fou l'empêche de planter), **couvert** (intégration dédiée), **exposé** (le même défaut est attendu),
**inconnu** (jamais essayé : à tester). Sauf mention, l'exposition vaut pour les trois versions de Minecraft.

Deux faits de Just Replay règlent beaucoup de lignes :

- Un replay se joue dans un monde vide neuf hébergé par le serveur intégré, avec le vrai profil du joueur ; les
  paquets de ce serveur sont bloqués et ceux de l'enregistrement sont rejoués côté client
  (`JR/replay/ReplaySession.java`, javadoc de classe et `createFreshLevel`). Il n'y a pas de faux joueur « Replay
  Viewer » sur lequel les mods serveur tournent.
- Chaque paquet rejoué est décodé puis traité dans un `try/catch` qui journalise une fois et passe au suivant
  (`JR/replay/Playback.java`, vers les lignes 303 et 354 en 1.21.1 et 26.1.2, 298 et 338 en 1.20.1). Un travail qu'un mod
  remet à plus tard sur le fil principal (file de tâches) n'est pas couvert par ce garde-fou : non vérifié.

| Mod ou situation | Problème chez Flashback (source) | Just Replay | Exposition |
|---|---|---|---|
| Essential | expulsé par la liste blanche du serveur intégré ([essential-bug-tracker #79](https://github.com/SparkUniverse/essential-bug-tracker/issues/79), contournement [0.11.0](https://modrinth.com/mod/flashback/version/0.11.0)) | monde solo neuf, le joueur en est l'hôte : `JR/replay/ReplaySession.java` | non exposé (à confirmer avec Essential) |
| More Quest Types (FTB Quests) | plantage : le faux joueur n'a pas d'équipe ([More-Quest-Types #16](https://github.com/KevzCz/More-Quest-Types/issues/16)) | vrai joueur, qui reçoit son équipe à la connexion : `JR/replay/ReplaySession.java` | non exposé |
| Items Displayed | plantage : monde du replay sans tables de butin ([Items-Displayed-Fabric #53](https://github.com/FREEDINN3R/Items-Displayed-Fabric/issues/53)) | monde neuf créé avec les datapacks normaux : `JR/replay/ReplaySession.java` | non exposé |
| PowerGrid | plantage serveur en sérialisant un fil ([PowerGrid #1146](https://github.com/patryk3211/PowerGrid/issues/1146), [flashback-neoforge-fixed #4](https://github.com/tatuto-riku/flashback-neoforge-fixed/issues/4)) | les entités enregistrées n'existent que côté client : `JR/replay/ReplaySession.java` | non exposé |
| Glowing Eyes | plantage au démarrage de l'enregistrement ([GlowingEyes #25](https://github.com/RaydanOMGr/GlowingEyes/issues/25)) | cause non publiée ; pas d'intégration : `JR/compat/CompatTargets.java` | inconnu |
| Mowzie's Mobs, Map Atlases, Carry On | exception d'un gestionnaire de paquet ou en parcourant la timeline ([flashback-neoforge-fixed #3](https://github.com/tatuto-riku/flashback-neoforge-fixed/issues/3), [mapatlases-neoforge #235](https://github.com/MehVahdJukaar/mapatlases-neoforge/issues/235), [CarryOn #892](https://github.com/Tschipp/CarryOn/issues/892)) | garde-fou par paquet : `JR/replay/Playback.java` ; données de carte écrites dans l'instantané : `JR/record/Recorder.java` | protégé |
| Paquet inconnu ou mal formé (serveur Paper, joueur inconnu) | [FlashbackServer #14](https://github.com/Zeffut/FlashbackServer/issues/14), [PR #66](https://github.com/Moulberry/Flashback/pull/66) | décodage et traitement protégés : `JR/replay/Playback.java` | protégé |
| JEI, datapack serveur seul | désaccord de registre à la synchro des recettes ([PR #67](https://github.com/Moulberry/Flashback/pull/67), déconnexion [0.41.1](https://modrinth.com/mod/flashback/version/0.41.1)) | registres synchronisés enregistrés et rejoués : `JR/format/SyncedRegistries.java` (1.21.1, 26.1.2), `JR/format/RegistryIds.java` ; paquet de recettes jeté à la lecture : `JR/replay/PacketFilter.java` | inconnu (à tester avec JEI et un datapack serveur) |
| Supplementaries, Moonlight, objets vides (`AirItem`) | registre de fluides, joueur non chargé ([Supplementaries #2016](https://github.com/MehVahdJukaar/Supplementaries/issues/2016), [Connector #2167](https://github.com/Sinytra/Connector/issues/2167), [Connector #2239](https://github.com/Sinytra/Connector/issues/2239)) | ids absents gardés (1.0.7, `CHANGELOG.md`) ; ces cas passaient par Sinytra Connector | inconnu (Supplementaries jamais essayé) |
| Mod mis à jour entre l'enregistrement et la lecture | ids de données d'entité décalés, plantage ([groundworks-excavator #37](https://github.com/peterwolf-pl/peterwolfs-groundworks-excavator/issues/37)) | liste des mods écrite mais jamais comparée : `JR/format/ReplayMeta.java`, `JR/replay/VersionCheck.java` ; le garde-fou évite le plantage, pas l'affichage faux | exposé (sans avertissement) |
| Armourer's Workshop | tenues absentes du replay ([Armourers-Workshop #537](https://github.com/Armourers-Workshop/Armourers-Workshop/issues/537), [#581](https://github.com/Armourers-Workshop/Armourers-Workshop/issues/581)) | `JR/compat/ArmourersWorkshopCompat.java` (NeoForge et Fabric 1.21.1 ; 1.20.1 et 26.1.2 sur la branche `feat/extend-mod-compat-targets`, non fusionnée) | couvert en 1.21.1 |
| Curios | modèle du joueur invisible ([Curios #524](https://github.com/TheIllusiveC4/Curios/issues/524)) | `JR/compat/CuriosCompat.java` : NeoForge 1.21.1, NeoForge et Forge 1.20.1 (README) | couvert sur ces cibles |
| Create : trains, convoyeurs à chaîne, nuages de Create: Atmospherics | trains invisibles ou figés, plantage, nuages qui sautent ([Create-Fly #251](https://github.com/ZurrTum/Create-Fly/issues/251), [Connector #2075](https://github.com/Sinytra/Connector/issues/2075), [Connector #2349](https://github.com/Sinytra/Connector/issues/2349)) | `JR/compat/CreateCompat.java` : NeoForge 1.21.1 seulement ; trains d'un enregistrement commencé en cours de partie non testés (`docs/COMPATIBILITY.md`, « Known limits ») | couvert en NeoForge 1.21.1 ; exposé ailleurs (Create Forge 1.20.1, Create Fly Fabric) ; Atmospherics inconnu |
| Create Aeronautics, Sable | incompatibilité ([Simulated-Project #867](https://github.com/Creators-of-Aeronautics/Simulated-Project/issues/867)) | `JR/compat/SableCompat.java`, `JR/compat/SimulatedCompat.java` : NeoForge 1.21.1, Sable aussi Fabric 1.21.1 (README) | couvert sur ces cibles |
| Immersive Aircraft | incompatibilité ([ImmersiveAircraft #366](https://github.com/Luke100000/ImmersiveAircraft/issues/366)) | `JR/compat/ImmersiveAircraftCompat.java` : NeoForge et Fabric 1.21.1 (README) | couvert sur ces cibles |
| Immersive Vehicles (MTS) | véhicules non rejoués sans changement du mod ([MinecraftTransportSimulator #2249](https://github.com/DonBruce64/MinecraftTransportSimulator/issues/2249)) | `JR/compat/ImmersiveVehiclesCompat.java` (NeoForge 1.21.1, Forge 1.20.1, NeoForge 26.1.2) | couvert, sans changement du mod |
| Epic Fight | NullPointerException quand le joueur suivi est un `RemotePlayer` ([epicfight #2599](https://github.com/Antikythera-Studios/epicfight/issues/2599)) | `JR/compat/EpicFightCompat.java` : NeoForge 1.21.1 ; vue depuis un joueur enregistré avec Epic Fight non essayée | inconnu |
| Fantastic Wings, A Good Place, LightFrame, Squishy Heads, Dream Displays | rendu ou animation propre au client absent du replay ([fantastic-wings #27](https://github.com/Fuzss/fantastic-wings/issues/27), [a-good-place #39](https://github.com/enjarai/a-good-place/issues/39), [lightframe #1](https://github.com/AvelcFox/lightframe/issues/1), [SquishyHeads #3](https://github.com/Meekiavelique/SquishyHeads/issues/3), [dreamdisplays #204](https://github.com/arnodoelinger/dreamdisplays/issues/204)) | aucune intégration : `JR/compat/CompatTargets.java` | inconnu |
| Iris : plantage au chargement, main absente avec shaders | [Iris #2817](https://github.com/IrisShaders/Iris/issues/2817), [Iris #3070](https://github.com/IrisShaders/Iris/issues/3070), [Iris #3191](https://github.com/IrisShaders/Iris/issues/3191), main retirée [0.41.1](https://modrinth.com/mod/flashback/version/0.41.1) | `JR/mixin/compat/IrisTimerMixin.java`, démarrage vérifié (`docs/COMPATIBILITY.md`, « Status per target ») ; vrais packs de shaders jamais regardés | inconnu pour l'image |
| Voxy, Moonrise, Starlight ou ScalableLux | rechargements et saccades, plantage, éclairage ([voxy #657](https://github.com/MCRcortex/voxy/issues/657), [Moonrise #83](https://github.com/Tuinity/Moonrise/issues/83), [0.37.0](https://modrinth.com/mod/flashback/version/0.37.0)) | `JR/mixin/HostChunkMapMixin.java`, `JR/replay/ChunkRetention.java`, `JR/replay/SectionRepair.java` touchent aux chunks et à l'éclairage | inconnu |
| CustomSkinLoader | export beaucoup plus lent ([MCCustomSkinLoader #322](https://github.com/xfl03/MCCustomSkinLoader/issues/322)) | skins des acteurs depuis des fichiers locaux : `JR/editor/SkinLibrary.java` | inconnu |
| ImmediatelyFast | contrôles de l'éditeur invisibles ([0.38.2](https://modrinth.com/mod/flashback/version/0.38.2)) | interface dessinée par le jeu, pas par ImGui : `JR/gui/ui/GlassScreen.java` | inconnu |
| Ixeris, RawInputBuffer | caméra qui bouge dans le menu pause, souris bloquée, plantage ([Ixeris #157](https://github.com/decce6/Ixeris/issues/157), [Ixeris #137](https://github.com/decce6/Ixeris/issues/137), [RawInputBuffer #4](https://github.com/Walksy/RawInputBuffer/issues/4)) | crochets souris : `JR/mixin/MouseHandlerHooksMixin.java` ; Ixeris listé « à vérifier » (`docs/COMPATIBILITY.md`, Tier 1) | inconnu |
| Visible Barriers | clic droit sur les joueurs impossible ([visiblebarriers #111](https://github.com/AmyMialeeMods/visiblebarriers/issues/111)) | sélection au viseur : `JR/gui/EntityTool.java` | inconnu |
| HUD d'autres mods visible (Stun Slam, Better Mount HUD, AFK Camera) | [Stun-Slam-Simulator #15](https://github.com/MediocreSoup/Stun-Slam-Simulator/issues/15), [better-mount-hud #26](https://github.com/Lortseam/better-mount-hud/issues/26), [AFK-camera #6](https://github.com/playsi/AFK-camera/issues/6) | HUD des autres mods masqué (`cleanHud`) ; pas sur Fabric 1.20.1 et 1.21.1 (`docs/COMPATIBILITY.md`, « Known limits of the targets ») | protégé sauf Fabric 1.20.1 et 1.21.1 |
| Simple Voice Chat mis à jour | plantages après des changements internes de SVC ([PR #20](https://github.com/Moulberry/Flashback/pull/20), [PR #37](https://github.com/Moulberry/Flashback/pull/37), [PR #68](https://github.com/Moulberry/Flashback/pull/68)) | API publique seule (`de.maxhenkel.voicechat.api`) : `JR/voice/plugin/JustReplayVoicechatPlugin.java` | moins exposé |
| Proxy Velocity : changement de serveur pendant l'enregistrement | déconnexion ([PR #65](https://github.com/Moulberry/Flashback/pull/65), [0.43.3](https://modrinth.com/mod/flashback/version/0.43.3)) | jamais essayé derrière un proxy : `JR/record/Recorder.java` | inconnu |
| Sinytra Connector, Quilt, lanceurs | plantages via Connector (lignes ci-dessus), export sous Quilt ([quilt-loader #455](https://github.com/QuiltMC/quilt-loader/issues/455)), mise en page perdue sous Lunar ([PR #58](https://github.com/Moulberry/Flashback/pull/58)) | NeoForge et Forge natifs, pas besoin de Connector ; réglages dans le dossier de config du chargeur : `JR/config/JRConfig.java` | non exposé (Connector) ; Quilt inconnu |
| Mods qui veulent ajouter leurs données au replay | méthode prévue pour les mods [0.38.1](https://modrinth.com/mod/flashback/version/0.38.1) ; ports et ajouts tiers ([flashback-neoforge-fixed](https://github.com/tatuto-riku/flashback-neoforge-fixed/issues/1), [FlashbackServer](https://github.com/Zeffut/FlashbackServer/issues/6), [PR #74](https://github.com/Moulberry/Flashback/pull/74) portage 1.20.1 avec Create et Valkyrien Skies) | intégrations internes seulement (`JR/compat/CompatTargets.java`), aucune API publique | JR moins bien (demande réelle : NeoForge, 1.20.1, Create, Valkyrien Skies) |

### Manques de Just Replay, par priorité

Gain : ce que le joueur y gagne. Effort : petit (un ou deux fichiers par version), moyen (une nouvelle piste ou un
nouvel écran), gros (touche au temps de l'export, au rendu ou à un nouveau mod). Tout changement se fait trois
fois, dans `common/1.20.1`, `common/1.21.1` et `common/26.1.2`.

| # | Manque | Gain | Effort | Pourquoi ce rang |
|---|---|---|---|---|
| 1 | Pack de ressources du serveur rejoué | élevé | moyen | tout replay fait sur un serveur à pack (textures, modèles, sons) s'affiche faux aujourd'hui |
| 2 | Avertissement des mods changés à l'ouverture | élevé | petit | les données existent déjà (`meta.mods`) ; explique d'avance plantages et contenus manquants |
| 3 | Vitesse, ralenti, timelapse et gel en keyframes dans l'export | élevé | gros | la grosse fonction cinéma qui manque face à Flashback |
| 4 | Repères pendant l'enregistrement | moyen | petit | retrouver un moment fort d'une longue session sans tout revoir |
| 5 | Visuels : vision nocturne, brouillard, particules et types d'entités masqués, contour forcé | moyen | moyen | plans de nuit, plans propres sans particules ni mobs parasites |
| 6 | Formats d'export : suite PNG dans l'écran, WebM, GIF, suréchantillonnage | moyen | moyen | sortie directe pour les pages de mods et les monteurs |
| 7 | Encodeurs matériels (NVENC, AMF, QSV) | moyen | moyen | exports beaucoup plus rapides |
| 8 | Points d'entrée et de sortie manuels pour l'export | moyen | petit | exporter un bout sans retoucher le chemin de caméra |
| 9 | Create hors NeoForge 1.21.1 (Forge 1.20.1, Fabric 1.21.1) | élevé pour les packs Create | gros | demande visible ([PR #74](https://github.com/Moulberry/Flashback/pull/74)) |
| 10 | Scènes multiples dans un replay | moyen | moyen | plusieurs plans du même enregistrement sans copier le dossier |
| 11 | Liste des joueurs : aller à, tout masquer, spectateurs | moyen | petit | gros serveurs, événements |
| 12 | Nom affiché et préfixe d'équipe d'un joueur enregistré | faible | petit | anonymiser ou renommer à l'écran |
| 13 | Barre d'objets et HUD du joueur en vue subjective | moyen | gros | vidéos « POV » |
| 14 | Positions à la première personne plus fines que le tick | faible | gros | rendu subjectif fluide à 60 i/s |
| 15 | Orbite autour d'un point fixe | faible | petit | complète la caméra de suivi |
| 16 | Projection 360° et orthographique, ciel transparent, profondeur, EXR | faible | gros | usages de niche (VR, compositing) |
| 17 | Dossiers, ouvrir depuis un fichier, fusionner des replays | faible | moyen | rangement de grosses collections |
| 18 | Blocs modifiés à la main pendant une pause | moyen | gros | retoucher le décor sans rejouer la scène |
| 19 | Traductions | moyen | moyen | décision du propriétaire (interface en anglais seule aujourd'hui) |
| 20 | API publique pour que d'autres mods écrivent leurs données | moyen | gros | décision d'architecture du propriétaire |

### Sélection pour la sous-tâche suivante

Les cinq premiers manques, dans cet ordre. Chacun vaut pour les neuf cibles (NeoForge, Forge et Fabric en 1.20.1,
1.21.1 et 26.1.2), avec la logique dans `common/<mc>/` et rien dans la colle des chargeurs sauf mention. Chacun doit
passer le build des neuf cibles, une ligne du self-test et des tests unitaires Java pur quand une partie s'y prête.

#### 1. Pack de ressources du serveur rejoué

- **Constat** : les paquets de pack de ressources sont jetés à la lecture (`JR/replay/PacketFilter.java`,
  `DROPPED`), donc un replay fait sur un serveur à pack obligatoire montre les textures, modèles et sons vanilla.
- **Comportement attendu** : à l'ouverture d'un replay, chaque pack poussé par le serveur pendant l'enregistrement
  (adresse, empreinte, pack obligatoire ou non) est appliqué sans fenêtre de confirmation, depuis le cache de packs
  de serveur du jeu quand l'empreinte y est ; un pack retiré pendant l'enregistrement est retiré au même tick.
  Les packs déjà actifs quand l'enregistrement commence (poussés à la connexion, souvent pendant la phase de
  configuration, donc absents du flux) sont écrits au démarrage dans `meta.json` ou l'instantané, sinon un
  enregistrement lancé en cours de partie les perd. Si le pack manque du cache,
  un réglage `playback.serverResourcePacks` (`cached`, défaut : cache seul ; `download` :
  télécharger ; `off` : jamais) décide ; un pack introuvable affiche un message et le replay continue. Les packs
  sont retirés à la fermeture du replay, comme à la déconnexion d'un serveur.
- **Versions** : 1.20.1 a un seul pack par serveur (un paquet de pack unique) ; 1.21.1 et 26.1.2 en ont plusieurs
  (paquets d'ajout et de retrait avec un identifiant). L'emplacement et la forme du cache diffèrent entre 1.20.1
  et les versions récentes : les relever dans les sources de chaque version avant d'écrire le code.
- **Preuve** : self-test qui enregistre un pack poussé par le serveur intégré, le retrouve à la lecture et vérifie
  qu'il est actif ; build des neuf cibles.

#### 2. Avertissement des mods changés à l'ouverture

- **Constat** : `meta.json` garde `mods` (`modid@version`, `JR/format/ReplayMeta.java`) mais rien ne le compare
  (`JR/replay/VersionCheck.java` ne regarde que la version du jeu et le chargeur).
- **Comportement attendu** : à l'ouverture, comparer avec les mods installés. Si un mod manque, a été ajouté ou a
  changé de version, ouvrir une fenêtre « Les mods ont changé depuis l'enregistrement » qui liste les trois groupes
  (manquants d'abord), avec « Ouvrir quand même » et « Annuler », et écrire la même liste une fois au journal. Ne
  pas compter les mods sans effet sur un replay (Just Replay lui-même, le chargeur, Minecraft, Java). Une case « Ne
  plus avertir pour ce replay » l'écrit dans `meta.json`. Un replay sans liste (anciens) s'ouvre sans fenêtre.
- **Versions** : les neuf cibles ; la comparaison est du Java pur (testée dans `common/<mc>/src/test`), la fenêtre
  réutilise `JR/gui/ConfirmDialog.java`.
- **Preuve** : tests unitaires de la comparaison (ajout, retrait, version changée, mods ignorés, liste vide) ;
  self-test qui ouvre un replay dont `meta.json` cite un faux mod et vérifie la fenêtre puis l'ouverture.

#### 3. Vitesse, ralenti, timelapse et gel en keyframes dans l'export

- **Constat** : la vitesse n'existe qu'en lecture ; `JR/editor/ReplayProject.java` n'a pas de piste de vitesse et
  l'export suit le temps du replay à x1.
- **Comportement attendu** : une piste « Vitesse » sur la timeline (mode Avancé) avec des keyframes de facteur
  (x0.1 à x8, palier ou rampe douce entre deux clés), un bloc « Timelapse » (une durée du replay jouée en une durée
  de sortie choisie) et un bloc « Gel » (l'image s'arrête pendant une durée de sortie, la caméra peut continuer).
  L'aperçu et l'export suivent la même courbe ; la durée de sortie s'affiche dans l'écran d'export. Les caméras
  gardent leurs keyframes en ticks du replay (elles ralentissent avec le replay), le choix d'un mode « caméra en
  temps réel » est laissé pour plus tard et noté. Les pistes audio de l'éditeur, les textes, images et effets
  restent en temps de sortie ; les sons du jeu et les voix suivent la vitesse comme en lecture et se coupent
  pendant un gel et hors de x0.5 à x2 (réglable).
- **Versions** : les neuf cibles ; 1.20.1 passe par son propre contrôle du temps (`common/1.20.1/.../replay/TimeControl.java`),
  26.1.2 par les horloges (`common/26.1.2/.../replay/ClockRates.java`) ; la table temps de sortie vers tick du
  replay est du Java pur, testée.
- **Preuve** : tests unitaires de la table (rampes, paliers, timelapse, gel) ; self-test qui exporte 2 s à x0.5 et
  vérifie le nombre d'images ; build des neuf cibles.

#### 4. Repères pendant l'enregistrement

- **Constat** : la touche M n'agit que dans un replay (`JR/client/JustReplayClient.java`).
- **Comportement attendu** : une touche « Repère d'enregistrement » (dans les contrôles du jeu, sans conflit avec
  les touches vanilla ni F7 et F8) pose un repère au tick courant pendant un enregistrement, avec un petit message
  « Repère 3 ». Les repères vont dans `meta.json` (tick, nom) et arrivent sur la timeline à la première ouverture
  du replay. Le tampon « dernières minutes » garde les repères de la fenêtre sauvée, décalés au bon tick ; « les N
  prochaines minutes » aussi.
- **Versions** : les neuf cibles ; la touche passe par `JR/client/JRKeys.java` et `JR/platform/KeyScope.java`.
- **Preuve** : test unitaire du décalage des repères d'un clip du tampon ; self-test qui pose deux repères en
  enregistrant et les retrouve dans le projet ouvert.

#### 5. Visuels du monde et filtre de rendu

- **Constat** : l'onglet Monde n'a que la météo et l'heure (`JR/gui/WorldPanel.java`) ; le masquage se fait entité
  par entité (`JR/gui/EntitiesPanel.java`) ; le contour lumineux ne sert qu'à la sélection
  (`JR/mixin/EntityEditGlowMixin.java`).
- **Comportement attendu** : dans l'onglet Monde, « Vision nocturne » (pleine lumière sans l'effet bleuté),
  « Brouillard » (normal, réduit, coupé) et « Particules » (toutes, aucune, ou une liste de types à cocher avec
  recherche) ; dans l'onglet Entités, « Masquer un type » (tous les mobs d'un type, vanilla ou moddé) et « Contour
  lumineux » par entité, avec sa couleur d'équipe. Tout s'applique en aperçu, en lecture et à l'export, est gardé
  dans `project.json` et s'annule avec Ctrl + Z.
- **Versions** : les neuf cibles ; le brouillard et les particules n'ont pas la même API en 1.20.1, 1.21.1 et
  26.1.2 (rendu par état en 26.1.2), à relever dans les sources de chaque version ; avec Iris, vérifier que le
  brouillard du pack n'est pas cassé.
- **Preuve** : self-test qui coupe les particules et masque un type, puis compte les particules et entités
  rendues ; captures en jeu à regarder par le propriétaire pour la vision nocturne et le brouillard.
