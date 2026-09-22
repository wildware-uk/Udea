#!/bin/sh
#
# One leg of `scripts/outside-game-proof.sh` (issue #269): runs the copied game's `runWindow` on the
# X display this process was started on, lets it draw for a good while, photographs the screen from
# outside the game while the window is still up, and counts what the photograph holds.
#
# It is run by the proof under `xvfb-run`, so the display is a virtual one and the window is a real
# window on it: the photograph is taken by ffmpeg reading the X server, not by the game capturing
# itself. A game that opened no window, or opened one and drew nothing into it, leaves a screen with
# no rover on it, and that is what the count below is for.
#
# ## The photograph is taken late, on purpose
#
# A window that draws two frames and dies is not a window a game can live in. In September 2026 an
# engine change ended the render loop about eight seconds in; every game that met it exited 0 with
# nothing logged, and the only test of the hook had drawn two frames. So this leg asks the game for
# a run of RUN_SECONDS, waits for it to report that it is still drawing SOAK_SECONDS in, and only
# then takes the picture. The picture is therefore of a window that has already lasted, and the
# waiting is what fails when it has not.
#
# Usage:  sh outside-game-window.sh <game-dir> <report-dir> <name> [extra Gradle arguments...]
# Writes: <report-dir>/<name>.log     the game's Gradle transcript
#         <report-dir>/<name>.png     the screen, as a picture
#         <report-dir>/<name>.txt     what the leg found, whether it got as far as counting or not
# Exits 0 when the window lasted the soak and the screen held the rover and the sky, non-zero
# otherwise. The reason is in <name>.txt either way: `xvfb-run` folds standard error into standard
# output, so the file is the one place a caller can read it back from.
set -eu

GAME=$1
REPORT=$2
NAME=$3
shift 3

: "${DISPLAY:?no X display: run this under xvfb-run, as outside-game-proof.sh does}"

# The screen is the window's size, so the photograph is the window and nothing else: an X server
# with no window manager puts a new window at the origin, undecorated.
WIDTH=1280
HEIGHT=720

# How long the window is asked to stay up, and how long it must have been drawing before the
# photograph is taken. The gap between them is what the photograph and the clean close happen in.
RUN_SECONDS=22
SOAK_SECONDS=15

# How long to wait for the window to say it is drawing. Gradle has compiled the game already by the
# time this leg runs, so this is a JVM start and a GL context on a software rasteriser.
READY_SECONDS=300

LOG="$REPORT/$NAME.log"
TXT="$REPORT/$NAME.txt"
rm -f "$TXT"

# Every exit of this leg writes its reason to the report file, so a caller never has to tell "it
# failed before counting" from "it counted and refused" by reading a console it does not have.
refuse() {
    echo "FAIL: $1" > "$TXT"
    echo "  $1" >&2
    exit 1
}

(cd "$GAME" && exec sh ./gradlew runWindow "-Pseconds=$RUN_SECONDS" --console=plain --stacktrace "$@") \
    > "$LOG" 2>&1 &
gradle=$!

# The game prints "window open" once it has drawn its first frames, and one "drawing, Ns" line a
# second after that. The wait below is for that count to reach SOAK_SECONDS - read as a number
# rather than matched as a string, because a slow frame on a software rasteriser can carry the
# count past a particular second without ever printing it. A window that opened and stopped never
# reaches it, and its process ends, which is the branch that catches it.
waited=0
while :; do
    # `|| true`: the log does not exist for the first moment of the background job's life, and a
    # missing file is "not yet", not a failure of this leg.
    drew=$( (sed -n 's/.*new-game: drawing, \([0-9][0-9]*\)s.*/\1/p' "$LOG" 2> /dev/null || true) | tail -1)
    if [ -n "$drew" ] && [ "$drew" -ge "$SOAK_SECONDS" ]; then
        break
    fi
    if ! kill -0 "$gradle" 2>/dev/null; then
        wait "$gradle" || true
        if grep -q "new-game: window open" "$LOG"; then
            refuse "the window opened and stopped drawing before ${SOAK_SECONDS}s; see $LOG"
        fi
        refuse "the game ended before its window said it was drawing; see $LOG"
    fi
    if [ "$waited" -ge "$READY_SECONDS" ]; then
        refuse "no window drawing for ${SOAK_SECONDS}s after ${READY_SECONDS}s; see $LOG"
    fi
    sleep 1
    waited=$((waited + 1))
done

# The picture, and the same picture as raw bytes for the count. Two grabs a moment apart rather
# than one decoded twice, because a PNG decoder is not something every machine this runs on has;
# both are of a scene that is still drawing, so they differ only in how far each rover has driven.
ffmpeg -loglevel error -y -f x11grab -video_size "${WIDTH}x$HEIGHT" -i "$DISPLAY" -frames:v 1 "$REPORT/$NAME.png"
ffmpeg -loglevel error -y -f x11grab -video_size "${WIDTH}x$HEIGHT" -i "$DISPLAY" -frames:v 1 \
    -f rawvideo -pix_fmt rgb24 "$REPORT/$NAME.rgb"

# The game closes itself at RUN_SECONDS, and says so with the seconds it drew for. A window that
# never closes, or that exits non-zero because it stopped short, is a failure too.
wait "$gradle" || refuse "runWindow did not exit cleanly; see $LOG"
grep -q "new-game: window closed" "$LOG" || refuse "the window did not report closing; see $LOG"

# What the photograph holds. "Rover" is the model's orange paint: red well above both green and
# blue, which nothing else on screen is - the sky runs from blue to a pale blue-grey, the tyres are
# near black and the cab near white. "Sky" is the sky's blue. Both are counted, so a screen with no
# rover and a screen with nothing at all fail for different, named reasons.
status=0
python3 - "$REPORT/$NAME.rgb" "$WIDTH" "$HEIGHT" "$TXT" "$SOAK_SECONDS" <<'PY' || status=$?
import sys

path, width, height, out, soak = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4], sys.argv[5]
data = open(path, "rb").read()
if len(data) != width * height * 3:
    sys.exit("the grab is %d bytes, and a %dx%d RGB frame is %d" % (len(data), width, height, width * height * 3))

MIN_ROVER = 2000  # pixels: three rovers at this camera distance cover several times this
MIN_SKY = width * height // 4

rover = sky = 0
for at in range(0, len(data), 3):
    r, g, b = data[at], data[at + 1], data[at + 2]
    if r > 110 and r > g + 40 and r > b + 70:
        rover += 1
    elif b > r + 25 and b > 60:
        sky += 1

report = "rover pixels %d (need %d), sky pixels %d (need %d), of %d\n" % (rover, MIN_ROVER, sky, MIN_SKY, width * height)
if sky < MIN_SKY:
    verdict = "FAIL: the screen holds no sky: the window drew nothing, or there is no window"
elif rover < MIN_ROVER:
    verdict = "FAIL: the screen holds the sky and no rover: the window is up and the model is not drawn"
else:
    verdict = "PASS: after %ss of drawing, the screen holds the sky and the rover" % soak
open(out, "w").write(report + verdict + "\n")
sys.stdout.write("  " + report + "  " + verdict + "\n")
sys.exit(0 if verdict.startswith("PASS") else 1)
PY
# The raw frame was only ever for the count; the PNG beside it is the picture a person looks at.
rm -f "$REPORT/$NAME.rgb"
exit "$status"
