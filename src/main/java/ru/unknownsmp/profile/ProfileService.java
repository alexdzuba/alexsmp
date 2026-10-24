package ru.unknownsmp.profile;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.db.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.logging.Level;

/** Загрузка, кэширование и периодическое сохранение профилей игроков. */
public final class ProfileService implements Module, Listener {
    private final UnknownSMP plugin;
    private final Map<UUID, Profile> profiles = new ConcurrentHashMap<>();
    private final List<StatHook> statHooks = new ArrayList<>();

    public interface StatHook {
        void onStat(Player p, Profile prof, String stat, long newValue);
    }

    public ProfileService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "profiles"; }
    @Override public String title() { return "Профили игроков"; }
    @Override public String[] depends() { return new String[]{"database"}; }

    @Override
    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        int interval = plugin.configs().get("performance.yml").getInt("profile-save-seconds", 60);
        Tasks.timer(20L * interval, 20L * interval, this::flushAll);
        Tasks.timer(20L, 20L, this::tickPlaytime);
        // На случай /reload — загрузить уже онлайн-игроков.
        for (Player p : Bukkit.getOnlinePlayers()) {
            Tasks.async(() -> {
                try {
                    Profile pr = loadBlocking(p.getUniqueId(), p.getName());
                    profiles.put(p.getUniqueId(), pr);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Не удалось загрузить профиль " + p.getName(), e);
                }
            });
        }
    }

    @Override
    public void disable() {
        flushAll();
    }

    public void addHook(StatHook h) { statHooks.add(h); }

    public Profile get(Player p) { return p == null ? null : profiles.get(p.getUniqueId()); }

    public Profile get(UUID u) { return profiles.get(u); }

    public java.util.Collection<Profile> online() { return profiles.values(); }

    // ---------- Загрузка ----------

    @EventHandler(priority = EventPriority.HIGH)
    public void preLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        try {
            Profile p = loadBlocking(e.getUniqueId(), e.getName());
            profiles.put(e.getUniqueId(), p);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось загрузить профиль " + e.getName(), ex);
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Text.c("&cОшибка загрузки данных аккаунта. Данные не повреждены — попробуйте позже.\n&7Администрация уведомлена."));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        Profile p = profiles.get(e.getPlayer().getUniqueId());
        if (p == null) {
            // Профиль не успел загрузиться (крайне редкий случай) — безопасно отключаем.
            e.getPlayer().kick(Text.c("&cДанные аккаунта не загружены. Переподключитесь."));
            return;
        }
        p.name = e.getPlayer().getName();
        p.lastSeen = System.currentTimeMillis();
        p.dirty = true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Profile p = profiles.get(e.getPlayer().getUniqueId());
        if (p == null) return;
        p.lastSeen = System.currentTimeMillis();
        p.dirty = true;
        save(p).whenComplete((v, t) -> {
            // Удаляем из кэша, только если игрок не вернулся за это время.
            if (Bukkit.getPlayer(p.uuid) == null) profiles.remove(p.uuid, p);
        });
    }

    public Profile loadBlocking(UUID uuid, String name) throws Exception {
        long start = plugin.configs().get("economy.yml").getLong("start-balance", 10000);
        return plugin.db().txBlocking(c -> {
            Profile p = new Profile(uuid, name);
            boolean exists = false;
            try (PreparedStatement ps = Database.ps(c, "SELECT * FROM players WHERE uuid=?", uuid.toString());
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    exists = true;
                    p.balance = rs.getLong("balance");
                    p.reputation = rs.getLong("reputation");
                    p.xp = rs.getLong("xp");
                    p.level = rs.getInt("level");
                    p.progRank = rs.getString("prog_rank");
                    p.title = rs.getString("title");
                    p.staffRole = rs.getString("staff_role") == null ? "player" : rs.getString("staff_role");
                    p.playtime = rs.getLong("playtime");
                    p.firstJoin = rs.getLong("first_join");
                    p.lastSeen = rs.getLong("last_seen");
                    p.parseSettings(rs.getString("settings"));
                    p.lastLoc = rs.getString("last_loc");
                    p.chatMode = rs.getString("chat_mode") == null ? "global" : rs.getString("chat_mode");
                    p.cosmetic = rs.getString("cosmetic");
                    p.combatFlag = rs.getInt("combat_flag") == 1;
                }
            }
            long now = System.currentTimeMillis();
            if (!exists) {
                p.balance = start;
                p.firstJoin = now;
                p.lastSeen = now;
                Database.update(c, "INSERT INTO players(uuid, name, name_lower, balance, first_join, last_seen, staff_role) VALUES (?,?,?,?,?,?,?)",
                        uuid.toString(), name, name.toLowerCase(), start, now, now, "player");
                Database.update(c, "INSERT INTO money_log(at, uuid, delta, balance_after, reason, ref) VALUES (?,?,?,?,?,?)",
                        now, uuid.toString(), start, start, "start_balance", null);
            } else {
                Database.update(c, "UPDATE players SET name=?, name_lower=? WHERE uuid=?", name, name.toLowerCase(), uuid.toString());
            }
            try (PreparedStatement ps = Database.ps(c, "SELECT stat, value FROM player_stats WHERE uuid=?", uuid.toString());
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) p.stats.put(rs.getString(1), rs.getLong(2));
            }
            try (PreparedStatement ps = Database.ps(c, "SELECT kind, ukey FROM unlocks WHERE uuid=?", uuid.toString());
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) p.unlocks.add(rs.getString(1) + ":" + rs.getString(2));
            }
            try (PreparedStatement ps = Database.ps(c, "SELECT name, loc FROM homes WHERE uuid=? ORDER BY created_at", uuid.toString());
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) p.homes.put(rs.getString(1), rs.getString(2));
            }
            try (PreparedStatement ps = Database.ps(c,
                    "SELECT ptype, reason, expires_at FROM punishments WHERE target=? AND active=1 AND ptype IN ('MUTE','TRADE')", uuid.toString());
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long exp = rs.getLong(3);
                    if (exp > 0 && exp < now) continue;
                    long until = exp <= 0 ? -1 : exp;
                    if (rs.getString(1).equals("MUTE")) { p.muteUntil = until; p.muteReason = rs.getString(2); }
                    else p.tradeBlockUntil = until;
                }
            }
            return p;
        });
    }

    // ---------- Сохранение ----------

    public java.util.concurrent.CompletableFuture<Void> save(Profile p) {
        // Снимок значений в текущем потоке, запись — в потоке БД.
        final String name = p.name, rank = p.progRank, title = p.title, settings = p.settingsString(), loc = p.lastLoc,
                chat = p.chatMode, cosm = p.cosmetic;
        final long rep = p.reputation, xp = p.xp, pt = p.playtime, seen = p.lastSeen;
        final int lvl = p.level;
        final boolean cf = p.combatFlag;
        final long activity = p.pendingActivity;
        p.pendingActivity = 0;
        final Map<String, Long> stats = new java.util.HashMap<>();
        for (String s : p.dirtyStats) stats.put(s, p.stat(s));
        p.dirtyStats.clear();
        p.dirty = false;
        final long day = LocalDate.now(Text.zone()).toEpochDay();
        return plugin.db().<Void>tx(c -> {
            Database.update(c, "UPDATE players SET name=?, reputation=?, xp=?, level=?, prog_rank=?, title=?, playtime=?, last_seen=?, settings=?, last_loc=?, chat_mode=?, cosmetic=?, combat_flag=? WHERE uuid=?",
                    name, rep, xp, lvl, rank, title, pt, seen, settings, loc, chat, cosm, cf ? 1 : 0, p.uuid.toString());
            upsertStats(c, p.uuid, stats);
            if (activity > 0) {
                Database.update(c, "INSERT INTO activity(uuid, day, seconds) VALUES (?,?,?) ON CONFLICT(uuid, day) DO UPDATE SET seconds = activity.seconds + excluded.seconds",
                        p.uuid.toString(), day, activity);
            }
            return null;
        }).exceptionally(t -> {
            plugin.getLogger().log(Level.WARNING, "Ошибка сохранения профиля " + name, t);
            // Возвращаем грязные флаги — сохраним при следующем цикле.
            p.dirtyStats.addAll(stats.keySet());
            p.pendingActivity += activity;
            p.dirty = true;
            return null;
        });
    }

    private static void upsertStats(Connection c, UUID u, Map<String, Long> stats) throws Exception {
        if (stats.isEmpty()) return;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO player_stats(uuid, stat, value) VALUES (?,?,?) ON CONFLICT(uuid, stat) DO UPDATE SET value = excluded.value")) {
            for (var e : stats.entrySet()) {
                ps.setString(1, u.toString());
                ps.setString(2, e.getKey());
                ps.setLong(3, e.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public void flushAll() {
        for (Profile p : profiles.values()) {
            if (p.dirty || !p.dirtyStats.isEmpty() || p.pendingActivity > 0) save(p);
        }
    }

    private void tickPlaytime() {
        for (Player pl : Bukkit.getOnlinePlayers()) {
            Profile p = profiles.get(pl.getUniqueId());
            if (p == null || plugin.auth() == null || !plugin.auth().isAuthed(pl)) continue;
            boolean afk = plugin.afk() != null && plugin.afk().isAfk(pl);
            if (afk) continue;
            p.playtime++;
            p.pendingActivity++;
            p.dirty = true;
            if (p.playtime % 60 == 0) addStat(pl, "playtime_min", 1);
        }
    }

    // ---------- Статистика и разблокировки ----------

    public void addStat(Player pl, String stat, long delta) {
        Profile p = get(pl);
        if (p == null || delta == 0) return;
        long v = p.stats.merge(stat, delta, Long::sum);
        p.dirtyStats.add(stat);
        for (StatHook h : statHooks) {
            try { h.onStat(pl, p, stat, v); } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Ошибка обработчика статистики", t);
            }
        }
    }

    public void setStatMax(Player pl, String stat, long value) {
        Profile p = get(pl);
        if (p == null) return;
        if (value > p.stat(stat)) {
            addStat(pl, stat, value - p.stat(stat));
        }
    }

    /** Разблокировка (титул, достижение, косметика, флаг диалога). true — если новая. */
    public boolean unlock(Player pl, String kind, String key) {
        Profile p = get(pl);
        if (p == null) return false;
        if (!p.unlocks.add(kind + ":" + key)) return false;
        final String u = p.uuid.toString();
        plugin.db().tx(c -> Database.update(c,
                "INSERT INTO unlocks(uuid, kind, ukey, at) VALUES (?,?,?,?) ON CONFLICT DO NOTHING", u, kind, key, System.currentTimeMillis()));
        return true;
    }

    public void lock(Player pl, String kind, String key) {
        Profile p = get(pl);
        if (p == null) return;
        if (!p.unlocks.remove(kind + ":" + key)) return;
        final String u = p.uuid.toString();
        plugin.db().tx(c -> Database.update(c, "DELETE FROM unlocks WHERE uuid=? AND kind=? AND ukey=?", u, kind, key));
    }

    /** Поиск UUID по имени (без учёта регистра) среди известных игроков. */
    public java.util.concurrent.CompletableFuture<UUID> findUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return java.util.concurrent.CompletableFuture.completedFuture(online.getUniqueId());
        for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().equalsIgnoreCase(name))
            return java.util.concurrent.CompletableFuture.completedFuture(p.getUniqueId());
        return plugin.db().query(c -> {
            String s = Database.strVal(c, "SELECT uuid FROM players WHERE name_lower=?", name.toLowerCase());
            return s == null ? null : UUID.fromString(s);
        });
    }

    public java.util.concurrent.CompletableFuture<String> findName(UUID u) {
        Player online = Bukkit.getPlayer(u);
        if (online != null) return java.util.concurrent.CompletableFuture.completedFuture(online.getName());
        return plugin.db().query(c -> Database.strVal(c, "SELECT name FROM players WHERE uuid=?", u.toString()));
    }

    public void forEachOnline(BiConsumer<Player, Profile> fn) {
        for (Player pl : Bukkit.getOnlinePlayers()) {
            Profile p = profiles.get(pl.getUniqueId());
            if (p != null) fn.accept(pl, p);
        }
    }
}
