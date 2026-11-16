# MBox

Native Android client (Kotlin + Jetpack Compose + Media3/ExoPlayer) for the MovieBox catalog.
Dark, poster-grid UI modelled on MovieBox: hero carousel, horizontal rails, search grid,
detail page with season/episode picker and a quality chooser, then fullscreen playback.

## How it works

1. `GET /subject-api/search-rank/v2` + `POST /subject-api/trending/v2` build the home feed.
2. `POST /subject-api/search` powers search.
3. Detail: `GET /subject-api/get`, `GET /subject-api/season-info/v2`.
4. Playback pipeline: `GET /subject-api/resource/v2` → `sourceUrl` → `GET /sniff/config` → `downloadUrl`
   (real signed CDN MP4). The CDN only accepts a player user-agent (`ExoPlayerLib/2.19.1`), no Referer.
5. Requests are signed with HMAC-MD5 over the canonical string
   `METHOD\naccept\ncontent-type\nbodyLen\nts\nmd5(body)\npath?sortedQuery` → `x-tr-signature: <ts>|2|<b64>`.
   A guest token from `/user-api/visitor-login` is fetched silently; no account or VIP is needed.

Signed CDN URLs are short-lived, so the app re-resolves on every playback.

## Build (GitHub Actions)

Push to `main` (or run the workflow manually):

- workflow: `.github/workflows/build.yml`
- Gradle 8.7 / JDK 17 / AGP 8.6.1 / compileSdk 35 / minSdk 24
- output: `MBox.apk`, uploaded as a workflow artifact **and** attached to a `build-<run>` release.

## Build locally

```bash
gradle assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

## Notes

- HEVC 1080p/720p streams play fine in the app (ExoPlayer); browsers usually cannot decode them.
- Package `app.mbox` (debug variant is `app.mbox.debug`, so it can be installed side by side).
