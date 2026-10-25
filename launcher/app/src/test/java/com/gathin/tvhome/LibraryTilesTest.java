package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** library_home_tiles: the library's tiles and their glass zoom exactly as Home's do (AppAdapter.focusScale). */
public class LibraryTilesTest {
    @Test public void focusScaleIsHomesZoom() {
        assertEquals(1f, AppAdapter.focusScale(false, true), 0f);     // animations off: no zoom, the glass hugs the tile
        assertEquals(1.10f, AppAdapter.focusScale(true, false), 0f);
        assertEquals(1.12f, AppAdapter.focusScale(true, true), 0f);   // advanced_effects
    }
}
