#!/bin/sh
#
# Publishes this engine, then builds, tests and runs `templates/new-game` from a directory
# OUTSIDE this repository against the published artifacts, and proves the gates it inherited can
# fail (issue #265).
#
# The legs below run in order. Each writes its whole transcript into the report directory, and the
# script stops at the first one that does not do what it says.
#
#   0. publish  `publishToMavenLocal` for the engine's modules, and again for `build-logic`,
#               which carries the convention plugins and the version catalog. Nothing leaves this
#               machine: the local Maven repository stands in for Maven Central, and is the whole
#               difference between this proof and a release.
#   0b. namespace  every plugin marker this run published is inside `dev.wildware`, and every
#               plugin the template applies has one. Central authorises a publisher per namespace
#               and a local repository does not, so this is the one rule the stand-in above drops
#               - and the one that took the first real release run down (issue #265).
#   0a. no-checkout-path  nothing in the copy names a path into this checkout, with a control
#               beside it that makes the same search find something it should. A game that reached
#               back into the tree it was copied from would build here and nowhere else.
#   1. build    `./gradlew build` in the copied game: compiles against the published engine, runs
#               the game's own tests, and runs udeaVerifyModuleGraph, udeaVerifyDeterminism,
#               udeaVerifyKotlinPin and udeaVerifyCompilerPlugin over it.
#   2. run      `./gradlew run`: the game simulates 600 ticks headless and prints where its
#               rovers ended up. "Builds" is not "runs", so both are here.
#   2b. window  `./gradlew runWindow` on a virtual X display, left drawing for fifteen seconds and
#               then photographed from outside the game by ffmpeg while the window is still up: the
#               screen must hold the rover model and the sky (issue #269).
#               `scripts/outside-game-window.sh` is the leg.
#   2c. window-red the same leg on a copy whose rovers name no model: the screen still holds the
#               sky and must hold no rover, so the count in 2b is seen to fail for the reason it
#               exists - a window that is up and drawing nothing the game declared.
#   2d. window-dead the same leg on a copy whose window closes itself after five seconds: 2b's
#               fifteen-second wait has to refuse it, so the soak in 2b is seen to be capable of
#               failing. This is the shape #275 shipped - a render loop that ended early and a game
#               that exited 0 with nothing logged.
#   3. bridge   the generated gamebridge.json names this game's own agent port range, which is
#               what keeps two games on one machine out of each other's instances.
#   4. det-red  a wall-clock read planted in the game's simulation package: udeaVerifyDeterminism
#               must fail with DET001. A gate nobody has seen fail is indistinguishable from one
#               that cannot.
#   5. graph-red a Kotlin scripting host added to the game's runtime classpath:
#               udeaVerifyModuleGraph must fail with UDEA-MG-005.
#   6. components the game's `@Replicated` component was numbered from `net-components.lock`, and
#               nothing in the game's build scripts passes `udea.projectComponents` by hand
#               (issue #274 - until it, there was no way for a game to pass it at all).
#   7. id-moved a name inserted ahead of it in the sorted file moves its id. id 0 is also what a
#               module numbering its own symbols hands out, so leg 6 alone cannot tell them apart.
#   8. no-lock / write-lock / after-write
#               with no registry, the build fails naming both the component and
#               `udeaWriteNetComponents`; that task then writes the registry from what the failed
#               build reported, and the game builds against it.
#
# The game is copied into a temporary directory outside this repository on purpose, and nothing
# in it names a path to this checkout: a game built from inside the tree, or one that includes
# the engine's build, would prove nothing about a game that does neither.
#
# Usage:  sh scripts/outside-game-proof.sh
# Report: build/reports/udea/outside-game/
#
# Needs `xvfb-run`, `ffmpeg`, `python3` and a software OpenGL driver for leg 2b, and fails rather
# than skipping without them: a proof that the template draws which quietly did not look is the
# failure mode this repository's GL tests already had once.
#
# Two places it writes that are not in this checkout, both overridable:
#   UDEA_OUTSIDE_GAME_DIR  where the game is copied to (default: $TMPDIR/udea-outside-game-proof)
#   UDEA_M2_REPO           a Maven repository of this run's own, instead of ~/.m2/repository.
#                          Passed to every Gradle run as `-Dmaven.repo.local`, which both
#                          `publishToMavenLocal` and a build's `mavenLocal()` read, so a machine
#                          whose `~/.m2` other builds share is not written to at all.
set -eu

