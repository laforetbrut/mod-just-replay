# Building from source

Just Replay 1.1.2 (mod id `justreplay`), author / auteur : vyrriox.

- [English](#english)
- [Français](#français)

Repository / Dépôt : <https://github.com/laforetbrut/mod-just-replay>

---

## English

### Requirements

| Tool | Notes |
|---|---|
| Git | To clone the repository. |
| JDK 17, 21, 25 | The JDK of the targets you build: Java 17 for Minecraft 1.20.1, 21 for 1.21.1, 25 for 26.1.2. The projects use a Gradle toolchain, with the foojay resolver plugin to locate one. |
| Gradle | Not needed. Each project ships its own wrapper (`gradlew`, `gradlew.bat`). |
| Bash, Python 3 | Only for the scripts of `tools/` (Python for `test_matrix.py` and `check_layers.py`). |

`fabric-1.20.1` compiles for Java 17, but Fabric Loom needs Java 21 to run: start its Gradle with Java 21. `fabric-26.1.2` sets its daemon to Java 25 in `gradle/gradle-daemon-jvm.properties`.

The continuous integration (`.github/workflows/build.yml`) builds the nine targets when a version tag is pushed, on pull requests, and when started by hand from the Actions page. A plain push to a branch does not start it. Each target's `./gradlew build` runs its unit tests; when a build fails, its test report is kept as the `test-report-<target>` artifact. A second job checks the log reader of `tools/test_matrix.py`, a third one the layers (`tools/check_layers.py`, see below).

### Repository layout

```
core/src/main/java/              plain Java shared by every target, written once (no game code)
core/src/test/java/              unit tests of core, run by every target's build
common/<mcversion>/src/main/     code and resources shared by every loader (1.20.1, 1.21.1, 26.1.2)
common/<mcversion>/src/test/     unit tests of that shared code, run by every loader's build
neoforge-1.20.1/                 NeoForge project for Minecraft 1.20.1
forge-1.20.1/                    Forge project for Minecraft 1.20.1
fabric-1.20.1/                   Fabric project for Minecraft 1.20.1
neoforge-1.21.1/                 NeoForge project for Minecraft 1.21.1
forge-1.21.1/                    Forge project for Minecraft 1.21.1
fabric-1.21.1/                   Fabric project for Minecraft 1.21.1
neoforge-26.1.2/                 NeoForge project for Minecraft 26.1.2
forge-26.1.2/                    Forge project for Minecraft 26.1.2
fabric-26.1.2/                   Fabric project for Minecraft 26.1.2
tools/                           branch generator, jar collector, test matrix, layer check
docs/                            this documentation
```

- Each `<loader>-<mcversion>/` folder is a **standalone Gradle project**. There is no root build.
- Its build adds `../core/src/main` and `../common/<mcversion>/src/main` to the main source set, and `../core/src/test` and `../common/<mcversion>/src/test` to the test source set. The loader folder only holds the glue: entry points, events, networking and the `Platform` implementation.
- `core/` holds the logic that does not touch the game: the editor project (cameras and their paths, cuts, overlays, time shifts), replay storage, formats and names, the speed track, effects, audio mixing, Blockbench models, the config file, the integration guard. It is the same file for every target, so a fix there lands on all nine at once and a port never touches it. It imports only the JDK, Gson and SLF4J (the libraries every target ships), never Minecraft, a loader or a class of `common/`, and compiles with Java 17, the lowest of the targets. Constants it needs live in `JustReplayCore` (mod id, logger, config file name) and `ArmorBits`; `JustReplay` and `ArmorSlots` hand the same values to the game code. A small game helper it needs is copied with the same code next to the class that uses it (`CameraPath.wrapDegrees`, `Overlay.stripFormatting`) instead of being called. Write new plain logic in core from the start.
- `python3 tools/check_layers.py` checks those rules: no game, loader or common class in core, no plain Java file left identical in every `common/<mcversion>/` (it belongs in core), no loader import in common. `--self-check` proves it on known mistakes. The CI runs both.
- NeoForge for Minecraft 1.20.1 is the 47.1 fork of Forge. Its project uses the legacy plugin of ModDevGradle.
- `CompatTargets.java` of each `common/<mcversion>/` lists the integrations enabled per loader.

### Build a jar

From the folder of the target you want:

```
cd neoforge-1.21.1
./gradlew build --no-build-cache
```

On Windows use `gradlew.bat`. The jar is written to `build/libs/` and named `justreplay-<loader>-<mcversion>-<version>.jar`, for example `justreplay-forge-1.20.1-1.1.0.jar`.

The build also runs the unit tests of `core/src/test` and `common/<mcversion>/src/test` (JUnit 5), the same ones on every target. They only use classes without game code and start no game; `./gradlew test` runs them alone, the report is in `build/reports/tests/test/`. They cover:

- the integration guard, the integration switches of the config and the skipped packets counter;
- the cleaning of replay names and the renaming of replay folders;
- the angles of camera paths (the camera turns the short way, like the game's `Mth.wrapDegrees`) and the formatting codes taken out of overlay names;
- the names of video exports (the exported range and the final file name) and what the render veil shows (share done, time spent and left, GUI scale of the window);
- the changed mods warning, the markers placed while recording and the server resource packs of a replay;
- the editor markers: saved to `project.json` and read back, older projects, refused ranges, places and targets, time insertions and removals;
- the light, fog and particle settings, and the speed track (video time of each replay tick);
- the Armourer's Workshop wardrobe skins a hidden armor slot takes with it.

- **Use `--no-build-cache` for a jar you will ship or run in production.** A cache hit once produced a Forge 1.20.1 jar without its refmap (see `ERROR_LOG.md`, now also guarded in that project). After such a build, check that the refmap is in the jar.
- Install the jar of `build/libs/`. NeoForge 1.20.1 and the Fabric projects of 1.20.1 and 1.21.1 write a development jar elsewhere, which only works in development runs.
- Outside of development, NeoForge and Forge 1.20.1 run on SRG names and Fabric 1.20.1 and 1.21.1 on intermediary names, so those jars are remapped after the build (`reobfJar`, `remapJar`). Minecraft 26.1 is not obfuscated.
- `tools/collect_jars.sh` copies each built jar into `jars/<version>/` at the repository root (not in Git); `tools/collect_jars.sh --build` builds them all first.

### Toolchain of each project

Read from each project's `gradle.properties`, `build.gradle` and wrapper.

Minecraft 1.20.1:

| | NeoForge 1.20.1 | Forge 1.20.1 | Fabric 1.20.1 |
|---|---|---|---|
| Gradle wrapper | 9.2.1 | 8.12.1 | 9.5.1 |
| Build plugin | `net.neoforged.moddev.legacyforge` 2.0.147 | `net.minecraftforge.gradle` 6.0.54, `org.parchmentmc.librarian.forgegradle` 1.2.0, `org.spongepowered.mixin` | `fabric-loom` 1.17.21 |
| Loader | NeoForge 47.1.106 | Forge 47.4.10 | Fabric Loader 0.19.5, Fabric API 0.92.12+1.20.1 |
| Parchment | 2023.09.03 | 2023.09.03 | none |
| Java of the game | 17 | 17 | 17 |
| Java running Gradle in CI | 17 | 17 | 21 |

Minecraft 1.21.1:

| | NeoForge 1.21.1 | Forge 1.21.1 | Fabric 1.21.1 |
|---|---|---|---|
| Gradle wrapper | 8.14 | 9.3.1 | 9.5.1 |
| Build plugin | `net.neoforged.moddev` 2.0.148 | `net.minecraftforge.gradle` 7.0.40 | `fabric-loom` 1.17.21 |
| Loader | NeoForge 21.1.221 | Forge 52.1.16 | Fabric Loader 0.19.5, Fabric API 0.116.17+1.21.1 |
| Parchment | 2024.11.17 | none | none |
| Java of the game | 21 | 21 | 21 |
| Java running Gradle in CI | 21 | 21 | 21 |

Minecraft 26.1.2:

| | NeoForge 26.1.2 | Forge 26.1.2 | Fabric 26.1.2 |
|---|---|---|---|
| Gradle wrapper | 9.2.1 | 9.5.0 | 9.7.1 |
| Build plugin | `net.neoforged.moddev` 2.0.147 | `net.minecraftforge.gradle` 7.0.40, `net.minecraftforge.accesstransformers` 2.0.0 | `net.fabricmc.fabric-loom` 1.18.2 |
| Loader | NeoForge 26.1.2.109 | Forge 64.1.3 | Fabric Loader 0.19.5, Fabric API 0.155.3+26.1.2 |
| Mappings | none, the game ships without obfuscation | none | none |
| Java of the game | 25 | 25 | 25 |
| Java running Gradle in CI | 25 | 25 | 25 |

### Development runs and self test

Every project takes these properties on `runClient`, from its own folder:

| Property | Purpose |
|---|---|
| `-Pselftest` | Automated record, replay, edit and export test in a development client. |
| `-Pjob=<file>` | Runs a scene job file (see the README). |
| `-PgameDir=<folder>` | Another game folder for the development client. |
| `-PselftestVehicle=<item id>` | With `-Pselftest` and Immersive Vehicles: the car the self test places (an item id or its start, `mts:` optional). By default the Official Content Pack's Mustang, else the first car of any installed pack. |
| `-PselftestFailCompat=<mod id>` | With `-Pselftest`: the integration the self test turns off at startup to check the compat safety net (one warning, listed as failed in the startup line, the replay goes on). `voicechat` (Simple Voice Chat, supported on every loader) by default, counted as installed when it is not; `none` turns the check off. Pick a mod the run does not test, its own checks fail once it is off. |
| `-PdevMods=<folder>` | `forge-1.20.1` only: loads the released jars of that folder, remapped to the names of the development game. A released 1.20.1 jar put in `mods/` fails on its first game call. Content packs that a mod reads from `mods/` (the Immersive Vehicles packs) still go in `<game folder>/mods`. |
| `-PmcHome=<folder>` | `forge-1.21.1` and `forge-26.1.2` only: another home for the development game, whose launcher keeps the game assets in `<home>/.minecraft/assets`. For a machine where that folder cannot be written. |

```
cd fabric-1.21.1
./gradlew runClient -Pselftest
```

The run ends with a `SELFTEST DONE` line in the log; a pass has 0 FAIL lines. To build and self test every target
one after the other, with one report, use `tools/test_matrix.py` ([TEST_MATRIX.md](TEST_MATRIX.md)).

### Production self test

A development run does not exercise the refmap or the remapped names. The three Fabric projects have a task that starts the self test on the remapped jar with Fabric API, with an offline player, so no account is needed:

```
cd fabric-1.21.1
./gradlew runProductionSelftest --no-build-cache
```

The game folder is `run-prod/` and the log `run-prod/logs/latest.log`. `fabric-1.20.1` also takes `-PprodRunDir=<folder>`. The NeoForge and Forge projects have no such task.

### Branch model

- `develop` holds everything: `core/`, `common/<mcversion>/` and one project per target. Every pull request goes to `develop`, from `feat/<name>` or `fix/<name>`.
- `main` is the latest release. To ship a version, a pull request from `develop` to `main` is merged (a plain merge commit: `main` gets the files of `develop` and nothing else), then the release is tagged on `main`.
- One branch per target, named like its folder (`neoforge-1.21.1`, `fabric-1.20.1`...), is generated from `main` by `tools/make_branches.sh` once `main` has the release. It holds that single project at the repository root, with the core and common code merged into `src/main` and their tests into `src/test`. A file the project has itself wins over the copy of common (on `main` its build leaves that copy out, like `fabric-1.20.1` does for `CuriosCompat`).
- **Target branches are never edited by hand.** Run the script from a clean checkout at the repository root, once `main` is pulled: it refuses a branch that is behind its copy on origin, and warns when `main` is behind the branch it builds from.

---

## Français

### Prérequis

| Outil | Notes |
|---|---|
| Git | Pour cloner le dépôt. |
| JDK 17, 21, 25 | Le JDK des cibles à compiler : Java 17 pour Minecraft 1.20.1, 21 pour 1.21.1, 25 pour 26.1.2. Les projets utilisent une toolchain Gradle, avec le plugin foojay pour la trouver. |
| Gradle | Inutile. Chaque projet a son propre wrapper (`gradlew`, `gradlew.bat`). |
| Bash, Python 3 | Seulement pour les scripts de `tools/` (Python pour `test_matrix.py` et `check_layers.py`). |

`fabric-1.20.1` compile pour Java 17, mais Fabric Loom a besoin de Java 21 pour tourner : lancer son Gradle avec Java 21. `fabric-26.1.2` règle son daemon sur Java 25 dans `gradle/gradle-daemon-jvm.properties`.

L'intégration continue (`.github/workflows/build.yml`) compile les neuf cibles quand un tag de version est poussé, sur les pull requests, et à la main depuis la page Actions. Un simple push sur une branche ne la lance pas. Le `./gradlew build` de chaque cible lance ses tests unitaires ; quand un build échoue, son rapport de tests est gardé dans l'artefact `test-report-<cible>`. Un deuxième job vérifie le lecteur de journal de `tools/test_matrix.py`, un troisième les couches (`tools/check_layers.py`, voir plus bas).

### Organisation du dépôt

```
core/src/main/java/              Java pur partagé par toutes les cibles, écrit une seule fois (sans code du jeu)
core/src/test/java/              tests unitaires de core, lancés par le build de chaque cible
common/<mcversion>/src/main/     code et ressources partagés par tous les loaders (1.20.1, 1.21.1, 26.1.2)
common/<mcversion>/src/test/     tests unitaires de ce code partagé, lancés par le build de chaque loader
<loader>-<mcversion>/            un projet Gradle par cible : neoforge, forge, fabric x 1.20.1, 1.21.1, 26.1.2
tools/                           générateur de branches, collecteur de jars, matrice de tests, contrôle des couches
docs/                            cette documentation
```

- Chaque dossier `<loader>-<mcversion>/` est un **projet Gradle autonome**. Il n'y a pas de build à la racine.
- Son build ajoute `../core/src/main` et `../common/<mcversion>/src/main` au source set principal, et `../core/src/test` et `../common/<mcversion>/src/test` au source set de test. Le dossier du loader ne contient que le code propre au loader : points d'entrée, événements, réseau et implémentation de `Platform`.
- `core/` contient la logique qui ne touche pas au jeu : projet de l'éditeur (caméras et leurs trajectoires, coupes, overlays, décalages de temps), stockage, formats et noms des replays, piste de vitesse, effets, mixage audio, modèles Blockbench, fichier de config, garde des intégrations. C'est le même fichier pour toutes les cibles : un correctif y arrive d'un coup sur les neuf, et un portage n'y touche jamais. Il n'importe que le JDK, Gson et SLF4J (les bibliothèques fournies par chaque cible), jamais Minecraft, un loader ou une classe de `common/`, et compile en Java 17, le plus bas des cibles. Les constantes dont il a besoin sont dans `JustReplayCore` (id du mod, logger, nom du fichier de config) et `ArmorBits` ; `JustReplay` et `ArmorSlots` donnent les mêmes valeurs au code du jeu. Une petite fonction du jeu dont il a besoin est recopiée à l'identique à côté de la classe qui s'en sert (`CameraPath.wrapDegrees`, `Overlay.stripFormatting`) au lieu d'être appelée. Écrire la nouvelle logique pure directement dans core.
- `python3 tools/check_layers.py` vérifie ces règles : aucune classe du jeu, d'un loader ou de common dans core, aucun fichier Java pur resté identique dans chaque `common/<mcversion>/` (sa place est dans core), aucun import de loader dans common. `--self-check` le prouve sur des erreurs connues. La CI lance les deux.
- NeoForge pour Minecraft 1.20.1 est le fork 47.1 de Forge. Son projet utilise le plugin legacy de ModDevGradle.
- `CompatTargets.java` de chaque `common/<mcversion>/` liste les intégrations actives par loader.

### Compiler un jar

Depuis le dossier de la cible :

```
cd neoforge-1.21.1
./gradlew build --no-build-cache
```

Sous Windows, utiliser `gradlew.bat`. Le jar est écrit dans `build/libs/` sous le nom `justreplay-<loader>-<mcversion>-<version>.jar`, par exemple `justreplay-forge-1.20.1-1.1.0.jar`.

Le build lance aussi les tests unitaires de `core/src/test` et de `common/<mcversion>/src/test` (JUnit 5), les mêmes sur chaque cible. Ils n'utilisent que des classes sans code du jeu et ne lancent aucun jeu ; `./gradlew test` les lance seuls, le rapport est dans `build/reports/tests/test/`. Ils couvrent :

- la garde des intégrations, les interrupteurs d'intégration de la config et le compteur de paquets ignorés ;
- le nettoyage des noms de replay et le renommage des dossiers de replay ;
- les angles des trajectoires de caméra (la caméra tourne par le plus court, comme `Mth.wrapDegrees` du jeu) et les codes de mise en forme retirés des noms d'overlays ;
- les noms des exports vidéo (la plage exportée et le nom final du fichier) et ce que montre le voile de rendu (part faite, temps passé et restant, échelle d'interface de la fenêtre) ;
- l'avertissement des mods changés, les repères posés pendant l'enregistrement et les packs de ressources du serveur d'un replay ;
- les repères de l'éditeur : sauvés dans `project.json` et relus, anciens projets, plages, points et cibles refusés, insertions et retraits de temps ;
- les réglages de lumière, de brouillard et de particules, et la piste de vitesse (temps vidéo de chaque tick du replay) ;
- les skins de garde-robe d'Armourer's Workshop qu'un emplacement d'armure masqué emporte avec lui.

- **Utiliser `--no-build-cache` pour un jar à publier ou à lancer en production.** Une reprise du cache a déjà produit un jar Forge 1.20.1 sans sa refmap (voir `ERROR_LOG.md`, ce projet est maintenant aussi protégé). Après un tel build, vérifier que la refmap est dans le jar.
- Installer le jar de `build/libs/`. NeoForge 1.20.1 et les projets Fabric de 1.20.1 et 1.21.1 écrivent ailleurs un jar de développement, qui ne marche qu'en développement.
- Hors développement, NeoForge et Forge 1.20.1 tournent sur les noms SRG et Fabric 1.20.1 et 1.21.1 sur les noms intermediary : ces jars sont remappés après le build (`reobfJar`, `remapJar`). Minecraft 26.1 n'est pas obfusqué.
- `tools/collect_jars.sh` copie chaque jar compilé dans `jars/<version>/` à la racine du dépôt (hors Git) ; `tools/collect_jars.sh --build` les compile d'abord.

### Outils de chaque projet

Les versions (wrapper, plugin, loader, Java) sont dans les tableaux de la section anglaise, lus dans les fichiers de chaque projet.

### Lancements de développement et test automatique

Chaque projet accepte ces propriétés sur `runClient`, depuis son dossier :

| Propriété | Rôle |
|---|---|
| `-Pselftest` | Test automatique d'enregistrement, lecture, édition et export dans un client de développement. |
| `-Pjob=<fichier>` | Déroule un fichier de tâche de scène (voir le README). |
| `-PgameDir=<dossier>` | Un autre dossier de jeu pour le client de développement. |
| `-PselftestVehicle=<id d'objet>` | Avec `-Pselftest` et Immersive Vehicles : la voiture que pose le test automatique (un id d'objet ou son début, `mts:` facultatif). Par défaut la Mustang de l'Official Content Pack, sinon la première voiture d'un pack installé. |
| `-PselftestFailCompat=<id du mod>` | Avec `-Pselftest` : l'intégration que le test automatique coupe au démarrage pour vérifier le filet de sécurité compat (un seul avertissement, donnée en échec dans la ligne de démarrage, le replay continue). `voicechat` (Simple Voice Chat, pris en charge sur tous les loaders) par défaut, compté comme installé s'il ne l'est pas ; `none` coupe ce contrôle. Choisis un mod que la passe ne teste pas : ses propres contrôles échouent une fois coupé. |
| `-PdevMods=<dossier>` | `forge-1.20.1` seulement : charge les jars publiés de ce dossier, remappés vers les noms du jeu de développement. Un jar 1.20.1 publié posé dans `mods/` échoue à son premier appel au jeu. Les packs de contenu qu'un mod lit dans `mods/` (ceux d'Immersive Vehicles) vont toujours dans `<dossier du jeu>/mods`. |
| `-PmcHome=<dossier>` | `forge-1.21.1` et `forge-26.1.2` seulement : un autre home pour le jeu de développement, dont le lanceur garde les assets du jeu dans `<home>/.minecraft/assets`. Pour une machine où ce dossier n'est pas inscriptible. |

```
cd fabric-1.21.1
./gradlew runClient -Pselftest
```

Le lancement se termine par une ligne `SELFTEST DONE` dans le journal ; une réussite n'a aucune ligne FAIL. Pour
compiler et tester toutes les cibles l'une après l'autre, avec un seul rapport, utilise `tools/test_matrix.py`
([TEST_MATRIX.md](TEST_MATRIX.md)).

### Test automatique en production

Un lancement de développement n'exerce ni la refmap ni les noms remappés. Les trois projets Fabric ont une tâche qui lance le test sur le jar remappé avec Fabric API, avec un joueur hors ligne, donc sans compte :

```
cd fabric-1.21.1
./gradlew runProductionSelftest --no-build-cache
```

Le dossier du jeu est `run-prod/` et le journal `run-prod/logs/latest.log`. `fabric-1.20.1` accepte aussi `-PprodRunDir=<dossier>`. Les projets NeoForge et Forge n'ont pas cette tâche.

### Modèle de branches

- `develop` contient tout : `core/`, `common/<mcversion>/` et un projet par cible. Toute pull request va vers `develop`, depuis `feat/<nom>` ou `fix/<nom>`.
- `main` est la dernière version publiée. Pour sortir une version, une pull request de `develop` vers `main` est fusionnée (un simple commit de fusion : `main` reçoit les fichiers de `develop` et rien d'autre), puis la version est taguée sur `main`.
- Une branche par cible, du nom de son dossier (`neoforge-1.21.1`, `fabric-1.20.1`...), est générée depuis `main` par `tools/make_branches.sh` une fois la version sur `main`. Elle contient ce seul projet à la racine, avec le code de core et de common fusionné dans `src/main` et leurs tests dans `src/test`. Un fichier que le projet a lui-même l'emporte sur la copie de common (sur `main` son build laisse cette copie de côté, comme `fabric-1.20.1` pour `CuriosCompat`).
- **Les branches de cible ne se modifient jamais à la main.** Lancer le script depuis une copie propre, à la racine du dépôt, une fois `main` à jour : il refuse une branche en retard sur sa copie d'origin, et prévient quand `main` est en retard sur la branche dont il part.
