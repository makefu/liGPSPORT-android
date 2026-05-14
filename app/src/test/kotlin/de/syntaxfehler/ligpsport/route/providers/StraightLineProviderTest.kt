package de.syntaxfehler.ligpsport.route.providers

import com.google.common.truth.Truth.assertThat
import de.syntaxfehler.ligpsport.route.Point
import kotlinx.coroutines.test.runTest
import org.junit.Test

class StraightLineProviderTest {
    @Test
    fun interpolate_includes_endpoints() {
        val start = Point(48.7700, 9.1800)
        val end = Point(48.8339, 9.2293)
        val pts = StraightLineProvider.interpolate(start, end, 20)
        assertThat(pts).hasSize(20)
        // Endpoints match within 0.0001° (rounding through Cartesian space).
        assertThat(pts.first().latitude).isWithin(1e-4).of(start.latitude)
        assertThat(pts.first().longitude).isWithin(1e-4).of(start.longitude)
        assertThat(pts.last().latitude).isWithin(1e-4).of(end.latitude)
        assertThat(pts.last().longitude).isWithin(1e-4).of(end.longitude)
    }

    @Test
    fun interpolate_degenerate_start_equals_end() {
        val p = Point(48.7700, 9.1800)
        val pts = StraightLineProvider.interpolate(p, p, 5)
        assertThat(pts).hasSize(5)
        for (q in pts) {
            assertThat(q.latitude).isEqualTo(p.latitude)
            assertThat(q.longitude).isEqualTo(p.longitude)
        }
    }

    @Test
    fun planGpx_returns_well_formed_gpx() = runTest {
        val provider = StraightLineProvider()
        val gpx = provider.planGpx(
            start = Point(48.7700, 9.1800),
            end = Point(48.8339, 9.2293),
        ).decodeToString()
        assertThat(gpx).contains("<gpx ")
        assertThat(gpx).contains("creator=\"ligpsport-straightline\"")
        assertThat(gpx).contains("<trkpt")
        // 20 trackpoints by default.
        assertThat(gpx.split("<trkpt").size - 1).isEqualTo(20)
    }

    @Test
    fun provider_is_offline() {
        assertThat(StraightLineProvider().isOffline).isTrue()
    }
}
