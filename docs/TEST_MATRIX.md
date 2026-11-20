# Test matrix

Just Replay 1.1.2 (mod id `justreplay`), author / auteur : vyrriox.

- [English](#english)
- [Français](#français)

---

## English

`tools/test_matrix.py` builds each target, then plays its in-game self test, one target after the other, and
writes one report for all of them. It never runs two builds or two games at once. Run it from the whole
repository (`develop` or `main`): a target branch holds a single project and no `tools/` folder.

### Requirements

- Python 3.8 or later (standard library only).
- The JDKs the targets build with: 17 (NeoForge and Forge 1.20.1), 21 (Fabric 1.20.1 and 1.21.1), 25 (26.1.2).
  The script reads them from `.github/workflows/build.yml` and finds the JDKs in `~/.jdks`, `~/.gradle/jdks`,
  `/usr/lib/jvm`, the usual `Program Files` folders, `JAVA_HOME_<n>_X64`, `JAVA<n>_HOME` or `JDK<n>_HOME`.
  `--jdk 21=<folder>` picks one by hand.
- On Linux without a screen: `xvfb-run`. The game then uses software rendering, which is slow but enough for
  the checks.
- No Minecraft account: the development clients are offline.

### Use

```bash
python3 tools/test_matrix.py --dry-run                 # the nine steps and their commands, nothing runs
python3 tools/test_matrix.py                           # every target
python3 tools/test_matrix.py --targets 1.21.1          # a Minecraft version (or a loader, or targets: a,b)
python3 tools/test_matrix.py --targets neoforge-1.21.1 --skip-build
python3 tools/test_matrix.py --skip-run --gradle-arg=--no-build-cache   # builds only, like the CI
python3 tools/test_matrix.py --parse neoforge-1.21.1/run-matrix/logs/latest.log
```

For each target the script:

1. checks that no Minecraft game is running: a build under a running development client crashes it, and the
   machine runs one game at a time;
2. builds the target with its JDK (`./gradlew build`: compile, unit tests, jar), with `--no-daemon` and two
   workers;
3. starts the development client with `-Pselftest=true` in a game folder of its own (`<target>/run-matrix`). The
   self test opens a fresh world, records, replays, seeks, edits, exports, then closes the game by itself;
4. waits until that game is gone (it ends what is left of it after a time out), then reads the `SELFTEST` lines
   of `<target>/run-matrix/logs/latest.log`.

A failed build skips that target's self test and the matrix goes on (`--stop-on-fail` stops it). Count about
2 minutes per build and 5 to 6 minutes per self test with software rendering, so about an hour and a half for
the nine targets.

### Report

Everything goes to `build/test-matrix/<date>/`, and `build/test-matrix/latest-report.md` is the last report:

- `report.md` and `report.json`: per target the build, the unit tests, the self test verdict, the PASS and FAIL
  counts, the time and the game log, then every FAIL line, crash report and time out;
- `selftest-<target>.txt`: every check of the target, one per line, `PASS` or `FAIL` first;
- in `report.md`, per target: the startup line of the game log (`Just Replay ... Integrations on: ...`), the
  number of integration warnings (`integration of Just Replay failed in`) and every `Replay: N packets skipped`
  or `Recording: N packets skipped` line. The self test turns Simple Voice Chat off at startup and writes an
  unreadable packet into its recording, so a normal run shows one warning, `failed: Simple Voice Chat (in self test
  forced failure)` in the startup line and `packet #16383 could not be read` in the skipped lines (see
  `-PselftestFailCompat` in `docs/BUILDING.md`). `--parse` prints them too;
- `gradle-build-<target>.log` and `gradle-run-<target>.log`: the whole Gradle output.

A line counts when it holds a verdict (`-> OK`, `OK`, `PASS`, `FAIL`; a line with two checks is FAIL if one
failed). The other `SELFTEST` lines are information. The self test verdict is PASS when the log has
`SELFTEST DONE`, at least one check and no FAIL; INCOMPLETE when the game closed before `SELFTEST DONE`; FAIL,
TIMEOUT or CRASH otherwise. Screenshots of the run are in `<target>/run-matrix/screenshots/`: looks, sounds and
smoothness still need a person.

Exit code: 0 everything passed, 1 a build or a self test failed, 2 something timed out, 3 a game was already
running, 4 bad options or a missing JDK.

### One game at a time

The check runs before every build and every game. By default a running game stops the matrix at once;
`--wait-busy 600` waits up to 10 minutes for it to close. A running server counts too, `--ignore-servers` only
waits for clients. `--list-games` shows what the script sees.

On a machine shared with other jobs, put every build and every game behind the machine lock:

```bash
python3 tools/test_matrix.py --wrap "vigie verrou-test --" --wait-busy 3600
```

### With the mods of a modpack

To test compatibility, give the `mods` folder of an instance and the target of that pack (its loader and
Minecraft version):

```bash
python3 tools/test_matrix.py --targets neoforge-1.21.1 --mods ~/instances/arcadia-v2/mods
```

- The game folder is `<target>/run-matrix-<pack name>` (`--game-dir` to choose it). The jars are linked into its
  `mods/` folder, or copied when links are not possible. Just Replay's own jars are left out: the development
  client runs the mod from the sources.
- Jars made for another loader are reported before the run.
- `forge-1.20.1`: released 1.20.1 jars use other names than the development game. The mods go to `devmods/` and
  are remapped through `-PdevMods`; jars that are not mods (the Immersive Vehicles content packs) stay in
  `mods/`. A mod that names its mixin targets in SRG itself, without a refmap (Armourer's Workshop 3.4), still
  stops that client at launch: test it with an installed game.
- `neoforge-1.20.1`: its development client cannot remap released jars, so most mods will not load there. Test
  1.20.1 packs on `forge-1.20.1`, or with an installed game.
- NeoForge and Forge 1.21.1 and 26.1.2 load released jars as they are. Fabric remaps them itself at launch.
- `forge-1.21.1` and `forge-26.1.2` keep the game assets in `<home>/.minecraft/assets`. Where that folder cannot
  be written (a sandbox), add `-P mcHome=<dir>` and put the assets of an earlier run in `<dir>/.minecraft/assets`
  (a link to `~/.gradle/caches/neoformruntime/assets` will do).
- For a whole matrix with mods, give a folder holding one subfolder per target (`neoforge-1.21.1/`,
  `forge-1.20.1/`...). A target without its subfolder runs without mods.
- A pack may need another loader version: `-P neo_version=21.1.265` (Sable and Create Aeronautics, for example)
  is passed to the build and to the game.
- The script only rewrites the `mods/` and `devmods/` folders of game folders it made (they hold a
  `.test-matrix` file). It refuses a game folder it did not make that already holds jars.

The startup line of the game log names the integrations that are on, off or not supported. The self test runs
extra checks for some mods when they are there (Immersive Vehicles, Curios, Emotecraft, Better Combat, ParCool,
Iron's Spells, More Player Models on 1.20.1 and NeoForge 1.21.1...): their lines show in the report like the others. What a pass proves is listed in
[COMPATIBILITY.md](COMPATIBILITY.md) (Run, Loaded, Read).

### Limits

- Development clients only: they do not use the refmap or the remapped names of a released jar. The Fabric
  projects also have `./gradlew runProductionSelftest` (see [BUILDING.md](BUILDING.md)).
- The self test covers recording, replay, seeks, the editor, exports and the integrations it knows. It does not
  judge how things look or sound.

---

## Français

`tools/test_matrix.py` compile chaque cible puis lance son test automatique en jeu, une cible après l'autre, et
écrit un seul rapport pour toutes. Il ne lance jamais deux builds ni deux jeux à la fois. Lance-le depuis le
dépôt complet (`develop` ou `main`) : une branche de cible ne contient qu'un projet et pas de dossier `tools/`.

### Prérequis

- Python 3.8 ou plus récent (bibliothèque standard seulement).
- Les JDK des cibles : 17 (NeoForge et Forge 1.20.1), 21 (Fabric 1.20.1 et 1.21.1), 25 (26.1.2). Le script les
  lit dans `.github/workflows/build.yml` et trouve les JDK dans `~/.jdks`, `~/.gradle/jdks`, `/usr/lib/jvm`, les
  dossiers `Program Files` habituels, `JAVA_HOME_<n>_X64`, `JAVA<n>_HOME` ou `JDK<n>_HOME`. `--jdk 21=<dossier>`
  en impose un.
- Sous Linux sans écran : `xvfb-run`. Le jeu passe alors en rendu logiciel, lent mais suffisant pour les
  vérifications.
- Pas de compte Minecraft : les clients de développement sont hors ligne.

### Utilisation

```bash
python3 tools/test_matrix.py --dry-run                 # les neuf étapes et leurs commandes, rien ne tourne
python3 tools/test_matrix.py                           # toutes les cibles
python3 tools/test_matrix.py --targets 1.21.1          # une version de Minecraft (ou un loader, ou des cibles : a,b)
python3 tools/test_matrix.py --targets neoforge-1.21.1 --skip-build
python3 tools/test_matrix.py --skip-run --gradle-arg=--no-build-cache   # builds seulement, comme la CI
python3 tools/test_matrix.py --parse neoforge-1.21.1/run-matrix/logs/latest.log
```

Pour chaque cible, le script :

1. vérifie qu'aucun jeu Minecraft ne tourne : un build sous un client de développement lancé le fait planter, et
   la machine ne fait tourner qu'un jeu à la fois ;
2. compile la cible avec son JDK (`./gradlew build` : compilation, tests unitaires, jar), avec `--no-daemon` et
   deux workers ;
3. lance le client de développement avec `-Pselftest=true` dans un dossier de jeu à lui (`<cible>/run-matrix`).
   Le test ouvre un monde neuf, enregistre, relit, saute dans le temps, édite, exporte, puis ferme le jeu tout
   seul ;
4. attend que ce jeu ait disparu (il termine ce qui en reste après un dépassement de délai), puis lit les lignes
   `SELFTEST` de `<cible>/run-matrix/logs/latest.log`.

Un build raté saute le test de sa cible et la matrice continue (`--stop-on-fail` l'arrête). Compte environ
2 minutes par build et 5 à 6 minutes par test en rendu logiciel, soit environ une heure et demie pour les neuf
cibles.

### Rapport

Tout va dans `build/test-matrix/<date>/`, et `build/test-matrix/latest-report.md` est le dernier rapport :

- `report.md` et `report.json` : par cible le build, les tests unitaires, le verdict du test, le nombre de PASS
  et de FAIL, la durée et le journal du jeu, puis chaque ligne FAIL, rapport de crash et dépassement de délai ;
- `selftest-<cible>.txt` : chaque vérification de la cible, une par ligne, précédée de `PASS` ou `FAIL` ;
- dans `report.md`, par cible : la ligne de démarrage du log du jeu (`Just Replay ... Integrations on: ...`), le
  nombre d'avertissements d'intégration (`integration of Just Replay failed in`) et chaque ligne
  `Replay: N packets skipped` ou `Recording: N packets skipped`. Le test automatique coupe Simple Voice Chat au
  démarrage et écrit un paquet illisible dans son enregistrement : une passe normale montre donc un avertissement,
  `failed: Simple Voice Chat (in self test forced failure)` dans la ligne de démarrage et
  `packet #16383 could not be read` dans les lignes de paquets ignorés (voir `-PselftestFailCompat` dans
  `docs/BUILDING.md`). `--parse` les affiche aussi ;
- `gradle-build-<cible>.log` et `gradle-run-<cible>.log` : toute la sortie de Gradle.

Une ligne compte quand elle porte un verdict (`-> OK`, `OK`, `PASS`, `FAIL` ; une ligne qui contient deux
vérifications est FAIL si l'une a échoué). Les autres lignes `SELFTEST` sont des informations. Le verdict du test
est PASS quand le journal contient `SELFTEST DONE`, au moins une vérification et aucun FAIL ; INCOMPLETE quand le
jeu s'est fermé avant `SELFTEST DONE` ; FAIL, TIMEOUT ou CRASH sinon. Les captures du test sont dans
`<cible>/run-matrix/screenshots/` : l'apparence, le son et la fluidité demandent toujours l'œil d'une personne.

Code de sortie : 0 tout est passé, 1 un build ou un test a échoué, 2 un délai a été dépassé, 3 un jeu tournait
déjà, 4 mauvaises options ou JDK manquant.

### Un seul jeu à la fois

La vérification passe avant chaque build et chaque jeu. Par défaut, un jeu lancé arrête la matrice tout de suite ;
`--wait-busy 600` attend jusqu'à 10 minutes qu'il se ferme. Un serveur lancé compte aussi, `--ignore-servers`
n'attend que les clients. `--list-games` montre ce que le script voit.

Sur une machine partagée avec d'autres travaux, mets chaque build et chaque jeu derrière le verrou de la machine :

```bash
python3 tools/test_matrix.py --wrap "vigie verrou-test --" --wait-busy 3600
```

### Avec les mods d'un modpack

Pour tester la compatibilité, donne le dossier `mods` d'une instance et la cible de ce pack (son loader et sa
version de Minecraft) :

```bash
python3 tools/test_matrix.py --targets neoforge-1.21.1 --mods ~/instances/arcadia-v2/mods
```

- Le dossier de jeu est `<cible>/run-matrix-<nom du pack>` (`--game-dir` pour le choisir). Les jars sont liés
  dans son dossier `mods/`, ou copiés quand un lien n'est pas possible. Les jars de Just Replay lui-même sont
  écartés : le client de développement fait tourner le mod depuis les sources.
- Les jars faits pour un autre loader sont signalés avant le lancement.
- `forge-1.20.1` : les jars 1.20.1 publiés n'utilisent pas les mêmes noms que le jeu de développement. Les mods
  vont dans `devmods/` et sont remappés par `-PdevMods` ; les jars qui ne sont pas des mods (les packs de contenu
  d'Immersive Vehicles) restent dans `mods/`. Un mod qui nomme lui-même ses cibles de mixin en SRG, sans refmap
  (Armourer's Workshop 3.4), arrête quand même ce client au lancement : teste-le avec un jeu installé.
- `neoforge-1.20.1` : son client de développement ne sait pas remapper les jars publiés, donc la plupart des mods
  n'y chargeront pas. Teste les packs 1.20.1 sur `forge-1.20.1`, ou avec un jeu installé.
- NeoForge et Forge 1.21.1 et 26.1.2 chargent les jars publiés tels quels. Fabric les remappe lui-même au
  lancement.
- `forge-1.21.1` et `forge-26.1.2` gardent les assets du jeu dans `<home>/.minecraft/assets`. Là où ce dossier
  n'est pas inscriptible (bac à sable), ajoute `-P mcHome=<dossier>` et mets les assets d'un lancement précédent
  dans `<dossier>/.minecraft/assets` (un lien vers `~/.gradle/caches/neoformruntime/assets` suffit).
- Pour une matrice complète avec des mods, donne un dossier qui contient un sous-dossier par cible
  (`neoforge-1.21.1/`, `forge-1.20.1/`...). Une cible sans son sous-dossier tourne sans mods.
- Un pack peut demander une autre version du loader : `-P neo_version=21.1.265` (Sable et Create Aeronautics,
  par exemple) est passé au build et au jeu.
- Le script ne réécrit que les dossiers `mods/` et `devmods/` des dossiers de jeu qu'il a créés (ils contiennent
  un fichier `.test-matrix`). Il refuse un dossier de jeu qu'il n'a pas créé et qui contient déjà des jars.

La ligne de démarrage du journal du jeu nomme les intégrations actives, coupées ou non prises en charge. Le test
fait des vérifications en plus pour certains mods quand ils sont là (Immersive Vehicles, Curios, Emotecraft,
Better Combat, ParCool, Iron's Spells, More Player Models en 1.20.1 et NeoForge 1.21.1...) : leurs lignes apparaissent dans le
rapport comme les autres. Ce qu'une réussite prouve est
décrit dans [COMPATIBILITY.md](COMPATIBILITY.md) (Run, Loaded, Read).

### Limites

- Clients de développement seulement : ils n'utilisent ni la refmap ni les noms remappés d'un jar publié. Les
  projets Fabric ont aussi `./gradlew runProductionSelftest` (voir [BUILDING.md](BUILDING.md)).
- Le test couvre l'enregistrement, la lecture, les sauts, l'éditeur, les exports et les intégrations qu'il
  connaît. Il ne juge ni l'apparence ni le son.
