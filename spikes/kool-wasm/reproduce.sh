#!/bin/sh
# Issue #225: build Kool `main` at a pinned commit with its JVM toolchain patched to 21, check
# what came out, compile a Kotlin 2.4.20 consumer against it, and draw that consumer's Wasm scene
# in headless Chrome on WebGL 2.
#
#     sh spikes/kool-wasm/reproduce.sh
#
# Every stage fails loudly and the script stops at the first failure (`set -e`). Nothing is
# published anywhere: Kool goes into a scratch Maven repository under $SPIKE_WORK.
#
# KOOL_NO_PATCH=1 builds the pinned commit exactly as upstream has it. That is the red run: the
# desktop classes come out as major 69 (Java 25) and there is no Android variant at all.
#
# Environment, all optional:
#   SPIKE_WORK    scratch root, outside the Udea repo   (default /srv/ssd1/workspace/kool-spike-225)
#   KOOL_JAVA     JDK that launches Kool's Gradle 9.7.1 (default sdkman 21.0.11-tem)
#   ANDROID_HOME  Android SDK                            (default $HOME/Android/Sdk)
#   CHROME        Chrome/Chromium binary                 (default: newest Playwright chromium)
#   SPIKE_STAGES  subset of "kool consumer browser"      (default: all three)
set -eu

KOOL_SHA=ab762acde2bac4f2c078a34da636e49423b11811
KOOL_URL=https://github.com/fabmax/kool.git
KOOL_VERSION=0.20.0-SNAPSHOT

HERE=$(cd "$(dirname "$0")" && pwd)
UDEA=$(cd "$HERE/../.." && pwd)
SPIKE_WORK=${SPIKE_WORK:-/srv/ssd1/workspace/kool-spike-225}
KOOL_DIR=$SPIKE_WORK/kool
REPO=$SPIKE_WORK/m2
KOOL_JAVA=${KOOL_JAVA:-$HOME/.sdkman/candidates/java/21.0.11-tem}
UDEA_JAVA=$HOME/.sdkman/candidates/java/21.0.11-tem
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
SPIKE_STAGES=${SPIKE_STAGES:-kool consumer browser}
OUT=$SPIKE_WORK/out
mkdir -p "$SPIKE_WORK" "$OUT"

