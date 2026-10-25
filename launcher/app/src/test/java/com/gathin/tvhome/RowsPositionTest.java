package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RowsPositionTest {
    // A 1080 px grid, a 300 px dock row, 260 px tiles, the clock ending at 200 px.
    @Test
    public void middleCentresTheDockRow() {
        assertEquals(390, AppAdapter.movedDockTop(700, 1080, 300, 260, true, 0, 200));
    }

    @Test
    public void offsetMovesTheUsualPlace() {
        assertEquals(600, AppAdapter.movedDockTop(700, 1080, 300, 260, false, -100, 200));
        assertEquals(440, AppAdapter.movedDockTop(700, 1080, 300, 260, true, 50, 200));
    }

    @Test
    public void theRowNeverGoesOverTheClockOrOffTheScreen() {
        assertEquals(200, AppAdapter.movedDockTop(700, 1080, 300, 260, false, -900, 200));
        assertEquals(820, AppAdapter.movedDockTop(700, 1080, 300, 260, false, 900, 200));
    }
}
