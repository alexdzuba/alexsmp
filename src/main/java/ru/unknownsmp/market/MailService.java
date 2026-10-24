package ru.unknownsmp.market;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.db.Database;
import ru.unknownsmp.gui.Items;
import ru.unknownsmp.gui.Menu;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Почта: 10 ячеек, одна доставка = одна ячейка (до стака). Хранится в БД, забирается атомарно:
 * запись удаляется в транзакции, и только затем предмет выдаётся; при нехватке места — возвращается в почту.
 */
public final class MailService implements Module, Listener {
    public record Mail(long id, ItemStack item, String source, long at) {}

    private final UnknownSMP plugin;
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private int capacity;

    public MailService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "mail"; }
    @Override public String title() { return "Почта"; }
    @Override public String[] depends() { return new String[]{"database", "profiles"}; }

    @Override
    public void enable() {
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.auth().onLogin(p -> Tasks.later(60, () -> {
            if (!p.isOnline()) return;
            Tasks.then(count(p.getUniqueId()), n -> {
                if (n != null && n > 0) Msg.send(p, "mail.waiting", "&eВ почте {n} доставок. Забрать: &f/mail", "n", n);
            }, null);
        }));
        plugin.commands().add("mail").player().perm("usmp.player.mail").module("mail").cat("Экономика")
                .usage("/mail").desc("Почта (доставки с рынка)").exec((s, a) -> open((Player) s, null));
    }

    @Override
    public void reload() {
        capacity = plugin.configs().get("market.yml").getInt("mail.capacity", 10);
    }

    public int capacity() { return capacity; }

    // ---------- Внутри транзакций ----------

    public static int count(Connection c, UUID owner) throws Exception {
        Long n = Database.longVal(c, "SELECT COUNT(*) FROM mail WHERE owner=?", owner.toString());
        return n == null ? 0 : n.intValue();
    }

    public static void insert(Connection c, UUID owner, ItemStack item, String source) throws Exception {
        if (item == null || item.getType().isAir()) return;
        Database.update(c, "INSERT INTO mail(owner, item, source, created_at) VALUES (?,?,?,?)",
                owner.toString(), item.serializeAsBytes(), source, System.currentTimeMillis());
    }

    /** Разбить количество предметов на стаки и положить в почту. */
    public static int insertSplit(Connection c, UUID owner, ItemStack proto, int amount, String source) throws Exception {
        int max = proto.getMaxStackSize();
        int n = 0;
        while (amount > 0) {
            int a = Math.min(max, amount);
            ItemStack it = proto.clone();
            it.setAmount(a);
            insert(c, owner, it, source);
            amount -= a;
            n++;
        }
        return n;
    }

    public static int slotsNeeded(ItemStack proto, int amount) {
        int max = proto.getMaxStackSize();
        return (amount + max - 1) / max;
    }

    // ---------- API ----------

    public CompletableFuture<Integer> count(UUID owner) {
        return plugin.db().query(c -> count(c, owner));
    }

    public CompletableFuture<List<Mail>> list(UUID owner) {
        return plugin.db().query(c -> {
            List<Mail> out = new ArrayList<>();
            try (PreparedStatement ps = Database.ps(c, "SELECT id, item, source, created_at FROM mail WHERE owner=? ORDER BY id", owner.toString());
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        out.add(new Mail(rs.getLong(1), ItemStack.deserializeBytes(rs.getBytes(2)), rs.getString(3), rs.getLong(4)));
                    } catch (Exception e) {
                        plugin.getLogger().warning("Повреждённая запись почты #" + rs.getLong(1) + ": " + e.getMessage());
                    }
                }
            }
            return out;
        });
    }

    /**
     * Выдать предмет онлайн-игроку: если есть место — сразу в инвентарь, иначе в почту (без лимита — это
     * возврат его же имущества, его нельзя потерять).
     */
    public void giveOrMail(Player p, ItemStack it, String source) {
        if (it == null || it.getType().isAir()) return;
        if (p != null && p.isOnline() && hasSpace(p, it)) {
            p.getInventory().addItem(it);
            p.saveData();
            return;
        }
        UUID u = p.getUniqueId();
        plugin.db().tx(c -> { insert(c, u, it, source); return null; }).whenComplete((v, t) -> {
            if (t != null) {
                plugin.getLogger().severe("НЕ УДАЛОСЬ положить предмет в почту " + u + ": " + Items.itemName(it) + " — " + t.getMessage());
                Tasks.sync(() -> { if (p.isOnline()) p.getWorld().dropItemNaturally(p.getLocation(), it); });
            } else {
                Tasks.sync(() -> { if (p.isOnline()) Msg.send(p, "mail.delivered", "&eПредмет отправлен в почту (инвентарь заполнен): &f/mail"); });
            }
        });
    }

    public static boolean hasSpace(Player p, ItemStack it) {
        int need = it.getAmount();
        for (ItemStack s : p.getInventory().getStorageContents()) {
            if (s == null || s.getType().isAir()) return true;
            if (s.isSimilar(it)) need -= s.getMaxStackSize() - s.getAmount();
            if (need <= 0) return true;
        }
        return false;
    }

    public static int freeSlots(Player p) {
        int n = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) if (s == null || s.getType().isAir()) n++;
        return n;
    }

    public void claim(Player p, long id, Runnable after) {
        if (!busy.add(p.getUniqueId())) return;
        final String u = p.getUniqueId().toString();
        Tasks.then(plugin.db().tx(c -> {
            byte[] data;
            try (PreparedStatement ps = Database.ps(c, "SELECT item FROM mail WHERE id=? AND owner=?", id, u); ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                data = rs.getBytes(1);
            }
            ItemStack it = ItemStack.deserializeBytes(data);
            if (Database.update(c, "DELETE FROM mail WHERE id=? AND owner=?", id, u) != 1) return null;
            return it;
        }), it -> {
            busy.remove(p.getUniqueId());
            if (it == null) { Msg.error(p, "mail.gone", "Эта доставка уже получена."); if (after != null) after.run(); return; }
            giveBack(p, it);
            plugin.logs().log(p, "market", "mail_claim", p.getLocation(), it.getAmount() + "x " + Items.itemName(it));
            if (after != null) after.run();
        }, err -> {
            busy.remove(p.getUniqueId());
            Msg.error(p, "mail.error", "Ошибка получения. Предмет остался в почте.");
        });
    }

    private void giveBack(Player p, ItemStack it) {
        var left = p.getInventory().addItem(it);
        p.saveData();
        if (!left.isEmpty()) {
            for (ItemStack l : left.values()) {
                plugin.db().tx(c -> { insert(c, p.getUniqueId(), l, "return"); return null; });
            }
            Msg.send(p, "mail.partial", "&eНе всё поместилось — остаток возвращён в почту.");
        }
    }

    public void claimAll(Player p, Runnable after) {
        Tasks.then(list(p.getUniqueId()), list -> {
            int free = freeSlots(p);
            List<Long> ids = new ArrayList<>();
            for (Mail m : list) { if (free-- <= 0) break; ids.add(m.id()); }
            if (ids.isEmpty()) {
                Msg.error(p, list.isEmpty() ? "mail.empty" : "mail.no-space", list.isEmpty() ? "Почта пуста." : "Освободите место в инвентаре.");
                return;
            }
            if (!busy.add(p.getUniqueId())) return;
            final String u = p.getUniqueId().toString();
            Tasks.then(plugin.db().tx(c -> {
                List<ItemStack> out = new ArrayList<>();
                for (long id : ids) {
                    byte[] data = null;
                    try (PreparedStatement ps = Database.ps(c, "SELECT item FROM mail WHERE id=? AND owner=?", id, u); ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) data = rs.getBytes(1);
                    }
                    if (data == null) continue;
                    if (Database.update(c, "DELETE FROM mail WHERE id=? AND owner=?", id, u) == 1) out.add(ItemStack.deserializeBytes(data));
                }
                return out;
            }), items -> {
                busy.remove(p.getUniqueId());
                for (ItemStack it : items) giveBack(p, it);
                Msg.ok(p, "mail.claimed", "Получено доставок: {n}", "n", items.size());
                plugin.logs().log(p, "market", "mail_claim_all", p.getLocation(), "n=" + items.size());
                if (after != null) after.run();
            }, err -> {
                busy.remove(p.getUniqueId());
                Msg.error(p, "mail.error", "Ошибка получения. Предметы остались в почте.");
            });
        }, null);
    }

    public void open(Player p, Menu parent) {
        Tasks.then(list(p.getUniqueId()), list -> new MailMenu(p, list, parent).open(), null);
    }

    private final class MailMenu extends Menu {
        private final List<Mail> list;

        MailMenu(Player p, List<Mail> list, Menu parent) {
            super(p, 3, "&8Почта (" + list.size() + "/" + capacity + ")");
            this.list = list;
            parent(parent);
        }

        @Override
        protected void build() {
            fill(Items.filler());
            int[] slots = {2, 3, 4, 5, 6, 11, 12, 13, 14, 15};
            for (int i = 0; i < slots.length; i++) {
                if (i < list.size()) {
                    Mail m = list.get(i);
                    set(slots[i], Items.display(m.item(), List.of("", "&7Откуда: &f" + source(m.source()), "&7Дата: &f" + Text.date(m.at()), "&eНажмите, чтобы забрать")),
                            c -> claim(viewer, m.id(), () -> MailService.this.open(viewer, parent)));
                } else {
                    set(slots[i], Items.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE).name("&7Свободная ячейка").build());
                }
            }
            for (int i = slots.length; i < list.size() && i < slots.length + 3; i++) {
                // Доставки сверх лимита (возвраты) показываются в нижнем ряду.
                Mail m = list.get(i);
                set(18 + i - slots.length + 1, Items.display(m.item(), List.of("", "&6Сверх лимита (возврат)", "&eНажмите, чтобы забрать")),
                        c -> claim(viewer, m.id(), () -> MailService.this.open(viewer, parent)));
            }
            set(22, Items.of(Material.HOPPER).name("&aЗабрать всё").lore("&7Сколько поместится в инвентарь").build(),
                    c -> claimAll(viewer, () -> MailService.this.open(viewer, parent)));
            backButton(26);
        }

        private String source(String s) {
            if (s == null) return "—";
            return switch (s.split(":")[0]) {
                case "auction" -> "аукцион";
                case "auction_cancel" -> "снятый лот";
                case "order" -> "ордер";
                case "order_cancel" -> "отменённый ордер";
                case "reward" -> "награда";
                case "event" -> "событие";
                case "return" -> "возврат";
                case "admin" -> "администрация";
                default -> s;
            };
        }
    }
}
