package de.syntaxfehler.ligpsport

import android.app.Application
import android.content.Context
import org.osmdroid.config.Configuration

/**
 * Bootstraps osmdroid before any [org.osmdroid.views.MapView] is inflated:
 *
 * - Sets a custom user-agent (Mapnik returns 403 to the default).
 * - Points the tile cache at app-private storage so we don't need
 *   `WRITE_EXTERNAL_STORAGE` on Android 11+.
 * - Caps the cache at 50 MiB so tiles persist across app launches —
 *   re-opening the map on the same area is then offline-fast.
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

        // App-private dirs avoid the storage-permission dance and get
        // wiped on uninstall. cacheDir is fair game for the OS to evict
        // under storage pressure, which is exactly what we want for
        // regenerable map tiles.
        val baseDir = java.io.File(cacheDir, "osmdroid").apply { mkdirs() }
        val tileDir = java.io.File(baseDir, "tiles").apply { mkdirs() }
        cfg.osmdroidBasePath = baseDir
        cfg.osmdroidTileCache = tileDir

        // 50 MiB hard ceiling; trim back to 45 MiB once we hit it so the
        // sweep doesn't run on every tile fetch. A z14 256-px PNG averages
        // ~12 KiB → 50 MiB ≈ 4000 tiles ≈ a city's worth at metro zoom.
        cfg.tileFileSystemCacheMaxBytes = 50L * 1024L * 1024L
        cfg.tileFileSystemCacheTrimBytes = 45L * 1024L * 1024L
        cfg.save(this, prefs)
    }
}
