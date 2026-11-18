package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The sky in words beside the temperature (local addition). */
public class WeatherWordsTest {
    @Test
    public void namesTheSky() {
        assertEquals("Сунчано", Weather.describe(0, 1, 12));
        assertEquals("Ведро", Weather.describe(0, 0, 12));
        assertEquals("Ведро", Weather.describe(0, -1, 23));
        assertEquals("Облачно", Weather.describe(3, 1, 12));
        assertEquals("Слаба киша", Weather.describe(61, 1, 12));
        assertEquals("Јака киша", Weather.describe(65, 1, 12));
        assertEquals("Снег", Weather.describe(73, 1, 12));
        assertEquals("Грмљавина са градом", Weather.describe(99, 1, 12));
        assertEquals("", Weather.describe(-1, 1, 12));
    }
}
