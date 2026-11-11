#!/usr/bin/env bash
# Plays a scripted match through the real UI and saves screenshots at phone, small-phone, tablet
# and desktop sizes. Needs a built jar (./mvnw package) and, on a headless machine, xvfb-run.
# Usage: tools/screenshot_tour.sh [output-dir] [size ...]   sizes: phone small tablet desktop
set -euo pipefail
cd "$(dirname "$0")/.."
out=${1:-target/tour}; shift || true
sizes=("$@"); [ ${#sizes[@]} -eq 0 ] && sizes=(phone small tablet desktop)
jar=$(ls target/supremacy-game-*.jar | grep -v original | head -1)
run=(java); command -v xvfb-run >/dev/null && [ -z "${DISPLAY:-}" ] && run=(xvfb-run -a -s "-screen 0 2600x1400x24" java)
status=0
for size in "${sizes[@]}"; do
  case $size in
    phone)   args=(2400 1080 2.625) ;;  # 6.5" 20:9 phone, 411 dp tall
    small)   args=(1600 720 2) ;;       # small 20:9 phone, 360 dp tall
    tablet)  args=(1280 800 1.333) ;;   # 10" tablet
    desktop) args=(1600 1000 1.25) ;;   # desktop window
    *) echo "unknown size $size"; exit 2 ;;
  esac
  data=$(mktemp -d)
  echo "== $size: ${args[*]}"
  "${run[@]}" -Dsupremacy.saveDir="$data" -cp "$jar" com.supremacy.board.ScreenshotTour "${args[@]}" "$out/$size" || status=1
  rm -rf "$data"
done
# Pass-and-play: two humans and the handover screens, at phone size.
data=$(mktemp -d)
echo "== hotseat"
"${run[@]}" -Dsupremacy.saveDir="$data" -Dsupremacy.tour=hotseat -cp "$jar" com.supremacy.board.ScreenshotTour 2400 1080 2.625 "$out/hotseat" || status=1
rm -rf "$data"
exit $status
