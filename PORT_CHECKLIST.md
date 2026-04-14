# Port checklist: Unacademy fork → upstream `packages/react-native-video`

Branch from **`TheWidlarzGroup/react-native-video`** (`upstream/master`). This checklist maps each fork-only commit and legacy path to where the same concern lives today (monorepo package **`packages/react-native-video`**, Nitro hybrids, Media3 ExoPlayer on Android, Swift `HybridVideoPlayer` on iOS).

**Fork point (common ancestor with upstream):** `53ed7e168e931b8696ac1bf525a3fd4823646666` (2018-09-27).

**One diff to guide the port (all fork changes since divergence):**

```bash
git diff 53ed7e168e931b8696ac1bf525a3fd4823646666..master
```

Upstream at time of mapping: `upstream/master` (fetched remote `upstream` → `https://github.com/TheWidlarzGroup/react-native-video.git`).

---

## 1. Old path → new home (primary mapping)

| Legacy path (fork) | Role | Upstream target (under `packages/react-native-video/`) |
| ------------------ | ---- | -------------------------------------------------------- |
| `Video.js` | Props + event wiring to native view | `src/core/VideoPlayer.ts`, `src/core/video-view/VideoView.tsx`, `src/core/video-view/VideoViewProps.ts`, `src/core/types/Events.ts`, `src/core/types/VideoConfig.ts`, `src/spec/nitro/*.nitro.ts` |
| `android-exoplayer/.../ReactExoplayerView.java` | ExoPlayer instance, track selection, listeners | `android/.../hybrids/videoplayer/HybridVideoPlayer.kt`, `android/.../core/player/*` (e.g. `MediaSourceUtils.kt`, `buildMediaSource` call chain), `android/.../view/VideoView.kt` |
| `android-exoplayer/.../ReactExoplayerViewManager.java` | View manager / props | Nitro: `HybridVideoViewViewManager.kt`, `VideoViewViewManager.nitro.ts`; Fabric: `src/spec/fabric/VideoViewNativeComponent.ts` (mostly `nitroId`) |
| `android-exoplayer/.../ExoPlayerView.java` | PlayerView / surface / layout | `android/.../view/VideoView.kt`, layouts `android/src/main/res/layout/player_view_*.xml`, `HybridVideoPlayer.kt` (`PlayerView.switchTargetView`) |
| `android-exoplayer/.../VideoEventEmitter.java` | Events to JS | `android/.../hybrids/videoplayereventemitter/HybridVideoPlayerEventEmitter.kt`, `src/spec/nitro/VideoPlayerEventEmitter.nitro.ts`, `src/core/types/Events.ts` |
| `android-exoplayer/.../react/*.java` (GL / scale / video render) | Green screen / GL pipeline | **No equivalent** — new Kotlin types under e.g. `android/src/main/java/com/twg/video/view/` or `core/`, integrated from `VideoView` / `HybridVideoPlayer` |
| `android/.../ReactVideoView.java` (+ related) | Alpha on **MediaPlayer** path | Upstream is ExoPlayer/Media3-centric; **port the ExoPlayer + `android-exoplayer/.../react/*` work only**, not the old MediaPlayer view |
| `ios/Video/RCTVideo.m`, `RCTVideoManager.m` | Player + props + output | `ios/hybrids/VideoPlayer/HybridVideoPlayer.swift`, `ios/hybrids/VideoPlayer/HybridVideoPlayer+Events.swift`, `ios/hybrids/VideoPlayerSource/HybridVideoPlayerSource.swift`, `ios/view/VideoComponentView.swift` |
| `ios/Video/ChromaImageFilter.{h,m}` | Chroma / shader | **New files** under `ios/core/` (or `ios/core/Filters/`) — picked up by `ReactNativeVideo.podspec` glob `ios/core/**/*.{h,m,mm,swift}`; **no** `RCTVideo.xcodeproj` edits in upstream |
| `ios/RCTVideo.xcodeproj/project.pbxproj` | Add sources to target | Replace with **podspec / folder placement** only |

---

## 2. Architecture reminders (so the port lands in the right layer)

- [ ] **Nitro-first:** New native-facing props/events usually require edits to `src/spec/nitro/*.nitro.ts`, then run **`bun run specs`** (runs `nitrogen`) in `packages/react-native-video`, then implement Kotlin/Swift hybrids. Generated shared types include `nitrogen/generated/shared/c++/NativeVideoConfig.hpp` (today: `uri`, `headers`, `drm`, `bufferConfig`, `metadata`, `externalSubtitles`, `initializeOnCreation` — **no `maxBitRate` yet**).
- [ ] **Source vs view:** `NativeVideoConfig` / `VideoConfig` vs `VideoViewProps` / `VideoViewViewManager` — e.g. **`maxBitRate`** likely belongs on **source/player config**; **`useGreenScreen`** is **view/output** (like existing `surfaceType`: `surface` | `texture`).
- [ ] **JS API:** Public API is `VideoPlayer`, `VideoView`, hooks (`src/index.tsx`), not a single `Video.js` class file.
- [ ] **Android rendering:** Upstream uses **Media3 `PlayerView`** and `VideoView.kt`; your GL stack must either wrap/replace the surface path used by `PlayerView` or add a dedicated mode (similar in spirit to `surfaceType`).