REPO=$(cd "$(dirname "$0")/.." && pwd)
REPORT="$REPO/build/reports/udea/outside-game"
WORK=${UDEA_OUTSIDE_GAME_DIR:-${TMPDIR:-/tmp}/udea-outside-game-proof}
GAME="$WORK/my-game"

: "${JAVA_HOME:?set JAVA_HOME to a JDK 21 - Gradle 8.13 does not support 25}"

# The local Maven repository this run publishes to and resolves from. `M2_REPO` is the older
# spelling, which only ever said where to *look*; `UDEA_M2_REPO` also says where to write.
M2_ROOT=${UDEA_M2_REPO:-${M2_REPO:-$HOME/.m2/repository}}
LOCAL_REPO_ARG=${UDEA_M2_REPO:+-Dmaven.repo.local=$UDEA_M2_REPO}

# The version both halves use. Read out of the template's own `gradle.properties`, so the engine
# is published at the version the game asks for and the two cannot drift: a proof that published
# 0.2.0 and built a game against 0.1.0 would resolve whatever was lying in the local repository
# from a previous run, which is the one failure this script must not be able to have.
UDEA_VERSION=$(sed -n 's/^udeaVersion=//p' "$REPO/templates/new-game/gradle.properties")
[ -n "$UDEA_VERSION" ] || { echo "no udeaVersion in templates/new-game/gradle.properties" >&2; exit 1; }

rm -rf "$WORK"
mkdir -p "$GAME" "$REPORT"
cp -r "$REPO/templates/new-game/." "$GAME/"

# The game's own wrapper. A new repository runs `gradle wrapper` once; copying this one keeps the
# proof off the network and pins the same Gradle the engine builds with.
cp "$REPO/gradlew" "$REPO/gradlew.bat" "$GAME/"
mkdir -p "$GAME/gradle"
cp -r "$REPO/gradle/wrapper" "$GAME/gradle/"
chmod +x "$GAME/gradlew"

say() { printf '\n=== %s\n' "$1"; }

fail() {
    echo "PROOF FAILED: $1" >&2
    exit 1
}

# --- 0a. nothing in the copy names a path into this checkout ----------------------------------
#
# "Outside this repository" is the whole claim, and a game that reached back into the tree it was
# copied from would still build here and nowhere else. `-I` skips the model, which is bytes.
#
# The control first, and it is not ceremony: a search that finds nothing and a search that is
# broken print the same thing, so this one is made to find something it certainly should before
# its silence is believed. An `--include` glob left unquoted, a pattern the shell ate, a directory
# that was not there - each of those would have gone through as a pass.
say "no-checkout-path: the copy names no path into $REPO"
grep -rIlF -- "com.example.newgame" "$GAME" > /dev/null ||
    fail "the checkout-path search is broken: it cannot find the game's own package in $GAME"
if grep -rIlF -- "$REPO" "$GAME" > "$REPORT/checkout-references.txt"; then
    fail "the copied game names this checkout; see $REPORT/checkout-references.txt"
fi
echo "  the control found the game's own package, and the search found no path into this checkout"

engine_gradle() {
    leg=$1
    shift
    say "$leg: ./gradlew $*"
    if (cd "$REPO" && sh ./gradlew "$@" ${LOCAL_REPO_ARG:+"$LOCAL_REPO_ARG"} --console=plain --stacktrace) > "$REPORT/$leg.log" 2>&1; then
        echo "  green  -> $REPORT/$leg.log"
        return 0
    fi
    echo "  red    -> $REPORT/$leg.log"
    return 1
}

game_gradle() {
    leg=$1
    shift
    say "$leg: ./gradlew $*"
    if (cd "$GAME" && sh ./gradlew "$@" ${LOCAL_REPO_ARG:+"$LOCAL_REPO_ARG"} --console=plain --stacktrace) > "$REPORT/$leg.log" 2>&1; then
        echo "  green  -> $REPORT/$leg.log"
        return 0
    fi
    echo "  red    -> $REPORT/$leg.log"
    return 1
}

# --- 0. the engine publishes ------------------------------------------------------------------
#
# Two builds, because `build-logic` is an included build: the outer `publishToMavenLocal` does
# not reach it, and it is the half that carries the plugins a game applies.
#
# The marker file is what leg 0b tells this run's artifacts from the ones a previous run left in
# the same repository. `publishToMavenLocal` writes into `~/.m2`, which nothing here empties, so
# "what is in the repository" and "what this publish produced" are different questions and only
# the second one is evidence.
touch "$WORK/publish-start"

