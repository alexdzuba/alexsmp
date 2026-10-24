package ru.unknownsmp.market;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import ru.unknownsmp.core.Commands;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.db.Database;
import ru.unknownsmp.economy.EconomyService;
import ru.unknownsmp.gui.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Обмен между игроками (/trade &lt;игрок&gt;). Общее окно: слева вещи первого игрока, справа — второго,
 * снизу — деньги и кнопки «Готов». Обмен проходит, когда оба готовы и 3 секунды ничего не меняется.
 * Любое изменение снимает готовность. Закрытие окна или выход отменяют обмен — вещи возвращаются владельцам.
 */
public final class TradeService implements Module, Listener {
    private static final int[] SLOTS_A = slots(0), SLOTS_B = slots(5);
    private static final int MONEY_A = 45, READY_A = 47, INFO = 49, READY_B = 51, MONEY_B = 53;

    private static int[] slots(int col0) {
        int[] out = new int[16];
        int i = 0;
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) out[i++] = r * 9 + col0 + c;
        return out;
    }

    private static boolean in(int[] arr, int slot) {
        for (int s : arr) if (s == slot) return true;
        return false;
    }

    private final class Session implements InventoryHolder {
        final Player a, b;
        final Inventory inv;
        long moneyA, moneyB;
        boolean readyA, readyB, done;
        int countdown = -1;
        org.bukkit.scheduler.BukkitTask task;

        Session(Player a, Player b) {
            this.a = a;
            this.b = b;
            this.inv = Bukkit.createInventory(this, 54, Text.c("&8Обмен: " + a.getName() + " ⇄ " + b.getName()));
        }

        @Override public @NotNull Inventory getInventory() { return inv; }

        boolean isA(Player p) { return p.getUniqueId().equals(a.getUniqueId()); }

        Player other(Player p) { return isA(p) ? b : a; }

        void render() {
            ItemStack bar = Items.filler(Material.IRON_BARS);
            for (int r = 0; r < 4; r++) inv.setItem(r * 9 + 4, bar);
            ItemStack line = Items.filler(Material.GRAY_STAINED_GLASS_PANE);
            for (int i = 36; i < 45; i++) inv.setItem(i, line);
            inv.setItem(36, Items.head(a).name("&f" + a.getName()).lore("&7Вещи слева").build());
            inv.setItem(44, Items.head(b).name("&f" + b.getName()).lore("&7Вещи справа").build());
            for (int i : new int[]{46, 48, 50, 52}) inv.setItem(i, line);
            inv.setItem(MONEY_A, moneyIcon(a, moneyA));
            inv.setItem(MONEY_B, moneyIcon(b, moneyB));
            inv.setItem(READY_A, readyIcon(a, readyA));
            inv.setItem(READY_B, readyIcon(b, readyB));
            String state = countdown >= 0 ? "&aОбмен через " + countdown + "..." : readyA && readyB ? "&aОба готовы" : "&7Положите вещи и нажмите «Готов»";
            inv.setItem(INFO, Items.of(Material.PAPER).name(state).lore("&7Любое изменение снимает готовность.",
                    "&7Закрыть окно — отменить обмен, вещи вернутся.").build());
        }

        private ItemStack moneyIcon(Player p, long m) {
            return Items.of(Material.GOLD_INGOT).name("&6Деньги " + p.getName() + ": " + Text.money(m))
                    .lore("&eЛКМ &7+1 000   &eПКМ &7+10 000", "&eShift+ЛКМ &7+100 000   &eShift+ПКМ &7сбросить",
                            "&8Менять может только " + p.getName()).build();
        }

        private ItemStack readyIcon(Player p, boolean r) {
            return Items.of(r ? Material.LIME_DYE : Material.RED_DYE).name(r ? "&a" + p.getName() + " готов" : "&c" + p.getName() + " не готов")
                    .lore("&8Нажать может только " + p.getName()).build();
        }

        void unready() {
            readyA = readyB = false;
            stopCountdown();
        }

        void stopCountdown() {
            countdown = -1;
            if (task != null) { task.cancel(); task = null; }
        }
    }

    private final UnknownSMP plugin;
    private final Map<UUID, long[]> requests = new ConcurrentHashMap<>(); // target -> {fromMost, fromLeast, until}
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private double maxDistance = 32;

    public TradeService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "trade"; }
    @Override public String title() { return "Обмен между игроками"; }
    @Override public String[] depends() { return new String[]{"economy", "mail"}; }

    @Override
    public void enable() {
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.commands().add("trade").player().perm("usmp.player.pay").module("trade").cat("Экономика").cooldown(1000)
                .usage("/trade <игрок> | accept | deny").desc("Обмен вещами и деньгами с игроком")
                .exec((s, a) -> command((Player) s, a))
                .tab((s, a) -> a.length == 1 ? Commands.players(a[0]) : List.of());
    }

    @Override
    public void disable() {
        for (Session s : new ArrayList<>(sessions.values())) cancel(s, "сервер перезапускается");
    }

    @Override
    public void reload() {
        YamlConfiguration c = plugin.configs().get("economy.yml");
        maxDistance = c.getDouble("trade.max-distance", 32);
    }

    // ------------------------------------------------------------------ Запрос

    private void command(Player p, String[] a) {
        if (a.length == 0) { Msg.error(p, "trade.usage", "Использование: /trade <игрок>, затем второй игрок пишет /trade accept"); return; }
        String sub = a[0].toLowerCase(java.util.Locale.ROOT);
        if (sub.equals("accept") || sub.equals("принять")) { accept(p); return; }
        if (sub.equals("deny") || sub.equals("отказ")) {
            if (requests.remove(p.getUniqueId()) != null) Msg.ok(p, "trade.denied", "Предложение обмена отклонено.");
            return;
        }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null || t == p || (plugin.auth() != null && !plugin.auth().isAuthed(t))) { Msg.error(p, "trade.offline", "Игрок не в сети."); return; }
        if (!canTrade(p, t)) return;
        // Встречный запрос — сразу открываем обмен.
        long[] back = requests.get(p.getUniqueId());
        if (back != null && back[2] > System.currentTimeMillis() && new UUID(back[0], back[1]).equals(t.getUniqueId())) { accept(p); return; }
        requests.put(t.getUniqueId(), new long[]{p.getUniqueId().getMostSignificantBits(), p.getUniqueId().getLeastSignificantBits(), System.currentTimeMillis() + 60_000});
        Msg.ok(p, "trade.sent", "Предложение обмена отправлено игроку {t}.", "t", t.getName());
        Msg.send(t, "trade.request", "&e{p} предлагает обмен. Принять: &a/trade accept&e, отклонить: &c/trade deny &7(60 сек)", "p", p.getName());
        Msg.sound(t, Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.4f);
    }

    private boolean canTrade(Player p, Player t) {
        if (!plugin.economy().tradeAllowed(p)) return false;
        if (sessions.containsKey(p.getUniqueId()) || sessions.containsKey(t.getUniqueId())) { Msg.error(p, "trade.busy", "Кто-то из вас уже в обмене."); return false; }
        if (plugin.lobby().isLobby(p.getWorld()) || (plugin.duels() != null && plugin.duels().isDuelWorld(p.getWorld()))) {
            Msg.error(p, "trade.world", "Обмен доступен только в мире выживания."); return false;
        }
        if (maxDistance > 0 && (!p.getWorld().equals(t.getWorld()) || p.getLocation().distanceSquared(t.getLocation()) > maxDistance * maxDistance)) {
            Msg.error(p, "trade.far", "Подойдите ближе (до {d} блоков).", "d", (int) maxDistance); return false;
        }
        return true;
    }

    private void accept(Player p) {
        long[] r = requests.remove(p.getUniqueId());
        if (r == null || r[2] < System.currentTimeMillis()) { Msg.error(p, "trade.none", "Нет активных предложений обмена."); return; }
        Player from = Bukkit.getPlayer(new UUID(r[0], r[1]));
        if (from == null) { Msg.error(p, "trade.offline", "Игрок не в сети."); return; }
        if (!canTrade(p, from) || !plugin.economy().tradeAllowed(from)) return;
        Session s = new Session(from, p);
        sessions.put(from.getUniqueId(), s);
        sessions.put(p.getUniqueId(), s);
        s.render();
        from.openInventory(s.inv);
        p.openInventory(s.inv);
    }

    // ------------------------------------------------------------------ Окно

    private Session sessionOf(Inventory top) {
        return top != null && top.getHolder(false) instanceof Session s ? s : null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void click(InventoryClickEvent e) {
        Session s = sessionOf(e.getView().getTopInventory());
        if (s == null || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        if (s.done) return;
        boolean isA = s.isA(p);
        int[] mine = isA ? SLOTS_A : SLOTS_B;
        InventoryAction act = e.getAction();
        // Эти действия могут затронуть чужие ячейки — запрещены.
        if (act == InventoryAction.COLLECT_TO_CURSOR || e.getClick() == ClickType.NUMBER_KEY || e.getClick() == ClickType.SWAP_OFFHAND
                || e.getClick() == ClickType.DOUBLE_CLICK) return;
        if (e.getClickedInventory() == null) return;
        boolean top = e.getClickedInventory().equals(s.inv);
        if (!top) {
            // Свой инвентарь: обычные клики разрешены, Shift — переносим в свою половину вручную.
            if (e.isShiftClick()) {
                ItemStack it = e.getCurrentItem();
                if (it == null || it.getType().isAir()) return;
                for (int slot : mine) {
                    if (s.inv.getItem(slot) == null) {
                        s.inv.setItem(slot, it.clone());
                        e.getClickedInventory().setItem(e.getSlot(), null);
                        changed(s);
                        return;
                    }
                }
                return;
            }
            e.setCancelled(false);
            return;
        }
        int slot = e.getRawSlot();
        if (in(mine, slot)) {
            if (e.isShiftClick()) {
                ItemStack it = s.inv.getItem(slot);
                if (it == null) return;
                var left = p.getInventory().addItem(it);
                s.inv.setItem(slot, left.isEmpty() ? null : left.values().iterator().next());
            } else {
                e.setCancelled(false);
            }
            changed(s);
            return;
        }
        if (slot == (isA ? MONEY_A : MONEY_B)) {
            long cur = isA ? s.moneyA : s.moneyB;
            long next = switch (e.getClick()) {
                case LEFT -> cur + 1_000;
                case RIGHT -> cur + 10_000;
                case SHIFT_LEFT -> cur + 100_000;
                case SHIFT_RIGHT -> 0;
                default -> cur;
            };
            long bal = plugin.economy().balance(p);
            if (next > bal) { next = bal; Msg.actionBar(p, "&cБольше, чем у вас есть (" + Text.money(bal) + ")"); }
            if (isA) s.moneyA = next; else s.moneyB = next;
            changed(s);
            return;
        }
        if (slot == (isA ? READY_A : READY_B)) {
            if (isA) s.readyA = !s.readyA; else s.readyB = !s.readyB;
            if (!(s.readyA && s.readyB)) s.stopCountdown();
            else startCountdown(s);
            s.render();
            Msg.sound(p, Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void drag(InventoryDragEvent e) {
        Session s = sessionOf(e.getView().getTopInventory());
        if (s == null || !(e.getWhoClicked() instanceof Player p)) return;
        int[] mine = s.isA(p) ? SLOTS_A : SLOTS_B;
        for (int raw : e.getRawSlots()) {
            if (raw < 54 && !in(mine, raw)) { e.setCancelled(true); return; }
        }
        if (s.done) { e.setCancelled(true); return; }
        changed(s);
    }

    /** Любое изменение: снять готовность и перерисовать на следующем тике. */
    private void changed(Session s) {
        s.unready();
        Tasks.later(1, () -> { if (!s.done) s.render(); });
    }

    private void startCountdown(Session s) {
        s.stopCountdown();
        s.countdown = 3;
        s.task = Tasks.timer(20, 20, () -> {
            if (s.done || !(s.readyA && s.readyB)) { s.stopCountdown(); return; }
            s.countdown--;
            if (s.countdown <= 0) { s.stopCountdown(); complete(s); return; }
            s.render();
            Msg.sound(s.a, Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.5f);
            Msg.sound(s.b, Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.5f);
        });
    }

    // ------------------------------------------------------------------ Завершение и отмена

    private List<ItemStack> take(Session s, int[] slots) {
        List<ItemStack> out = new ArrayList<>();
        for (int sl : slots) {
            ItemStack it = s.inv.getItem(sl);
            if (it != null && !it.getType().isAir()) out.add(it);
            s.inv.setItem(sl, null);
        }
        return out;
    }

    private void complete(Session s) {
        if (s.done) return;
        if (!s.a.isOnline() || !s.b.isOnline()) { cancel(s, "игрок вышел"); return; }
        s.done = true;
        final long ma = s.moneyA, mb = s.moneyB;
        final UUID ua = s.a.getUniqueId(), ub = s.b.getUniqueId();
        Tasks.then(plugin.db().tx(c -> {
            long[] bal = {-1, -1};
            if (ma > 0) { EconomyService.debit(c, ua, ma, "trade_out", s.b.getName()); EconomyService.credit(c, ub, ma, "trade_in", s.a.getName()); }
            if (mb > 0) { EconomyService.debit(c, ub, mb, "trade_out", s.a.getName()); EconomyService.credit(c, ua, mb, "trade_in", s.b.getName()); }
            bal[0] = EconomyService.balance(c, ua);
            bal[1] = EconomyService.balance(c, ub);
            return bal;
        }), bal -> {
            plugin.economy().apply(ua, bal[0]);
            plugin.economy().apply(ub, bal[1]);
            List<ItemStack> fromA = take(s, SLOTS_A), fromB = take(s, SLOTS_B);
            end(s);
            for (ItemStack it : fromA) plugin.mail().giveOrMail(s.b, it, "trade");
            for (ItemStack it : fromB) plugin.mail().giveOrMail(s.a, it, "trade");
            s.a.saveData();
            s.b.saveData();
            Msg.ok(s.a, "trade.done", "Обмен с {p} завершён.", "p", s.b.getName());
            Msg.ok(s.b, "trade.done", "Обмен с {p} завершён.", "p", s.a.getName());
            Msg.sound(s.a, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.6f);
            Msg.sound(s.b, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.6f);
            String details = s.a.getName() + " -> " + s.b.getName() + ": " + describe(fromA) + " +" + ma + " | "
                    + s.b.getName() + " -> " + s.a.getName() + ": " + describe(fromB) + " +" + mb;
            plugin.logs().log(s.a, "market", "trade", s.a.getLocation(), details);
        }, err -> {
            // Денег не хватило или ошибка БД — обмен не состоялся, окно остаётся открытым.
            s.done = false;
            s.unready();
            s.render();
            String why = err instanceof Database.TxAbort ab ? ab.def : "ошибка операции";
            Msg.error(s.a, "trade.failed", "Обмен не прошёл: {r}", "r", why);
            Msg.error(s.b, "trade.failed", "Обмен не прошёл: {r}", "r", why);
        });
    }

    private static String describe(List<ItemStack> items) {
        if (items.isEmpty()) return "—";
        StringBuilder sb = new StringBuilder();
        for (ItemStack it : items) { if (!sb.isEmpty()) sb.append(", "); sb.append(it.getAmount()).append("x").append(it.getType()); }
        return sb.toString();
    }

    private void end(Session s) {
        s.done = true;
        s.stopCountdown();
        sessions.remove(s.a.getUniqueId(), s);
        sessions.remove(s.b.getUniqueId(), s);
        Tasks.later(1, () -> {
            if (s.a.getOpenInventory().getTopInventory().equals(s.inv)) s.a.closeInventory();
            if (s.b.getOpenInventory().getTopInventory().equals(s.inv)) s.b.closeInventory();
        });
    }

    private void cancel(Session s, String reason) {
        if (s.done) return;
        List<ItemStack> fromA = take(s, SLOTS_A), fromB = take(s, SLOTS_B);
        end(s);
        for (ItemStack it : fromA) plugin.mail().giveOrMail(s.a, it, "trade_return");
        for (ItemStack it : fromB) plugin.mail().giveOrMail(s.b, it, "trade_return");
        Msg.error(s.a, "trade.cancelled", "Обмен отменён: {r}. Вещи возвращены.", "r", reason);
        Msg.error(s.b, "trade.cancelled", "Обмен отменён: {r}. Вещи возвращены.", "r", reason);
    }

    @EventHandler
    public void close(InventoryCloseEvent e) {
        Session s = sessionOf(e.getInventory());
        if (s == null || s.done) return;
        Tasks.later(1, () -> cancel(s, e.getPlayer().getName() + " закрыл окно"));
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        requests.remove(e.getPlayer().getUniqueId());
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) cancel(s, e.getPlayer().getName() + " вышел");
    }
}
