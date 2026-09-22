#!/bin/sh
#
# One leg of `scripts/outside-game-proof.sh` (issue #269): runs the copied game's `runWindow` on the
# X display this process was started on, photographs the screen from outside the game while the
# window is up, and counts what the photograph holds.
#
# It is run by the proof under `xvfb-run`, so the display is a virtual one and the window is a real
# window on it: the photograph is taken by ffmpeg reading the X server, not by the game capturing
# itself. A game that opened no window, or opened one and drew nothing into it, leaves a screen with
# no rover on it, and that is what the count below is for.
#
# Usage:  sh outside-game-window.sh <game-dir> <report-dir> <name> [extra Gradle arguments...]
# Writes: <report-dir>/<name>.log     the game's Gradle transcript
#         <report-dir>/<name>.png     the screen, as a picture
#         <report-dir>/<name>.txt     what the count found
# Exits 0 when the screen held the rover and the sky, and non-zero otherwise.
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

# Long enough for the photograph to be taken with the window still up, and short enough that the
# game closes itself: the leg waits for it rather than killing anything.
FRAMES=1200

# How long to wait for the window to say it is drawing. Gradle has compiled the game already by the
# time this leg runs, so this is a JVM start and a GL context on a software rasteriser.
READY_SECONDS=300

LOG="$REPORT/$NAME.log"
(cd "$GAME" && exec sh ./gradlew runWindow "-Pframes=$FRAMES" --console=plain --stacktrace "$@") > "$LOG" 2>&1 &
gradle=$!

waited=0
until grep -q "new-game: window open" "$LOG"; do
    if ! kill -0 "$gradle" 2>/dev/null; then
        echo "  the game ended before its window said it was drawing; see $LOG" >&2
        wait "$gradle" || true
        exit 1
    fi
    if [ "$waited" -ge "$READY_SECONDS" ]; then
        echo "  no window after ${READY_SECONDS}s; see $LOG" >&2
        exit 1
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

# The game closes itself after FRAMES frames; a window that never closes is a failure too.
wait "$gradle" || { echo "  runWindow did not exit cleanly; see $LOG" >&2; exit 1; }
grep -q "new-game: window closed" "$LOG" || { echo "  the window did not report closing; see $LOG" >&2; exit 1; }

# What the photograph holds. "Rover" is the model's orange paint: red well above both green and
# blue, which nothing else on screen is - the sky runs from blue to a pale blue-grey, the tyres are
# near black and the cab near white. "Sky" is the sky's blue. Both are counted, so a screen with no
# rover and a screen with nothing at all fail for different, named reasons.
status=0
python3 - "$REPORT/$NAME.rgb" "$WIDTH" "$HEIGHT" "$REPORT/$NAME.txt" <<'PY' || status=$?
import sys

path, width, height, out = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
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
    verdict = "PASS: the screen holds the sky and the rover"
# Into the report file as well as onto the console: `xvfb-run` folds the command's standard error
# into its standard output, so the file is the one place a caller can read the reason back from.
open(out, "w").write(report + verdict + "\n")
sys.stdout.write("  " + report + "  " + verdict + "\n")
sys.exit(0 if verdict.startswith("PASS") else 1)
PY
# The raw frame was only ever for the count; the PNG beside it is the picture a person looks at.
rm -f "$REPORT/$NAME.rgb"
exit "$status"
