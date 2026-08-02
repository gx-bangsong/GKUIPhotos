# Glimpse Offline Productivity Enhancements

A fully **offline, zero-network, no-cloud-ML** feature set for LineageOS Glimpse.
Everything below uses only the Android framework + the libraries already present
in the project (Media3/ExoPlayer, Glide, androidx.exifinterface, Material 3). The
only "new" heavy code is a self-contained GIF codec (`GifCodec`) implemented in
pure Kotlin so that GIF decode/encode never needs a native or networked library.

> Design language preserved throughout: Material 3 / Material You dynamic color,
> the existing `Theme.Glimpse` / `Theme.Glimpse.MediaViewer` palettes, the
> viewer's top+bottom sheet chrome, and the coroutines + Flow + ListAdapter
> architecture the app already uses.

---

## 1. Video player upgrade — multi-speed & Picture-in-Picture

| Aspect | Implementation |
| --- | --- |
| Multi-speed | A capsule `speedButton` is overlaid on `media_view.xml`, shown only for the active video player. Tapping it opens a `PopupMenu` with **0.5x / 1.0x / 1.5x / 2.0x**. The choice is applied with `PlaybackParameters(speed, /*pitch=*/1f)` on the ExoPlayer (变速不变调) and persisted via `SharedPreferences.videoPlaybackSpeed`, so it survives across videos/sessions. |
| PiP | `ViewActivity` declares `android:supportsPictureInPicture="true"`. `onUserLeaveHint()` enters PiP (with aspect-ratio + source-rect hints, and `setAutoEnterEnabled`/`setSeamlessResizeEnabled` on API 31+) whenever a video is playing. `onPause()` skips pausing during PiP hand-off; `onPictureInPictureModeChanged()` toggles the toolbar/sheet chrome. |

Files: `viewmodels/LocalPlayerViewModel.kt`, `ui/recyclerview/MediaViewerAdapter.kt`,
`res/layout/media_view.xml`, `ViewActivity.kt`, `AndroidManifest.xml`,
`ext/SharedPreferences.kt`.

---

## 2. Image share privacy — one-tap EXIF stripping

Tapping **Share** on an image now opens `SharePrivacyBottomSheet`
(`res/layout/dialog_share_privacy.xml`) with two toggles:

- ☐ Strip location (all `TAG_GPS_*`)
- ☐ Strip device & time (`TAG_MAKE`, `TAG_MODEL`, `TAG_SOFTWARE`, `TAG_DATETIME*`, lens/serial, …)

`ExifStripper.strip()` (Dispatchers.IO) byte-copies the original into the app cache,
then `androidx.exifinterface.media.ExifInterface` nulls the selected tags in place.
The **original file is never modified**; the cleaned temp `Uri` is handed to
`Intent.ACTION_SEND`. Non-image sharing falls back to the original chooser.

Files: `utils/media/ExifStripper.kt`, `ui/dialogs/SharePrivacyBottomSheet.kt`,
`ViewActivity.kt` (share-button routing).

---

## 3. Offline app-source auto-aggregation (virtual smart albums)

`SourceAlbumAggregator` performs a **single MediaStore query** and two complementary,
fully-offline strategies — no files are copied or moved:

- **Strategy A (path match):** the `DATA` column is matched against known app storage
  dirs (美团众包, 蜂鸟即配, 顺丰同城, 达达快送, 淘宝, 微信, …).
- **Strategy B (EXIF):** a bounded number of unmatched images are read for
  `TAG_SOFTWARE` signature strings.

The Albums tab now renders a high-priority **"Smart albums (app sources)"** carousel
above the regular albums (`AlbumsAdapter`, `source_albums_section.xml`,
`source_album_card.xml`). Tapping a source album launches `ViewActivity` with the
matched MediaStore URIs via clip-data so they swipe as a normal media list.

Files: `utils/media/SourceAlbumAggregator.kt`, `viewmodels/AlbumsViewModel.kt`,
`ui/recyclerview/AlbumsAdapter.kt`, `fragments/AlbumsFragment.kt`,
`res/layout/source_albums_section.xml`, `res/layout/source_album_card.xml`.

---

## 4. Flexible edit-save mechanism + global setting

`ImageEditorActivity` is a brand-new, in-app, on-device editor (rotate / filter /
crop / ID-photo). On **Save** it honors a new global setting
`图片编辑保存默认行为` (`ListPreference` `edit_save_behavior`):

1. **Ask every time** (default) — `AlertDialog` → Overwrite / Save as new.
2. **Always overwrite** — fast-path direct write (`openOutputStream(uri, "wt")`);
   on `SecurityException`/null it falls back to `MediaStore.createWriteRequest`
   consent, then writes.
3. **Always save as new** — inserts a fresh entry under `Pictures/Glimpse`.

`EditSaveBehavior` (enum) + `SharedPreferences.editSaveBehavior` back the setting,
exposed in `res/xml/root_preferences.xml` → Editing category.

Files: `ImageEditorActivity.kt`, `utils/EditSaveBehavior.kt`, `ext/SharedPreferences.kt`,
`res/xml/root_preferences.xml`, `res/values/arrays.xml`, `res/layout/activity_image_editor.xml`.

---

