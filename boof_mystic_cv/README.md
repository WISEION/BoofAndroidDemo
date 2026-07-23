# Boof Mystic CV

A focused camera app that shows **live histogram enhancement** using BoofCV. It is a separate,
minimal Gradle module (`:boof_mystic_cv`) built directly on BoofCV's `VisualizeCamera2Activity` — the
clean example base class rather than the heavier demo infrastructure in `:app`.

## Features

### Two enhancement modes (single toggle button)
* **Global** histogram equalization — one transform computed over the whole frame.
* **Local** (adaptive) histogram equalization — a sliding window per pixel.

The mode button (top-left) switches between them and shows a toast naming the new mode.

### Gestures (on the live preview)
| Gesture | Action |
|---|---|
| One-finger **horizontal** swipe | Change resolution — right = higher, left = lower (`LOW → MEDIUM → HIGH → MAX`) |
| One-finger **vertical** swipe | Cycle through the device cameras (up = next, down = previous) |
| **Double-tap** | Toggle the live histogram overlay |
| **Long-press & hold** | Momentarily show the raw ("before") image for comparison |

Gestures use view coordinates, so they behave the same in portrait or landscape.

### Shutter (bottom-center)
* **Tap** → photo.
* **Press & hold** → record video; release to stop.
* Each capture is saved in **both** the enhanced and raw versions.

Photos go to `Pictures/Boof Mystic CV` (via `MediaStore` on Android 10+, public storage on older
versions). Videos are recorded with `MediaCodec`/`MediaMuxer` and registered with the gallery.

### Settings (gear button, top-right)
* **Local radius** (5–100, default 50) — local-equalization window size.
* **Histogram bins** (16–256) — posterize/quantize level applied to the result (contrast granularity).
* **Processing downscale** (1× / 2× / 4×) — process at a reduced resolution for speed.
* **Color processing** — grayscale or per-channel color equalization.
* **On-screen info** — mode, resolution, camera, FPS overlay.
* **Live histogram overlay** — small bar graph of the current intensity distribution.

## Building

Open the repository in Android Studio and select the **boof_mystic_cv** run configuration. The module
pulls `org.boofcv:boofcv-android` and `boofcv-core` (0.41) from Maven Central, same as `:app`.

Build variants mirror the demo: `debug` (breakpoints, slow), `fast` (full speed), `release`.

## Notes / limitations

* Video recording feeds already-rendered frames into a software `MediaCodec` encoder. On lower-end
  devices the encoder may not keep up at full resolution — use **Processing downscale** to lighten
  the load. Recording two streams (enhanced + raw) simultaneously roughly doubles the cost.
* Video is captured without audio.
* Camera cycling iterates the IDs reported by `CameraManager`; some logical/physical IDs may not
  open on every device — swipe again to advance to the next one.