engine_gradle publish publishToMavenLocal "-PudeaVersion=$UDEA_VERSION" ||
    fail "the engine did not publish; see $REPORT/publish.log"
engine_gradle publish-build-logic -p build-logic publishToMavenLocal "-PudeaVersion=$UDEA_VERSION" ||
    fail "build-logic did not publish; see $REPORT/publish-build-logic.log"

M2=$M2_ROOT/dev/wildware/udea
for artifact in udea-core udea-annotations udea-agent-host udea-codegen udea-assets udea-assets-compiler udea-render udea-build-logic udea-version-catalog; do
    [ -d "$M2/$artifact/$UDEA_VERSION" ] ||
        fail "$artifact:$UDEA_VERSION is not in the local Maven repository after publishing"
done
ls "$M2" > "$REPORT/published-artifacts.txt"
echo "  published $(wc -l < "$REPORT/published-artifacts.txt") artifacts at $UDEA_VERSION -> $REPORT/published-artifacts.txt"

# --- 0b. every plugin marker published sits inside the verified namespace ----------------------
#
# Gradle publishes a *plugin marker* for every plugin: a POM whose group is the plugin id and
# whose artifact is `<id>.gradle.plugin`. A plugin id is therefore a Maven group, and Sonatype
# authorises this account for `dev.wildware` alone - verified by a DNS TXT record on wildware.dev
# - and answers a PUT anywhere else with 403.
#
# A local Maven repository enforces none of that, which is how this proof was green while the
# first real run of `.github/workflows/release.yml` failed on it: the engine's modules published
# and the convention plugins did not, because they were named `udea.*` (snapshot run 35523838813,
# issue #265). So the check is on the coordinates rather than on the upload, which is the part
# that can be done offline - and it reads them out of the repository the publish above wrote,
# rather than out of a build script that says what it intends to write.
say "namespace: the coordinates of every plugin marker this run published"
MARKERS="$REPORT/plugin-markers.txt"
find "$M2_ROOT" -path '*.gradle.plugin/*' -name '*.pom' -newer "$WORK/publish-start" \
    | sed "s|^$M2_ROOT/||" | sort > "$MARKERS"

# A `grep -v` over an empty file reports nothing and looks exactly like a pass, so the file being
# non-empty is asserted before anything is concluded from what is in it.
[ -s "$MARKERS" ] ||
    fail "this run published no plugin markers at all, so the namespace check below proves nothing"
echo "  $(wc -l < "$MARKERS") markers -> $MARKERS"

if grep -v '^dev/wildware/' "$MARKERS" > "$REPORT/markers-outside-namespace.txt"; then
    cat "$REPORT/markers-outside-namespace.txt"
    fail "those plugin markers publish under a namespace Central refuses with a 403; see $REPORT/markers-outside-namespace.txt"
fi

# The other direction, and the one a rename can break silently: a plugin the template applies
# whose marker never got published is a plugin an outside game cannot resolve. The list is read
# out of the template rather than written here, so the two cannot drift.
sed -n 's/.*id("\([^"]*\)") version udeaVersion.*/\1/p' "$REPO/templates/new-game/settings.gradle.kts" \
    | sort -u > "$REPORT/template-plugin-ids.txt"
[ -s "$REPORT/template-plugin-ids.txt" ] ||
    fail "no plugin ids were read out of templates/new-game/settings.gradle.kts"
while read -r plugin_id; do
    marker_path="$(printf '%s' "$plugin_id" | tr . /)/$plugin_id.gradle.plugin/"
    grep -q "^$marker_path" "$MARKERS" ||
        fail "the template applies $plugin_id, and this run published no marker for it"
done < "$REPORT/template-plugin-ids.txt"
echo "  every plugin the template applies has a marker inside dev.wildware"

# Nothing in the game may name this checkout. The previous shape of this proof included the
# engine's build, and a path left behind would make every leg below pass for the wrong reason.
if grep -rn "udea\.path\|includeBuild" "$GAME" --include='*.kts' --include='*.properties' > "$REPORT/no-path.txt"; then
    cat "$REPORT/no-path.txt"
    fail "the game names a path into the engine's checkout; it is not resolving from a repository"
fi