## 5. Offline ID-photo cropping tool

Inside the editor, the **ID Photo** chip row locks the crop overlay to standard
ratios — 一寸 25:35, 二寸 35:49, 护照/签证 35:45, 方形头像. The custom `CropImageView`
draws a **rule-of-thirds grid + a dashed face-alignment oval** as alignment guides;
the user pans/pinch-zooms the image to frame head-and-shoulders, purely by hand
(no face detection, no cloud). `crop()` maps the visible rectangle back through the
inverse view matrix to extract the exact pixel region.

Files: `ui/views/CropImageView.kt`, `utils/media/ImageFilter.kt` (ratio presets),
`ImageEditorActivity.kt`.

---

## 6. Offline image toolbox (replaces "search inside this photo")

A floating **capsule "Image toolbox"** button sits at the bottom-center of the
viewer (above the action sheet) and opens `ImageToolboxBottomSheet`. It offers three
pure-local tools, all running on `Dispatchers.Default/IO` with a progress bar:

1. **Compress** — quality presets (85/70/50 %) or a hard **≤500 KB** budget
   (iterative quality + one downscale pass via `Bitmap.compress`).
2. **Convert format** — JPEG ⇄ PNG ⇄ WEBP (framework decode + re-encode). HEIC
   inputs decode via the platform decoder and re-export as the chosen format.
3. **Video → GIF** (videos only) — `MediaMetadataRetriever.getFrameAtTime` samples
   the first N seconds and `GifCodec.encode` packs them into an animated GIF, with a
   determinate progress callback (reported back to the main thread).

Results are written to `Pictures/Glimpse` through the MediaStore.

Files: `utils/media/ImageToolbox.kt`, `ui/dialogs/ImageToolboxBottomSheet.kt`,
`res/layout/dialog_image_toolbox.xml`, `ViewActivity.kt` (capsule button),
`res/values/themes.xml` (`Theme.Glimpse.MediaViewer.ToolboxCapsule`).

---

## 7. Full GIF editor

When the media is `image/gif`, the viewer's **Edit** action opens
`GifEditorActivity` instead of treating the GIF as a static image. It decodes every
frame with `GifCodec.decode` (correct compositing/disposal) and exposes a
video-player-like surface:

- **Per-frame preview** (frame scrubber SeekBar) + auto-play loop.
- **Speed** — slower (×0.5) / faster (×2) adjusts frame delays.
- **Trim** — start/end frame sliders crop the timeline.
- **Rotate / filter** — applied to every frame at export (like a still image).
- **Export** — `GifCodec.encode` re-encodes a brand-new animated GIF to the MediaStore.

Files: `GifEditorActivity.kt`, `utils/media/GifCodec.kt` (self-contained LZW decode
+ encode + median-cut quantizer), `res/layout/activity_gif_editor.xml`,
`res/menu/menu_gif_editor.xml`, `ViewActivity.kt` (GIF routing).

---

## Self-contained GIF codec (`GifCodec.kt`)

Pure-Kotlin, dependency-free GIF89a decoder + encoder (LZW + median-cut color
quantization). Used by both the GIF editor (module 7) and video→GIF (module 6).
This is what keeps the whole pipeline strictly offline — no FFmpeg/GIF native lib.

---

## Permissions & dependencies

**Permissions** — no new runtime permissions required. The existing manifest already
declares everything these features need:

- `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` (MediaStore reads, incl. EXIF),
- `ACCESS_MEDIA_LOCATION` (GPS EXIF access),
- `MANAGE_MEDIA` (write/overwrite via `createWriteRequest`).

Writing results to `Pictures/Glimpse` uses scoped storage (no
`WRITE_EXTERNAL_STORAGE` needed on API 30+, which is the `minSdk`). PiP requires the
manifest `supportsPictureInPicture` flag (added) — no permission.

**Dependencies** — **none added.** Everything reuses libs already in `libs.versions.toml`:
`androidx.exifinterface`, `androidx.media3-*`, `com.bumptech.glide:glide`,
`com.google.android.material:material`, `androidx.lifecycle` (coroutines/Flow),
`androidx.preference`. No network/ML/native codec libraries were introduced.

---

## Performance & threading

All compression / conversion / GIF encode / EXIF strip / source-aggregation runs on
`Dispatchers.IO` or `Dispatchers.Default` via `lifecycleScope`/`viewModelScope`.
Every long operation surfaces a progress indicator (`LinearProgressIndicator`,
`CircularProgressIndicator`, or the editor's saving overlay) so the UI never freezes.
Images are downsampled to a 2048px max edge before editing to bound memory; source
aggregation is one MediaStore query + a bounded EXIF scan.

---

## Entry-point map

| Feature | Entry point |
| --- | --- |
| Speed selector | Capsule on the video player (`media_view.xml`) |
| PiP | Automatic on Home gesture while a video plays |
| Share privacy | Share button (images) → bottom sheet |
| Source albums | Albums tab → top carousel |
| Image editor + ID photo | Edit button (images) → `ImageEditorActivity` |
| Image toolbox | Floating capsule in viewer → bottom sheet |
| GIF editor | Edit button (animated GIFs) → `GifEditorActivity` |
| Edit-save setting | Settings → Editing |
