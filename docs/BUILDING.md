# Building from source

Just Replay 1.1.0 (mod id `justreplay`), author / auteur : vyrriox.

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
| Bash | Only for the scripts of `tools/`. |

`fabric-1.20.1` compiles for Java 17, but Fabric Loom needs Java 21 to run: start its Gradle with Java 21. `fabric-26.1.2` sets its daemon to Java 25 in `gradle/gradle-daemon-jvm.properties`.

The continuous integration (`.github/workflows/build.yml`) builds the nine targets when a version tag is pushed, on pull requests, and when started by hand from the Actions page. A plain push to a branch does not start it.

### Repository layout

```
common/<mcversion>/src/main/     code and resources shared by every loader (1.20.1, 1.21.1, 26.1.2)
neoforge-1.20.1/                 NeoForge project for Minecraft 1.20.1
forge-1.20.1/                    Forge project for Minecraft 1.20.1
fabric-1.20.1/                   Fabric project for Minecraft 1.20.1
neoforge-1.21.1/                 NeoForge project for Minecraft 1.21.1
forge-1.21.1/                    Forge project for Minecraft 1.21.1
fabric-1.21.1/                   Fabric project for Minecraft 1.21.1
neoforge-26.1.2/                 NeoForge project for Minecraft 26.1.2
forge-26.1.2/                    Forge project for Minecraft 26.1.2
fabric-26.1.2/                   Fabric project for Minecraft 26.1.2
tools/                           branch generator, jar collector
docs/                            this documentation
```

- Each `<loader>-<mcversion>/` folder is a **standalone Gradle project**. There is no root build.
- Its build adds `../common/<mcversion>/src/main` to the main source set. The loader folder only holds the glue: entry points, events, networking and the `Platform` implementation.
- NeoForge for Minecraft 1.20.1 is the 47.1 fork of Forge. Its project uses the legacy plugin of ModDevGradle.
- `CompatTargets.java` of each `common/<mcversion>/` lists the integrations enabled per loader.

### Build a jar

From the folder of the target you want:

```
cd neoforge-1.21.1
./gradlew build --no-build-cache
```

On Windows use `gradlew.bat`. The jar is written to `build/libs/` and named `justreplay-<loader>-<mcversion>-<version>.jar`, for example `justreplay-forge-1.20.1-1.1.0.jar`.

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

```
cd fabric-1.21.1
./gradlew runClient -Pselftest
```

The run ends with a `SELFTEST DONE` line in the log; a pass has 0 FAIL lines.

### Production self test

A development run does not exercise the refmap or the remapped names. The three Fabric projects have a task that starts the self test on the remapped jar with Fabric API, with an offline player, so no account is needed:

```
cd fabric-1.21.1
./gradlew runProductionSelftest --no-build-cache
```

The game folder is `run-prod/` and the log `run-prod/logs/latest.log`. `fabric-1.20.1` also takes `-PprodRunDir=<folder>`. The NeoForge and Forge projects have no such task.

### Branch model

- `main` holds everything: `common/<mcversion>/` and one project per target.
- One branch per target, named like its folder (`neoforge-1.21.1`, `fabric-1.20.1`...), is generated from `main` by `tools/make_branches.sh`. It holds that single project at the repository root, with the common code merged into `src/main`.
- **Target branches are never edited by hand.** Change `main`, then run the script from a clean checkout at the repository root.
- Every pull request goes to `main`, from `feat/<name>` or `fix/<name>`.

---

## Français

### Prérequis

| Outil | Notes |
|---|---|
| Git | Pour cloner le dépôt. |
| JDK 17, 21, 25 | Le JDK des cibles à compiler : Java 17 pour Minecraft 1.20.1, 21 pour 1.21.1, 25 pour 26.1.2. Les projets utilisent une toolchain Gradle, avec le plugin foojay pour la trouver. |
| Gradle | Inutile. Chaque projet a son propre wrapper (`gradlew`, `gradlew.bat`). |
| Bash | Seulement pour les scripts de `tools/`. |

`fabric-1.20.1` compile pour Java 17, mais Fabric Loom a besoin de Java 21 pour tourner : lancer son Gradle avec Java 21. `fabric-26.1.2` règle son daemon sur Java 25 dans `gradle/gradle-daemon-jvm.properties`.

L'intégration continue (`.github/workflows/build.yml`) compile les neuf cibles quand un tag de version est poussé, sur les pull requests, et à la main depuis la page Actions. Un simple push sur une branche ne la lance pas.

### Organisation du dépôt

```
common/<mcversion>/src/main/     code et ressources partagés par tous les loaders (1.20.1, 1.21.1, 26.1.2)
<loader>-<mcversion>/            un projet Gradle par cible : neoforge, forge, fabric x 1.20.1, 1.21.1, 26.1.2
tools/                           générateur de branches, collecteur de jars
docs/                            cette documentation
```

- Chaque dossier `<loader>-<mcversion>/` est un **projet Gradle autonome**. Il n'y a pas de build à la racine.
- Son build ajoute `../common/<mcversion>/src/main` au source set principal. Le dossier du loader ne contient que le code propre au loader : points d'entrée, événements, réseau et implémentation de `Platform`.
- NeoForge pour Minecraft 1.20.1 est le fork 47.1 de Forge. Son projet utilise le plugin legacy de ModDevGradle.
- `CompatTargets.java` de chaque `common/<mcversion>/` liste les intégrations actives par loader.

### Compiler un jar

Depuis le dossier de la cible :

```
cd neoforge-1.21.1
./gradlew build --no-build-cache
```

Sous Windows, utiliser `gradlew.bat`. Le jar est écrit dans `build/libs/` sous le nom `justreplay-<loader>-<mcversion>-<version>.jar`, par exemple `justreplay-forge-1.20.1-1.1.0.jar`.

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

```
cd fabric-1.21.1
./gradlew runClient -Pselftest
```

Le lancement se termine par une ligne `SELFTEST DONE` dans le journal ; une réussite n'a aucune ligne FAIL.

### Test automatique en production

Un lancement de développement n'exerce ni la refmap ni les noms remappés. Les trois projets Fabric ont une tâche qui lance le test sur le jar remappé avec Fabric API, avec un joueur hors ligne, donc sans compte :

```
cd fabric-1.21.1
./gradlew runProductionSelftest --no-build-cache
```

Le dossier du jeu est `run-prod/` et le journal `run-prod/logs/latest.log`. `fabric-1.20.1` accepte aussi `-PprodRunDir=<dossier>`. Les projets NeoForge et Forge n'ont pas cette tâche.

### Modèle de branches

- `main` contient tout : `common/<mcversion>/` et un projet par cible.
- Une branche par cible, du nom de son dossier (`neoforge-1.21.1`, `fabric-1.20.1`...), est générée depuis `main` par `tools/make_branches.sh`. Elle contient ce seul projet à la racine, avec le code commun fusionné dans `src/main`.
- **Les branches de cible ne se modifient jamais à la main.** Changer `main`, puis lancer le script depuis une copie propre, à la racine du dépôt.
- Toute pull request va vers `main`, depuis `feat/<nom>` ou `fix/<nom>`.
