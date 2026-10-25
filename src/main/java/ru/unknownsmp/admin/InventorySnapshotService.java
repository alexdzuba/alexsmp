package ru.unknownsmp.admin;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.db.Database;
import ru.unknownsmp.gui.ConfirmMenu;
import ru.unknownsmp.gui.Items;
import ru.unknownsmp.gui.Menu;
import ru.unknownsmp.gui.PagedMenu;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Снимки инвентаря: при входе, выходе, смене мира, смерти и раз в несколько минут (если инвентарь изменился).
 * Хранятся последние N снимков на игрока. Админ может посмотреть снимок и вернуть его игроку: /usmp inv <игрок>.
 */
public final class InventorySnapshotService implements Module, Listener {
    private record Snap(long id, long at, String reason, int items) {}

    private final UnknownSMP plugin;
    private final Map<UUID, Integer> lastHash = new ConcurrentHashMap<>();
    private boolean enabled = true;
    private int keep = 30, intervalMin = 10;

    public InventorySnapshotService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "invsnapshots"; }
    @Override public String title() { return "Снимки инвентаря"; }
    @Override public String[] depends() { return new String[]{"database"}; }

    @Override
    public void enable() {
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Tasks.timer(20L * 60, 20L * 60, this::periodic);
    }

    @Override
    public void disable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { take(p, "server_stop"); } catch (Throwable ignored) { /* БД уже закрывается */ }
        }
    }

    @Override
    public void reload() {
        YamlConfiguration c = plugin.configs().main();
        enabled = c.getBoolean("inventory-snapshots.enabled", true);
        keep = Math.max(3, c.getInt("inventory-snapshots.keep", 30));
        intervalMin = Math.max(1, c.getInt("inventory-snapshots.interval-minutes", 10));
    }

    // ------------------------------------------------------------------ Запись

    private int tick;

    private void periodic() {
        if (!enabled || ++tick % intervalMin != 0) return;
        for (Player p : Bukkit.getOnlinePlayers()) take(p, "auto");
    }

    private boolean skip(Player p) {
        // Наборы дуэлей не сохраняем: вещи SMP на это время лежат в хранилище дуэлей.
        return plugin.duels() != null && plugin.duels().isDuelWorld(p.getWorld());
    }

    /** Снимок текущего инвентаря (если он не пустой и изменился с прошлого раза). */
    public void take(Player p, String reason) {
        if (!enabled || p == null || skip(p)) return;
        ItemStack[] contents = p.getInventory().getContents();
        ItemStack[] copy = new ItemStack[contents.length];
        int count = 0;
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType().isAir()) copy[i] = ItemStack.empty();
            else { copy[i] = it.clone(); count++; }
        }
        if (count == 0) return;
        final byte[] data = ItemStack.serializeItemsAsBytes(copy);
        int hash = Arrays.hashCode(data);
        Integer prev = lastHash.put(p.getUniqueId(), hash);
        if (prev != null && prev == hash && !reason.equals("death") && !reason.equals("before_restore")) return;
        final String u = p.getUniqueId().toString(), name = p.getName();
        final int items = count, xp = p.getLevel();
        final long now = System.currentTimeMillis();
        plugin.db().tx(c -> {
            Database.update(c, "INSERT INTO inv_snapshots(uuid, name, at, reason, data, xp_level, items) VALUES (?,?,?,?,?,?,?)",
                    u, name, now, reason, data, xp, items);
            Database.update(c, "DELETE FROM inv_snapshots WHERE uuid=? AND id NOT IN (SELECT id FROM inv_snapshots WHERE uuid=? ORDER BY at DESC LIMIT " + keep + ")", u, u);
            return null;
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Tasks.later(60, () -> { if (p.isOnline()) take(p, "join"); });
    }

    // LOWEST: раньше, чем что-либо успеет очистить инвентарь.
    @EventHandler(priority = EventPriority.LOWEST)
    public void quit(PlayerQuitEvent e) {
        take(e.getPlayer(), "quit");
        lastHash.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void death(PlayerDeathEvent e) {
        take(e.getEntity(), "death");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent e) {
        if (e.getTo().getWorld() != null && !e.getTo().getWorld().equals(e.getFrom().getWorld())) take(e.getPlayer(), "world_leave:" + e.getFrom().getWorld().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void changed(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        Tasks.later(40, () -> { if (p.isOnline()) take(p, "world_enter:" + p.getWorld().getName()); });
    }

    // ------------------------------------------------------------------ Просмотр и возврат

    /** /usmp inv <игрок> */
    public void command(CommandSender s, String[] r) {
        if (!(s instanceof Player viewer)) { Msg.error(s, "admin.player-only", "Только для игрока в игре."); return; }
        if (r.length < 1) { Msg.error(s, "inv.usage", "Использование: /usmp inv <игрок> — снимки инвентаря"); return; }
        Player online = Bukkit.getPlayerExact(r[0]);
        @SuppressWarnings("deprecation")
        OfflinePlayer op = online != null ? online : Bukkit.getOfflinePlayer(r[0]);
        UUID u = op.getUniqueId();
        String name = op.getName() == null ? r[0] : op.getName();
        Tasks.then(plugin.db().query(c -> {
            List<Snap> out = new ArrayList<>();
            try (PreparedStatement ps = Database.ps(c, "SELECT id, at, reason, items FROM inv_snapshots WHERE uuid=? ORDER BY at DESC", u.toString());
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Snap(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4)));
            }
            return out;
        }), list -> openList(viewer, u, name, list), err -> Msg.error(viewer, "inv.error", "Не удалось прочитать снимки: " + err.getMessage()));
    }

    private static String reasonText(String r) {
        if (r == null) return "?";
        if (r.startsWith("world_leave:")) return "уход из мира " + r.substring(12);
        if (r.startsWith("world_enter:")) return "вход в мир " + r.substring(12);
        return switch (r) {
            case "join" -> "вход на сервер";
            case "quit" -> "выход с сервера";
            case "death" -> "смерть (до выпадения вещей)";
            case "auto" -> "автоснимок";
            case "server_stop" -> "остановка сервера";
            case "before_restore" -> "перед возвратом снимка";
            default -> r;
        };
    }

    private void openList(Player viewer, UUID u, String name, List<Snap> list) {
        new PagedMenu(viewer, "&8Снимки: " + name) {
            @Override
            protected List<Entry> entries() {
                List<Entry> out = new ArrayList<>();
                for (Snap sn : list) {
                    out.add(new Entry(Items.of(sn.reason().equals("death") ? Material.SKELETON_SKULL : Material.CHEST)
                            .name("&e" + Text.date(sn.at()))
                            .lore("&7Причина: &f" + reasonText(sn.reason()), "&7Предметов: &f" + sn.items(), "", "&eНажмите, чтобы открыть").build(),
                            c -> openSnap(viewer, u, name, sn, this)));
                }
                return out;
            }
        }.open();
    }

    private void openSnap(Player viewer, UUID u, String name, Snap sn, Menu back) {
        Tasks.then(plugin.db().query(c -> {
            try (PreparedStatement ps = Database.ps(c, "SELECT data, xp_level FROM inv_snapshots WHERE id=?", sn.id());
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Object[]{rs.getBytes(1), rs.getInt(2)};
            }
        }), row -> {
            if (row == null) { Msg.error(viewer, "inv.gone", "Снимок уже удалён."); return; }
            ItemStack[] items = ItemStack.deserializeItemsFromBytes((byte[]) row[0]);
            int xp = (Integer) row[1];
            new Menu(viewer, 6, "&8" + name + " — " + Text.date(sn.at())) {
                @Override
                protected void build() {
                    // 0-35 инвентарь, 36-39 броня, 40 вторая рука.
                    for (int i = 0; i < items.length && i < 41; i++) {
                        if (items[i] == null || items[i].isEmpty()) continue;
                        int slot = i < 9 ? 27 + i : i < 36 ? i - 9 : i; // хотбар — нижний ряд, как в игре
                        set(slot, Items.display(items[i], List.of()));
                    }
                    for (int i = 41; i < 54; i++) if (i != 45 && i != 49 && i != 53) set(i, Items.filler());
                    set(45, Items.of(Material.LIME_CONCRETE).name("&a&lВернуть игроку")
                            .lore("&7Текущий инвентарь " + name + " заменится этим снимком.", "&7Текущий сначала сохранится отдельным снимком.",
                                    "&7Уровень опыта в снимке: &f" + xp, "", "&eИгрок должен быть в сети").build(), c -> restore(viewer, u, name, items));
                    set(53, Items.of(Material.HOPPER).name("&b&lВыдать копию мне").lore("&7Все предметы снимка в ваш инвентарь/почту").build(), c -> {
                        for (ItemStack it : items) if (it != null && !it.isEmpty()) plugin.mail().giveOrMail(viewer, it.clone(), "snapshot");
                        plugin.logs().log(viewer, "admin", "inv_copy", viewer.getLocation(), name + " " + sn.id());
                        Msg.ok(viewer, "inv.copied", "Предметы снимка выданы вам.");
                    });
                    parent(back);
                    backButton(49);
                }
            }.open();
        }, err -> Msg.error(viewer, "inv.error", "Не удалось прочитать снимок: " + err.getMessage()));
    }

    private void restore(Player viewer, UUID u, String name, ItemStack[] items) {
        Player t = Bukkit.getPlayer(u);
        if (t == null) { Msg.error(viewer, "player.offline", "Игрок не в сети."); return; }
        ConfirmMenu.open(viewer, "&8Вернуть вещи " + name + "?", null, List.of("&7Инвентарь игрока будет заменён снимком."), () -> {
            if (!t.isOnline()) { Msg.error(viewer, "player.offline", "Игрок не в сети."); return; }
            take(t, "before_restore");
            PlayerInventory inv = t.getInventory();
            ItemStack[] target = new ItemStack[inv.getSize()];
            for (int i = 0; i < target.length && i < items.length; i++) target[i] = items[i] == null || items[i].isEmpty() ? null : items[i].clone();
            inv.setContents(target);
            t.saveData();
            Msg.ok(viewer, "inv.restored", "Вещи {p} возвращены из снимка.", "p", name);
            Msg.ok(t, "inv.restored-you", "Администратор вернул ваши вещи.");
            plugin.logs().log(viewer, "admin", "inv_restore", viewer.getLocation(), name);
        });
    }
}
