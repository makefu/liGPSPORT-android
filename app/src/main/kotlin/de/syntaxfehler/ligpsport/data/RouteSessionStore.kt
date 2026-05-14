package de.syntaxfehler.ligpsport.data

import java.util.concurrent.atomic.AtomicReference

/**
 * In-process holder for the user's current route session — the
 * destination they picked and (optionally) the GPX the active router
 * planned for it. Survives Compose navigation (which destroys
 * Composable `remember` state), so going Map → Upload → Back to map
 * doesn't lose the planned polyline.
 *
 * Deliberately not persisted across process death — a fresh launch
 * starts with no destination.
 *
 * Cleared explicitly by the user via the "X" on the destination card
 * (or when they pick a new destination on the map).
 */
object RouteSessionStore {

    data class Session(
        val destinationName: String,
        val destinationLat: Double,
        val destinationLon: Double,
        val plannedGpx: ByteArray? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Session) return false
            return destinationName == other.destinationName &&
                destinationLat == other.destinationLat &&
                destinationLon == other.destinationLon &&
                (plannedGpx?.contentEquals(other.plannedGpx) ?: (other.plannedGpx == null))
        }

        override fun hashCode(): Int {
            var r = destinationName.hashCode()
            r = 31 * r + destinationLat.hashCode()
            r = 31 * r + destinationLon.hashCode()
            r = 31 * r + (plannedGpx?.contentHashCode() ?: 0)
            return r
        }
    }

    private val ref = AtomicReference<Session?>(null)

    fun get(): Session? = ref.get()
    fun set(session: Session) {
        ref.set(session)
    }
    fun clear() {
        ref.set(null)
    }

    /** Update only the GPX field of the current session, keeping the
     *  destination intact. No-op if there is no current session. */
    fun setPlannedGpx(gpx: ByteArray?) {
        while (true) {
            val cur = ref.get() ?: return
            val next = cur.copy(plannedGpx = gpx)
            if (ref.compareAndSet(cur, next)) return
        }
    }
}
