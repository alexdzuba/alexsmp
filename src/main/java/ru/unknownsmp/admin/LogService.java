package ru.unknownsmp.admin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.db.Database;
import ru.unknownsmp.profile.Profile;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Журналы: действия (команды, сделки, баланс, смерти, события, администрирование, подозрительные действия)
 * и изменения блоков (для расследований и ручных откатов). Запись пакетная и асинхронная.
 * Пароли, хеши и сессионные данные сюда не попадают.
 */
public final class LogService implements Module, Listener {
    public record Action(long at, String uuid, String name, String category, String action, String world, Integer x, Integer y, Integer z, String details) {}

    public record BlockEntry(long id, long at, String uuid, String name, String action, String world, int x, int y, int z,
                             String oldData, String newData, byte[] extra, boolean rolledBack) {}

    private final UnknownSMP plugin;
    private final ConcurrentLinkedQueue<Action> actions = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<BlockEntry> blocks = new ConcurrentLinkedQueue<>();
    private volatile boolean active;
    private final java.util.List<java.util.function.Consumer<Action>> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Подписка на записи журнала (вызывается из любого потока, обработчик должен быть быстрым). */
    public void addListener(java.util.function.Consumer<Action> l) { listeners.add(l); }

    private void emit(Action a) {
        for (var l : listeners) {
            try { l.accept(a); } catch (Throwable ignored) { }
        }
    }

