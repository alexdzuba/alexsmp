package ru.unknownsmp.admin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.db.Database;
import ru.unknownsmp.gui.ConfirmMenu;
import ru.unknownsmp.gui.Items;
import ru.unknownsmp.gui.PagedMenu;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ручные откаты изменений блоков: поиск по игроку, времени и координатам, предпросмотр последствий,
 * подтверждение, пакетное применение и запись результата в журнал. Автоматических откатов нет.
 */
public final class RollbackService implements Module {
    public record Query(String uuid, String name, long since, String world, Integer x, Integer z, Integer radius) {}

    private final UnknownSMP plugin;
    private final AtomicBoolean running = new AtomicBoolean();

    public RollbackService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "rollback"; }
    @Override public String title() { return "Откаты"; }
    @Override public String[] depends() { return new String[]{"logs"}; }

    @Override
    public void enable() {}

    public CompletableFuture<List<LogService.BlockEntry>> find(Query q) {
        return plugin.db().query(c -> {
            StringBuilder sql = new StringBuilder("SELECT * FROM block_log WHERE rolled_back=0 AND at >= ?");
            List<Object> args = new ArrayList<>();
            args.add(q.since());
            if (q.uuid() != null) { sql.append(" AND uuid=?"); args.add(q.uuid()); }
            else if (q.name() != null) { sql.append(" AND name=?"); args.add(q.name()); }
            if (q.world() != null && q.radius() != null) {
                sql.append(" AND world=? AND x BETWEEN ? AND ? AND z BETWEEN ? AND ?");
                args.add(q.world());
                args.add(q.x() - q.radius()); args.add(q.x() + q.radius());
                args.add(q.z() - q.radius()); args.add(q.z() + q.radius());
            }
            sql.append(" ORDER BY id DESC LIMIT 50000");
            List<LogService.BlockEntry> out = new ArrayList<>();
            try (PreparedStatement ps = Database.ps(c, sql.toString(), args.toArray()); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(LogService.readBlock(rs));
            }
            return out;
        });
    }

    /** Открыть предпросмотр. */
    public void preview(Player admin, Query q) {
        Msg.send(admin, "rollback.searching", "&7Поиск изменений...");
        Tasks.then(find(q), list -> {
            if (list.isEmpty()) { Msg.error(admin, "rollback.empty", "Изменений по запросу не найдено."); return; }
            Map<String, Integer> byAction = new LinkedHashMap<>();
            Map<String, Integer> byWorld = new LinkedHashMap<>();
            int containers = 0;
            for (var e : list) {
                byAction.merge(e.action(), 1, Integer::sum);
                byWorld.merge(e.world(), 1, Integer::sum);
                if (e.extra() != null) containers++;
            }
            final int cont = containers;
            new PagedMenu(admin, "&8Откат: предпросмотр (" + list.size() + ")") {
                @Override
                protected List<Entry> entries() {
                    List<Entry> out = new ArrayList<>();
                    int n = 0;
                    for (var e : list) {
                        if (n++ >= 450) break;
                        Material m = material(e.action().equals("PLACE") ? e.newData() : e.oldData());
                        out.add(new Entry(Items.of(m == null || !m.isItem() ? Material.PAPER : m).name("&f" + e.action() + " &7" + e.name())
                                .lore("&7" + e.world() + " " + e.x() + ", " + e.y() + ", " + e.z(), "&7" + Text.date(e.at()),
                                        "&7Будет: &f" + shortData(e.action().equals("PLACE") ? "minecraft:air" : e.oldData()),
                                        e.extra() != null ? "&6+ содержимое контейнера" : "").build(), null));
                    }
                    return out;
                }

                @Override
                protected void bottomBar() {
                    List<String> lore = new ArrayList<>();
                    lore.add("&7Всего изменений: &f" + list.size());
                    for (var e : byAction.entrySet()) lore.add("&7" + e.getKey() + ": &f" + e.getValue());
                    for (var e : byWorld.entrySet()) lore.add("&7Мир " + e.getKey() + ": &f" + e.getValue());
                    lore.add("&7Контейнеров с содержимым: &f" + cont);
                    lore.add("");
                    lore.add("&cИзменения будут возвращены к прежнему состоянию.");
                    lore.add("&cБлоки, изменённые позже другими игроками, тоже будут перезаписаны.");
                    set(47, Items.of(Material.BOOK).name("&eСводка").lore(lore).build());
                    set(51, Items.of(Material.LIME_CONCRETE).name("&a&lВыполнить откат").lore(lore).build(), c ->
                            ConfirmMenu.open(viewer, "&4Подтвердить откат?", null, List.of("&7Изменений: &f" + list.size(),
                                    "&7Действие будет записано в журнал."), () -> apply(viewer, q, list)));
                }
            }.open();
        }, err -> Msg.error(admin, "rollback.error", "Ошибка поиска: {e}", "e", err.getMessage()));
    }

    private static Material material(String data) {
        if (data == null) return null;
        String s = data.contains("[") ? data.substring(0, data.indexOf('[')) : data;
        return Material.matchMaterial(s);
    }

    private static String shortData(String d) {
        if (d == null) return "?";
        return d.replace("minecraft:", "").length() > 40 ? d.replace("minecraft:", "").substring(0, 40) + "…" : d.replace("minecraft:", "");
    }

    private void apply(Player admin, Query q, List<LogService.BlockEntry> list) {
        if (!running.compareAndSet(false, true)) { Msg.error(admin, "rollback.running", "Другой откат уже выполняется."); return; }
        plugin.logs().log(admin, "admin", "rollback_start", admin.getLocation(), describe(q) + " changes=" + list.size());
        // Список отсортирован от новых к старым — применяем в этом порядке, чтобы вернуть самое раннее состояние.
        Iterator<LogService.BlockEntry> it = list.iterator();
        List<Long> done = new ArrayList<>();
        int[] stats = {0, 0};
        int perTick = plugin.configs().get("performance.yml").getInt("rollback.blocks-per-tick", 300);
        final org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = Tasks.timer(1, 1, () -> {
            int n = 0;
            while (it.hasNext() && n++ < perTick) {
                var e = it.next();
                try {
                    World w = Bukkit.getWorld(e.world());
                    if (w == null) { stats[1]++; continue; }
                    Block b = w.getBlockAt(e.x(), e.y(), e.z());
                    String target = e.oldData() == null ? "minecraft:air" : e.oldData();
                    BlockData bd = Bukkit.createBlockData(target);
                    b.setBlockData(bd, false);
                    if (e.extra() != null && b.getState(false) instanceof Container ct) {
                        ItemStack[] items = ItemStack.deserializeItemsFromBytes(e.extra());
                        var inv = ct.getInventory();
                        for (int i = 0; i < items.length && i < inv.getSize(); i++) inv.setItem(i, items[i] == null || items[i].isEmpty() ? null : items[i]);
                    }
                    done.add(e.id());
                    stats[0]++;
                } catch (Exception ex) {
                    stats[1]++;
                }
            }
            if (!it.hasNext()) {
                task[0].cancel();
                running.set(false);
                markRolledBack(done);
                plugin.logs().log(admin, "admin", "rollback_done", null, describe(q) + " ok=" + stats[0] + " fail=" + stats[1]);
                if (admin.isOnline()) Msg.ok(admin, "rollback.done", "Откат завершён: восстановлено {ok}, ошибок {f}.", "ok", stats[0], "f", stats[1]);
            }
        });
    }

    private void markRolledBack(List<Long> ids) {
        if (ids.isEmpty()) return;
        plugin.db().tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE block_log SET rolled_back=1 WHERE id=?")) {
                int n = 0;
                for (long id : ids) {
                    ps.setLong(1, id);
                    ps.addBatch();
                    if (++n % 1000 == 0) ps.executeBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    private static String describe(Query q) {
        return "player=" + (q.name() != null ? q.name() : q.uuid()) + " since=" + Text.date(q.since())
                + (q.radius() != null ? " r=" + q.radius() + " @" + q.world() + " " + q.x() + "," + q.z() : "");
    }

    /** Разбор команды: /usmp rollback <игрок|*> <время> [радиус]. */
    public void command(Player admin, String[] a) {
        if (a.length < 2) {
            Msg.error(admin, "rollback.usage", "Использование: /usmp rollback <игрок|*> <время, напр. 2h> [радиус]");
            return;
        }
        long t = Text.parseDuration(a[1]);
        if (t <= 0) { Msg.error(admin, "mod.bad-time", "Неверный срок."); return; }
        Integer radius = null;
        if (a.length > 2) try { radius = Math.max(1, Math.min(500, Integer.parseInt(a[2]))); } catch (NumberFormatException ignored) {}
        if (a[0].equals("*") && radius == null) { Msg.error(admin, "rollback.need-radius", "Для отката всех игроков укажите радиус."); return; }
        Location l = admin.getLocation();
        final Integer r = radius;
        if (a[0].equals("*")) {
            preview(admin, new Query(null, null, System.currentTimeMillis() - t, l.getWorld().getName(), l.getBlockX(), l.getBlockZ(), r));
            return;
        }
        Tasks.then(plugin.profiles().findUuid(a[0]), u -> {
            Query q = u != null ? new Query(u.toString(), null, System.currentTimeMillis() - t, r == null ? null : l.getWorld().getName(), l.getBlockX(), l.getBlockZ(), r)
                    : new Query(null, a[0].startsWith("#") ? a[0] : "#" + a[0], System.currentTimeMillis() - t, r == null ? null : l.getWorld().getName(), l.getBlockX(), l.getBlockZ(), r);
            preview(admin, q);
        }, null);
    }
}