say() { printf '\n== %s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

stage_kool() {
    say "Kool at $KOOL_SHA"
    [ -d "$KOOL_DIR/.git" ] || git clone -q "$KOOL_URL" "$KOOL_DIR"
    git -C "$KOOL_DIR" fetch -q origin "$KOOL_SHA" 2>/dev/null || true
    git -C "$KOOL_DIR" checkout -q --detach "$KOOL_SHA"
    git -C "$KOOL_DIR" reset -q --hard "$KOOL_SHA"
    [ "$(git -C "$KOOL_DIR" rev-parse HEAD)" = "$KOOL_SHA" ] || fail "Kool is not at the pinned commit"

    if [ "${KOOL_NO_PATCH:-0}" = 1 ]; then
        say "KOOL_NO_PATCH=1: building upstream unmodified"
    else
        say "applying toolchain-21.patch"
        git -C "$KOOL_DIR" apply --verbose "$HERE/toolchain-21.patch"
    fi
    git -C "$KOOL_DIR" diff --stat

    printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$KOOL_DIR/local.properties"
    rm -rf "$REPO/de/fabmax/kool"

    # The caps: this box is shared, and Kool's own gradle.properties asks for -Xmx8g twice.
    # --no-build-cache: Kool turns the build cache on, and a task restored from an earlier run
    # (patched or not) would be a result this run did not produce.
    say "Kool Gradle launched by $("$KOOL_JAVA/bin/java" -version 2>&1 | head -1)"
    started=$(date +%s)
    (cd "$KOOL_DIR" && JAVA_HOME=$KOOL_JAVA sh gradlew --console=plain --max-workers=4 --no-build-cache \
        "-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g" -Pkotlin.daemon.jvmargs=-Xmx3g \
        -Dmaven.repo.local="$REPO" :kool-core:publishToMavenLocal)
    echo "Kool Gradle build took $(( $(date +%s) - started ))s"

    base=$REPO/de/fabmax/kool
    say "published variants of kool-core"
    ls -1 "$base" | grep '^kool-core' || true

    say "jvm (desktop): class-file major version"
    jar=$base/kool-core-desktop/$KOOL_VERSION/kool-core-desktop-$KOOL_VERSION.jar
    [ -f "$jar" ] || fail "no desktop jar at $jar"
    rm -rf "$OUT/desktop-classes" && mkdir -p "$OUT/desktop-classes"
    (cd "$OUT/desktop-classes" && unzip -q -o "$jar" 'de/fabmax/kool/KoolSystem.class' 'de/fabmax/kool/platform/Lwjgl3Context.class')
    for c in de/fabmax/kool/KoolSystem de/fabmax/kool/platform/Lwjgl3Context; do
        major=$("$UDEA_JAVA/bin/javap" -v "$OUT/desktop-classes/$c.class" | awk '/major version/ {print $3}')
        echo "$c.class: major version $major"
        [ "$major" = 65 ] || fail "$c.class is major $major, expected 65 (Java 21)"
    done

    say "android: aar present, classes major version"
    aar=$base/kool-core-android/$KOOL_VERSION/kool-core-android-$KOOL_VERSION.aar
    [ -f "$aar" ] || fail "no android aar at $aar (upstream ships Android commented out)"
    rm -rf "$OUT/android-aar" && mkdir -p "$OUT/android-aar"
    (cd "$OUT/android-aar" && unzip -q -o "$aar" classes.jar && unzip -q -o classes.jar 'de/fabmax/kool/KoolSystem.class')
    echo "de/fabmax/kool/KoolSystem.class (aar): major version $("$UDEA_JAVA/bin/javap" -v "$OUT/android-aar/de/fabmax/kool/KoolSystem.class" | awk '/major version/ {print $3}')"

    say "wasmJs: klib present and built for wasm"
    klib=$base/kool-core-wasm-js/$KOOL_VERSION/kool-core-wasm-js-$KOOL_VERSION.klib
    [ -f "$klib" ] || fail "no wasmJs klib at $klib"
    rm -rf "$OUT/wasm-klib" && mkdir -p "$OUT/wasm-klib"
    (cd "$OUT/wasm-klib" && unzip -q -o "$klib" 'default/manifest')
    grep -E '^(builtins_platform|wasm_targets|compiler_version|abi_version|metadata_version)=' "$OUT/wasm-klib/default/manifest"
    grep -q '^builtins_platform=WASM$' "$OUT/wasm-klib/default/manifest" || fail "klib is not a WASM klib"
}

stage_consumer() {
    say "consumer: Kotlin 2.4.20 on Udea's Gradle compiles jvm + wasmJs against the scratch repo"
    rm -rf "$HERE/consumer/build/dist"
    (cd "$UDEA" && JAVA_HOME=$UDEA_JAVA sh gradlew -p spikes/kool-wasm/consumer --console=plain \
        --max-workers=4 -Pspike.repo="$REPO" compileKotlinJvm wasmJsBrowserDistribution)
    [ -f "$HERE/consumer/build/dist/wasmJs/productionExecutable/index.html" ] || fail "no wasm distribution"
    ls -1 "$HERE/consumer/build/dist/wasmJs/productionExecutable"
}

stage_browser() {
    if [ -z "${CHROME:-}" ]; then
        CHROME=$(ls -d "$HOME"/.cache/ms-playwright/chromium-*/chrome-linux64/chrome 2>/dev/null | sort -V | tail -1)
    fi
    [ -x "$CHROME" ] || fail "no Chrome binary (set CHROME)"
    say "browser: $("$CHROME" --version)"
    node "$HERE/draw-check.mjs" "$CHROME" "$HERE/consumer/build/dist/wasmJs/productionExecutable" \
        "$OUT/issue225-kool-wasm-webgl2-scene.png"
}

for s in $SPIKE_STAGES; do "stage_$s"; done
say "OK: $SPIKE_STAGES"