    public LogService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "logs"; }
    @Override public String title() { return "Журналы"; }
    @Override public String[] depends() { return new String[]{"database"}; }

    @Override
    public void enable() {
        active = true;
        Tasks.timer(40, 40, this::flush);
        Tasks.asyncTimer(20 * 60 * 5, 20 * 60 * 60 * 6, this::cleanup);
        Bukkit.getPluginManager().registerEvents(new LogListener(plugin, this), plugin);
    }

    @Override
    public void disable() {
        flush();
        active = false;
    }

    public void log(Player p, String category, String action, Location l, String details) {
        if (!active) return;
        Action act = new Action(System.currentTimeMillis(), p == null ? null : p.getUniqueId().toString(), p == null ? "#system" : p.getName(),
                category, action, l == null || l.getWorld() == null ? null : l.getWorld().getName(),
                l == null ? null : l.getBlockX(), l == null ? null : l.getBlockY(), l == null ? null : l.getBlockZ(), details);
        actions.add(act);
        emit(act);
    }

    /** Системная запись (без игрока). */
    public void sys(String category, String action, Location l, String details) {
        log((Player) null, category, action, l, details);
    }

    public void log(UUID uuid, String name, String category, String action, String details) {
        if (!active) return;
        Action act = new Action(System.currentTimeMillis(), uuid == null ? null : uuid.toString(), name, category, action, null, null, null, null, details);
        actions.add(act);
        emit(act);
    }

    /** Подозрительное действие: журнал + уведомление персонала. Автоматических наказаний нет. */
    public void suspicious(Player p, String type, String details) {
        log(p, "suspicious", type, p == null ? null : p.getLocation(), details);
        if (plugin.monitor() != null) plugin.monitor().suspicious();
        // Оповещения в чат выключены по умолчанию (alerts.chat в config.yml): подозрения видны в журнале и в меню «Проверки на читы».
        if (!plugin.configs().main().getBoolean("alerts.chat", false)) return;
        Tasks.sync(() -> {
            for (Player st : Bukkit.getOnlinePlayers()) {
                if (!plugin.roles().has(st, "usmp.mod.alerts")) continue;
                Profile pr = plugin.profiles().get(st);
                if (pr != null && !pr.setting("staff_alerts")) continue;
                Msg.send(st, "logs.suspicious", "&6[Подозрительно] &f{p}&7: {t} &8{d}", "p", p == null ? "?" : p.getName(), "t", type, "d", details == null ? "" : details);
            }
        });
    }

    public void block(Player p, String actor, String action, Location l, String oldData, String newData, byte[] extra) {
        if (!active || l == null || l.getWorld() == null) return;
        blocks.add(new BlockEntry(0, System.currentTimeMillis(), p == null ? null : p.getUniqueId().toString(), p == null ? actor : p.getName(),
                action, l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), oldData, newData, extra, false));
    }

    private void flush() {
        if (actions.isEmpty() && blocks.isEmpty()) return;
        List<Action> a = new ArrayList<>();
        List<BlockEntry> b = new ArrayList<>();
        Action x;
        while ((x = actions.poll()) != null && a.size() < 5000) a.add(x);
        BlockEntry y;
        while ((y = blocks.poll()) != null && b.size() < 10000) b.add(y);
        writeFiles(a);
        plugin.db().tx(c -> {
            if (!a.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO action_log(at, uuid, name, category, action, world, x, y, z, details) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                    for (Action r : a) {
                        Database.bind(ps, r.at(), r.uuid(), r.name(), r.category(), r.action(), r.world(), r.x(), r.y(), r.z(),
                                r.details() == null ? null : r.details().length() > 2000 ? r.details().substring(0, 2000) : r.details());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            if (!b.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO block_log(at, uuid, name, action, world, x, y, z, old_data, new_data, extra) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
                    for (BlockEntry r : b) {
                        Database.bind(ps, r.at(), r.uuid(), r.name(), r.action(), r.world(), r.x(), r.y(), r.z(), r.oldData(), r.newData(), r.extra());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            return null;
        }).exceptionally(t -> {
            plugin.getLogger().warning("Ошибка записи журнала (" + (a.size() + b.size()) + " записей возвращены в очередь): " + t.getMessage());
            actions.addAll(a);
            blocks.addAll(b);
            return null;
        });
    }

    /** Категории, которые дублируются в понятные текстовые файлы по дням (для файлового менеджера хостинга). */
    private static final java.util.Map<String, String> FILES = java.util.Map.of(
            "market", "market", "economy", "economy", "clanbank", "economy", "admin", "admin",
            "suspicious", "suspicious", "combat", "pvp", "death", "pvp", "teleport", "homes", "auth", "auth", "chat", "chat");
    private static final java.time.format.DateTimeFormatter TIME = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");

    private void writeFiles(List<Action> list) {
        if (list.isEmpty()) return;
        java.util.Map<String, StringBuilder> out = new java.util.HashMap<>();
        for (Action r : list) {
            String file = FILES.get(r.category());
            if (file == null) continue;
            var t = java.time.Instant.ofEpochMilli(r.at()).atZone(ru.unknownsmp.core.Text.zone());
            String key = file + "-" + t.toLocalDate();
            String where = r.world() == null ? "" : "  @ " + r.world() + " " + r.x() + " " + r.y() + " " + r.z();
            out.computeIfAbsent(key, k -> new StringBuilder())
                    .append("[").append(TIME.format(t)).append("] ").append(r.name() == null ? "-" : r.name())
                    .append(" | ").append(r.action()).append(" | ").append(r.details() == null ? "" : r.details()).append(where).append("\n");
        }
        if (out.isEmpty()) return;
        ru.unknownsmp.core.Tasks.async(() -> {
            java.io.File dir = new java.io.File(plugin.getDataFolder(), "logs");
            dir.mkdirs();
            for (var e : out.entrySet()) {
                try {
                    java.nio.file.Files.writeString(new java.io.File(dir, e.getKey() + ".log").toPath(), e.getValue().toString(),
                            java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                } catch (Exception ex) {
                    plugin.getLogger().warning("Не удалось записать журнал " + e.getKey() + ": " + ex.getMessage());
                }
            }
        });
    }

    private void cleanup() {
        var cfg = plugin.configs().get("logging.yml");
        long actionDays = cfg.getLong("retention.actions-days", 60);
        long blockDays = cfg.getLong("retention.blocks-days", 30);
        long now = System.currentTimeMillis();
        plugin.db().tx(c -> {
            int a = actionDays > 0 ? Database.update(c, "DELETE FROM action_log WHERE at < ? AND category NOT IN ('admin','economy','market','clanbank')", now - actionDays * 86_400_000L) : 0;
            int b = blockDays > 0 ? Database.update(c, "DELETE FROM block_log WHERE at < ?", now - blockDays * 86_400_000L) : 0;
            if (a + b > 0) plugin.getLogger().info("Очистка журналов: удалено " + a + " действий и " + b + " записей блоков старше срока хранения.");
            return null;
        });
    }

    public int queueSize() { return actions.size() + blocks.size(); }

    // ---------- Чтение ----------

    public CompletableFuture<List<Action>> query(String uuid, String category, long since, int limit) {
        return plugin.db().query(c -> {
            StringBuilder sql = new StringBuilder("SELECT at, uuid, name, category, action, world, x, y, z, details FROM action_log WHERE at >= ?");
            List<Object> args = new ArrayList<>();
            args.add(since);
            if (uuid != null) { sql.append(" AND uuid=?"); args.add(uuid); }
            if (category != null) { sql.append(" AND category=?"); args.add(category); }
            sql.append(" ORDER BY id DESC LIMIT ").append(Math.max(1, Math.min(500, limit)));
            List<Action> out = new ArrayList<>();
            try (PreparedStatement ps = Database.ps(c, sql.toString(), args.toArray()); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Action(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                            rs.getObject(7) == null ? null : rs.getInt(7), rs.getObject(8) == null ? null : rs.getInt(8),
                            rs.getObject(9) == null ? null : rs.getInt(9), rs.getString(10)));
                }
            }
            return out;
        });
    }

    public CompletableFuture<List<BlockEntry>> blockHistory(Location l, int limit) {
        String w = l.getWorld().getName();
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        return plugin.db().query(c -> {
            List<BlockEntry> out = new ArrayList<>();
            try (PreparedStatement ps = Database.ps(c, "SELECT * FROM block_log WHERE world=? AND x=? AND z=? AND y=? ORDER BY id DESC LIMIT " + limit, w, x, z, y);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(readBlock(rs));
            }
            return out;
        });
    }

    public static BlockEntry readBlock(ResultSet rs) throws java.sql.SQLException {
        return new BlockEntry(rs.getLong("id"), rs.getLong("at"), rs.getString("uuid"), rs.getString("name"), rs.getString("action"),
                rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"), rs.getString("old_data"), rs.getString("new_data"),
                rs.getBytes("extra"), rs.getInt("rolled_back") == 1);
    }
}
