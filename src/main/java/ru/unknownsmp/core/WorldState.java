package ru.unknownsmp.core;

import ru.unknownsmp.db.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Глобальное состояние сервера (открытие измерений, прогресс коллективных целей и т.п.) в таблице world_state. */
public final class WorldState implements Module {
    private final UnknownSMP plugin;
    private final Map<String, String> values = new ConcurrentHashMap<>();

    public WorldState(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "state"; }
    @Override public String title() { return "Состояние мира"; }
    @Override public String[] depends() { return new String[]{"database"}; }

    @Override
    public void enable() throws Exception {
        plugin.db().queryBlocking(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT k, v FROM world_state"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) if (rs.getString(2) != null) values.put(rs.getString(1), rs.getString(2));
            }
            return null;
        });
    }

    public String get(String k) { return values.get(k); }

    public String get(String k, String def) { return values.getOrDefault(k, def); }

    public long getLong(String k, long def) {
        try {
            String v = values.get(k);
            return v == null ? def : Long.parseLong(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean getBool(String k) { return "true".equals(values.get(k)); }

    public CompletableFuture<Integer> set(String k, Object v) {
        if (v == null) values.remove(k);
        else values.put(k, String.valueOf(v));
        final String val = v == null ? null : String.valueOf(v);
        return plugin.db().tx(c -> Database.update(c,
                "INSERT INTO world_state(k, v) VALUES (?,?) ON CONFLICT(k) DO UPDATE SET v = excluded.v", k, val));
    }
}
