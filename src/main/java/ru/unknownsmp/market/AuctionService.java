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
import ru.unknownsmp.gui.MenuListener;
import ru.unknownsmp.gui.PagedMenu;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Аукцион игроков: выставляется весь стак, цена фиксирована продавцом, без срока и комиссии.
 * Покупка — одна транзакция: списание у покупателя, зачисление продавцу, удаление лота и доставка в почту
 * покупателя (затем сразу выдаётся в инвентарь, если есть место).
 */
public final class AuctionService implements Module {
    public record Lot(long id, UUID seller, String sellerName, ItemStack item, long price, long at) {}

    private final UnknownSMP plugin;
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();

    public AuctionService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "auction"; }
    @Override public String title() { return "Аукцион"; }
    @Override public String[] depends() { return new String[]{"economy", "mail"}; }

    @Override
    public void enable() {
        plugin.commands().add("ah").player().perm("usmp.player.market").module("auction").cat("Экономика")
                .usage("/ah [<цена> | sell <цена> | my]").desc("Аукцион игроков").exec((s, a) -> {
                    Player p = (Player) s;
                    if (a.length >= 1 && (a[0].equalsIgnoreCase("sell") || a[0].equalsIgnoreCase("продать"))) {
                        if (a.length < 2) { Msg.error(p, "ah.usage", "Использование: /ah sell <цена> (весь стак в руке)"); return; }
                        long price = Text.parseAmount(a[1]);
                        list(p, price);
                        return;
                    }
                    if (a.length >= 1 && a[0].equalsIgnoreCase("my")) { new MyLots(p, null).open(); return; }
                    // /ah <цена> — выставить предмет из руки по этой цене.
                    if (a.length == 1) {
                        long price = Text.parseAmount(a[0]);
                        if (price > 0) { list(p, price); return; }
                    }
                    new Browse(p, null, null).open();
                }).tab((s, a) -> a.length == 1 ? List.of("sell", "my") : List.of());
    }

    // ------------------------------------------------------------------ Выставление

    public void list(Player p, long price) {
        long max = plugin.configs().get("market.yml").getLong("auction.max-price", 1_000_000_000_000L);
        if (price < 1 || price > max) { Msg.error(p, "ah.price", "Цена должна быть от 1 до {m}.", "m", Text.money(max)); return; }
        if (!plugin.economy().tradeAllowed(p)) return;
        if (plugin.duels() != null && plugin.duels().isDuelWorld(p.getWorld())) { Msg.error(p, "ah.world", "Недоступно в дуэлях."); return; }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir() || MenuListener.isMenuItem(hand)) { Msg.error(p, "ah.empty-hand", "Возьмите предмет в руку."); return; }
        if (!busy.add(p.getUniqueId())) return;
        int maxLots = plugin.configs().get("market.yml").getInt("auction.max-lots-per-player", 10);
        ItemStack item = hand.clone();
        p.getInventory().setItemInMainHand(null);
        p.saveData();
        final UUID u = p.getUniqueId();
        final String name = p.getName();
        Tasks.then(plugin.db().tx(c -> {
            Long cnt = Database.longVal(c, "SELECT COUNT(*) FROM auction_lots WHERE seller=?", u.toString());
            if (cnt != null && cnt >= maxLots) throw new Database.TxAbort("ah.limit", "Достигнут лимит лотов (" + maxLots + ").");
            return Database.insert(c, "INSERT INTO auction_lots(seller, seller_name, item, material, amount, price, created_at) VALUES (?,?,?,?,?,?,?)",
                    u.toString(), name, item.serializeAsBytes(), item.getType().name(), item.getAmount(), price, System.currentTimeMillis());
        }), id -> {
            busy.remove(u);
            Msg.ok(p, "ah.listed", "Лот #{id} выставлен: {n}x {i} за {pr}.", "id", id, "n", item.getAmount(), "i", Items.itemName(item), "pr", Text.money(price));
            plugin.logs().log(p, "market", "ah_list", p.getLocation(), "#" + id + " " + item.getAmount() + "x " + Items.itemName(item) + uid(item) + " = " + price);
        }, err -> {
            busy.remove(u);
            plugin.mail().giveOrMail(p, item, "return");
            if (err instanceof Database.TxAbort ab) Msg.error(p, ab.key, ab.def);
            else Msg.error(p, "ah.error", "Лот не выставлен — предмет возвращён.");
        });
    }

    private String uid(ItemStack it) {
        String u = plugin.items() == null ? null : plugin.items().uidOf(it);
        return u == null ? "" : " [uid " + u + "]";
    }

    // ------------------------------------------------------------------ Покупка и отмена

    public void buy(Player p, Lot lot, Runnable after) {
        if (lot.seller().equals(p.getUniqueId())) { Msg.error(p, "ah.own", "Это ваш лот."); return; }
        if (!plugin.economy().tradeAllowed(p)) return;
        if (!busy.add(p.getUniqueId())) return;
        final boolean direct = MailService.hasSpace(p, lot.item());
        final UUID u = p.getUniqueId();
        final int cap = plugin.mail().capacity();
        Tasks.then(plugin.db().tx(c -> {
            Long price = Database.longVal(c, "SELECT price FROM auction_lots WHERE id=?", lot.id());
            if (price == null) throw new Database.TxAbort("ah.sold", "Лот уже продан или снят.");
            if (price != lot.price()) throw new Database.TxAbort("ah.changed", "Лот изменился, обновите список.");
            if (!direct && MailService.count(c, u) >= cap) throw new Database.TxAbort("ah.mail-full", "Инвентарь и почта заполнены — сделка не может быть доставлена.");
            if (Database.update(c, "DELETE FROM auction_lots WHERE id=?", lot.id()) != 1) throw new Database.TxAbort("ah.sold", "Лот уже продан.");
            long buyerBal = EconomyService.debit(c, u, price, "ah_buy", "#" + lot.id());
            long sellerBal = EconomyService.credit(c, lot.seller(), price, "ah_sell", "#" + lot.id());
            long mailId = Database.insert(c, "INSERT INTO mail(owner, item, source, created_at) VALUES (?,?,?,?)",
                    u.toString(), lot.item().serializeAsBytes(), "auction:" + lot.id(), System.currentTimeMillis());
            return new long[]{buyerBal, sellerBal, mailId};
        }), r -> {
            busy.remove(u);
            plugin.economy().apply(Map.of(u, r[0], lot.seller(), r[1]));
            Msg.ok(p, "ah.bought", "Куплено: {i} за {pr}.", "i", Items.itemName(lot.item()), "pr", Text.money(lot.price()));
            plugin.logs().log(p, "market", "ah_buy", p.getLocation(), "#" + lot.id() + " from " + lot.sellerName() + " " + lot.item().getAmount()
                    + "x " + Items.itemName(lot.item()) + uid(lot.item()) + " = " + lot.price());
            Player seller = Bukkit.getPlayer(lot.seller());
            if (seller != null) Msg.ok(seller, "ah.sold-notify", "Ваш лот {i} купил {b} за {pr}.", "i", Items.itemName(lot.item()), "b", p.getName(), "pr", Text.money(lot.price()));
            plugin.quests().progress(seller, "EARN", "auction", lot.price());
            if (seller != null) plugin.profiles().addStat(seller, "ah_sold", 1);
            if (direct) plugin.mail().claim(p, r[2], after);
            else {
                Msg.send(p, "ah.to-mail", "&eПредмет доставлен в почту: &f/mail");
                if (after != null) after.run();
            }
        }, err -> {
            busy.remove(u);
            if (err instanceof Database.TxAbort ab) Msg.error(p, ab.key, ab.def);
            else Msg.error(p, "ah.error", "Покупка не выполнена. Деньги не списаны.");
            if (after != null) after.run();
        });
    }

    public void cancel(Player p, Lot lot, boolean admin, Runnable after) {
        if (!busy.add(p.getUniqueId())) return;
        final UUID owner = lot.seller();
        Tasks.then(plugin.db().tx(c -> {
            String sql = admin ? "DELETE FROM auction_lots WHERE id=?" : "DELETE FROM auction_lots WHERE id=? AND seller=?";
            int n = admin ? Database.update(c, sql, lot.id()) : Database.update(c, sql, lot.id(), owner.toString());
            if (n != 1) throw new Database.TxAbort("ah.sold", "Лот уже продан или снят.");
            return Database.insert(c, "INSERT INTO mail(owner, item, source, created_at) VALUES (?,?,?,?)",
                    owner.toString(), lot.item().serializeAsBytes(), "auction_cancel:" + lot.id(), System.currentTimeMillis());
        }), mailId -> {
            busy.remove(p.getUniqueId());
            plugin.logs().log(p, admin ? "admin" : "market", "ah_cancel", p.getLocation(), "#" + lot.id() + " " + Items.itemName(lot.item()));
            if (!admin && MailService.hasSpace(p, lot.item())) plugin.mail().claim(p, mailId, after);
            else {
                Msg.send(p, "ah.cancel-mail", admin ? "&7Лот снят, предмет отправлен владельцу в почту." : "&eЛот снят, предмет в почте: &f/mail");
                if (after != null) after.run();
            }
        }, err -> {
            busy.remove(p.getUniqueId());
            if (err instanceof Database.TxAbort ab) Msg.error(p, ab.key, ab.def);
            else Msg.error(p, "ah.error", "Ошибка снятия лота.");
        });
    }

    // ------------------------------------------------------------------ Данные

    public CompletableFuture<List<Lot>> lots(String search, UUID seller) {
        return plugin.db().query(c -> {
            List<Lot> out = new ArrayList<>();
            String sql = "SELECT id, seller, seller_name, item, price, created_at FROM auction_lots" + (seller != null ? " WHERE seller=?" : "") + " ORDER BY id DESC LIMIT 2000";
            try (PreparedStatement ps = seller != null ? Database.ps(c, sql, seller.toString()) : Database.ps(c, sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        ItemStack it = ItemStack.deserializeBytes(rs.getBytes(4));
                        if (search != null) {
                            String q = search.toLowerCase(Locale.ROOT);
                            if (!Items.itemName(it).toLowerCase(Locale.ROOT).contains(q) && !it.getType().name().toLowerCase(Locale.ROOT).contains(q)) continue;
                        }
                        out.add(new Lot(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3), it, rs.getLong(5), rs.getLong(6)));
                    } catch (Exception e) {
                        plugin.getLogger().warning("Повреждённый лот #" + rs.getLong(1) + ": " + e.getMessage());
                    }
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ Меню

    public void openBrowse(Player p, Menu parent) { new Browse(p, parent, null).open(); }

    private final class Browse extends PagedMenu {
        private final String search;
        private List<Lot> cache;

        Browse(Player p, Menu parent, String search) {
            super(p, search == null ? "&8Аукцион" : "&8Аукцион: " + search);
            this.search = search;
            parent(parent);
        }

        @Override
        public void open() {
            Tasks.then(lots(search, null), l -> { cache = l; super.open(); }, null);
        }

        @Override
        protected List<Entry> entries() {
            List<Entry> out = new ArrayList<>();
            if (cache == null) return out;
            for (Lot lot : cache) {
                boolean own = lot.seller().equals(viewer.getUniqueId());
                out.add(new Entry(Items.display(lot.item(), List.of("", "&7Цена: &6" + Text.money(lot.price()), "&7Продавец: &f" + lot.sellerName(),
                        "&7Выставлен: &f" + Text.date(lot.at()), "", own ? "&cВаш лот — нажмите, чтобы снять" : "&eНажмите, чтобы купить")), c -> {
                    if (own) {
                        ConfirmMenu.open(viewer, "&8Снять лот?", Items.display(lot.item(), List.of()), List.of("&7Предмет вернётся вам"),
                                () -> cancel(viewer, lot, false, () -> new Browse(viewer, parent, search).open()));
                    } else if (plugin.roles().has(viewer, "usmp.mod.market") && c.shift() && c.right()) {
                        ConfirmMenu.open(viewer, "&8Снять лот (модерация)?", Items.display(lot.item(), List.of()), List.of("&7Предмет уйдёт владельцу в почту"),
                                () -> cancel(viewer, lot, true, () -> new Browse(viewer, parent, search).open()));
                    } else {
                        ConfirmMenu.open(viewer, "&8Купить за " + Text.money(lot.price()) + "?", Items.display(lot.item(), List.of()),
                                List.of("&7Цена: &6" + Text.money(lot.price()), "&7Продавец: &f" + lot.sellerName()),
                                () -> buy(viewer, lot, null));
                    }
                }));
            }
            return out;
        }

        @Override
        protected void bottomBar() {
            set(46, Items.of(Material.OAK_SIGN).name("&eПоиск").lore(search == null ? "&7Поиск по названию" : "&7Сейчас: &f" + search, "&7ПКМ — сбросить").build(), c -> {
                if (c.right()) { new Browse(viewer, parent, null).open(); return; }
                ChatInput.ask(viewer, Msg.t("ah.ask-search", "&fВведите часть названия предмета:"), s -> new Browse(viewer, parent, s).open());
            });
            set(47, Items.of(Material.CHEST).name("&aМои лоты").build(), c -> new MyLots(viewer, this).open());
            set(48, Items.of(Material.GOLD_INGOT).name("&6Выставить предмет из руки").lore("&7/ah sell <цена>", "&7Выставляется весь стак").build(), c ->
                    ChatInput.ask(viewer, Msg.t("ah.ask-price", "&fВведите цену за весь стак в руке:"), s -> list(viewer, Text.parseAmount(s))));
            set(50, Items.of(Material.WRITABLE_BOOK).name("&bОрдера").lore("&7Покупка и продажа по заявкам").build(), c -> plugin.orders().open(viewer, this));
            set(51, Items.of(Material.ENDER_CHEST).name("&dПочта").build(), c -> plugin.mail().open(viewer, this));
            set(52, Items.of(Material.EMERALD).name("&aСерверный магазин").build(), c -> plugin.shop().openShop(viewer, this));
        }
    }

    private final class MyLots extends PagedMenu {
        private List<Lot> cache;

        MyLots(Player p, Menu parent) {
            super(p, "&8Мои лоты");
            parent(parent);
        }

        @Override
        public void open() {
            Tasks.then(lots(null, viewer.getUniqueId()), l -> { cache = l; super.open(); }, null);
        }

        @Override
        protected List<Entry> entries() {
            List<Entry> out = new ArrayList<>();
            if (cache == null) return out;
            for (Lot lot : cache) {
                out.add(new Entry(Items.display(lot.item(), List.of("", "&7Цена: &6" + Text.money(lot.price()), "&cНажмите, чтобы снять")),
                        c -> ConfirmMenu.open(viewer, "&8Снять лот?", Items.display(lot.item(), List.of()), List.of("&7Предмет вернётся вам"),
                                () -> cancel(viewer, lot, false, () -> new MyLots(viewer, parent).open()))));
            }
            return out;
        }
    }
}
