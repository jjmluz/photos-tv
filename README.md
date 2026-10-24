# Fotos for Google TV

A Google Photos client for the Google TV Streamer (Android TV 14 / API 34), built with
Jetpack Compose for TV (Material 3). The app is in European Portuguese only: the UI text is
written in pt-PT and the app forces the pt-PT locale, so dates and the video player's controls
match regardless of the TV's system language.

- **Tabs**: Fotos, Álbuns and Pessoas along the top; moving along them switches page
- **Back**: on a list, moves focus back to its first item (the newest photo, the first album or
  person); pressed again there, it exits the app
- **Photos**: your whole library as a timeline, grouped by day, newest first
- **Timeline**: on Fotos, moving right past the last column opens a list of years and months
  (a side menu). Up/down pick a month, OK jumps there; the grid then fills in
  newer photos as you scroll up. Moving right again reaches a column of years: up/down move
  between years, OK jumps to that year's newest month, left returns to its months. The month list takes one small request per month with photos
  on first use, and is cached after that.
- **Albums**: your albums (owned and shared), and the photos in each one
- **Pessoas**: the people (and pets) Google Photos recognised, as round faces with their name and
  number of photos; open one to see their photos by day
- **Viewer**: full-screen at the TV's native resolution. Videos stream over Google's adaptive HLS
  (starts fast, steps up to 1080p, which is the most Google Photos streams), with the 1080p/720p
  files and then the original upload as fallbacks. 4K originals can't be streamed: their server
  ignores byte-range requests. ◀ ▶ to browse, ▲ ▼ to show the date and place (when Google
  Photos has one), OK to play a video, Back to return to the grid.
  While a video plays, a progress bar shows the time played and the length; ◀ ▶ (or rewind/fast
  forward) jump 10 s, 30 s while held, OK or play/pause pauses, and Back stops it

Nothing is stored on the device except Coil's image cache (up to 768 MB on disk), which is cleared when the session ends.

## How it talks to Google Photos

In March 2025 Google removed library-wide read access from the public Photos Library API. Third-party
apps can now only see photos they uploaded themselves, or photos picked one session at a time with the
Picker API. So this app uses the same private `batchexecute` RPC endpoint that photos.google.com uses
(RPC ids from [Google Photos Toolkit](https://github.com/xob0t/Google-Photos-Toolkit)):

| Feature | RPC |
| --- | --- |
| Timeline | `lcxiM` |
| Albums | `Z5xsfc` |
| Album contents | `snAcKc` |
| People | `yQelMe` with `[pageToken, null, null, [[1, null, 1]]]` |
| A person's photos | `EzkLib` (search) with `["", [[1, personId, 0, 0]], pageToken]` |

Signing in is done by importing your Google web session from a desktop browser. Google blocks
every other route for a third-party TV app: its sign-in page refuses WebViews, and the device sign-in
flow requires a Play Services attestation (`MissingDroidguard`) that only Google's own apps can produce.

**Caveats:** the endpoint is undocumented and against Google's ToS for third-party clients. It can
change without notice. If it does, `data/PhotosApi.kt` is the only file that should need updating.

## Build

You need Android Studio (its bundled JDK 21) and SDK platform 37.

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleRelease
```

The release build is minified and signed with the debug key, for sideloading.

On an emulator (for example the Google TV AVD), the debug build skips sign-in and shows a made-up
library with images from picsum.photos, so the UI can be tried without a Google session
(`app/src/debug/.../Demo.kt`). Release builds, and debug builds on a real TV, always use Google Photos.

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:installDebug
```

## Install on the Google TV Streamer

1. On the TV, go to **Settings → System → About** and press **Android TV OS build** 7 times to
   unlock developer options.
2. Go to **Settings → System → Developer options** and turn on **Wireless debugging**. Open it, choose
   **Pair device with pairing code**, and note the IP, port and code.
3. On the Mac:

```bash
adb pair <tv-ip>:<pairing-port>
```

```bash
adb connect <tv-ip>:<debug-port>
```

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:installRelease
```

The app shows up under **Apps** on the Google TV home screen as **Fotos**.

## Sign in

The app borrows the Google session of your desktop Chrome. The **Fotos TV** extension in
`chrome-extension/` keeps it in sync, so the TV signs itself back in whenever Google rotates the
session cookies.

1. In Chrome, open `chrome://extensions`, turn on **Developer mode**, choose **Load unpacked** and
   pick the `chrome-extension` folder.
2. Open the Fotos app on the TV. Its sign-in screen shows the TV's address (e.g. `192.168.86.27:8765`)
   and a pairing code.
3. Click the extension's icon, enter both and choose **Guardar e sincronizar**. Chrome asks to let
   the extension reach that address; allow it.
4. Make sure you're signed in at [photos.google.com](https://photos.google.com) in that Chrome profile.

From then on the extension sends Chrome's Google cookies to the TV every minute, and a few seconds
after Google rotates one. The TV only receives while the app is running: if the session ends while
it's closed, the app opens on the sign-in screen and signs in within a minute, as long as Chrome is
open on the computer. If the TV's address changes, enter the new one in the extension.

The cookies travel encrypted (AES-GCM, with a key derived from the pairing code), and the app refuses
anything that doesn't decrypt with its code, is older than 10 minutes, or repeats an earlier push. Only
`google.com` cookies are sent, and the app keeps only the ones Photos needs.

### Without the extension

Export the cookies of photos.google.com in `cookies.txt` format (for example with *Get cookies.txt
LOCALLY*) and, with the app on its sign-in screen, push the file:

```bash
adb push ~/Downloads/cookies.txt /sdcard/Android/data/com.jjmluz.photostv/files/cookies.txt
```

The app imports it and deletes it. Delete your local copy too: it holds a live Google session. A
one-off export stops working when Google rotates the session, so the app eventually returns to the
sign-in screen.

## Project layout

```
app/src/main/java/com/jjmluz/photostv/
  PhotosTvApp.kt        OkHttp + Coil setup, sign-out
  MainActivity.kt       auth gate and top-level navigation
  MainViewModel.kt      feeds, tab/album/viewer state, focus restoration keys
  data/
    PhotosApi.kt        batchexecute client and response parsing
    Feed.kt             incremental page loading, date jumps, loading newer items
    MonthIndex.kt       months that have photos, for the timeline panel
    WebViewCookieJar.kt shares WebView cookies with OkHttp
    CookieImport.kt     parses cookies.txt into the cookie store
    CookieSync.kt       receives encrypted cookies from the Chrome extension
  ui/
    SignInScreen.kt     sign-in by importing a browser session
    HomeScreen.kt       top tabs (Fotos, Álbuns, Pessoas)
    MediaGridView.kt    day-grouped, paged photo grid
    TimelinePanel.kt    years/months panel on the right of Fotos
    AlbumsScreen.kt     album list and album contents
    PeopleScreen.kt     people grid and a person's photos
    ViewerScreen.kt     full-screen viewer, slideshow, video player
```

## Launcher banner

The Google TV launcher shows apps as 320×180 cards (`res/drawable/banner.xml`): a white card with the
pinwheel and "Fotos". Vector drawables can't hold text, so the word is stored as outlines generated
from Roboto. To change it, edit and re-run the generator:

```bash
"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java" -Djava.awt.headless=true tools/Banner.java "/Applications/Android Studio.app/Contents/plugins/design-tools/resources/layoutlib/data/fonts/RobotoStatic-Regular.ttf" app/src/main/res/drawable/banner.xml
```
