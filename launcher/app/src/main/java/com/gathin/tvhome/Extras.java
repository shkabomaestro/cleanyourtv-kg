package com.gathin.tvhome;

import android.content.Context;

/**
 * Local addition: the fork's own switches, from the theme's config.json with their defaults in config.xml
 * (Config.liveExtras). weather_effects draws the weather outside over the picture (WeatherFx), news_ticker runs the
 * headlines along the bottom of Home (NewsTicker). Both need the radio add-on, which reads the network for them.
 */
final class Extras {
    private Extras() {
    }

    static boolean weatherEffects(Context context) {
        return read(context)[0];
    }

    static boolean newsTicker(Context context) {
        return read(context)[1];
    }

    private static boolean[] read(Context context) {
        try {
            return Config.liveExtras(context);
        } catch (RuntimeException e) {
            return new boolean[]{false, false};   // an unreadable theme: both off
        }
    }
}
