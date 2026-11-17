package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The live weather picks its scene from the WMO weather code Open-Meteo gives (local addition). */
public class WeatherFxSceneTest {
    @Test
    public void sortsTheCodes() {
        assertEquals(WeatherFx.CLEAR, WeatherFx.scene(0, 1, 0, 5, 12).kind);
        assertEquals(WeatherFx.PARTLY, WeatherFx.scene(2, 1, 0, 5, 12).kind);
        assertEquals(WeatherFx.OVERCAST, WeatherFx.scene(3, 1, 0, 5, 12).kind);
        assertEquals(WeatherFx.FOG, WeatherFx.scene(45, 1, 0, 5, 12).kind);
        assertEquals(WeatherFx.DRIZZLE, WeatherFx.scene(53, 1, 0, 5, 12).kind);
        assertEquals(WeatherFx.RAIN, WeatherFx.scene(63, 1, 0, 5, 12).kind);
        assertEquals(WeatherFx.RAIN, WeatherFx.scene(81, 1, 0, 5, 12).kind);
        assertEquals(WeatherFx.SNOW, WeatherFx.scene(73, 1, 0, 5, 12).kind);
        assertEquals(WeatherFx.SLEET, WeatherFx.scene(66, 1, 0, 5, 12).kind);
        assertEquals(WeatherFx.STORM, WeatherFx.scene(95, 1, 0, 5, 12).kind);
        assertTrue(WeatherFx.scene(99, 1, 0, 5, 12).hail);
        assertEquals(WeatherFx.NONE, WeatherFx.scene(-1, 1, 0, 5, 12).kind);
    }

    @Test
    public void rainGetsHeavierWithTheCodeAndTheMeasuredRain() {
        float light = WeatherFx.scene(61, 1, 0, 0, 12).intensity;
        float heavy = WeatherFx.scene(65, 1, 0, 0, 12).intensity;
        assertTrue(light < heavy);
        assertTrue(WeatherFx.scene(61, 1, 6f, 0, 12).intensity > light);   // 6 mm in the hour: more than "slight"
    }

    @Test
    public void dayNightAndWind() {
        assertFalse(WeatherFx.scene(0, 0, 0, 0, 12).day);
        assertTrue(WeatherFx.scene(0, -1, 0, 0, 12).day);    // unknown: by the hour
        assertFalse(WeatherFx.scene(0, -1, 0, 0, 23).day);
        assertEquals(0f, WeatherFx.scene(63, 1, 0, 0, 12).slant, 0.001f);
        assertEquals(0.5f, WeatherFx.scene(63, 1, 0, 200, 12).slant, 0.001f);
    }
}
