package ru.unknownsmp.market;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.unknownsmp.core.ChatInput;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.db.Database;
import ru.unknownsmp.economy.EconomyService;
import ru.unknownsmp.gui.ConfirmMenu;
import ru.unknownsmp.gui.Items;
import ru.unknownsmp.gui.Menu;
import ru.unknownsmp.gui.PagedMenu;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ордера на покупку и продажу обычных предметов. Деньги покупателя не резервируются; предметы продавца
 * хранятся в ордере. Совпадающие ордера исполняются автоматически; если у покупателя не хватает денег или места
 * в почте, ордер остаётся активным, а исполнение приостанавливается до следующей проверки.
 */
public final class OrderService implements Module {
    public record Order(long id, UUID owner, String ownerName, String side, Material material, int total, int left, long price, long at, boolean suspended, String note) {}

    private final UnknownSMP plugin;
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();

    public OrderService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "orders"; }
    @Override public String title() { return "Ордера"; }
    @Override public String[] depends() { return new String[]{"economy", "mail"}; }

    @Override
    public void enable() {
        Tasks.timer(20 * 60, 20 * 60, this::matchAll);
        plugin.commands().add("orders").player().perm("usmp.player.market").module("orders").cat("Экономика")
                .usage("/orders").desc("Ордера на покупку и продажу").exec((s, a) -> open((Player) s, null));
    }

    private int maxOrders() { return plugin.configs().get("market.yml").getInt("orders.max-per-player", 10); }

    // ------------------------------------------------------------------ Создание

    public void createBuy(Player p, Material m, int qty, long price) {
        if (!validate(p, m, qty, price)) return;
        final UUID u = p.getUniqueId();
        final String n = p.getName();
        final int max = maxOrders();
        Tasks.then(plugin.db().tx(c -> {
            checkLimit(c, u, max);
            long id = Database.insert(c, "INSERT INTO orders(owner, owner_name, side, material, qty_total, qty_left, price, created_at, suspended) VALUES (?,?,?,?,?,?,?,?,0)",
                    u.toString(), n, "BUY", m.name(), qty, qty, price, System.currentTimeMillis());
            List<String> notices = new ArrayList<>();
            Map<UUID, Long> bals = new HashMap<>();
            matchTx(c, m.name(), notices, bals);
            return new Object[]{id, notices, bals};
        }), r -> {
            Msg.ok(p, "orders.buy-created", "Ордер на покупку #{id}: {q}x {m} по {pr}.", "id", r[0], "q", qty, "m", Items.materialName(m), "pr", Text.money(price));
            plugin.logs().log(p, "market", "order_buy", p.getLocation(), "#" + r[0] + " " + qty + "x " + m + " @" + price);
            after(r);
        }, err -> fail(p, err));
    }

    public void createSell(Player p, Material m, int qty, long price) {
        if (!validate(p, m, qty, price)) return;
        if (count(p, m) < qty) { Msg.error(p, "orders.not-enough-items", "В инвентаре нет {q}x {m}.", "q", qty, "m", Items.materialName(m)); return; }
        if (!busy.add(p.getUniqueId())) return;
        take(p, m, qty);
        p.saveData();
        final UUID u = p.getUniqueId();
        final String n = p.getName();
        final int max = maxOrders();
        Tasks.then(plugin.db().tx(c -> {
            checkLimit(c, u, max);
            long id = Database.insert(c, "INSERT INTO orders(owner, owner_name, side, material, qty_total, qty_left, price, created_at, suspended) VALUES (?,?,?,?,?,?,?,?,0)",
                    u.toString(), n, "SELL", m.name(), qty, qty, price, System.currentTimeMillis());
            List<String> notices = new ArrayList<>();
            Map<UUID, Long> bals = new HashMap<>();
            matchTx(c, m.name(), notices, bals);
            return new Object[]{id, notices, bals};
        }), r -> {
            busy.remove(u);
            Msg.ok(p, "orders.sell-created", "Ордер на продажу #{id}: {q}x {m} по {pr}.", "id", r[0], "q", qty, "m", Items.materialName(m), "pr", Text.money(price));
            plugin.logs().log(p, "market", "order_sell", p.getLocation(), "#" + r[0] + " " + qty + "x " + m + " @" + price);
            after(r);
        }, err -> {
            busy.remove(u);
            returnItems(p, m, qty);
            fail(p, err);
        });
    }

    private boolean validate(Player p, Material m, int qty, long price) {
        if (!plugin.economy().tradeAllowed(p)) return false;
        if (m == null || !m.isItem() || m.isAir()) { Msg.error(p, "orders.material", "Неверный предмет."); return false; }
        if (qty < 1 || qty > 64 * 54) { Msg.error(p, "orders.qty", "Количество: от 1 до {m}.", "m", 64 * 54); return false; }
        if (price < 1 || price > 1_000_000_000L) { Msg.error(p, "orders.price", "Цена за штуку: от 1 до 1 000 000 000."); return false; }
        try { Math.multiplyExact(price, (long) qty); } catch (ArithmeticException e) { return false; }
        return true;
    }

    private static void checkLimit(Connection c, UUID u, int max) throws Exception {
        Long cnt = Database.longVal(c, "SELECT COUNT(*) FROM orders WHERE owner=?", u.toString());
        if (cnt != null && cnt >= max) throw new Database.TxAbort("orders.limit", "Достигнут лимит ордеров (" + max + ").");
    }

    private void fail(Player p, Throwable err) {
        if (err instanceof Database.TxAbort ab) Msg.error(p, ab.key, ab.def);
        else Msg.error(p, "orders.error", "Операция не выполнена из-за ошибки. Деньги и предметы не потеряны.");
    }

    @SuppressWarnings("unchecked")
    private void after(Object[] r) {
        plugin.economy().apply((Map<UUID, Long>) r[2]);
        for (String n : (List<String>) r[1]) {
            String[] parts = n.split("\\|", 2);
            Player t = Bukkit.getPlayer(UUID.fromString(parts[0]));
            if (t != null) Msg.send(t, "orders.notice", "&a{m}", "m", parts[1]);
        }
    }

    // ------------------------------------------------------------------ Исполнение

    private static Order read(ResultSet rs) throws Exception {
        return new Order(rs.getLong("id"), UUID.fromString(rs.getString("owner")), rs.getString("owner_name"), rs.getString("side"),
                Material.matchMaterial(rs.getString("material")), rs.getInt("qty_total"), rs.getInt("qty_left"), rs.getLong("price"),
                rs.getLong("created_at"), rs.getInt("suspended") == 1, rs.getString("note"));
    }

    private Order best(Connection c, String mat, String side, Set<Long> skip) throws Exception {
        StringBuilder sql = new StringBuilder("SELECT * FROM orders WHERE material=? AND side=? AND qty_left > 0");
        if (!skip.isEmpty()) {
            sql.append(" AND id NOT IN (");
            int i = 0;
            for (long id : skip) { if (i++ > 0) sql.append(','); sql.append(id); }
            sql.append(')');
        }
        sql.append(side.equals("BUY") ? " ORDER BY price DESC, id ASC" : " ORDER BY price ASC, id ASC").append(" LIMIT 1");
        try (PreparedStatement ps = Database.ps(c, sql.toString(), mat, side); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? read(rs) : null;
        }
    }

    /** Сопоставление ордеров по материалу (внутри транзакции записи). */
    private void matchTx(Connection c, String mat, List<String> notices, Map<UUID, Long> bals) throws Exception {
        Set<Long> skipBuy = new HashSet<>();
        Set<Long> skipSell = new HashSet<>();
        int cap = plugin.mail().capacity();
        for (int iter = 0; iter < 100; iter++) {
            Order buy = best(c, mat, "BUY", skipBuy);
            Order sell = best(c, mat, "SELL", skipSell);
            if (buy == null || sell == null || buy.price() < sell.price()) break;
            if (buy.owner().equals(sell.owner())) { skipSell.add(sell.id()); continue; }
            long price = buy.id() < sell.id() ? buy.price() : sell.price();
            int qty = Math.min(buy.left(), sell.left());
            long bal = EconomyService.balance(c, buy.owner());
            long afford = bal / price;
            int maxStack = new ItemStack(buy.material()).getMaxStackSize();
            int mailFree = Math.max(0, cap - MailService.count(c, buy.owner()));
            long bySlots = (long) mailFree * maxStack;
            int q = (int) Math.min(qty, Math.min(afford, bySlots));
            if (q <= 0) {
                String note = afford <= 0 ? "funds" : "mail";
                Database.update(c, "UPDATE orders SET suspended=1, note=? WHERE id=?", note, buy.id());
                if (!buy.suspended()) notices.add(buy.owner() + "|Ордер #" + buy.id() + " приостановлен: " + (afford <= 0 ? "недостаточно денег" : "почта заполнена") + ".");
                skipBuy.add(buy.id());
                continue;
            }
            long total = price * q;
            bals.put(buy.owner(), EconomyService.debit(c, buy.owner(), total, "order_buy", "#" + buy.id() + "/#" + sell.id()));
            bals.put(sell.owner(), EconomyService.credit(c, sell.owner(), total, "order_sell", "#" + sell.id() + "/#" + buy.id()));
            Database.update(c, "UPDATE orders SET qty_left = qty_left - ?, suspended=0, note=NULL WHERE id=?", q, buy.id());
            Database.update(c, "UPDATE orders SET qty_left = qty_left - ? WHERE id=?", q, sell.id());
            MailService.insertSplit(c, buy.owner(), new ItemStack(buy.material()), q, "order:" + buy.id());
            Database.update(c, "DELETE FROM orders WHERE qty_left <= 0 AND id IN (?, ?)", buy.id(), sell.id());
            Database.update(c, "INSERT INTO action_log(at, uuid, name, category, action, details) VALUES (?,?,?,?,?,?)",
                    System.currentTimeMillis(), buy.owner().toString(), buy.ownerName(), "market", "order_fill",
                    q + "x " + mat + " @" + price + " buy#" + buy.id() + " sell#" + sell.id() + " seller=" + sell.ownerName());
            notices.add(buy.owner() + "|Ордер #" + buy.id() + ": куплено " + q + "x " + Items.materialName(buy.material()) + " за " + Text.money(total) + " (в почте).");
            notices.add(sell.owner() + "|Ордер #" + sell.id() + ": продано " + q + "x " + Items.materialName(sell.material()) + " за " + Text.money(total) + ".");
        }
    }

    private void matchAll() {
        Tasks.then(plugin.db().tx(c -> {
            List<String> mats = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT b.material FROM orders b JOIN orders s ON b.material = s.material AND b.side='BUY' AND s.side='SELL' AND b.price >= s.price");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) mats.add(rs.getString(1));
            }
            List<String> notices = new ArrayList<>();
            Map<UUID, Long> bals = new HashMap<>();
            for (String m : mats) matchTx(c, m, notices, bals);
            return new Object[]{0L, notices, bals};
        }), this::after, err -> plugin.getLogger().warning("Ошибка исполнения ордеров: " + err.getMessage()));
    }

    // ------------------------------------------------------------------ Прямое исполнение

    /** Продать свои предметы в чужой ордер на покупку (продавец онлайн). */
    public void fillBuy(Player p, Order o, int qty) {
        if (!plugin.economy().tradeAllowed(p)) return;
        if (o.owner().equals(p.getUniqueId())) { Msg.error(p, "orders.own", "Это ваш ордер."); return; }
        qty = Math.min(qty, o.left());
        if (qty <= 0 || count(p, o.material()) < qty) { Msg.error(p, "orders.not-enough-items", "В инвентаре нет {q}x {m}.", "q", qty, "m", Items.materialName(o.material())); return; }
        if (!busy.add(p.getUniqueId())) return;
        final int q = qty;
        take(p, o.material(), q);
        p.saveData();
        final UUID u = p.getUniqueId();
        final int cap = plugin.mail().capacity();
        Tasks.then(plugin.db().tx(c -> {
            Long left = Database.longVal(c, "SELECT qty_left FROM orders WHERE id=? AND side='BUY'", o.id());
            if (left == null || left < q) throw new Database.TxAbort("orders.changed", "Ордер изменился или уже исполнен.");
            int free = cap - MailService.count(c, o.owner());
            if (MailService.slotsNeeded(new ItemStack(o.material()), q) > free) throw new Database.TxAbort("orders.buyer-mail", "У покупателя заполнена почта — сделка невозможна.");
            long total = o.price() * q;
            Map<UUID, Long> bals = new HashMap<>();
            bals.put(o.owner(), EconomyService.debit(c, o.owner(), total, "order_buy", "#" + o.id() + " direct"));
            bals.put(u, EconomyService.credit(c, u, total, "order_sell", "#" + o.id() + " direct"));
            Database.update(c, "UPDATE orders SET qty_left = qty_left - ?, suspended=0, note=NULL WHERE id=?", q, o.id());
            Database.update(c, "DELETE FROM orders WHERE qty_left <= 0 AND id=?", o.id());
            MailService.insertSplit(c, o.owner(), new ItemStack(o.material()), q, "order:" + o.id());
            return new Object[]{total, bals};
        }), r -> {
            busy.remove(u);
            @SuppressWarnings("unchecked") Map<UUID, Long> bals = (Map<UUID, Long>) r[1];
            plugin.economy().apply(bals);
            Msg.ok(p, "orders.sold", "Продано {q}x {m} за {t}.", "q", q, "m", Items.materialName(o.material()), "t", Text.money((Long) r[0]));
            plugin.logs().log(p, "market", "order_fill_direct", p.getLocation(), "#" + o.id() + " " + q + "x " + o.material() + " to " + o.ownerName());
            Player owner = Bukkit.getPlayer(o.owner());
            if (owner != null) Msg.ok(owner, "orders.filled", "Ваш ордер #{id}: куплено {q}x {m} (в почте).", "id", o.id(), "q", q, "m", Items.materialName(o.material()));
        }, err -> {
            busy.remove(u);
            returnItems(p, o.material(), q);
            if (err instanceof Database.TxAbort ab && ab.key.equals("economy.insufficient")) Msg.error(p, "orders.buyer-funds", "У покупателя недостаточно средств.");
            else fail(p, err);
        });
    }

    /** Купить из чужого ордера на продажу. */
    public void fillSell(Player p, Order o, int qty) {
        if (!plugin.economy().tradeAllowed(p)) return;
        if (o.owner().equals(p.getUniqueId())) { Msg.error(p, "orders.own", "Это ваш ордер."); return; }
        qty = Math.min(qty, o.left());
        if (qty <= 0) return;
        ItemStack proto = new ItemStack(o.material());
        int need = MailService.slotsNeeded(proto, qty);
        if (MailService.freeSlots(p) < need) { Msg.error(p, "shop.no-space", "Недостаточно места в инвентаре (нужно {n} ячеек).", "n", need); return; }
        if (!busy.add(p.getUniqueId())) return;
        final int q = qty;
        final UUID u = p.getUniqueId();
        Tasks.then(plugin.db().tx(c -> {
            Long left = Database.longVal(c, "SELECT qty_left FROM orders WHERE id=? AND side='SELL'", o.id());
            if (left == null || left < q) throw new Database.TxAbort("orders.changed", "Ордер изменился или уже исполнен.");
            long total = o.price() * q;
            Map<UUID, Long> bals = new HashMap<>();
            bals.put(u, EconomyService.debit(c, u, total, "order_buy", "#" + o.id() + " direct"));
            bals.put(o.owner(), EconomyService.credit(c, o.owner(), total, "order_sell", "#" + o.id() + " direct"));
            Database.update(c, "UPDATE orders SET qty_left = qty_left - ? WHERE id=?", q, o.id());
            Database.update(c, "DELETE FROM orders WHERE qty_left <= 0 AND id=?", o.id());
            return new Object[]{total, bals};
        }), r -> {
            busy.remove(u);
            @SuppressWarnings("unchecked") Map<UUID, Long> bals = (Map<UUID, Long>) r[1];
            plugin.economy().apply(bals);
            returnItems(p, o.material(), q);
            Msg.ok(p, "orders.bought", "Куплено {q}x {m} за {t}.", "q", q, "m", Items.materialName(o.material()), "t", Text.money((Long) r[0]));
            plugin.logs().log(p, "market", "order_buy_direct", p.getLocation(), "#" + o.id() + " " + q + "x " + o.material() + " from " + o.ownerName());
            Player owner = Bukkit.getPlayer(o.owner());
            if (owner != null) Msg.ok(owner, "orders.filled-sell", "Ваш ордер #{id}: продано {q}x {m}.", "id", o.id(), "q", q, "m", Items.materialName(o.material()));
        }, err -> {
            busy.remove(u);
            fail(p, err);
        });
    }

    public void cancel(Player p, Order o, Runnable after) {
        if (!busy.add(p.getUniqueId())) return;
        final UUID owner = o.owner();
        final boolean admin = !owner.equals(p.getUniqueId());
        Tasks.then(plugin.db().tx(c -> {
            Long left = Database.longVal(c, "SELECT qty_left FROM orders WHERE id=? AND owner=?", o.id(), owner.toString());
            if (left == null) throw new Database.TxAbort("orders.gone", "Ордер уже исполнен или отменён.");
            Database.update(c, "DELETE FROM orders WHERE id=?", o.id());
            if (o.side().equals("SELL") && left > 0) MailService.insertSplit(c, owner, new ItemStack(o.material()), left.intValue(), "order_cancel:" + o.id());
            return left;
        }), left -> {
            busy.remove(p.getUniqueId());
            Msg.ok(p, "orders.cancelled", o.side().equals("SELL") ? "Ордер отменён, {n} шт. отправлено в почту." : "Ордер отменён.", "n", left);
            plugin.logs().log(p, admin ? "admin" : "market", "order_cancel", p.getLocation(), "#" + o.id());
            if (after != null) after.run();
        }, err -> {
            busy.remove(p.getUniqueId());
            fail(p, err);
        });
    }

    // ------------------------------------------------------------------ Инвентарь

    private static int count(Player p, Material m) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) if (ShopService.isPlain(it) && it.getType() == m) n += it.getAmount();
        return n;
    }

    private static void take(Player p, Material m, int qty) {
        ItemStack[] cont = p.getInventory().getStorageContents();
        for (int i = 0; i < cont.length && qty > 0; i++) {
            ItemStack it = cont[i];
            if (!ShopService.isPlain(it) || it.getType() != m) continue;
            int t = Math.min(qty, it.getAmount());
            it.setAmount(it.getAmount() - t);
            if (it.getAmount() <= 0) cont[i] = null;
            qty -= t;
        }
        p.getInventory().setStorageContents(cont);
    }

    private void returnItems(Player p, Material m, int qty) {
        ItemStack proto = new ItemStack(m);
        while (qty > 0) {
            int a = Math.min(proto.getMaxStackSize(), qty);
            ItemStack it = proto.clone();
            it.setAmount(a);
            plugin.mail().giveOrMail(p, it, "return");
            qty -= a;
        }
    }

    // ------------------------------------------------------------------ Меню

    public CompletableFuture<List<Order>> list(String side, UUID owner) {
        return plugin.db().query(c -> {
            List<Order> out = new ArrayList<>();
            StringBuilder sql = new StringBuilder("SELECT * FROM orders WHERE qty_left > 0");
            List<Object> args = new ArrayList<>();
            if (side != null) { sql.append(" AND side=?"); args.add(side); }
            if (owner != null) { sql.append(" AND owner=?"); args.add(owner.toString()); }
            sql.append(" ORDER BY material, price DESC LIMIT 2000");
            try (PreparedStatement ps = Database.ps(c, sql.toString(), args.toArray()); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Order o = read(rs);
                    if (o.material() != null) out.add(o);
                }
            }
            return out;
        });
    }

    public void open(Player p, Menu parent) {
        open(p, parent, "BUY", false);
    }

    private void open(Player p, Menu parent, String side, boolean mine) {
        Tasks.then(list(mine ? null : side, mine ? p.getUniqueId() : null), list -> new OrdersMenu(p, parent, side, mine, list).open(), null);
    }

    private final class OrdersMenu extends PagedMenu {
        private final String side;
        private final boolean mine;
        private final List<Order> list;

        OrdersMenu(Player p, Menu parent, String side, boolean mine, List<Order> list) {
            super(p, mine ? "&8Мои ордера" : side.equals("BUY") ? "&8Ордера на покупку" : "&8Ордера на продажу");
            this.side = side;
            this.mine = mine;
            this.list = list;
            parent(parent);
        }

        @Override
        protected List<Entry> entries() {
            List<Entry> out = new ArrayList<>();
            for (Order o : list) {
                boolean own = o.owner().equals(viewer.getUniqueId());
                boolean buy = o.side().equals("BUY");
                List<String> lore = new ArrayList<>(List.of(buy ? "&aПОКУПКА" : "&cПРОДАЖА", "&7Цена: &6" + Text.money(o.price()) + " &7за шт.",
                        "&7Осталось: &f" + o.left() + " &7из " + o.total(), "&7Автор: &f" + o.ownerName()));
                if (o.suspended()) lore.add("&eИсполнение приостановлено (" + ("mail".equals(o.note()) ? "почта заполнена" : "нет денег") + ")");
                lore.add("");
                lore.add(own ? "&cНажмите, чтобы отменить" : buy ? "&eНажмите, чтобы продать в этот ордер" : "&eНажмите, чтобы купить");
                out.add(new Entry(Items.of(o.material()).name("&f" + Items.materialName(o.material()) + " &8#" + o.id()).lore(lore).build(), c -> {
                    if (own || (plugin.roles().has(viewer, "usmp.mod.market") && c.shift() && c.right())) {
                        ConfirmMenu.open(viewer, "&8Отменить ордер #" + o.id() + "?", null,
                                List.of(buy ? "&7Ордер на покупку будет удалён" : "&7Остаток предметов вернётся в почту"),
                                () -> cancel(viewer, o, () -> OrderService.this.open(viewer, parent, side, mine)));
                        return;
                    }
                    ChatInput.ask(viewer, Msg.t("orders.ask-qty", "&fСколько штук? (до {n})", "n", o.left()), s -> {
                        int q;
                        try { q = Integer.parseInt(s.trim()); } catch (NumberFormatException e) { Msg.error(viewer, "input.number", "Нужно число."); return; }
                        if (buy) fillBuy(viewer, o, q);
                        else fillSell(viewer, o, q);
                    });
                }));
            }
            return out;
        }

        @Override
        protected void bottomBar() {
            set(46, Items.of(Material.LIME_DYE).name("&aОрдера на покупку").glow(!mine && side.equals("BUY")).build(), c -> OrderService.this.open(viewer, parent, "BUY", false));
            set(47, Items.of(Material.RED_DYE).name("&cОрдера на продажу").glow(!mine && side.equals("SELL")).build(), c -> OrderService.this.open(viewer, parent, "SELL", false));
            set(48, Items.of(Material.PLAYER_HEAD).name("&eМои ордера").glow(mine).build(), c -> OrderService.this.open(viewer, parent, side, true));
            set(50, Items.of(Material.EMERALD).name("&aСоздать ордер на покупку").lore("&7Возьмите в руку образец предмета", "&7Деньги не резервируются").build(), c -> askCreate(viewer, true));
            set(51, Items.of(Material.CHEST).name("&cСоздать ордер на продажу").lore("&7Возьмите предмет в руку", "&7Предметы будут изъяты в ордер").build(), c -> askCreate(viewer, false));
        }
    }

    private void askCreate(Player p, boolean buy) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) { Msg.error(p, "orders.hand", "Возьмите в руку образец предмета."); return; }
        if (!ShopService.isPlain(hand)) { Msg.error(p, "orders.plain", "Ордера доступны только для обычных предметов (без чар, названий и т.п.)."); return; }
        Material m = hand.getType();
        ChatInput.ask(p, Msg.t("orders.ask-create-qty", "&fКоличество {m}:", "m", Items.materialName(m)), qs -> {
            int q;
            try { q = Integer.parseInt(qs.trim()); } catch (NumberFormatException e) { Msg.error(p, "input.number", "Нужно число."); return; }
            ChatInput.ask(p, Msg.t("orders.ask-create-price", "&fЦена за 1 шт.:"), ps -> {
                long price = Text.parseAmount(ps);
                if (buy) createBuy(p, m, q, price);
                else createSell(p, m, q, price);
            });
        });
    }
}
