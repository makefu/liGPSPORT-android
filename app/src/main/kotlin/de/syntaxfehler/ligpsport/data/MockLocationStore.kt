package de.syntaxfehler.ligpsport.data

import de.syntaxfehler.ligpsport.route.Point
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-local mock location injection point used by the adb e2e
 * harness so it doesn't depend on a real GPS lock on a phone sitting
 * indoors. [UploadPipeline.planAndUpload] consults this *before*
 * `FusedLocationProviderClient` so a freshly-set mock wins over a
 * stale system fix.
 *
 * Deliberately in-memory only — survives only as long as the app
 * process. The harness sets it just before each `PLAN_AND_UPLOAD`.
 */
object MockLocationStore {
    private val ref = AtomicReference<Point?>(null)

    fun set(lat: Double, lon: Double) {
        ref.set(Point(lat, lon))
    }

    fun get(): Point? = ref.get()

    fun clear() {
        ref.set(null)
    }
}