---

## 3. Feature workstreams (collapse duplicate commits)

Use this as the actual execution order after you branch from `upstream/master`.

### A. Max bitrate (`maxBitRate`)

Fork: `ba538340`, `99316c29` (+ prop in `Video.js`).

- [ ] Add field to **`src/core/types/VideoConfig.ts`** (`VideoConfig` + `NativeVideoConfig`) and thread through **`HybridVideoPlayerSource`** (Android/iOS).
- [ ] Android: apply in ExoPlayer **`TrackSelector` / `Parameters`** when building or attaching the player (likely in **`HybridVideoPlayer.kt`** or `core/player/*` where `ExoPlayer` is configured).
- [ ] iOS: apply **`AVPlayerItem.preferredMaximumResolution`** / bitrate caps as appropriate for your streams (see fork’s `RCTVideo.m` logic).
- [ ] Expose to JS via the same path other `VideoConfig` options use (`useVideoPlayer` / source factory).
- [ ] Run **`bun run specs`** and fix generated + hybrid implementations.

### B. Manifest / playlist event (`onManifestFileChange`)

Fork: `c30a0649` (+ `Video.js`).

- [ ] Add **`VideoPlayerEvents`** entry in **`src/core/types/Events.ts`**.
- [ ] Add listener on **`VideoPlayerEventEmitter.nitro.ts`** and implement in **`HybridVideoPlayerEventEmitter.kt`** / **`HybridVideoPlayerEventEmitter.swift`**.
- [ ] Emit from Android when manifest/playlist updates (HLS/DASH) — likely near **MediaSource** / **`DefaultMediaSourceFactory`** / manifest callback (your old code was in **`ExoPlayerView.java`**).
- [ ] iOS: only if you still need parity; map from fork’s event semantics.

### C. ExoPlayer view / timing / duration / parse fixes

Fork: `9717585a`, `38f10bc4`, `99478794`, `ae2e5dbf` (mostly **`ExoPlayerView.java`**, **`ReactExoplayerView.java`**, **`VideoEventEmitter.java`**).

- [ ] **Index / bounds checks** → guard Media3 timeline/period access in the Kotlin observer/listener code (equivalent of old `ExoPlayerView` index checks).
- [ ] **Duration / segment / relative time** → map to **`onProgress` / `onLoad` / custom metadata** in **`HybridVideoPlayer+Events.swift`** and Android event emitter; align payload types with **`Events.ts`**.
- [ ] **Long parse / crash fix** → re-validate on current **`MediaItem` / `MediaSource`** parsing path (`createMediaItemFromVideoConfig`, `buildMediaSource`, `SourceLoader`).

### D. Green screen / alpha (Android ExoPlayer + iOS shader)

Fork: `a83f8c3b` (MediaPlayer path GL — **optional reference only**), `e8541787`, `da643207`, `2437d698`, `4cc48c99`, `038aa7f9`, `e7ad796b`, `68577310`, `93a4ffcb`, `3266d413`, `dc42c7ed`, `4068697e`, `33edfdd0`; merges `25296ab3`, `17b6b138`, `cbf2f096`.

- [ ] **Android:** Port `GLTextureView`, `VideoRenderer`, `Scalable*` helpers from Java → Kotlin (or keep minimal Java in `android/src/main/java/...` if needed), wired from **`VideoView.kt` / `HybridVideoPlayer.kt`** when “green screen” mode is on (`useGreenScreen` → new prop name per your API).
- [ ] **iOS:** Port **`ChromaImageFilter`** into **`ios/core/`**; integrate with **`VideoComponentView`** / **`AVPlayerLayer`** / composition pipeline (fork used `RCTVideo.m` — now Swift stack).
- [ ] **JS:** Replace `useGreenScreen` PropTypes with **`VideoViewProps`** (and Nitro **`VideoViewViewManager`** if it must cross the hybrid boundary).
- [ ] **Project files:** Do **not** edit `RCTVideo.xcodeproj`; rely on **CocoaPods** globs in **`ReactNativeVideo.podspec`**.

### E. Merge-only commits (no unique file delta)

- [ ] `25296ab3` — merge; no separate port row.
- [ ] `17b6b138` — merge PR #3; no separate port row.
- [ ] `cbf2f096` — merge PR #4; no separate port row.

---

## 4. Per-commit map (fork SHA → intent → upstream touchpoints)

Check off when the behavior is reproduced on upstream’s tree.

