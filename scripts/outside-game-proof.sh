#!/bin/sh
#
# Publishes this engine, then builds, tests and runs `templates/new-game` from a directory
# OUTSIDE this repository against the published artifacts, and proves the gates it inherited can
# fail (issue #265).
#
# Six legs, in order. Each writes its whole transcript into the report directory, and the script
# stops at the first one that does not do what it says.
#
#   0. publish  `publishToMavenLocal` for the engine's modules, and again for `build-logic`,
#               which carries the convention plugins and the version catalog. Nothing leaves this
#               machine: the local Maven repository stands in for Maven Central, and is the whole
#               difference between this proof and a release.
#   1. build    `./gradlew build` in the copied game: compiles against the published engine, runs
#               the game's own tests, and runs udeaVerifyModuleGraph, udeaVerifyDeterminism,
#               udeaVerifyKotlinPin and udeaVerifyCompilerPlugin over it.
#   2. run      `./gradlew run`: the game simulates 600 ticks headless and prints where its
#               rovers ended up. "Builds" is not "runs", so both are here.
#   3. bridge   the generated gamebridge.json names this game's own agent port range, which is
#               what keeps two games on one machine out of each other's instances.
#   4. det-red  a wall-clock read planted in the game's simulation package: udeaVerifyDeterminism
#               must fail with DET001. A gate nobody has seen fail is indistinguishable from one
#               that cannot.
#   5. graph-red a Kotlin scripting host added to the game's runtime classpath:
#               udeaVerifyModuleGraph must fail with UDEA-MG-005.
#
# The game is copied into a temporary directory outside this repository on purpose, and nothing
# in it names a path to this checkout: a game built from inside the tree, or one that includes
# the engine's build, would prove nothing about a game that does neither.
#
# Usage:  sh scripts/outside-game-proof.sh
# Report: build/reports/udea/outside-game/
set -eu

REPO=$(cd "$(dirname "$0")/.." && pwd)
REPORT="$REPO/build/reports/udea/outside-game"
WORK=${UDEA_OUTSIDE_GAME_DIR:-${TMPDIR:-/tmp}/udea-outside-game-proof}
GAME="$WORK/my-game"

: "${JAVA_HOME:?set JAVA_HOME to a JDK 21 - Gradle 8.13 does not support 25}"

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

engine_gradle() {
    leg=$1
    shift
    say "$leg: ./gradlew $*"
    if (cd "$REPO" && sh ./gradlew "$@" --console=plain --stacktrace) > "$REPORT/$leg.log" 2>&1; then
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
    if (cd "$GAME" && sh ./gradlew "$@" --console=plain --stacktrace) > "$REPORT/$leg.log" 2>&1; then
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
engine_gradle publish publishToMavenLocal "-PudeaVersion=$UDEA_VERSION" ||
    fail "the engine did not publish; see $REPORT/publish.log"
engine_gradle publish-build-logic -p build-logic publishToMavenLocal "-PudeaVersion=$UDEA_VERSION" ||
    fail "build-logic did not publish; see $REPORT/publish-build-logic.log"

M2=${M2_REPO:-$HOME/.m2/repository}/dev/wildware/udea
for artifact in udea-core udea-annotations udea-agent-host udea-codegen udea-build-logic udea-version-catalog; do
    [ -d "$M2/$artifact/$UDEA_VERSION" ] ||
        fail "$artifact:$UDEA_VERSION is not in the local Maven repository after publishing"
done
ls "$M2" > "$REPORT/published-artifacts.txt"
echo "  published $(wc -l < "$REPORT/published-artifacts.txt") artifacts at $UDEA_VERSION -> $REPORT/published-artifacts.txt"

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

say "PROOF GREEN"
echo "a game outside this repository resolved the engine from a repository, built, ran, and"
echo "inherited gates that fail when broken."
echo "transcripts: $REPORT"
