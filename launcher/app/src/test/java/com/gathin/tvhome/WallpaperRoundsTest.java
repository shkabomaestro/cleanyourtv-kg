package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

/** rounds (local): every picture once per round, in random order, and a new round never repeats the last one. */
public class WallpaperRoundsTest {
    private static List<File> pictures(int n) {
        List<File> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new File("/w", String.format("p%02d.jpg", i)));
        return out;
    }

    @Test
    public void everyPictureOnceBeforeAnyComesBack() {
        List<File> all = pictures(50);
        Set<String> played = new HashSet<>();
        Random random = new Random(7);
        String previous = "";
        for (int round = 0; round < 4; round++) {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < all.size(); i++) {
                File next = WallpaperRotation.unplayed(all, played, previous, random);
                assertTrue("repeated within a round: " + next, seen.add(next.getName()));
                assertNotEquals(previous, next.getName());
                previous = next.getName();
            }
            assertEquals(50, seen.size());
        }
    }

    @Test
    public void eachTimeOfDayGoesRoundOnItsOwn() {
        List<File> all = pictures(10);
        List<File> night = all.subList(0, 3);
        Set<String> played = new HashSet<>();
        Random random = new Random(3);
        // the morning shows four, then night comes: the night group starts with nothing played
        for (int i = 0; i < 4; i++) WallpaperRotation.unplayed(all.subList(3, 10), played, "", random);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 3; i++) seen.add(WallpaperRotation.unplayed(night, played, "", random).getName());
        assertEquals(3, seen.size());
        assertEquals(7, played.size());   // the morning's four are still remembered for the next morning
    }

    @Test
    public void thePlayedListSurvivesARestart() {
        Set<String> names = new java.util.LinkedHashSet<>(List.of("a.jpg", "b.jpg"));
        assertEquals(names, WallpaperRotation.played(WallpaperRotation.played(names)));
        assertTrue(WallpaperRotation.played("").isEmpty());
        assertTrue(WallpaperRotation.validOrder(WallpaperRotation.ORDER_ROUNDS));
    }
}
