package com.gathin.tvhome;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

final class WallpaperRotation {
    /** Playback orders persisted in config.json (wallpaper_order). */
    static final String ORDER_SEQUENTIAL = "sequential";
    static final String ORDER_SHUFFLE = "shuffle";
    /**
     * Local addition: shuffled in rounds. Every picture gets its turn, in random order, before any comes back; then a
     * new round starts, never with the picture just shown. The round survives a restart (WallpaperPlayer keeps it).
     */
    static final String ORDER_ROUNDS = "rounds";

    static boolean validOrder(String order) {
        return ORDER_SEQUENTIAL.equals(order) || ORDER_SHUFFLE.equals(order) || ORDER_ROUNDS.equals(order);
    }

    /**
     * rounds: a random picture of images that this round has not shown yet, added to played. When every one of
     * images has been played, they all leave played and a new round begins without previous. played may hold names
     * from other groups (wallpaper_moods): each group goes round on its own.
     */
    static File unplayed(List<File> images, Set<String> played, String previous, Random random) {
        List<File> candidates = new ArrayList<>();
        for (File image : images) if (!played.contains(image.getName())) candidates.add(image);
        if (candidates.isEmpty()) {
            for (File image : images) played.remove(image.getName());
            candidates.addAll(images);
            if (candidates.size() > 1) candidates.removeIf(image -> image.getName().equals(previous));
        }
        if (candidates.isEmpty()) return null;
        File picked = candidates.get(random.nextInt(candidates.size()));
        played.add(picked.getName());
        return picked;
    }

    /** The played names as kept in the preferences: one per line. */
    static Set<String> played(String kept) {
        Set<String> names = new LinkedHashSet<>();
        if (kept != null) for (String name : kept.split("\n")) if (!name.isEmpty()) names.add(name);
        return names;
    }

    static String played(Set<String> names) {
        return String.join("\n", names);
    }

    static List<File> images(File directory) {
        List<File> result = new ArrayList<>();
        File[] entries = directory == null ? null : directory.listFiles();
        if (entries == null) return result;
        Arrays.sort(entries, Comparator.comparing(File::getName));
        for (File entry : entries) {
            String name = entry.getName().toLowerCase(Locale.ROOT);
            if (entry.isFile() && entry.length() > 0 && entry.length() <= 10 * 1024 * 1024
                    && (name.endsWith(".jpg") || name.endsWith(".jpeg")
                    || name.endsWith(".png") || name.endsWith(".webp"))) {
                result.add(entry);
                if (result.size() == 200) break;   // local: 200, for the Kragujevac theme's eighty-odd pictures and room to grow
            }
        }
        return result;
    }

    static File find(List<File> images, String name) {
        for (File image : images) if (image.getName().equals(name)) return image;
        return null;
    }

    /** The on-TV choice (Launcher settings, Wallpaper) that turns the wallpaper off. */
    static final String CHOICE_NONE = "none";

    /**
     * What an on-TV wallpaper choice puts on Home in place of the theme's rotation: null when the theme decides
     * (no choice, or a picture that is no longer there), an empty list for {@link #CHOICE_NONE}, or the one picture
     * chosen from the theme's collection or its single {@code single} wallpaper.
     */
    static List<File> chosen(List<File> collection, File single, String choice) {
        if (choice == null) return null;
        if (CHOICE_NONE.equals(choice)) return Collections.emptyList();
        File picked = find(collection, choice);
        if (picked == null && single != null && choice.equals(single.getName())) picked = single;
        return picked == null ? null : Collections.singletonList(picked);
    }

    static File pick(List<File> images, String previous, Random random) {
        List<File> candidates = new ArrayList<>(images);
        if (candidates.size() > 1) candidates.removeIf(image -> image.getName().equals(previous));
        return candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
    }

    /**
     * The next picture in the sorted collection, wrapping to the first at the end. The list from
     * {@link #images(File)} is sorted by filename, so this is a stable order across restarts. A
     * previous name that is no longer in the collection starts again at the first picture.
     */
    static File next(List<File> images, String previous) {
        if (images.isEmpty()) return null;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(previous)) {
                return images.get((i + 1) % images.size());
            }
        }
        return images.get(0);
    }

    /** Picks the next picture for {@code order}; an unknown order keeps the historical shuffle. */
    static File pick(List<File> images, String previous, String order, Random random) {
        return ORDER_SEQUENTIAL.equals(order) ? next(images, previous) : pick(images, previous, random);
    }

    static boolean due(long previous, long now, long interval) {
        // The minute tick is a Handler postDelayed, so it drifts by tens of milliseconds and often lands a few
        // hundred short of the interval. Compared exactly, that pass is skipped and the picture waits a whole
        // extra interval - a one-minute collection then changes every two. Two seconds of slack absorbs the
        // drift without letting a genuinely early call advance the picture.
        long slack = Math.min(2_000L, interval / 10);
        return interval > 0 && (previous == 0 || now < previous || now - previous >= interval - slack);
    }
}
