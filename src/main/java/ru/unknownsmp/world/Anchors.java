package ru.unknownsmp.world;

import org.bukkit.Location;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Именованные точки построек (места NPC, голограмм, секретов). Сохраняются в БД и переживают перезапуск. */
public final class Anchors {
    private Anchors() {}

    private static final Map<String, Location> cache = new ConcurrentHashMap<>();

    public static void put(String name, Location l) {
        if (l == null || l.getWorld() == null) return;
        cache.put(name, l.clone());
        var st = UnknownSMP.get().state();
        if (st != null) st.set("anchor." + name, Text.loc(l));
    }

    public static Location get(String name) {
        Location l = cache.get(name);
        if (l != null) return l.clone();
        var st = UnknownSMP.get().state();
        if (st == null) return null;
        l = Text.loc(st.get("anchor." + name));
        if (l != null) cache.put(name, l.clone());
        return l == null ? null : l.clone();
    }
}
