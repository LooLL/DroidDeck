# VR module

DroidDeck downloads its VR payload on request instead of shipping it in the apk. `build.sh <out>`
writes `vr.tar.zst`, `vr.sha256` and `vr.files.txt` (every file with its sha256):

| Path | Contents |
| --- | --- |
| `lib/` | `libopencv_java4.so` and `libmediapipe_tasks_jni.so`, unchanged from the Maven artifacts pinned in `libraries.txt` |
| `models/` | MediaPipe hand and pose landmarker models, each checked against its `.provenance.json` |
| `calibration/` | the Luma Ultra base stereo calibration |
| `linuxfs/` | the SteamVR driver, Vulkan layer and hand render models built by `tools/vr/build.sh`, staged into the Linux runtime |
| `licenses/` | DroidDeck's GPL-3.0 license and the source commit for the driver and layer, and the licenses of everything built into them or shipped beside them: MediaPipe, OpenCV, OpenVR, the Google VR SDK arm model, Monado, `sdPolygon` and Wayland |

The apk keeps the OpenCV and MediaPipe Java classes, so `libraries.txt` must name the same versions
as `app/build.gradle`. The app installs the module into its files directory, loads the two
libraries from there and stages `linuxfs/` into the runtime at each session start. Uninstalling
removes both.

## Publishing

`build-vr.yml` in [DroidDeck-Components](https://github.com/Droid-Deck/DroidDeck-Components) builds
this directory at a given ref and publishes it as a new `vr-rN` release; a published tag is never
replaced. Copy the tag, sha256 and size it reports into `release.env`. Any change here, in
`tools/vr` or in the pinned libraries needs a new tag.

For a local build, set `droiddeck.vrModule=<path to vr.tar.zst>` in `local.properties` (or pass
`-PvrModule=`). The apk then carries that archive and installs it instead of the pinned release.
