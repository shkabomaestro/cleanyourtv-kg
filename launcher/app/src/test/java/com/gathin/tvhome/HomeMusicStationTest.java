package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** stations.txt is typed by the owner: one "Name|http..." per line, anything else is skipped. */
public class HomeMusicStationTest {
    @Test
    public void readsNameAndAddress() {
        assertArrayEquals(new String[]{"Радио 34", "http://stream.radio34.rs:8889/;"},
                HomeMusic.Station.parse("﻿ Радио 34 | http://stream.radio34.rs:8889/; "));
    }

    @Test
    public void skipsWhatIsNotAStation() {
        assertNull(HomeMusic.Station.parse(""));
        assertNull(HomeMusic.Station.parse("# comment"));
        assertNull(HomeMusic.Station.parse("|https://example.com/a.mp3"));
        assertNull(HomeMusic.Station.parse("Name|ftp://example.com/a.mp3"));
        assertNull(HomeMusic.Station.parse("music|https://example.com/a.mp3"));   // the two built-in sources keep their names
        assertNull(HomeMusic.Station.parse("off|https://example.com/a.mp3"));
    }
}
