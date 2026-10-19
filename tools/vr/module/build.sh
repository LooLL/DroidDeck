#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../../.." && pwd)
out=${1:?usage: tools/vr/module/build.sh <out dir>}
mkdir -p "$out"
out=$(cd "$out" && pwd)

work=$repo/build/vr-module
root=$work/root
guest=$root/linuxfs/usr/local/lib/droiddeck-vr
rm -rf "$work"
mkdir -p "$root/lib" "$root/licenses/mediapipe" "$root/licenses/opencv" "$root/licenses/openvr" "$work/aar"

"$repo/tools/vr/build.sh" --native "$guest"
install -Dm644 "$guest/layer/VK_LAYER_DROIDDECK_vr_implicit.json" \
    "$root/linuxfs/usr/share/vulkan/implicit_layer.d/droiddeck_vr.json"

while read -r coordinate url sha256 library; do
    aar=$work/aar/${library%.so}.aar
    curl -fsSL --retry 5 -o "$aar" "$url"
    echo "$sha256  $aar" | sha256sum -c --quiet || { echo "$coordinate: checksum mismatch" >&2; exit 1; }
    unzip -q -p "$aar" "jni/arm64-v8a/$library" > "$root/lib/$library"
    [ -s "$root/lib/$library" ] || { echo "$coordinate has no arm64-v8a $library" >&2; exit 1; }
done < "$here/libraries.txt"
unzip -q -p "$work/aar/libmediapipe_tasks_jni.aar" META-INF/NOTICE > "$root/licenses/mediapipe/NOTICE"

cp -R "$here/models" "$here/calibration" "$root/"
for model in "$root"/models/*.task; do
    expected=$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["sha256"])' "${model%.task}.provenance.json")
    echo "$expected  $model" | sha256sum -c --quiet || { echo "$(basename "$model"): checksum mismatch" >&2; exit 1; }
done
cp "$repo/docs/third-party/handtracking/LICENSE_MEDIAPIPE" "$root/licenses/mediapipe/LICENSE"
cp "$repo/docs/third-party/handtracking/LICENSE_OPENCV" "$root/licenses/opencv/LICENSE"
cp "$repo/tools/vr/src/OPENVR_LICENSE" "$root/licenses/openvr/LICENSE"

find "$root" -type d -exec chmod 755 {} +
find "$root" -type f -exec chmod 644 {} +
find "$root/linuxfs" -type f \( -name '*.so' -o -path '*/bin/*' \) -exec chmod 755 {} +

(cd "$root" && find . -type f | LC_ALL=C sort | sed 's|^\./||' | xargs sha256sum) > "$out/vr.files.txt"
tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner -C "$root" -cf - . | zstd -19 -T0 -q -f -o "$out/vr.tar.zst"
sha256sum "$out/vr.tar.zst" | cut -d' ' -f1 > "$out/vr.sha256"
echo "built $out/vr.tar.zst: $(stat -c %s "$out/vr.tar.zst") bytes, sha256 $(cat "$out/vr.sha256")"
