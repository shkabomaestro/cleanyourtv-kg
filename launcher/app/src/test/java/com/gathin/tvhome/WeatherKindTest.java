package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The weather service describes the sky in its own words; the line under the clock has three icons. */
public class WeatherKindTest {
    @Test
    public void sortsTheServicesWordsIntoThreeIcons() {
        assertEquals(Weather.CLEAR, Weather.kind("Vedro"));
        assertEquals(Weather.CLEAR, Weather.kind("Pretežno vedro"));
        assertEquals(Weather.CLOUDY, Weather.kind("Oblačno"));
        assertEquals(Weather.CLOUDY, Weather.kind("Umereno oblačno"));
        assertEquals(Weather.CLOUDY, Weather.kind("Magla"));
        assertEquals(Weather.CLOUDY, Weather.kind(""));
        assertEquals(Weather.WET, Weather.kind("Slaba kiša"));
        assertEquals(Weather.WET, Weather.kind("Pljusak sa grmljavinom"));
        assertEquals(Weather.WET, Weather.kind("Sneg"));
        assertEquals(Weather.WET, Weather.kind("Susnežica"));
        assertEquals(Weather.WET, Weather.kind("Rosulja"));
    }

    @Test
    public void coloursTheAirIndexByItsBand() {
        assertEquals(0xFF4CAF50, Weather.airColour(0));
        assertEquals(0xFF4CAF50, Weather.airColour(50));
        assertEquals(0xFFFDD835, Weather.airColour(51));
        assertEquals(0xFFFB8C00, Weather.airColour(150));
        assertEquals(0xFFE53935, Weather.airColour(151));
        assertEquals(0xFF8E24AA, Weather.airColour(300));
        assertEquals(0xFF7E0023, Weather.airColour(301));
    }
}
