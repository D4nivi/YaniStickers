package io.github.danivi.yanistickers.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionComparatorTest {

    @Test
    fun `detects higher semantic version across segments`() {
        assertTrue(AppVersionComparator.isNewer("v1.0.1", "1.0.0"))
        assertTrue(AppVersionComparator.isNewer("v1.1.0", "1.0.9"))
        assertTrue(AppVersionComparator.isNewer("2.0.0", "1.99.99"))
        assertTrue(AppVersionComparator.isNewer("v1.10.0", "1.2.0"))
        assertTrue(AppVersionComparator.isNewer("v1.1", "1.0.5"))
    }

    @Test
    fun `returns false for equal or older versions`() {
        assertFalse(AppVersionComparator.isNewer("v1.0.0", "1.0.0"))
        assertFalse(AppVersionComparator.isNewer("v1.0", "1.0.0"))
        assertFalse(AppVersionComparator.isNewer("v1.0.0", "1.0.1"))
        assertFalse(AppVersionComparator.isNewer("v0.9.9", "1.0.0"))
    }

    @Test
    fun `handles prerelease suffixes and invalid strings safely`() {
        assertTrue(AppVersionComparator.isNewer("v1.0.0", "1.0.0-beta1"))
        assertFalse(AppVersionComparator.isNewer("v1.0.0-beta1", "1.0.0"))
        assertFalse(AppVersionComparator.isNewer("invalid", "1.0.0"))
        assertFalse(AppVersionComparator.isNewer("v1.0.1", "invalid"))
    }

    @Test
    fun `normalizes v prefix`() {
        assertEquals("1.2.3", AppVersionComparator.normalize(" v1.2.3 "))
        assertEquals("1.2.3", AppVersionComparator.normalize("V1.2.3"))
    }
}
