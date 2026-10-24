package ru.unknownsmp.events;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataType;
import ru.unknownsmp.core.Keys;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.UnknownSMP;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Базовый класс события. Награда — только за личный вклад реальными действиями; присутствие не засчитывается.
 * Вклад AFK-игроков не учитывается, повторные однотипные действия имеют убывающую ценность.
 */
public abstract class GameEvent implements Listener {
    protected final UnknownSMP plugin;
    protected final EventService service;
    protected Location loc;
    protected long startedAt;
    protected long endsAt;
    protected boolean finished;
    protected final Map<UUID, Double> contribution = new HashMap<>();
    protected final Map<String, Integer> actionCounts = new HashMap<>();
    protected final List<Entity> spawned = new ArrayList<>();
    protected final Set<UUID> participants = new HashSet<>();

    protected GameEvent(UnknownSMP plugin, EventService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public abstract String type();

    public abstract String title();

    /** Атмосферное предупреждение до начала (без точных координат). */
    public abstract String warning();

    /** Длительность в минутах. */
    public abstract int minutes();

    /** Подготовка мира (вызывается при старте). Возвращает false, если место не подходит. */
    protected abstract boolean setup();

    /** Тик раз в секунду. */
    protected void tick() {}

    /** Условие успеха на момент окончания. */
    protected abstract boolean succeeded();

    /** Очистка мира после завершения. */
    protected void cleanup() {
        for (Entity e : spawned) if (e.isValid()) e.remove();
        spawned.clear();
    }

    public String description() { return ""; }

    public Location location() { return loc; }

    public void setLocation(Location l) { this.loc = l; }

    public long endsAt() { return endsAt; }

    public boolean isFinished() { return finished; }

    public Map<UUID, Double> contribution() { return contribution; }

    /** Вклад за действие. key — тип действия (для убывающей ценности повторов). */
    public void contribute(Player p, double base, String key) {
        if (finished || p == null) return;
        if (plugin.afk() != null && plugin.afk().isAfk(p)) return;
        String k = p.getUniqueId() + ":" + key;
        int n = actionCounts.merge(k, 1, Integer::sum);
        int soft = plugin.configs().get("events.yml").getInt("diminishing-after", 30);
        double mult = n <= soft ? 1.0 : Math.max(0.05, 1.0 / (1 + (n - soft) * 0.15));
        contribution.merge(p.getUniqueId(), base * mult, Double::sum);
        participants.add(p.getUniqueId());
    }

    protected void mark(Entity e) {
        e.getPersistentDataContainer().set(Keys.EVENT_ENTITY, PersistentDataType.STRING, type());
        e.setPersistent(false);
        spawned.add(e);
        hold(e.getLocation());
    }

    protected void announce(String key, String def, Object... kv) {
        Msg.broadcast(key, def, kv);
    }

    protected boolean near(Player p, double r) {
        return loc != null && p.getWorld().equals(loc.getWorld()) && p.getLocation().distanceSquared(loc) <= r * r;
    }

    void begin() {
        startedAt = System.currentTimeMillis();
        endsAt = startedAt + minutes() * 60_000L;
    }

    /** Досрочное завершение (например, все метеоритные блоки добыты). */
    protected void finishNow() {
        service.finish(this, succeeded());
    }

    private final Set<long[]> held = new HashSet<>();

    /** Держит чанки события загруженными, пока оно идёт: иначе при пустой округе чанк выгружается и непостоянные существа (стражи) исчезают. */
    protected void hold(Location at) {
        if (at == null || at.getWorld() == null) return;
        int cx = at.getBlockX() >> 4, cz = at.getBlockZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            long[] key = {cx + dx, cz + dz};
            boolean dup = false;
            for (long[] h : held) if (h[0] == key[0] && h[1] == key[1]) { dup = true; break; }
            if (dup) continue;
            at.getWorld().addPluginChunkTicket(cx + dx, cz + dz, plugin);
            held.add(key);
        }
    }

    void markFinished() {
        finished = true;
        if (loc != null && loc.getWorld() != null) for (long[] h : held) loc.getWorld().removePluginChunkTicket((int) h[0], (int) h[1], plugin);
        held.clear();
    }

    /** Минимальный вклад для награды. */
    public double minContribution() {
        return plugin.configs().get("events.yml").getDouble("types." + type() + ".min-contribution", 3);
    }

    public long moneyPool() {
        return plugin.configs().get("events.yml").getLong("types." + type() + ".money-pool", 5000);
    }
}
