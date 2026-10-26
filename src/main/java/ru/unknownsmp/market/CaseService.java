package ru.unknownsmp.market;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import ru.unknownsmp.core.ItemSpec;
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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кейсы (/cases) и лотерея (/lottery). Обе вещи забирают деньги из экономики: средняя выдача кейса меньше его цены,
 * с банка лотереи удерживается налог.
 */
public final class CaseService implements Module {
    private record Reward(int weight, String spec) {
        boolean money() { return spec.startsWith("money:"); }
        long amount() { try { return Long.parseLong(spec.substring(6)); } catch (NumberFormatException e) { return 0; } }
    }

    private record Case(String id, String name, Material icon, long price, List<Reward> rewards, int total) {}

    private final UnknownSMP plugin;
    private final Map<String, Case> cases = new LinkedHashMap<>();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private final Random rnd = new Random();
    private boolean casesOn = true, lotteryOn = true;
    private long ticketPrice = 10_000;
    private int maxTickets = 1000, drawHour = 20, minPlayers = 2;
    private double tax = 0.10;

    public CaseService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "cases"; }
    @Override public String title() { return "Кейсы и лотерея"; }
    @Override public String[] depends() { return new String[]{"economy"}; }

    @Override
    public void enable() {
        reload();
        plugin.commands().add("cases").player().perm("usmp.player.pay").module("cases").cat("Экономика")
                .usage("/cases").desc("Кейсы с наградами").exec((s, a) -> open((Player) s, null));
        plugin.commands().add("lottery").player().perm("usmp.player.pay").module("cases").cat("Экономика")
                .usage("/lottery [buy <кол-во>]").desc("Ежедневная лотерея").exec((s, a) -> {
                    Player p = (Player) s;
                    if (a.length >= 2 && a[0].equalsIgnoreCase("buy")) {
                        try { buyTickets(p, Integer.parseInt(a[1])); } catch (NumberFormatException e) { Msg.error(p, "lottery.usage", "Использование: /lottery buy <кол-во>"); }
                    } else lotteryMenu(p, null);
                });
        Tasks.timer(20L * 60, 20L * 60, this::checkDraw);
    }

    @Override public void disable() {}

    @Override
    public void reload() {
        YamlConfiguration c = plugin.configs().get("economy.yml");
        casesOn = c.getBoolean("cases.enabled", true);
        cases.clear();
        ConfigurationSection list = c.getConfigurationSection("cases.list");
        if (list != null) for (String id : list.getKeys(false)) {
            ConfigurationSection s = list.getConfigurationSection(id);
            if (s == null) continue;
            List<Reward> rw = new ArrayList<>();
            int total = 0;
            for (String line : s.getStringList("rewards")) {
                String[] parts = line.trim().split("\\s+", 2);
                if (parts.length < 2) continue;
                try {
                    int w = Integer.parseInt(parts[0]);
                    if (w <= 0) continue;
                    rw.add(new Reward(w, parts[1]));
                    total += w;
                } catch (NumberFormatException ignored) {}
            }
            Material icon = Material.matchMaterial(s.getString("icon", "CHEST"));
            if (rw.isEmpty()) continue;
            cases.put(id, new Case(id, s.getString("name", id), icon == null ? Material.CHEST : icon, Math.max(1, s.getLong("price", 50_000)), rw, total));
        }
        lotteryOn = c.getBoolean("lottery.enabled", true);
        ticketPrice = Math.max(1, c.getLong("lottery.ticket-price", 10_000));
        maxTickets = Math.max(1, c.getInt("lottery.max-tickets", 1000));
        drawHour = Math.max(0, Math.min(23, c.getInt("lottery.draw-hour", 20)));
        minPlayers = Math.max(1, c.getInt("lottery.min-players", 2));
        tax = Math.max(0, Math.min(0.9, c.getDouble("lottery.tax-percent", 10) / 100.0));
    }

    public boolean casesEnabled() { return casesOn && !cases.isEmpty(); }

    public boolean lotteryEnabled() { return lotteryOn; }

    // ------------------------------------------------------------------ Кейсы

    private ItemStack icon(Reward r) {
        if (r.money()) return Items.of(Material.GOLD_NUGGET).name("&6+" + Text.money(r.amount())).build();
        if (r.spec().startsWith("ce:")) {
            var en = ru.unknownsmp.items.CustomEnchantService.all().get(r.spec().substring(3).split(":")[0]);
            return Items.of(Material.ENCHANTED_BOOK).name("&dКнига: " + (en == null ? r.spec() : en.name())).glow().build();
        }
        ItemStack it = ItemSpec.parse(r.spec());
        if (it == null) return Items.of(Material.PAPER).name("&f" + r.spec()).build();
        return Items.display(it, List.of());
    }

    private static String chance(Reward r, Case c) {
        double pct = r.weight() * 100.0 / c.total();
        return pct >= 1 ? String.format("%.0f%%", pct) : String.format("%.1f%%", pct);
    }

    public void open(Player p, Menu parent) {
        if (!casesEnabled()) { Msg.error(p, "cases.disabled", "Кейсы отключены."); return; }
        new Menu(p, 3, "&8Кейсы") {
            @Override
            protected void build() {
                fill(Items.filler());
                int[] slots = {11, 13, 15, 10, 12, 14, 16};
                int i = 0;
                for (Case c : cases.values()) {
                    if (i >= slots.length) break;
                    List<String> lore = new ArrayList<>();
                    lore.add("&7Цена: &6" + Text.money(c.price()));
                    lore.add("");
                    lore.add("&7Может выпасть:");
                    for (Reward r : c.rewards()) lore.add(" &8• &f" + Text.strip(Items.itemName(icon(r))) + " &7(" + chance(r, c) + ")");
                    lore.add("");
                    lore.add("&eНажмите, чтобы открыть");
                    set(slots[i++], Items.of(c.icon()).name(c.name()).lore(lore).glow().build(),
                            cl -> ConfirmMenu.open(viewer, "&8Открыть " + Text.strip(c.name()) + "?", new ItemStack(c.icon()),
                                    List.of("&7Цена: &6" + Text.money(c.price())), () -> buy(viewer, c)));
                }
                if (parent != null) parent(parent);
                backButton(22);
            }
        }.open();
    }

    private Reward roll(Case c) {
        int x = rnd.nextInt(c.total());
        for (Reward r : c.rewards()) { x -= r.weight(); if (x < 0) return r; }
        return c.rewards().get(c.rewards().size() - 1);
    }

    private void buy(Player p, Case c) {
        if (!plugin.economy().tradeAllowed(p)) return;
        UUID u = p.getUniqueId();
        if (!busy.add(u)) { Msg.error(p, "cases.busy", "Подождите, кейс ещё открывается."); return; }
        Reward win = roll(c);
        Tasks.then(plugin.db().tx(con -> {
            long bal = EconomyService.debit(con, u, c.price(), "case_open", c.id());
            if (win.money()) bal = EconomyService.credit(con, u, win.amount(), "case_win", c.id());
            return bal;
        }), bal -> {
            plugin.economy().apply(u, bal);
            plugin.logs().log(p, "economy", "case_open", p.getLocation(), c.id() + " -> " + win.spec());
            if (p.isOnline()) animate(p, c, win); else finishItem(p, win);
        }, err -> {
            busy.remove(u);
            if (err instanceof Database.TxAbort ab) Msg.error(p, ab.key, ab.def);
            else Msg.error(p, "shop.error", "Покупка не выполнена. Деньги не списаны.");
        });
    }

    /** Рулетка: предметы едут по среднему ряду и замедляются, выигрыш останавливается под стрелкой. */
    private void animate(Player p, Case c, Reward win) {
        int steps = 28;
        List<Reward> strip = new ArrayList<>();
        for (int i = 0; i < steps + 9; i++) strip.add(roll(c));
        strip.set(steps + 4, win); // слот 13 (середина) на последнем шаге
        Menu m = new Menu(p, 3, "&8Открываем " + Text.strip(c.name())) {
            @Override
            protected void build() {
                fill(Items.filler());
                set(4, Items.of(Material.HOPPER).name("&e▼").build());
                set(22, Items.of(Material.HOPPER).name("&e▲").build());
            }
        };
        m.lock();
        m.open();
        Inventory inv = m.getInventory();
        long delay = 0;
        for (int s = 0; s <= steps; s++) {
            final int step = s;
            delay += 2 + (s * s) / 60; // плавное замедление
            Tasks.later(delay, () -> {
                if (p.isOnline() && p.getOpenInventory().getTopInventory() == inv) {
                    for (int k = 0; k < 9; k++) inv.setItem(9 + k, icon(strip.get(step + k)));
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
                }
                if (step == steps) Tasks.later(10, () -> finish(p, c, win));
            });
        }
    }

    private void finish(Player p, Case c, Reward win) {
        busy.remove(p.getUniqueId());
        String name = Text.strip(Items.itemName(icon(win)));
        if (p.isOnline()) {
            Msg.title(p, "&6&lВЫИГРЫШ", "&f" + name, 5, 50, 15);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        }
        if (win.weight() * 100.0 / c.total() <= 2.0) {
            Msg.broadcast("cases.jackpot", "&6{p}&f выбил &e{r}&f из {c}&f!", "p", p.getName(), "r", name, "c", c.name());
        }
        finishItem(p, win);
    }

    private void finishItem(Player p, Reward win) {
        if (win.money()) return; // деньги уже зачислены вместе со списанием
        plugin.items().resolve(win.spec(), p.getName(), "case").thenAccept(it -> {
            if (it != null) Tasks.sync(() -> plugin.mail().giveOrMail(p, it, "case"));
        });
    }

    // ------------------------------------------------------------------ Лотерея

    private String today() { return LocalDate.now(Text.zone()).toString(); }

    private record Pot(long tickets, int players, long mine) {}

    private java.util.concurrent.CompletableFuture<Pot> pot(UUID u) {
        return plugin.db().query(c -> {
            long t = 0, mine = 0;
            int n = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, tickets FROM lottery_tickets"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    t += rs.getLong(2);
                    n++;
                    if (u != null && u.toString().equals(rs.getString(1))) mine = rs.getLong(2);
                }
            }
            return new Pot(t, n, mine);
        });
    }

    private String untilDraw() {
        ZonedDateTime now = ZonedDateTime.now(Text.zone());
        ZonedDateTime next = now.withHour(drawHour).withMinute(0).withSecond(0).withNano(0);
        if (!next.isAfter(now) || today().equals(plugin.state().get("lottery_last_draw"))) next = next.plusDays(1);
        if (!next.isAfter(now)) next = next.plusDays(1);
        return Text.duration(java.time.Duration.between(now, next).toMillis());
    }

    public void lotteryMenu(Player p, Menu parent) {
        if (!lotteryOn) { Msg.error(p, "lottery.disabled", "Лотерея отключена."); return; }
        Tasks.then(pot(p.getUniqueId()), pot -> new Menu(p, 3, "&8Лотерея") {
            @Override
            protected void build() {
                fill(Items.filler());
                long bank = pot.tickets() * ticketPrice;
                double chance = pot.tickets() == 0 ? 0 : pot.mine() * 100.0 / pot.tickets();
                set(4, Items.of(Material.SUNFLOWER).name("&6&lБанк: " + Text.money(Math.round(bank * (1 - tax))))
                        .lore("&7Участников: &f" + pot.players(), "&7Билетов: &f" + Text.num(pot.tickets()),
                                "&7Ваши билеты: &f" + pot.mine() + " &7(шанс " + String.format("%.1f", chance) + "%)",
                                "", "&7Розыгрыш каждый день в &f" + drawHour + ":00&7 (через " + untilDraw() + ")",
                                "&7Победитель забирает банк (налог " + Math.round(tax * 100) + "%).",
                                "&8Нужно минимум " + minPlayers + " участника, иначе билеты переходят на завтра.").build());
                int[][] opts = {{11, 1}, {13, 10}, {15, 100}};
                for (int[] o : opts) {
                    int n = o[1];
                    set(o[0], Items.of(Material.PAPER).amount(Math.min(64, n)).name("&eКупить " + n + " билет" + (n == 1 ? "" : "ов"))
                            .lore("&7Цена: &6" + Text.money(ticketPrice * n)).build(), c -> { viewer.closeInventory(); buyTickets(viewer, n); });
                }
                if (parent != null) parent(parent);
                backButton(22);
            }
        }.open(), err -> Msg.error(p, "lottery.error", "Лотерея временно недоступна."));
    }

    private void buyTickets(Player p, int n) {
        if (!lotteryOn) return;
        if (n <= 0) return;
        if (!plugin.economy().tradeAllowed(p)) return;
        UUID u = p.getUniqueId();
        if (!busy.add(u)) return;
        long cost = ticketPrice * n;
        Tasks.then(plugin.db().tx(c -> {
            Long have = Database.longVal(c, "SELECT tickets FROM lottery_tickets WHERE uuid=?", u.toString());
            long cur = have == null ? 0 : have;
            if (cur + n > maxTickets) throw new Database.TxAbort("lottery.max", "Максимум " + maxTickets + " билетов на розыгрыш (у вас " + cur + ").");
            long bal = EconomyService.debit(c, u, cost, "lottery_ticket", null);
            if (have == null) Database.update(c, "INSERT INTO lottery_tickets(uuid, name, tickets) VALUES (?,?,?)", u.toString(), p.getName(), (long) n);
            else Database.update(c, "UPDATE lottery_tickets SET tickets = tickets + ?, name=? WHERE uuid=?", (long) n, p.getName(), u.toString());
            return bal;
        }), bal -> {
            busy.remove(u);
            plugin.economy().apply(u, bal);
            Msg.ok(p, "lottery.bought", "Куплено билетов: {n} за {c}. Розыгрыш в {h}:00.", "n", n, "c", Text.money(cost), "h", drawHour);
            plugin.logs().log(p, "economy", "lottery_buy", p.getLocation(), n + " " + cost);
        }, err -> {
            busy.remove(u);
            if (err instanceof Database.TxAbort ab) Msg.error(p, ab.key, ab.def);
            else Msg.error(p, "shop.error", "Покупка не выполнена. Деньги не списаны.");
        });
    }

    private volatile boolean drawing;

    private void checkDraw() {
        if (!lotteryOn || drawing) return;
        ZonedDateTime now = ZonedDateTime.now(Text.zone());
        if (now.getHour() < drawHour || today().equals(plugin.state().get("lottery_last_draw"))) return;
        drawing = true;
        final String day = today();
        Tasks.then(plugin.db().tx(c -> {
            List<String[]> rows = new ArrayList<>();
            long total = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name, tickets FROM lottery_tickets"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) { rows.add(new String[]{rs.getString(1), rs.getString(2), String.valueOf(rs.getLong(3))}); total += rs.getLong(3); }
            }
            if (rows.size() < minPlayers || total <= 0) return null; // переносим на завтра
            long x = (long) (rnd.nextDouble() * total);
            String[] win = rows.get(rows.size() - 1);
            for (String[] r : rows) { x -= Long.parseLong(r[2]); if (x < 0) { win = r; break; } }
            long prize = Math.round(total * ticketPrice * (1 - tax));
            UUID wu = UUID.fromString(win[0]);
            long bal = EconomyService.credit(c, wu, prize, "lottery_win", day);
            Database.update(c, "DELETE FROM lottery_tickets");
            return new Object[]{wu, win[1], prize, bal, Long.parseLong(win[2]), total};
        }), res -> {
            drawing = false;
            plugin.state().set("lottery_last_draw", day);
            if (res == null) {
                Msg.broadcast("lottery.rollover", "&6[Лотерея] &7Сегодня мало участников — билеты переходят на завтрашний розыгрыш.");
                return;
            }
            Object[] r = (Object[]) res;
            UUID wu = (UUID) r[0];
            plugin.economy().apply(wu, (Long) r[3]);
            Msg.broadcast("lottery.win", "&6[Лотерея] &e{p}&f выигрывает &6{c}&f! (билетов: {t} из {all})",
                    "p", r[1], "c", Text.money((Long) r[2]), "t", r[4], "all", r[5]);
            Player w = Bukkit.getPlayer(wu);
            if (w != null) Msg.title(w, "&6&lЛОТЕРЕЯ", "&fВы выиграли &6" + Text.money((Long) r[2]), 10, 70, 20);
            plugin.logs().sys("economy", "lottery_draw", null, r[1] + " " + r[2]);
        }, err -> {
            drawing = false;
            plugin.getLogger().warning("[Лотерея] Ошибка розыгрыша: " + err.getMessage());
        });
    }
}
