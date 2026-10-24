package ru.unknownsmp.events;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.ItemStack;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.db.Database;
import ru.unknownsmp.gui.ConfirmMenu;
import ru.unknownsmp.gui.Items;
import ru.unknownsmp.gui.Menu;
import ru.unknownsmp.market.MailService;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Глобальное открытие измерений. Незер: 5 осколков из событий и тайников → Ключ Незера → ритуал.
 * Энд: одновременно (а) прошло 7 дней после открытия Незера и (б) выполнено коллективное задание сервера.
 */
public final class DimensionService implements Module, Listener {
    public static final int FRAGMENTS = 5;
    private final UnknownSMP plugin;
    private final Map<String, Long> goals = new LinkedHashMap<>();
    private final Map<String, Long> progress = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> busy = ConcurrentHashMap.newKeySet();

    public DimensionService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "dimensions"; }
    @Override public String title() { return "Открытие измерений"; }
    @Override public String[] depends() { return new String[]{"state", "items", "mail"}; }

    @Override
    public void enable() throws Exception {
        reload();
        plugin.db().queryBlocking(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT item, SUM(amount) FROM collective GROUP BY item"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) progress.put(rs.getString(1), rs.getLong(2));
            }
            return null;
        });
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Tasks.timer(20 * 60, 20 * 60, this::checkEnd);
        recoverRitual();
        plugin.commands().add("dimensions").player().perm("usmp.player.events").module("dimensions").cat("События")
                .usage("/dimensions").desc("Открытие Незера и Энда, коллективное задание").exec((s, a) -> open((Player) s, null));
    }

    @Override
    public void reload() {
        goals.clear();
        ConfigurationSection g = plugin.configs().get("events.yml").getConfigurationSection("end.collective-goal");
        if (g != null) for (String k : g.getKeys(false)) {
            Material m = Material.matchMaterial(k);
            if (m != null) goals.put(m.name(), g.getLong(k));
        }
        if (goals.isEmpty()) {
            goals.put("ENDER_PEARL", 512L);
            goals.put("BLAZE_ROD", 256L);
            goals.put("OBSIDIAN", 512L);
            goals.put("DIAMOND", 128L);
        }
    }

    public boolean netherOpen() { return plugin.state().getBool("nether_open"); }

    public boolean endOpen() { return plugin.state().getBool("end_open"); }

    public boolean canBeIn(World w) {
        if (w == null) return false;
        if (w.getEnvironment() == World.Environment.NETHER) return netherOpen();
        if (w.getEnvironment() == World.Environment.THE_END) return endOpen();
        return true;
    }

    // ------------------------------------------------------------------ Блокировка порталов

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void portal(PlayerPortalEvent e) {
        if (plugin.lobby().isLobby(e.getFrom().getWorld())) return;
        World to = e.getTo() == null ? null : e.getTo().getWorld();
        World.Environment env = to != null ? to.getEnvironment()
                : e.getCause() == PlayerPortalEvent.TeleportCause.END_PORTAL ? World.Environment.THE_END : World.Environment.NETHER;
        if (env == World.Environment.NETHER && !netherOpen() && e.getFrom().getWorld().getEnvironment() != World.Environment.NETHER) {
            e.setCancelled(true);
            Msg.actionBar(e.getPlayer(), Msg.t("dim.nether-closed", "&cНезер запечатан. Соберите Ключ Незера (/dimensions)."));
        } else if (env == World.Environment.THE_END && !endOpen() && e.getFrom().getWorld().getEnvironment() != World.Environment.THE_END) {
            e.setCancelled(true);
            Msg.actionBar(e.getPlayer(), Msg.t("dim.end-closed", "&cЭнд запечатан. Нужны 7 дней после открытия Незера и коллективное задание."));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void entityPortal(EntityPortalEvent e) {
        if (e.getTo() == null) return;
        if (!canBeIn(e.getTo().getWorld())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void portalCreate(PortalCreateEvent e) {
        if (plugin.lobby().isLobby(e.getWorld())) return;
        if (e.getReason() == PortalCreateEvent.CreateReason.FIRE && !netherOpen()) {
            e.setCancelled(true);
            if (e.getEntity() instanceof Player p) Msg.error(p, "dim.nether-sealed", "Портал не зажигается: Незер запечатан.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void eye(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        if (e.getClickedBlock().getType() == Material.END_PORTAL_FRAME && e.getItem() != null && e.getItem().getType() == Material.ENDER_EYE && !endOpen()) {
            e.setCancelled(true);
            Msg.error(e.getPlayer(), "dim.end-frame", "Рамка не принимает око: Энд запечатан.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!canBeIn(p.getWorld())) Tasks.later(2, () -> p.teleport(plugin.world().spawn()));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void useKey(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (it == null || !"nether_key".equals(plugin.items().typeOf(it))) return;
        e.setCancelled(true);
        startRitual(e.getPlayer(), it);
    }

    // ------------------------------------------------------------------ Осколки и ключ

    /** Выдать следующий осколок лучшему участнику события (пока Незер закрыт). */
    public void maybeAwardFragment(Player p, String eventType) {
        if (netherOpen()) return;
        double chance = plugin.configs().get("events.yml").getDouble("nether.fragment-chance", 1.0);
        if (Math.random() > chance) return;
        Tasks.then(plugin.db().query(c -> {
            for (int i = 1; i <= FRAGMENTS; i++) {
                Long n = Database.longVal(c, "SELECT COUNT(*) FROM unique_items WHERE type=? AND status IN ('ACTIVE','CONSUMED','USED','RITUAL')", "nether_fragment_" + i);
                if (n == null || n == 0) return i;
            }
            Long key = Database.longVal(c, "SELECT COUNT(*) FROM unique_items WHERE type='nether_key' AND status IN ('ACTIVE','RITUAL','USED')");
            return key != null && key > 0 ? -1 : 0;
        }), idx -> {
            if (idx == null || idx <= 0) return;
            Tasks.then(plugin.items().create("nether_fragment_" + idx, p.getName(), "event:" + eventType), it -> {
                plugin.mail().giveOrMail(p, it, "event");
                Msg.broadcast("dim.fragment-found", "&6✦ {p} получил &cОсколок Незера {n}/{t}&6! Соберите все осколки, чтобы создать Ключ Незера.",
                        "p", p.getName(), "n", idx, "t", FRAGMENTS);
                plugin.logs().log(p, "progress", "fragment", p.getLocation(), "nether_fragment_" + idx);
            }, err -> plugin.getLogger().warning("Осколок не выдан: " + err.getMessage()));
        }, null);
    }

    public void assembleKey(Player p) {
        if (netherOpen()) { Msg.error(p, "dim.already-open", "Незер уже открыт."); return; }
        Map<Integer, Integer> slots = new LinkedHashMap<>();
        ItemStack[] cont = p.getInventory().getStorageContents();
        for (int s = 0; s < cont.length; s++) {
            String t = plugin.items().typeOf(cont[s]);
            if (t == null || !t.startsWith("nether_fragment_") || !plugin.items().isAuthentic(cont[s])) continue;
            try { slots.putIfAbsent(Integer.parseInt(t.substring(16)), s); } catch (NumberFormatException ignored) {}
        }
        if (slots.size() < FRAGMENTS) {
            Msg.error(p, "dim.need-fragments", "Нужны все {t} разных осколков Незера (у вас {n}).", "t", FRAGMENTS, "n", slots.size());
            return;
        }
        if (!busy.add(p.getUniqueId())) return;
        List<String> uids = new ArrayList<>();
        for (int s : slots.values()) {
            uids.add(plugin.items().uidOf(cont[s]));
            cont[s] = null;
        }
        p.getInventory().setStorageContents(cont);
        p.saveData();
        for (String u : uids) plugin.items().setStatus(u, "CONSUMED", p.getName());
        Tasks.then(plugin.items().create("nether_key", p.getName(), "assembled"), key -> {
            busy.remove(p.getUniqueId());
            plugin.mail().giveOrMail(p, key, "reward");
            Msg.broadcast("dim.key-assembled", "&c&l{p} собрал Ключ Незера! &fРитуал открытия может начаться в любой момент...", "p", p.getName());
            plugin.logs().log(p, "progress", "nether_key", p.getLocation(), String.join(",", uids));
        }, err -> {
            busy.remove(p.getUniqueId());
            // Ключ не создан — возвращаем статус осколков (сами предметы восстанавливаем из UID нельзя, поэтому создаём заново).
            plugin.getLogger().severe("Ключ Незера не создан: " + err.getMessage() + ". Осколки помечены CONSUMED, требуется администратор.");
            Msg.error(p, "dim.key-error", "Ошибка создания ключа. Администрация уведомлена, осколки зафиксированы в журнале.");
            plugin.logs().suspicious(p, "nether_key_error", String.join(",", uids));
        });
    }

    private void startRitual(Player p, ItemStack key) {
        if (netherOpen()) { Msg.error(p, "dim.already-open", "Незер уже открыт."); return; }
        if (!plugin.items().isAuthentic(key)) { Msg.error(p, "items.counterfeit", "Ключ не прошёл проверку подлинности."); return; }
        long retry = plugin.state().getLong("nether_retry_at", 0);
        if (retry > System.currentTimeMillis()) { Msg.error(p, "dim.retry", "Ключ ещё не восстановил силу. Повтор через {t}.", "t", Text.duration(retry - System.currentTimeMillis())); return; }
        if (!p.getWorld().equals(plugin.survivalWorld())) { Msg.error(p, "dim.world", "Ритуал можно провести только в основном мире."); return; }
        double minDist = plugin.configs().get("events.yml").getDouble("nether.ritual-min-distance", 300);
        if (p.getLocation().distance(plugin.world().spawn()) < minDist) { Msg.error(p, "dim.too-close", "Слишком близко к спавну (нужно от {d} блоков).", "d", (int) minDist); return; }
        if (plugin.events().active() != null || plugin.events().pending() != null) { Msg.error(p, "dim.busy", "Сейчас идёт другое событие."); return; }
        ConfirmMenu.open(p, "&4Начать ритуал?", Items.of(key).build(), List.of("&7Ключ будет помещён в алтарь.", "&7Защитите алтарь от трёх волн.",
                "&7При неудаче ключ вернётся вам, повтор через час."), () -> {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (!"nether_key".equals(plugin.items().typeOf(hand))) return;
            ItemStack k = hand.clone();
            p.getInventory().setItemInMainHand(null);
            p.saveData();
            String uid = plugin.items().uidOf(k);
            plugin.items().setStatus(uid, "RITUAL", p.getName());
            plugin.state().set("ritual_holder", p.getUniqueId().toString());
            plugin.state().set("ritual_key", Base64.getEncoder().encodeToString(k.serializeAsBytes()));
            Location at = p.getLocation().getBlock().getLocation().add(0.5, 0, 0.5);
            if (!plugin.events().startRitual(p, at, k)) {
                ritualFinished(false, p.getUniqueId(), k, at, "ритуал не смог начаться: идёт другое событие или алтарь нельзя построить здесь (см. консоль)", false);
            }
        });
    }

    /** Итог ритуала (вызывается RitualEvent). */
    public void ritualFinished(boolean success, UUID activator, ItemStack key, Location at) {
        ritualFinished(success, activator, key, at, null, true);
    }

    /** cooldown=false — провал не по вине игроков (ошибка, остановка администратором): повтор сразу, без отката. */
    public void ritualFinished(boolean success, UUID activator, ItemStack key, Location at, String reason, boolean cooldown) {
        String uid = plugin.items().uidOf(key);
        plugin.state().set("ritual_holder", null);
        plugin.state().set("ritual_key", null);
        if (success) {
            plugin.items().setStatus(uid, "USED", "ritual");
            plugin.state().set("nether_open", true);
            plugin.state().set("nether_open_at", System.currentTimeMillis());
            Msg.broadcast("dim.nether-opened", "&4&l✦ НЕЗЕР ОТКРЫТ! ✦ &fТеперь можно строить и зажигать порталы.");
            for (Player p : Bukkit.getOnlinePlayers()) Msg.title(p, "&4&lНЕЗЕР ОТКРЫТ", "&7Порталы снова работают", 10, 80, 20);
            plugin.logs().sys("progress", "nether_open", at, "uid=" + uid);
        } else {
            plugin.items().setStatus(uid, "ACTIVE", "returned");
            plugin.db().tx(c -> { MailService.insert(c, activator, key, "reward"); return null; });
            if (cooldown) {
                long retry = System.currentTimeMillis() + plugin.configs().get("events.yml").getLong("retry-minutes", 60) * 60_000L;
                plugin.state().set("nether_retry_at", retry);
                Msg.broadcast("dim.ritual-failed", "&7Ритуал прерван. Ключ вернулся к владельцу (в почту). Повтор возможен через час.");
            } else {
                Msg.broadcast("dim.ritual-failed-nocd", "&7Ритуал прерван. Ключ вернулся к владельцу (в почту). Можно повторить сразу.");
            }
            if (reason != null) {
                Msg.broadcast("dim.ritual-failed-reason", "&7Причина: &f{r}", "r", reason);
                plugin.getLogger().info("Ритуал Незера провален: " + reason);
            }
        }
    }

    /** Если сервер остановился во время ритуала — вернуть ключ владельцу. */
    private void recoverRitual() {
        String holder = plugin.state().get("ritual_holder");
        String data = plugin.state().get("ritual_key");
        if (holder == null || data == null) return;
        try {
            ItemStack k = ItemStack.deserializeBytes(Base64.getDecoder().decode(data));
            UUID u = UUID.fromString(holder);
            plugin.items().setStatus(plugin.items().uidOf(k), "ACTIVE", "recovered");
            plugin.db().tx(c -> { MailService.insert(c, u, k, "reward"); return null; }).thenRun(() -> {
                plugin.state().set("ritual_holder", null);
                plugin.state().set("ritual_key", null);
            });
            plugin.getLogger().warning("Ритуал был прерван остановкой сервера — Ключ Незера возвращён владельцу в почту.");
        } catch (Exception e) {
            plugin.getLogger().severe("Не удалось восстановить ключ ритуала: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ Энд

    public Map<String, Long> goals() { return goals; }

    public long progress(String mat) { return progress.getOrDefault(mat, 0L); }

    public boolean collectiveDone() {
        for (var g : goals.entrySet()) if (progress(g.getKey()) < g.getValue()) return false;
        return true;
    }

    public void donate(Player p, Material m) {
        Long goal = goals.get(m.name());
        if (goal == null) return;
        long need = goal - progress(m.name());
        if (need <= 0) { Msg.error(p, "dim.goal-done", "Эта часть задания уже выполнена."); return; }
        int have = 0;
        for (ItemStack it : p.getInventory().getStorageContents())
            if (it != null && it.getType() == m && ru.unknownsmp.market.ShopService.isPlain(it)) have += it.getAmount();
        int take = (int) Math.min(have, need);
        if (take <= 0) { Msg.error(p, "dim.no-items", "У вас нет {m}.", "m", Items.materialName(m)); return; }
        if (!busy.add(p.getUniqueId())) return;
        int left = take;
        ItemStack[] cont = p.getInventory().getStorageContents();
        for (int i = 0; i < cont.length && left > 0; i++) {
            if (cont[i] == null || cont[i].getType() != m || !ru.unknownsmp.market.ShopService.isPlain(cont[i])) continue;
            int t = Math.min(left, cont[i].getAmount());
            cont[i].setAmount(cont[i].getAmount() - t);
            if (cont[i].getAmount() <= 0) cont[i] = null;
            left -= t;
        }
        p.getInventory().setStorageContents(cont);
        p.saveData();
        final int amount = take;
        final String u = p.getUniqueId().toString();
        Tasks.then(plugin.db().tx(c -> Database.update(c,
                "INSERT INTO collective(uuid, item, amount) VALUES (?,?,?) ON CONFLICT(uuid, item) DO UPDATE SET amount = collective.amount + excluded.amount", u, m.name(), amount)), n -> {
            busy.remove(p.getUniqueId());
            progress.merge(m.name(), (long) amount, Long::sum);
            Msg.ok(p, "dim.donated", "Вы внесли {n}x {m} в коллективное задание.", "n", amount, "m", Items.materialName(m));
            plugin.progression().addReputation(p, Math.max(1, amount / 16), "collective");
            plugin.logs().log(p, "progress", "collective", p.getLocation(), amount + "x " + m);
            if (collectiveDone() && plugin.state().getLong("end_task_done_at", 0) == 0) {
                plugin.state().set("end_task_done_at", System.currentTimeMillis());
                Msg.broadcast("dim.collective-done", "&5&l✦ Коллективное задание выполнено! &fЭнд откроется, когда пройдёт 7 дней с открытия Незера.");
                checkEnd();
            }
        }, err -> {
            busy.remove(p.getUniqueId());
            ItemStack back = new ItemStack(m);
            int l2 = amount;
            while (l2 > 0) {
                int a = Math.min(l2, m.getMaxStackSize());
                ItemStack it = back.clone();
                it.setAmount(a);
                plugin.mail().giveOrMail(p, it, "return");
                l2 -= a;
            }
            Msg.error(p, "dim.donate-error", "Ошибка. Предметы возвращены.");
        });
    }

    private void checkEnd() {
        if (endOpen() || !netherOpen() || !collectiveDone()) return;
        long days = plugin.configs().get("events.yml").getLong("end.days-after-nether", 7);
        long openedAt = plugin.state().getLong("nether_open_at", Long.MAX_VALUE);
        if (System.currentTimeMillis() < openedAt + days * 86_400_000L) return;
        plugin.state().set("end_open", true);
        plugin.state().set("end_open_at", System.currentTimeMillis());
        Msg.broadcast("dim.end-opened", "&5&l✦ ЭНД ОТКРЫТ! ✦ &fПорталы Края снова принимают око эндера.");
        for (Player p : Bukkit.getOnlinePlayers()) Msg.title(p, "&5&lЭНД ОТКРЫТ", "&7Край ждёт смельчаков", 10, 80, 20);
        plugin.logs().sys("progress", "end_open", null, "");
        rewardCollective();
    }

    private void rewardCollective() {
        long pool = plugin.configs().get("events.yml").getLong("end.reward-pool", 500_000);
        Tasks.then(plugin.db().query(c -> {
            Map<UUID, Long> out = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, SUM(amount) FROM collective GROUP BY uuid"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(UUID.fromString(rs.getString(1)), rs.getLong(2));
            }
            return out;
        }), contrib -> {
            long total = contrib.values().stream().mapToLong(Long::longValue).sum();
            if (total <= 0) return;
            for (var e : contrib.entrySet()) {
                long money = pool * e.getValue() / total;
                if (money > 0) plugin.economy().deposit(e.getKey(), money, "collective_reward", "end");
            }
        }, null);
    }

    public void forceNether(boolean open) {
        plugin.state().set("nether_open", open);
        if (open && plugin.state().getLong("nether_open_at", 0) == 0) plugin.state().set("nether_open_at", System.currentTimeMillis());
    }

    public void forceEnd(boolean open) {
        plugin.state().set("end_open", open);
    }

    // ------------------------------------------------------------------ Меню

    public void open(Player p, Menu parent) {
        new Menu(p, 4, "&8Измерения") {
            @Override
            protected void build() {
                fill(Items.filler());
                boolean nOpen = netherOpen();
                List<String> nl = new ArrayList<>();
                if (nOpen) nl.add("&aОткрыт " + Text.date(plugin.state().getLong("nether_open_at", 0)));
                else {
                    nl.add("&cЗапечатан");
                    nl.add("&7Соберите " + FRAGMENTS + " осколков Незера из событий и тайников,");
                    nl.add("&7создайте Ключ Незера и проведите ритуал.");
                    long retry = plugin.state().getLong("nether_retry_at", 0);
                    if (retry > System.currentTimeMillis()) nl.add("&7Повтор ритуала через &f" + Text.duration(retry - System.currentTimeMillis()));
                }
                set(11, Items.of(Material.NETHERRACK).name("&4&lНезер").lore(nl).build());
                if (!nOpen) set(20, Items.of(Material.NETHER_STAR).name("&cСобрать Ключ Незера").lore("&7Нужны все 5 разных осколков в инвентаре", "&7ПКМ ключом — начать ритуал").build(),
                        c -> { viewer.closeInventory(); assembleKey(viewer); });
                List<String> el = new ArrayList<>();
                if (endOpen()) el.add("&aОткрыт");
                else {
                    el.add("&cЗапечатан");
                    long days = plugin.configs().get("events.yml").getLong("end.days-after-nether", 7);
                    long openedAt = plugin.state().getLong("nether_open_at", 0);
                    if (openedAt == 0) el.add("&7Условие 1: открыть Незер");
                    else {
                        long left = openedAt + days * 86_400_000L - System.currentTimeMillis();
                        el.add(left > 0 ? "&7Условие 1: ещё &f" + Text.duration(left) : "&aУсловие 1: выполнено");
                    }
                    el.add(collectiveDone() ? "&aУсловие 2: коллективное задание выполнено" : "&7Условие 2: коллективное задание");
                }
                set(15, Items.of(Material.END_STONE).name("&5&lЭнд").lore(el).build());
                int slot = 22;
                for (var g : goals.entrySet()) {
                    Material m = Material.matchMaterial(g.getKey());
                    long pr = progress(g.getKey());
                    set(slot++, Items.of(m).name("&e" + Items.materialName(m)).lore("&7Собрано: &f" + Math.min(pr, g.getValue()) + " / " + g.getValue(),
                            Text.bar(pr / (double) g.getValue(), 20, "&a", "&8"), "", pr >= g.getValue() ? "&aВыполнено" : "&eНажмите, чтобы внести из инвентаря").build(),
                            c -> { donate(viewer, m); refresh(); });
                    if (slot > 25) break;
                }
                if (parent != null) parent(parent);
                backButton(31);
            }
        }.open();
    }
}
