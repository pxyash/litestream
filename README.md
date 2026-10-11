# LiteStream

Lightweight Stremio-addon client for Android TV and tablets (Kotlin, Compose, Media3).

## Build the APK on GitHub
1. Create a new GitHub repo and upload everything in this folder (keep the `.github` folder).
2. Open the repo's **Actions** tab, then **Build APK**, then **Run workflow** (it also runs on every push).
3. When it finishes, open the run and download the **LiteStream-apk** artifact. Unzip it and install the APK.

## Using it
- Settings: paste an addon URL (`…/manifest.json` or `stremio://…`) and press Install. Cinemeta (catalogs/metadata) is installed on first launch.
- For TorBox, install a stream addon that supports TorBox (e.g. a Torrentio config with your TorBox key). Your key stays inside the addon URL, which the app only displays as a hostname.
- Home → pick a title → Play → pick a source (filter by provider, sort by quality/size/seeders) → player.
- Player: OK/tap shows controls; the gear icon has playback speed and audio track; the CC button picks subtitles.
