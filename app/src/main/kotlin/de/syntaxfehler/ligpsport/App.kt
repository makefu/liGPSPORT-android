package de.syntaxfehler.ligpsport

import android.app.Application
import android.content.Context
import de.syntaxfehler.ligpsport.strava.StravaStore
import org.osmdroid.config.Configuration

/**
 * Bootstraps osmdroid before any [org.osmdroid.views.MapView] is inflated:
 *
 * - Sets a custom user-agent (Mapnik returns 403 to the default).
 * - Points the tile cache at [filesDir]/osmdroid so it survives the
 *   OS's low-storage cache sweep — previously the cache lived under
 *   [cacheDir] and Android happily evicted it whenever free space
 *   dropped, which presented to the user as "the map re-downloads
 *   every launch". filesDir storage counts against the app's quota
 *   but stays put until the user explicitly clears it.
 * - 300 MiB hard ceiling with an aggressive [setExpirationOverrideDuration]
 *   so tiles outlive whatever short max-age Mapnik returns in its
 *   `Cache-Control` headers (default OSM cache lifetime is ~1 day).
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        val cfg = Configuration.getInstance()
        // Load first — picks up any persisted user adjustments — then
        // overwrite the fields we care about so they survive even when
        // an older prefs file is on disk.
        cfg.load(this, prefs)
        cfg.userAgentValue = BuildConfig.APPLICATION_ID

        // Persistent (filesDir) instead of cacheDir: aggressive tile
        // cache, not opportunistic. Wipes only on uninstall or via
        // Settings → Storage → Clear data.
        val baseDir = java.io.File(filesDir, "osmdroid").apply { mkdirs() }
        val tileDir = java.io.File(baseDir, "tiles").apply { mkdirs() }
        cfg.osmdroidBasePath = baseDir
        cfg.osmdroidTileCache = tileDir

        // 300 MiB ceiling, trim back to 270 MiB once we hit it. A z14
        // 256-px PNG averages ~12 KiB → 300 MiB ≈ 25 000 tiles, room
        // for several countries at navigation-relevant zoom levels.
        cfg.tileFileSystemCacheMaxBytes = 300L * 1024L * 1024L
        cfg.tileFileSystemCacheTrimBytes = 270L * 1024L * 1024L

        // OSM Mapnik tiles return a short Cache-Control max-age (~1 d)
        // — without these overrides osmdroid considers them stale and
        // re-fetches every launch even when the bytes are still in the
        // file cache. Force a 30-day TTL and extend re-fetched tiles by
        // a week so they don't all expire on the same day.
        cfg.expirationOverrideDuration = 30L * 24L * 60L * 60L * 1000L
        cfg.expirationExtendedDuration = 7L * 24L * 60L * 60L * 1000L

        // Memory cache (in-RAM decoded bitmaps). Defaults are tuned for
        // ~9 tiles which thrashes on any modern map screen. 250 tiles
        // ≈ 32 MiB of bitmap RAM at z14, well within the heap budget
        // for our minSdk-30 target and eliminates re-decode jank when
        // the user pans within a recently-loaded area.
        cfg.cacheMapTileCount = 250.toShort()
        cfg.cacheMapTileOvershoot = 20.toShort()

        cfg.save(this, prefs)

        // One-shot: copies any Strava credentials baked in at build
        // time into the editable Settings fields and enables the
        // feature. A no-op on every launch after the first, and on
        // builds that carry no credentials.
        StravaStore(this).seedFromBuildConfig()
    }
}