# --- 1. the game builds against the published engine, with the engine's gates over it ---------
game_gradle build build || fail "the outside game did not build; see $REPORT/build.log"
for task in udeaVerifyModuleGraph udeaVerifyDeterminism udeaVerifyKotlinPin udeaVerifyCompilerPlugin; do
    grep -q "Task :game:$task\|Task :$task" "$REPORT/build.log" ||
        fail "$task did not run on the outside game; a gate that does not run cannot fail"
done

# --- 2. it runs -------------------------------------------------------------------------------
game_gradle run run || fail "the outside game did not run; see $REPORT/run.log"
grep -q "new-game ran 600 ticks" "$REPORT/run.log" ||
    fail "the run did not print its tick count; see $REPORT/run.log"

# --- 2b. it opens a window with its model in it (issue #269) -----------------------------------
#
# The template used to stop at a headless simulation, so the first thing a game that draws did was
# copy a launcher out of this repository. This is the leg that says it no longer has to: the copied
# game, built against the published engine alone, opens a window, draws in it for fifteen seconds,
# and is then photographed from outside itself with its model on the screen.
#
# Fifteen seconds rather than a couple of frames, because "it drew" and "a game can live in it" are
# different claims and only the second one is worth making (#275).
#
# A missing tool is a failure and not a skip, for the reason the usage note above gives.
for tool in xvfb-run ffmpeg python3; do
    command -v "$tool" > /dev/null || fail "leg 2b needs $tool, and there is none on PATH"
done
WINDOW_LEG="$REPO/scripts/outside-game-window.sh"
# The screen is the window's size, so the photograph is the window and nothing else.
#
# `-nocursor` is asked for and does NOT take effect here: the X server still draws its root
# cursor, a black-on-white X, in the middle of the screen, and it is in both photographs. It is
# left in rather than papered over because neither count can see it - it is nine-ish pixels of
# black and white, which is neither the rover's orange nor the sky's blue.
SCREEN="-screen 0 1280x720x24 -nocursor"
# `LIBGL_ALWAYS_SOFTWARE`: a virtual X server has no GPU, so Mesa's software rasteriser draws.
say "window: ./gradlew runWindow on a virtual display, drawing for 15s, then photographed"
rm -f "$REPORT/window.txt"
LIBGL_ALWAYS_SOFTWARE=1 xvfb-run -a -s "$SCREEN" \
    sh "$WINDOW_LEG" "$GAME" "$REPORT" window ${LOCAL_REPO_ARG:+"$LOCAL_REPO_ARG"} ||
    fail "the outside game did not show its model in a window; see $REPORT/window.log and $REPORT/window.png"
grep -q "^PASS" "$REPORT/window.txt" ||
    fail "the window leg exited green without writing a passing count; see $REPORT/window.txt"
echo "  the screen -> $REPORT/window.png"

# --- 2c. and the photograph can tell a window with the model from one without ----------------
#
# The count in 2b passes on a screen that holds the rover. This is the same leg on a copy whose
# rovers name no model: the window opens and draws its sky, and the leg has to fail - naming the
# missing rover rather than a missing window - or 2b's pass says nothing about the model.
ROVERS="$GAME/game/src/main/kotlin/com/example/newgame/sim/RoverSystem.kt"
cp "$ROVERS" "$WORK/RoverSystem.window.orig"
sed -i '/it += Drawn(GameAssets.models.rover, assets)/d' "$ROVERS"
if grep -q "it += Drawn(" "$ROVERS"; then
    fail "the no-model mutation did not apply"
fi
say "window-red: the same window, with no model named"
rm -f "$REPORT/window-red.txt"
if LIBGL_ALWAYS_SOFTWARE=1 xvfb-run -a -s "$SCREEN" \
    sh "$WINDOW_LEG" "$GAME" "$REPORT" window-red ${LOCAL_REPO_ARG:+"$LOCAL_REPO_ARG"}; then
    fail "leg 2b's count passed a window whose rovers name no model; see $REPORT/window-red.png"
fi
# Read back from the file the leg wrote, not from its console: a leg that died before counting
# anything also exits non-zero, and that is not the failure this control is for.
grep -q "the model is not drawn" "$REPORT/window-red.txt" ||
    fail "the window leg failed for another reason than a missing model; see $REPORT/window-red.log"
echo "  refused, for the missing model -> $REPORT/window-red.png"
cp "$WORK/RoverSystem.window.orig" "$ROVERS"