| Status | Commit | Subject | Files touched (fork) | Port to (upstream package) |
| ------ | ------ | ------- | --------------------- | --------------------------- |
| [ ] | `ba538340` | maxbitrate support | `Video.js`, `ReactExoplayerView.java`, `ReactExoplayerViewManager.java`, `RCTVideo.m`, `RCTVideoManager.m` | `VideoConfig` + `NativeVideoConfig`, Nitro, `HybridVideoPlayer.kt`, `HybridVideoPlayer.swift` |
| [ ] | `99316c29` | Fixed bitrate on ios | `RCTVideo.m` | `HybridVideoPlayer.swift` (+ source/item setup) |
| [ ] | `c30a0649` | ManifestFileChange event | `Video.js`, `ExoPlayerView.java`, `ReactExoplayerView.java`, `VideoEventEmitter.java` | `Events.ts`, `VideoPlayerEventEmitter.nitro.ts`, `HybridVideoPlayerEventEmitter` (Kotlin/Swift), manifest callback site in Android player pipeline |
| [ ] | `9717585a` | index check | `ExoPlayerView.java` | Timeline/index guards in Kotlin player listener code |
| [ ] | `38f10bc4` | added duration | `ExoPlayerView.java`, `ReactExoplayerView.java`, `VideoEventEmitter.java` | Event payloads + observers (`HybridVideoPlayer+Events`, Android emitter) |
| [ ] | `99478794` | long Parse crash fix | `ExoPlayerView.java` | `MediaItem` / `MediaSource` / manifest parse path (`HybridVideoPlayerSource`, `buildMediaSource`) |
| [ ] | `ae2e5dbf` | relative segment time | `ExoPlayerView.java` | Progress/event timing semantics vs `Events.ts` |
| [ ] | `a83f8c3b` | added alpha view | `android/.../react/*`, `ReactVideoView.java`, `ReactVideoViewManager.java` | Prefer ExoPlayer path only: same as row `e8541787`; MediaPlayer path can be dropped if unused |
| [ ] | `e8541787` | GL texture + exoplayer | `Video.js`, exoplayer + `android-exoplayer/.../react/*` | `VideoView.kt`, `HybridVideoPlayer.kt`, new Kotlin GL/renderer module, `VideoViewProps` / Nitro view manager |
| [ ] | `da643207` | gltexture working | `GLTextureView.java` (exoplayer pkg) | Ported Kotlin GL view |
| [ ] | `2437d698` | prop `useGreenScreen` | `Video.js`, exoplayer + `GLTextureView.java` | `VideoViewViewManager.nitro.ts`, `VideoViewProps`, `HybridVideoViewViewManager.kt` |
| [ ] | `4cc48c99` | alpha shader | `ExoPlayerView.java`, `ReactExoplayerView.java`, `VideoRenderer.java` | Shader/renderer integration in new Android pipeline |
| [ ] | `038aa7f9` | Alpha view iOS | `ChromaImageFilter.*`, `RCTVideo.m`, `RCTVideoManager.m` | `ios/core/ChromaImageFilter.*`, `VideoComponentView.swift`, `HybridVideoPlayer.swift` |
| [ ] | `e7ad796b` | alpha shader | `VideoRenderer.java` | Android renderer |
| [ ] | `68577310` | Fixed shader | `ChromaImageFilter.m` | iOS filter |
| [ ] | `25296ab3` | Merge alphaview | — | *(skip — merge commit)* |
| [ ] | `93a4ffcb` | alpha shader | `VideoRenderer.java` | Android renderer |
| [ ] | `3266d413` | green screen resume/pause | `GLTextureView.java` | Lifecycle in Kotlin GL view + `HybridVideoPlayer` pause/resume |
| [ ] | `17b6b138` | Merge PR #3 | — | *(skip — merge commit)* |
| [ ] | `dc42c7ed` | Added chroma files | `ios/RCTVideo.xcodeproj/project.pbxproj` | File placement under `ios/core/` + podspec (no pbxproj) |
| [ ] | `4068697e` | updated shader | `VideoRenderer.java` | Android renderer |
| [ ] | `cbf2f096` | Merge PR #4 | — | *(skip — merge commit)* |
| [ ] | `33edfdd0` | Updated shader iOS | `ChromaImageFilter.m` | iOS filter |

---

## 5. Suggested verification

- [ ] **`packages/example`** (or your app) plays HLS/DASH with **max bitrate** respected.
- [ ] **Manifest** updates fire the new JS listener with the expected payload.
- [ ] **Green screen** on Android + iOS matches fork behavior (resume/pause, color key).
- [ ] Run upstream’s **lint / typecheck / Android & iOS builds** for `packages/react-native-video`.

---

## 6. Optional: single squashed patch (reference only)

```bash
git diff 53ed7e168e931b8696ac1bf525a3fd4823646666..master > unacademy-fork-port.patch
```

Use it as a **semantic** guide; do not expect it to apply cleanly to `upstream/master`.
