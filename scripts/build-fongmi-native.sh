#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MPVLIB_DIR="${MPVLIB_DIR:-$ROOT_DIR/.work/mpvlibAndroid}"
MPV_COMMIT="1a29fe7407149a572d924de48942d6ce1cc61314"
MPV_DIR="$MPVLIB_DIR/buildscripts/deps/mpv"
PATCH_FILE="$ROOT_DIR/native/patches/fongmi-mpv-dolby-vision-output-mode.patch"
AAR_FILE="$MPVLIB_DIR/app/build/outputs/aar/app-release.aar"
DEST_DIR="$ROOT_DIR/app/libs"
DEST_FILE="$DEST_DIR/mpvlib-fongmi-patched.aar"

if [[ ! -d "$MPV_DIR/.git" ]]; then
  echo "FongMi mpv source was not prepared. Run buildscripts/include/ci.sh install first." >&2
  exit 1
fi

if [[ ! -f "$PATCH_FILE" ]]; then
  echo "Native Dolby Vision patch not found: $PATCH_FILE" >&2
  exit 1
fi

echo "Pinning FongMi mpv to $MPV_COMMIT"
git -C "$MPV_DIR" fetch --depth=1 origin "$MPV_COMMIT"
git -C "$MPV_DIR" checkout --detach FETCH_HEAD
actual_commit="$(git -C "$MPV_DIR" rev-parse HEAD)"
if [[ "$actual_commit" != "$MPV_COMMIT" ]]; then
  echo "Unexpected FongMi mpv commit: $actual_commit" >&2
  exit 1
fi

echo "Applying runtime Dolby Vision sink patch"
git -C "$MPV_DIR" apply --check "$PATCH_FILE"
git -C "$MPV_DIR" apply "$PATCH_FILE"
git -C "$MPV_DIR" diff --check

# The native build recipe carries a gpu-next shader hunk written for a
# different vo_gpu_next.c implementation. FongMi's pinned mpv uses mppl_video
# instead, while the csputils.c and video_shaders.c hunks match this revision.
# Filter only that incompatible file section from the temporary checkout of
# mpvlibAndroid; leave its repository and the two compatible shader patches
# untouched.
SHADER_PATCH="$MPVLIB_DIR/buildscripts/patches/mpv_video_shaders.patch"
if [[ ! -f "$SHADER_PATCH" ]]; then
  echo "FongMi shader patch not found: $SHADER_PATCH" >&2
  exit 1
fi

python3 - "$SHADER_PATCH" <<'PY'
from pathlib import Path
import re
import sys

patch = Path(sys.argv[1])
source = patch.read_text(encoding="utf-8")
sections = re.split(r"(?m)(?=^diff --git )", source)
kept = []
removed = 0

for section in sections:
    if section.startswith("diff --git a/video/out/vo_gpu_next.c b/video/out/vo_gpu_next.c"):
        removed += 1
        continue
    kept.append(section)

if removed != 1:
    raise SystemExit(
        "Expected exactly one incompatible vo_gpu_next.c shader patch section; "
        f"found {removed}. Refusing to alter the native patch."
    )

filtered = "".join(kept)
if "diff --git a/video/out/vo_gpu_next.c b/video/out/vo_gpu_next.c" in filtered:
    raise SystemExit("vo_gpu_next.c shader patch section was not fully removed")

patch.write_text(filtered, encoding="utf-8")
print("Excluded the incompatible vo_gpu_next.c shader section; retained csputils.c and video_shaders.c.")
PY

echo "Building patched FongMi mpv and Android AAR for arm64"
(
  cd "$MPVLIB_DIR/buildscripts"
  ./buildall.sh --arch arm64 -n mpv
  ./buildall.sh --arch arm64 -n mpv-android
)

if [[ ! -s "$AAR_FILE" ]]; then
  echo "Patched FongMi AAR was not produced: $AAR_FILE" >&2
  find "$MPVLIB_DIR/app/build/outputs" -type f -maxdepth 5 -print 2>/dev/null || true
  exit 1
fi

mkdir -p "$DEST_DIR"
cp "$AAR_FILE" "$DEST_FILE"
echo "Patched FongMi AAR staged at $DEST_FILE"