# --- 2d. and it can tell a window that lasted from one that died early -------------------------
#
# 2b waits fifteen seconds for the window to report that it is still drawing, and that wait is the
# whole of the soak: if it could not fail, 2b would be a two-frame test wearing a long coat. This
# is the same leg on a copy whose window closes itself after five seconds while still being asked
# for a full run - the shape #275 shipped, where a render loop ended about eight seconds in and
# every game that met it exited 0 with nothing logged.
WINDOW_SRC="$GAME/game/src/main/kotlin/com/example/newgame/NewGameWindow.kt"
cp "$WINDOW_SRC" "$WORK/NewGameWindow.orig"
sed -i 's/drawn >= runSeconds && !closing/drawn >= 5f \&\& !closing/' "$WINDOW_SRC"
grep -q "drawn >= 5f && !closing" "$WINDOW_SRC" || fail "the early-close mutation did not apply"
say "window-dead: the same window, closing itself after 5s of the run it was asked for"
if LIBGL_ALWAYS_SOFTWARE=1 xvfb-run -a -s "$SCREEN" \
    sh "$WINDOW_LEG" "$GAME" "$REPORT" window-dead ${LOCAL_REPO_ARG:+"$LOCAL_REPO_ARG"}; then
    fail "leg 2b's fifteen-second soak passed a window that stopped drawing after five seconds"
fi
grep -q "stopped drawing before 15s" "$REPORT/window-dead.txt" ||
    fail "the window leg failed for another reason than the window stopping; see $REPORT/window-dead.txt"
# And the launcher itself says so rather than exiting 0 in silence, which is what made #275 invisible.
grep -q "new-game: the window stopped drawing after" "$REPORT/window-dead.log" ||
    fail "the launcher ended early without saying so; see $REPORT/window-dead.log"
echo "  refused, for the window that stopped -> $REPORT/window-dead.txt"
cp "$WORK/NewGameWindow.orig" "$WINDOW_SRC"

# --- 3. its own agent port range --------------------------------------------------------------
say "bridge: gamebridge.json"
cp "$GAME/gamebridge.json" "$REPORT/gamebridge.json"
cat "$REPORT/gamebridge.json"
grep -q '"portRange": "7860-7879"' "$REPORT/gamebridge.json" ||
    fail "gamebridge.json does not carry the game's own port range"

# --- 4. the determinism gate can fail on the game's own code ----------------------------------
SIM="$GAME/game/src/main/kotlin/com/example/newgame/sim/RoverSystem.kt"
cp "$SIM" "$WORK/RoverSystem.kt.orig"
sed -i 's|rover.x += rover.speed \* dt|rover.x += rover.speed * dt + (System.currentTimeMillis() % 2L)|' "$SIM"
grep -q currentTimeMillis "$SIM" || fail "the determinism mutation did not apply"
if game_gradle det-red udeaVerifyDeterminism; then
    fail "udeaVerifyDeterminism passed a wall-clock read in the game's simulation package"
fi
grep -q "DET001" "$REPORT/det-red.log" ||
    fail "udeaVerifyDeterminism failed for some other reason than DET001; see $REPORT/det-red.log"
cp "$WORK/RoverSystem.kt.orig" "$SIM"

# --- 5. the module-graph gate can fail on the game's own classpath ----------------------------
BUILD_SCRIPT="$GAME/game/build.gradle.kts"
cp "$BUILD_SCRIPT" "$WORK/build.gradle.kts.orig"
cat >> "$BUILD_SCRIPT" <<'KTS'

