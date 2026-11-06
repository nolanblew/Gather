package ninja.takeya.gather

import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class GatherWindowTest {
    @Test fun navigationAdaptsToAvailableWindowAndShortDesktopHeight() {
        assertEquals(GatherNavigation.Bottom, GatherWindow(360.dp, 760.dp).navigation)
        assertEquals(GatherNavigation.Rail, GatherWindow(760.dp, 760.dp).navigation)
        assertEquals(GatherNavigation.Drawer, GatherWindow(1440.dp, 900.dp).navigation)
        assertEquals(GatherNavigation.Rail, GatherWindow(1440.dp, 320.dp).navigation)
    }

    @Test fun bookFoldRegionsExcludePhysicalOcclusion() {
        val fold = GatherFold(360.dp, 0.dp, 376.dp, 820.dp, vertical = true)
        val window = GatherWindow(736.dp, 820.dp, listOf(fold))
        assertEquals(2, window.regions.size)
        assertTrue(window.regions.all { it.x + it.width <= fold.left || it.x >= fold.right })
        assertEquals(720.dp, window.regions.sumOf { it.width.value.toDouble() }.toFloat().dp)
        assertTrue(window.regions.all { it.height == window.height && it.y == 0.dp })
    }

    @Test fun tabletopChoosesTheLargerUnobstructedRegion() {
        val fold = GatherFold(0.dp, 280.dp, 800.dp, 300.dp, vertical = false)
        val window = GatherWindow(800.dp, 900.dp, listOf(fold))
        assertEquals(GatherRegion(0.dp, 300.dp, 800.dp, 600.dp), window.primaryRegion)
        assertTrue(window.regions.all { it.y + it.height <= fold.top || it.y >= fold.bottom })
    }

    @Test fun aFlatWindowRemainsOneCompleteRegion() {
        val window = GatherWindow(320.dp, 540.dp)
        assertEquals(listOf(GatherRegion(0.dp, 0.dp, 320.dp, 540.dp)), window.regions)
        assertNull(window.verticalFold)
        assertNull(window.horizontalFold)
    }
}