// Planted by scripts/outside-game-proof.sh: UDEA-MG-005 must refuse a scripting host on a
// shipped game's runtime classpath, in this repository and in a game's own.
dependencies { implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:2.4.20") }
KTS
if game_gradle graph-red :game:udeaVerifyModuleGraph; then
    fail "udeaVerifyModuleGraph passed a Kotlin scripting host on the outside game's runtime classpath"
fi
grep -q "UDEA-MG-005" "$REPORT/graph-red.log" ||
    fail "udeaVerifyModuleGraph failed for some other reason than UDEA-MG-005; see $REPORT/graph-red.log"
cp "$WORK/build.gradle.kts.orig" "$BUILD_SCRIPT"

# --- 6. the component id space reached the game without the game wiring it (issue #274) -------
#
# This is the leg the issue is about. robot-game could not build at all: its first `@Replicated`
# component failed with "the build did not set udea.projectComponents", and no published
# convention, extension or property existed to set it - so it read `net-components.lock` itself
# and re-implemented the sorting, which is the part that decides what an id *is*.
#
# Three things are asserted, in the order that makes each mean something.
say "components: the game hand-wires nothing"

# The negative first, with its positive control beside it. A `grep` that finds nothing reads
# exactly like a `grep` that did not run, so the same search is made to return a hit on a string
# that is certainly there before its miss on the string that must not be.
grep -rn "udeaAgent" "$GAME" --include='*.kts' > "$REPORT/grep-control.txt" ||
    fail "the control grep found nothing, so the search below proves nothing about the game"
echo "  control: $(wc -l < "$REPORT/grep-control.txt") hit(s) for a string that is in the template"
if grep -rn "udea.projectComponents" "$GAME" --include='*.kts' > "$REPORT/hand-wired.txt"; then
    cat "$REPORT/hand-wired.txt"
    fail "the game passes udea.projectComponents itself, so this proves nothing about the conventions"
fi
echo "  and no hit for udea.projectComponents anywhere in the game's build scripts"

REPLICATOR=$(find "$GAME/game/build/generated/ksp" -name 'RoverReplicator.kt' | head -1)
[ -n "$REPLICATOR" ] ||
    fail "no RoverReplicator.kt was generated, so the game's @Replicated component never compiled"
cp "$REPLICATOR" "$REPORT/RoverReplicator.kt"
grep -q "ComponentTypeId(0)" "$REPORT/RoverReplicator.kt" ||
    fail "RoverReplicator does not carry the id its position in net-components.lock gives it"
echo "  RoverReplicator.kt -> $REPORT/RoverReplicator.kt"

# --- 7. and the id came from that file, not from counting this module's symbols ---------------
#
# id 0 is also what a module numbering its own symbols would hand out, so leg 6 on its own cannot
# tell the two apart. A name inserted *before* the game's in the sorted file has to move it.
say "components: a name inserted ahead of it moves the id"
LOCK="$GAME/net-components.lock"
cp "$LOCK" "$WORK/net-components.lock.orig"
printf 'aaa.Placeholder\ncom.example.newgame.sim.Rover\n' > "$LOCK"
game_gradle id-moved :game:kspKotlin ||
    fail "the game did not re-run KSP after the id space changed; see $REPORT/id-moved.log"
REPLICATOR=$(find "$GAME/game/build/generated/ksp" -name 'RoverReplicator.kt' | head -1)
cp "$REPLICATOR" "$REPORT/RoverReplicator-shifted.kt"
grep -q "ComponentTypeId(1)" "$REPORT/RoverReplicator-shifted.kt" ||
    fail "a name inserted ahead of the game's component did not move its id; see $REPORT/RoverReplicator-shifted.kt"
echo "  ComponentTypeId(0) -> ComponentTypeId(1): the file is what numbers it"
cp "$WORK/net-components.lock.orig" "$LOCK"

# --- 8. with no registry at all, the build fails and the task writes one -----------------------
#
# The round trip the issue asks for, end to end: the failure names the way out, the way out works
# on the build that just failed, and the build it fixes goes green.
say "components: no registry -> named failure -> udeaWriteNetComponents -> green"
rm "$LOCK"
if game_gradle no-lock :game:kspKotlin; then
    fail "the game compiled a @Replicated component with no id space at all"
fi
grep -q "udeaWriteNetComponents" "$REPORT/no-lock.log" ||
    fail "the failure does not name the task that writes the registry; see $REPORT/no-lock.log"
grep -q "com.example.newgame.sim.Rover" "$REPORT/no-lock.log" ||
    fail "the failure does not name the component it could not number; see $REPORT/no-lock.log"

game_gradle write-lock :udeaWriteNetComponents ||
    fail "udeaWriteNetComponents could not write a registry after the failed build; see $REPORT/write-lock.log"
cp "$LOCK" "$REPORT/net-components.lock.written"
grep -q '^com\.example\.newgame\.sim\.Rover$' "$REPORT/net-components.lock.written" ||
    fail "the written registry does not name the game's component; see $REPORT/net-components.lock.written"
game_gradle after-write build ||
    fail "the game did not build against the registry the task wrote; see $REPORT/after-write.log"
cp "$WORK/net-components.lock.orig" "$LOCK"

say "PROOF GREEN"
echo "a game outside this repository resolved the engine from a repository, built, ran, and"
echo "inherited gates that fail when broken."
echo "transcripts: $REPORT"
