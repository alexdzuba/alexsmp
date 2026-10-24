package ru.unknownsmp.duels;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;
import ru.unknownsmp.core.Commands;
import ru.unknownsmp.core.ItemSpec;
import ru.unknownsmp.core.Keys;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.db.Database;
import ru.unknownsmp.gui.Items;
import ru.unknownsmp.gui.Menu;
import ru.unknownsmp.gui.PagedMenu;
import ru.unknownsmp.lobby.VoidGenerator;
import ru.unknownsmp.profile.Profile;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Режим «Дуэли»: отдельный мир с хабом и аренами «Древний колизей», бои 1 на 1 с наборами.
 * Инвентарь SMP на время дуэлей сохраняется в БД и полностью восстанавливается при выходе
 * (восстановление перезаписывает инвентарь, поэтому повторное применение безопасно и не создаёт дублей).
 */
public final class DuelService implements Module, Listener {
    private static final int BUILD_VERSION = 1;

    public record Kit(String id, String name, Material icon, List<ItemStack> items, ItemStack[] armor, ItemStack offhand, boolean build, String description) {}

    static final class Arena {
        final int id;
        final Location center, a, b;
        Match match;

        Arena(int id, Location center) {
            this.id = id;
            this.center = center;
            this.a = center.clone().add(-15, 1, 0.0);
            this.a.setYaw(-90);
            this.b = center.clone().add(15, 1, 0.0);
            this.b.setYaw(90);
        }
    }

    static final class Match {
        final Arena arena;
        final Player p1, p2;
        final Kit kit;
        boolean fighting;
        boolean ended;
        long started;
        BukkitTask task;

        Match(Arena arena, Player p1, Player p2, Kit kit) {
            this.arena = arena;
            this.p1 = p1;
            this.p2 = p2;
            this.kit = kit;
        }

        Player other(Player p) { return p.equals(p1) ? p2 : p1; }
    }

    record Challenge(UUID from, UUID to, String kit, long until) {}

    private final UnknownSMP plugin;
    private World world;
    private final List<Arena> arenas = new ArrayList<>();
    private final Map<String, Kit> kits = new LinkedHashMap<>();
    private final Map<UUID, Match> matches = new ConcurrentHashMap<>();
    private final Map<String, Deque<UUID>> queues = new ConcurrentHashMap<>();
    private final Map<UUID, Challenge> challenges = new ConcurrentHashMap<>();
    private final Map<UUID, byte[]> stashCache = new ConcurrentHashMap<>();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private int matchSeconds, countdown;

    public DuelService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "duels"; }
    @Override public String title() { return "Дуэли"; }
    @Override public String[] depends() { return new String[]{"database", "profiles", "lobby", "state"}; }

    @Override
    public void enable() {
        YamlConfiguration c = cfg();
        String name = c.getString("world", "usmp_duels");
        world = Bukkit.getWorld(name);
        if (world == null) {
            world = new WorldCreator(name).generator(new VoidGenerator()).generateStructures(false).createWorld();
        }
        if (world == null) throw new IllegalStateException("Не удалось создать мир дуэлей");
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        world.setGameRule(GameRule.NATURAL_REGENERATION, c.getBoolean("natural-regeneration", false));
        // На мирной сложности здоровье восстанавливается само независимо от правила, поэтому без регенерации ставим лёгкую (мобы и так отключены).
        world.setDifficulty(c.getBoolean("natural-regeneration", false) ? Difficulty.PEACEFUL : Difficulty.EASY);
        world.setTime(c.getLong("time", 13000));
        world.setStorm(false);
        int count = Math.max(1, Math.min(16, c.getInt("arenas", 4)));
        int built = (int) plugin.state().getLong("duels_built_count", 0);
        boolean wiped = built > 0 && world.getBlockAt(0, ArenaBuilder.Y - 1, 300).getType().isAir() && world.getBlockAt(0, ArenaBuilder.Y, 300).getType().isAir();
        if (wiped) plugin.getLogger().warning("Дуэли: мир арен был пересоздан — строю заново.");
        if (wiped || plugin.state().getLong("duels_built", 0) < BUILD_VERSION) {
            ArenaBuilder.hub(world);
            built = 0;
        }
        for (int i = 0; i < count; i++) {
            Location center = new Location(world, i * 150 + 0.5, ArenaBuilder.Y, 300.5);
            if (i >= built) {
                ArenaBuilder.arena(world, i * 150, 300);
                plugin.getLogger().info("Построена арена дуэлей #" + (i + 1));
            }
            arenas.add(new Arena(i + 1, center));
        }
        plugin.state().set("duels_built", BUILD_VERSION);
        plugin.state().set("duels_built_count", Math.max(built, count));
        world.setSpawnLocation(0, 101, 0);
        loadKits();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        registerCommands();
        Tasks.timer(20, 20, this::tickQueues);
    }

    @Override
    public void reload() {
        loadKits();
    }

    @Override
    public void disable() {
        // Завершаем бои ничьей и возвращаем инвентари SMP всем, кто в мире дуэлей.
        for (Match m : new ArrayList<>(matches.values())) finish(m, null, "server");
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isDuelWorld(p.getWorld())) restoreNow(p);
        }
    }

    private YamlConfiguration cfg() { return plugin.configs().get("duels.yml"); }

    private void loadKits() {
        kits.clear();
        ConfigurationSection s = cfg().getConfigurationSection("kits");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection k = s.getConfigurationSection(id);
            if (k == null) continue;
            List<ItemStack> items = new ArrayList<>();
            for (String spec : k.getStringList("items")) {
                int copies = 1;
                int star = spec.lastIndexOf('*');
                if (star > 0) {
                    try { copies = Math.max(1, Math.min(36, Integer.parseInt(spec.substring(star + 1).trim()))); } catch (NumberFormatException ignored) {}
                    spec = spec.substring(0, star);
                }
                ItemStack it = ItemSpec.parse(spec);
                if (it != null) for (int c = 0; c < copies; c++) items.add(tag(it.clone()));
            }
            ItemStack[] armor = new ItemStack[4];
            String[] slots = {"boots", "leggings", "chestplate", "helmet"};
            for (int i = 0; i < 4; i++) {
                ItemStack a = ItemSpec.parse(k.getString(slots[i]));
                armor[i] = a == null ? null : tag(a);
            }
            ItemStack off = ItemSpec.parse(k.getString("offhand"));
            Material icon = Material.matchMaterial(k.getString("icon", "IRON_SWORD"));
            kits.put(id, new Kit(id, k.getString("name", id), icon == null ? Material.IRON_SWORD : icon, items, armor, off == null ? null : tag(off),
                    k.getBoolean("build", false), k.getString("description", "")));
        }
    }

    private static ItemStack tag(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        m.getPersistentDataContainer().set(Keys.of("duel_item"), PersistentDataType.BYTE, (byte) 1);
        m.setUnbreakable(true);
        it.setItemMeta(m);
        return it;
    }

    private static boolean isDuelItem(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(Keys.of("duel_item"), PersistentDataType.BYTE);
    }

    public boolean isDuelWorld(World w) { return w != null && world != null && w.equals(world); }

    public Location hub() { return new Location(world, 0.5, ArenaBuilder.Y + 1, 0.5, 180, 0); }

    public boolean inMatch(Player p) { return matches.containsKey(p.getUniqueId()); }

    public Map<String, Kit> kits() { return kits; }

    // ------------------------------------------------------------------ Вход и выход

    /** Вход в режим дуэлей (только из лобби). Инвентарь SMP сохраняется в БД до очистки. */
    public void enter(Player p) {
        if (isDuelWorld(p.getWorld())) { openMenu(p); return; }
        if (!plugin.lobby().isLobby(p.getWorld())) {
            Msg.error(p, "duels.from-lobby", "В режим дуэлей можно войти только из лобби: /lobby");
            return;
        }
        if (plugin.combat() != null && plugin.combat().isTagged(p)) { Msg.error(p, "combat.blocked", "Нельзя во время боя."); return; }
        if (!busy.add(p.getUniqueId())) return;
        p.closeInventory();
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = inv.getContents();
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) copy[i] = contents[i] == null ? ItemStack.empty() : contents[i].clone();
        final byte[] data = ItemStack.serializeItemsAsBytes(copy);
        final int lvl = p.getLevel();
        final double prog = p.getExp();
        final double hp = p.getHealth();
        final int food = p.getFoodLevel();
        final String u = p.getUniqueId().toString();
        Tasks.then(plugin.db().tx(c -> {
            Long exists = Database.longVal(c, "SELECT COUNT(*) FROM duel_stash WHERE uuid=?", u);
            if (exists != null && exists > 0) {
                // Незавершённое хранилище (например, после сбоя) — сначала его нужно восстановить.
                throw new Database.TxAbort("duels.stash-exists", "Сначала будут восстановлены ваши вещи. Повторите вход.");
            }
            Database.update(c, "INSERT INTO duel_stash(uuid, data, xp_level, xp_progress, health, food, created_at) VALUES (?,?,?,?,?,?,?)",
                    u, data, lvl, prog, hp, food, System.currentTimeMillis());
            return null;
        }), v -> {
            busy.remove(p.getUniqueId());
            if (!p.isOnline()) {
                // Игрок вышел, пока сохранялись вещи: его инвентарь не тронут — удаляем хранилище.
                plugin.db().tx(c -> Database.update(c, "DELETE FROM duel_stash WHERE uuid=?", u));
                return;
            }
            stashCache.put(p.getUniqueId(), data);
            inv.clear();
            p.setLevel(0);
            p.setExp(0);
            p.saveData();
            plugin.logs().log(p, "duels", "enter", p.getLocation(), "stash " + data.length + " bytes");
            p.teleportAsync(hub(), PlayerTeleportEvent.TeleportCause.PLUGIN).thenRun(() -> Tasks.sync(() -> {
                if (!p.isOnline()) return;
                prepareHub(p);
                Msg.title(p, "&c&lДУЭЛИ", "&7Выберите набор и найдите соперника", 5, 50, 15);
                Msg.send(p, "duels.welcome", "&fВаши вещи SMP надёжно сохранены и вернутся при выходе. &e/duels&f — меню, &e/lobby&f — выход.");
            }));
        }, err -> {
            busy.remove(p.getUniqueId());
            if (err instanceof Database.TxAbort ab) {
                Msg.error(p, ab.key, ab.def);
                restoreFromDb(p, null);
            } else {
                Msg.error(p, "duels.error", "Не удалось сохранить вещи — вход в дуэли отменён. Ваши вещи на месте.");
            }
        });
    }

    /** Выход из дуэлей в лобби. */
    public void leave(Player p) {
        if (inMatch(p)) { Msg.error(p, "duels.in-match", "Нельзя выйти во время боя."); return; }
        leaveQueues(p);
        p.getInventory().clear();
        restoreFromDb(p, () -> plugin.lobby().toLobby(p));
    }

    private void prepareHub(Player p) {
        PlayerInventory inv = p.getInventory();
        inv.clear();
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(max == null ? 20 : max.getValue());
        p.setFoodLevel(20);
        p.setSaturation(10);
        p.setFireTicks(0);
        if (p.getGameMode() == GameMode.SURVIVAL) p.setGameMode(GameMode.ADVENTURE);
        inv.setItem(0, hubItem(Material.DIAMOND_SWORD, "&c&lНайти бой &7(ПКМ)", "queue"));
        inv.setItem(4, hubItem(Material.PLAYER_HEAD, "&e&lВызвать игрока &7(ПКМ)", "challenge"));
        inv.setItem(6, hubItem(Material.BOOK, "&b&lСтатистика &7(ПКМ)", "stats"));
        inv.setItem(8, hubItem(Material.RED_BED, "&7&lВ лобби &7(ПКМ)", "leave"));
    }

    private ItemStack hubItem(Material m, String name, String action) {
        ItemStack it = tag(Items.of(m).name(name).build());
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().remove(Keys.MENU_ITEM);
        meta.getPersistentDataContainer().set(Keys.of("duel_action"), PersistentDataType.STRING, action);
        it.setItemMeta(meta);
        return it;
    }

    /** Синхронное восстановление из кэша (выход с сервера/остановка) + асинхронное удаление записи. */
    private void restoreNow(Player p) {
        byte[] data = stashCache.remove(p.getUniqueId());
        if (data == null) return;
        apply(p, data, -1, -1);
        final String u = p.getUniqueId().toString();
        plugin.db().tx(c -> Database.update(c, "DELETE FROM duel_stash WHERE uuid=?", u));
    }

    /** Восстановление из БД: сначала читаем, применяем (перезапись), сохраняем игрока, затем удаляем запись. */
    private void restoreFromDb(Player p, Runnable after) {
        final String u = p.getUniqueId().toString();
        Tasks.then(plugin.db().query(c -> {
            try (PreparedStatement ps = Database.ps(c, "SELECT data, xp_level, xp_progress FROM duel_stash WHERE uuid=?", u);
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Object[]{rs.getBytes(1), rs.getInt(2), rs.getDouble(3)};
            }
        }), row -> {
            if (!p.isOnline()) return;
            if (row != null) {
                apply(p, (byte[]) row[0], (Integer) row[1], (Double) row[2]);
                stashCache.remove(p.getUniqueId());
                plugin.db().tx(c -> Database.update(c, "DELETE FROM duel_stash WHERE uuid=?", u));
                plugin.logs().log(p, "duels", "restore", p.getLocation(), "ok");
            }
            if (after != null) after.run();
        }, err -> {
            Msg.error(p, "duels.restore-error", "Не удалось восстановить вещи прямо сейчас. Они в безопасности в БД — попробуйте ещё раз.");
            plugin.logs().log(p, "duels", "restore_error", null, err.getMessage());
        });
    }

    private void apply(Player p, byte[] data, int lvl, double prog) {
        ItemStack[] items = ItemStack.deserializeItemsFromBytes(data);
        PlayerInventory inv = p.getInventory();
        inv.clear();
        ItemStack[] target = new ItemStack[inv.getSize()];
        for (int i = 0; i < target.length && i < items.length; i++) target[i] = items[i] == null || items[i].isEmpty() ? null : items[i];
        inv.setContents(target);
        if (lvl >= 0) { p.setLevel(lvl); p.setExp((float) Math.max(0, Math.min(0.999, prog))); }
        p.saveData();
    }

    // ------------------------------------------------------------------ Очереди и вызовы

    private void joinQueue(Player p, Kit k) {
        if (!isDuelWorld(p.getWorld())) { Msg.error(p, "duels.not-in", "Сначала войдите в режим дуэлей."); return; }
        if (inMatch(p)) return;
        Deque<UUID> q = queues.computeIfAbsent(k.id(), x -> new ArrayDeque<>());
        if (q.contains(p.getUniqueId())) {
            q.remove(p.getUniqueId());
            Msg.send(p, "duels.queue-left", "&7Вы вышли из очереди {k}&7.", "k", k.name());
            return;
        }
        leaveQueues(p);
        q.add(p.getUniqueId());
        Msg.send(p, "duels.queue-joined", "&fВы в очереди &r{k}&f. Ожидание соперника... ({n} в очереди)", "k", k.name(), "n", q.size());
    }

    private void leaveQueues(Player p) {
        for (Deque<UUID> q : queues.values()) q.remove(p.getUniqueId());
    }

    private void tickQueues() {
        for (var e : queues.entrySet()) {
            Deque<UUID> q = e.getValue();
            q.removeIf(u -> { Player p = Bukkit.getPlayer(u); return p == null || !isDuelWorld(p.getWorld()) || inMatch(p); });
            while (q.size() >= 2) {
                Arena a = freeArena();
                if (a == null) break;
                Player p1 = Bukkit.getPlayer(q.poll());
                Player p2 = Bukkit.getPlayer(q.poll());
                Kit k = kits.get(e.getKey());
                if (p1 == null || p2 == null || k == null) continue;
                start(a, p1, p2, k);
            }
            for (UUID u : q) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) Msg.actionBar(p, "&eПоиск соперника: " + Text.strip(kits.getOrDefault(e.getKey(), kits.values().iterator().next()).name()) + " &7(" + q.size() + " в очереди)");
            }
        }
        long now = System.currentTimeMillis();
        challenges.values().removeIf(c -> c.until() < now);
    }

    private Arena freeArena() {
        for (Arena a : arenas) if (a.match == null) return a;
        return null;
    }

    private void challenge(Player p, Player t, Kit k) {
        if (t == null || t.equals(p) || !isDuelWorld(t.getWorld())) {
            Msg.error(p, "duels.target-not-in", "Игрок должен находиться в режиме дуэлей.");
            return;
        }
        if (inMatch(t)) { Msg.error(p, "duels.target-busy", "Игрок сейчас в бою."); return; }
        challenges.put(t.getUniqueId(), new Challenge(p.getUniqueId(), t.getUniqueId(), k.id(), System.currentTimeMillis() + 60_000));
        Msg.ok(p, "duels.challenge-sent", "Вызов отправлен {t} ({k}&a).", "t", t.getName(), "k", k.name());
        Msg.send(t, "duels.challenge", "&e{p}&f вызывает вас на дуэль: &r{k}&f. &a/duel accept &fили &c/duel deny &7(60 сек.)", "p", p.getName(), "k", k.name());
        Msg.sound(t, Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.2f);
    }

    private void accept(Player t) {
        Challenge c = challenges.remove(t.getUniqueId());
        if (c == null || c.until() < System.currentTimeMillis()) { Msg.error(t, "duels.no-challenge", "Нет активных вызовов."); return; }
        Player p = Bukkit.getPlayer(c.from());
        Kit k = kits.get(c.kit());
        if (p == null || k == null || !isDuelWorld(p.getWorld()) || inMatch(p) || inMatch(t)) {
            Msg.error(t, "duels.challenge-invalid", "Вызов больше недействителен.");
            return;
        }
        Arena a = freeArena();
        if (a == null) { Msg.error(t, "duels.no-arena", "Все арены заняты, попробуйте через минуту."); return; }
        leaveQueues(p);
        leaveQueues(t);
        start(a, p, t, k);
    }

    // ------------------------------------------------------------------ Бой

    private void start(Arena a, Player p1, Player p2, Kit k) {
        Match m = new Match(a, p1, p2, k);
        a.match = m;
        matches.put(p1.getUniqueId(), m);
        matches.put(p2.getUniqueId(), m);
        for (Player p : new Player[]{p1, p2}) {
            p.closeInventory();
            giveKit(p, k);
            p.setGameMode(GameMode.SURVIVAL);
        }
        p1.teleport(a.a);
        p2.teleport(a.b);
        Msg.send(p1, "duels.vs", "&fСоперник: &c{o} &7({k}&7, арена #{a})", "o", p2.getName(), "k", k.name(), "a", a.id);
        Msg.send(p2, "duels.vs", "&fСоперник: &c{o} &7({k}&7, арена #{a})", "o", p1.getName(), "k", k.name(), "a", a.id);
        final int[] left = {countdown()};
        m.task = Tasks.timer(0, 20, () -> {
            if (m.ended) return;
            if (!m.fighting) {
                if (left[0] > 0) {
                    for (Player p : new Player[]{p1, p2}) {
                        Msg.title(p, "&e" + left[0], "&7Приготовьтесь", 0, 22, 0);
                        Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
                    }
                    left[0]--;
                } else {
                    m.fighting = true;
                    m.started = System.currentTimeMillis();
                    for (Player p : new Player[]{p1, p2}) {
                        Msg.title(p, "&c&lБОЙ!", "", 0, 20, 10);
                        Msg.sound(p, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.5f);
                    }
                }
                return;
            }
            long elapsed = (System.currentTimeMillis() - m.started) / 1000;
            long remain = matchSeconds() - elapsed;
            for (Player p : new Player[]{p1, p2}) Msg.actionBar(p, "&7Осталось: &f" + Text.duration(remain * 1000));
            if (remain <= 0) finish(m, null, "time");
        });
    }

    private int countdown() { return cfg().getInt("countdown-seconds", 5); }

    private int matchSeconds() { return cfg().getInt("match-seconds", 300); }

    private void giveKit(Player p, Kit k) {
        PlayerInventory inv = p.getInventory();
        inv.clear();
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        for (ItemStack it : k.items()) inv.addItem(it.clone());
        ItemStack[] armor = new ItemStack[4];
        for (int i = 0; i < 4; i++) armor[i] = k.armor()[i] == null ? null : k.armor()[i].clone();
        inv.setArmorContents(armor);
        if (k.offhand() != null) inv.setItemInOffHand(k.offhand().clone());
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(max == null ? 20 : max.getValue());
        p.setFoodLevel(20);
        p.setSaturation(20);
        p.setFireTicks(0);
        p.setFallDistance(0);
    }

    /** Завершение боя. winner == null — ничья. */
    private void finish(Match m, Player winner, String reason) {
        if (m.ended) return;
        m.ended = true;
        m.fighting = false;
        if (m.task != null) m.task.cancel();
        matches.remove(m.p1.getUniqueId());
        matches.remove(m.p2.getUniqueId());
        Player loser = winner == null ? null : m.other(winner);
        if (winner != null) {
            Msg.title(winner, "&a&lПОБЕДА", "&7над " + loser.getName(), 5, 50, 15);
            if (loser.isOnline()) Msg.title(loser, "&c&lПОРАЖЕНИЕ", "&7победил " + winner.getName(), 5, 50, 15);
            Msg.sound(winner, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
            rate(winner, loser);
            rateKit(winner, loser, m.kit.id());
            plugin.profiles().addStat(winner, "duel_wins", 1);
            plugin.profiles().addStat(loser, "duel_losses", 1);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (isDuelWorld(p.getWorld()) && p != winner && p != loser)
                    Msg.send(p, "duels.result", "&c{w}&7 победил &f{l}&7 ({k}&7)", "w", winner.getName(), "l", loser.getName(), "k", m.kit.name());
            }
        } else {
            for (Player p : new Player[]{m.p1, m.p2}) if (p.isOnline()) Msg.title(p, "&e&lНИЧЬЯ", reason.equals("time") ? "&7Время вышло" : "", 5, 50, 15);
        }
        plugin.logs().log(winner, "duels", "match_end", m.arena.center, m.p1.getName() + " vs " + m.p2.getName() + " kit=" + m.kit.id()
                + " winner=" + (winner == null ? "-" : winner.getName()) + " reason=" + reason);
        Runnable back = () -> {
            for (Player p : new Player[]{m.p1, m.p2}) {
                if (!p.isOnline() || !isDuelWorld(p.getWorld())) continue;
                p.teleport(hub());
                prepareHub(p);
            }
            resetArena(m.arena);
            m.arena.match = null;
        };
        if (reason.equals("server")) back.run();
        else Tasks.later(60, back);
    }

    /** Убрать постройки, жидкости и брошенные сущности, восстановить арену. */
    private void resetArena(Arena a) {
        World w = a.center.getWorld();
        int cx = a.center.getBlockX(), cz = a.center.getBlockZ();
        for (Entity en : w.getNearbyEntities(a.center, 32, 40, 32)) {
            if (en instanceof Player) continue;
            en.remove();
        }
        for (int dx = -21; dx <= 21; dx++) for (int dz = -21; dz <= 21; dz++) {
            if (dx * dx + dz * dz > 21 * 21) continue;
            for (int y = ArenaBuilder.Y + 1; y <= ArenaBuilder.Y + 40; y++) {
                var b = w.getBlockAt(cx + dx, y, cz + dz);
                if (!b.getType().isAir()) b.setType(Material.AIR, false);
            }
        }
        ArenaBuilder.arena(w, cx, cz);
    }

    // ------------------------------------------------------------------ Тиры (как на MCTiers)

    private static final String[] TIERS = {"LT5", "HT5", "LT4", "HT4", "LT3", "HT3", "LT2", "HT2", "LT1", "HT1"};

    /** Тир по рейтингу набора. Пороги настраиваются в duels.yml → tiers. */
    public String tierOf(long rating) {
        List<Integer> th = cfg().getIntegerList("tiers");
        if (th.size() != TIERS.length) th = List.of(0, 1030, 1080, 1140, 1210, 1290, 1380, 1480, 1590, 1710);
        String t = TIERS[0];
        for (int i = 0; i < TIERS.length; i++) if (rating >= th.get(i)) t = TIERS[i];
        return t;
    }

    public static String tierColor(String tier) {
        return switch (tier) {
            case "HT1" -> "&6&l";
            case "LT1" -> "&6";
            case "HT2", "LT2" -> "&d";
            case "HT3", "LT3" -> "&b";
            case "HT4", "LT4" -> "&a";
            default -> "&7";
        };
    }

    /** Лучший тир игрока среди наборов, в которых он сыграл хотя бы один бой. null — ещё не играл. */
    public String bestTier(Player p) {
        Profile pr = plugin.profiles().get(p);
        if (pr == null) return null;
        long best = -1;
        for (String k : kits.keySet()) {
            Long r = pr.stats.get("duel_rating_" + k);
            if (r != null && r > best) best = r;
        }
        return best < 0 ? null : tierOf(best);
    }

    public String tierTag(Player p) {
        String t = bestTier(p);
        return t == null ? "" : tierColor(t) + t + " ";
    }

    private void rateKit(Player w, Player l, String kit) {
        Profile pw = plugin.profiles().get(w), pl = plugin.profiles().get(l);
        if (pw == null || pl == null) return;
        String key = "duel_rating_" + kit;
        long rw = pw.stats.getOrDefault(key, 1000L), rl = pl.stats.getOrDefault(key, 1000L);
        double ew = 1.0 / (1 + Math.pow(10, (rl - rw) / 400.0));
        long d = Math.max(1, Math.round(32 * (1 - ew)));
        String before = tierOf(rw);
        plugin.profiles().addStat(w, key, (pw.stats.containsKey(key) ? 0 : 1000) + d);
        plugin.profiles().addStat(l, key, (pl.stats.containsKey(key) ? 0 : 1000) - Math.min(d, rl));
        String after = tierOf(pw.stat(key));
        if (!after.equals(before)) {
            Msg.broadcast("duels.tier-up", "&6{p}&f получил тир {t}&f в наборе {k}&f!", "p", w.getName(), "t", tierColor(after) + after, "k", kits.get(kit).name());
            if (plugin.display() != null) plugin.display().refresh(w);
        }
        if (l.isOnline() && plugin.display() != null) plugin.display().refresh(l);
    }

    private void rate(Player w, Player l) {
        Profile pw = plugin.profiles().get(w), pl = plugin.profiles().get(l);
        if (pw == null || pl == null) return;
        long rw = pw.stats.getOrDefault("duel_rating", 1000L), rl = pl.stats.getOrDefault("duel_rating", 1000L);
        double ew = 1.0 / (1 + Math.pow(10, (rl - rw) / 400.0));
        int k = 32;
        long dw = Math.round(k * (1 - ew));
        if (dw < 1) dw = 1;
        plugin.profiles().addStat(w, "duel_rating", (pw.stats.containsKey("duel_rating") ? 0 : 1000) + dw);
        plugin.profiles().addStat(l, "duel_rating", (pl.stats.containsKey("duel_rating") ? 0 : 1000) - Math.min(dw, rl));
        Msg.send(w, "duels.rating", "&7Рейтинг: &a+{d} &7→ &f{r}", "d", dw, "r", pw.stat("duel_rating"));
        if (l.isOnline()) Msg.send(l, "duels.rating-loss", "&7Рейтинг: &c-{d} &7→ &f{r}", "d", dw, "r", pl.stat("duel_rating"));
    }

    // ------------------------------------------------------------------ События

    @EventHandler(priority = EventPriority.LOW)
    public void damage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !isDuelWorld(p.getWorld())) return;
        Match m = matches.get(p.getUniqueId());
        if (m == null || !m.fighting) {
            e.setCancelled(true);
            if (e.getCause() == EntityDamageEvent.DamageCause.VOID) Tasks.sync(() -> p.teleport(m == null ? hub() : p.equals(m.p1) ? m.arena.a : m.arena.b));
            return;
        }
        if (e instanceof EntityDamageByEntityEvent ed) {
            Entity d = ed.getDamager();
            Player attacker = d instanceof Player pp ? pp : d instanceof Projectile pr && pr.getShooter() instanceof Player sp ? sp : null;
            // Чужой игрок бить не может; взрывы кристаллов, якорей и TNT в бою разрешены.
            if (attacker != null && !attacker.equals(m.other(p))) { e.setCancelled(true); return; }
        }
        if (e.getFinalDamage() >= p.getHealth()) {
            // Тотем в руке — даём сработать по-ванильному.
            if (p.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                    || p.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING) return;
            e.setCancelled(true);
            p.getWorld().strikeLightningEffect(p.getLocation());
            finish(m, m.other(p), "kill");
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void move(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!isDuelWorld(p.getWorld())) return;
        Match m = matches.get(p.getUniqueId());
        if (m != null && !m.fighting && !m.ended) {
            Location f = e.getFrom(), t = e.getTo();
            if (f.getX() != t.getX() || f.getZ() != t.getZ()) {
                Location back = f.clone();
                back.setYaw(t.getYaw());
                back.setPitch(t.getPitch());
                e.setTo(back);
            }
            return;
        }
        if (m != null && e.getTo().distanceSquared(m.arena.center) > 24 * 24) {
            e.setTo(p.equals(m.p1) ? m.arena.a : m.arena.b);
        } else if (m == null && e.getTo().getY() < ArenaBuilder.Y - 20) {
            e.setTo(hub());
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void interact(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!isDuelWorld(p.getWorld())) return;
        ItemStack it = e.getItem();
        if (it != null && it.hasItemMeta()) {
            String action = it.getItemMeta().getPersistentDataContainer().get(Keys.of("duel_action"), PersistentDataType.STRING);
            if (action != null && (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK)) {
                e.setCancelled(true);
                switch (action) {
                    case "queue" -> new KitMenu(p, false, null).open();
                    case "challenge" -> new ChallengeMenu(p).open();
                    case "stats" -> stats(p, p);
                    case "leave" -> leave(p);
                    default -> {}
                }
                return;
            }
        }
        if (!inMatch(p) && e.getAction() == Action.PHYSICAL) return;
        if (!inMatch(p) && e.getClickedBlock() != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void place(BlockPlaceEvent e) {
        if (!isDuelWorld(e.getBlock().getWorld())) return;
        if (e.getPlayer().getGameMode() == GameMode.CREATIVE && plugin.roles().has(e.getPlayer(), "usmp.admin.build")) return;
        if (canBuild(e.getPlayer(), e.getBlock().getLocation())) return;
        e.setCancelled(true);
    }

    /** Стройка разрешена только в бою с набором build=true, внутри арены и выше пола. */
    private boolean canBuild(Player p, Location l) {
        Match m = matches.get(p.getUniqueId());
        if (m == null || !m.fighting || !m.kit.build()) return false;
        if (l.getBlockY() <= ArenaBuilder.Y) return false;
        double dx = l.getX() - m.arena.center.getX(), dz = l.getZ() - m.arena.center.getZ();
        return dx * dx + dz * dz <= 20 * 20;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void bucket(org.bukkit.event.player.PlayerBucketEmptyEvent e) {
        if (!isDuelWorld(e.getBlock().getWorld())) return;
        if (!canBuild(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void explodeEntity(org.bukkit.event.entity.EntityExplodeEvent e) {
        if (isDuelWorld(e.getLocation().getWorld())) e.blockList().clear();
    }

    @EventHandler(priority = EventPriority.LOW)
    public void explodeBlock(org.bukkit.event.block.BlockExplodeEvent e) {
        if (isDuelWorld(e.getBlock().getWorld())) e.blockList().clear();
    }

    @EventHandler(priority = EventPriority.LOW)
    public void brk(BlockBreakEvent e) {
        if (!isDuelWorld(e.getBlock().getWorld())) return;
        if (e.getPlayer().getGameMode() == GameMode.CREATIVE && plugin.roles().has(e.getPlayer(), "usmp.admin.build")) return;
        if (canBuild(e.getPlayer(), e.getBlock().getLocation())) { e.setDropItems(false); return; }
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void drop(PlayerDropItemEvent e) {
        if (isDuelWorld(e.getPlayer().getWorld())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void pickup(EntityPickupItemEvent e) {
        if (isDuelWorld(e.getEntity().getWorld())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void food(FoodLevelChangeEvent e) {
        // Голод и насыщение в дуэлях отключены: еда всегда полная, естественной регенерации нет (см. natural-regeneration в duels.yml).
        if (isDuelWorld(e.getEntity().getWorld())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void invClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !isDuelWorld(p.getWorld()) || inMatch(p)) return;
        if (e.getView().getTopInventory().getHolder(false) instanceof Menu) return;
        e.setCancelled(true);
    }

    /** Любой выход из мира дуэлей — изъятие предметов дуэлей и восстановление вещей SMP. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void changedWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (isDuelWorld(e.getFrom()) && !isDuelWorld(p.getWorld())) {
            leaveQueues(p);
            purgeDuelItems(p);
            if (stashCache.containsKey(p.getUniqueId())) restoreNow(p);
            else restoreFromDb(p, null);
        }
    }

    private void purgeDuelItems(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) if (isDuelItem(inv.getItem(i))) inv.setItem(i, null);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void quit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        challenges.remove(p.getUniqueId());
        leaveQueues(p);
        Match m = matches.get(p.getUniqueId());
        if (m != null) finish(m, m.other(p), "quit");
        if (isDuelWorld(p.getWorld())) {
            p.getInventory().clear();
            if (stashCache.containsKey(p.getUniqueId())) restoreNow(p);
            p.teleport(plugin.lobby().spawn());
        }
    }

    /** Восстановление после сбоя: если запись хранилища осталась — вернуть вещи SMP. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Tasks.later(20, () -> {
            if (!p.isOnline() || isDuelWorld(p.getWorld())) return;
            purgeDuelItems(p);
            restoreFromDb(p, null);
        });
    }

    // ------------------------------------------------------------------ Меню и команды

    public void openMenu(Player p) {
        if (!isDuelWorld(p.getWorld())) { enter(p); return; }
        new Menu(p, 3, "&8Дуэли") {
            @Override
            protected void build() {
                fill(Items.filler());
                set(10, Items.of(Material.DIAMOND_SWORD).name("&c&lНайти бой").lore("&7Очередь по выбранному набору").build(), c -> new KitMenu(p, false, null).open());
                set(12, Items.of(Material.PLAYER_HEAD).name("&e&lВызвать игрока").lore("&7Вызов на дуэль игроку в хабе").build(), c -> new ChallengeMenu(p).open());
                set(14, Items.of(Material.BOOK).name("&b&lСтатистика").build(), c -> stats(p, p));
                set(16, Items.of(Material.RED_BED).name("&7&lВ лобби").lore("&7Ваши вещи SMP вернутся").build(), c -> { p.closeInventory(); leave(p); });
            }
        }.open();
    }

    private final class KitMenu extends Menu {
        private final boolean forChallenge;
        private final Player target;

        KitMenu(Player p, boolean forChallenge, Player target) {
            super(p, 4, forChallenge ? "&8Набор для вызова" : "&8Выбор набора");
            this.forChallenge = forChallenge;
            this.target = target;
        }

        @Override
        protected void build() {
            fill(Items.filler());
            int slot = 10;
            for (Kit k : kits.values()) {
                Deque<UUID> q = queues.get(k.id());
                boolean in = q != null && q.contains(viewer.getUniqueId());
                List<String> lore = new ArrayList<>();
                if (!k.description().isEmpty()) { lore.add("&f" + k.description()); lore.add(""); }
                Profile vp = plugin.profiles().get(viewer);
                Long myRating = vp == null ? null : vp.stats.get("duel_rating_" + k.id());
                String myTier = myRating == null ? null : tierOf(myRating);
                lore.add("&7Ваш тир: " + (myTier == null ? "&8нет боёв" : tierColor(myTier) + myTier + " &7(" + myRating + ")"));
                Map<String, Integer> counts = new LinkedHashMap<>();
                for (ItemStack it : k.items()) counts.merge(Items.itemName(it), it.getAmount(), Integer::sum);
                int shown = 0;
                for (var en : counts.entrySet()) {
                    if (shown++ >= 8) { lore.add("&8…и ещё " + (counts.size() - 8)); break; }
                    lore.add("&7• " + en.getValue() + "x " + en.getKey());
                }
                if (k.build()) lore.add("&eМожно строить на арене");
                lore.add("");
                if (!forChallenge) lore.add("&7В очереди: &f" + (q == null ? 0 : q.size()));
                lore.add(forChallenge ? "&eНажмите, чтобы вызвать" : in ? "&cНажмите, чтобы выйти из очереди" : "&aНажмите, чтобы встать в очередь");
                set(slot++, Items.of(k.icon()).name(k.name()).glow(in).lore(lore).build(), c -> {
                    viewer.closeInventory();
                    if (forChallenge) challenge(viewer, target, k);
                    else joinQueue(viewer, k);
                });
                if (slot == 17) slot = 19; if (slot > 25) break;
            }
            closeButton(31);
        }
    }

    private final class ChallengeMenu extends PagedMenu {
        ChallengeMenu(Player p) {
            super(p, "&8Кого вызвать?");
        }

        @Override
        protected List<Entry> entries() {
            List<Entry> out = new ArrayList<>();
            for (Player o : world.getPlayers()) {
                if (o.equals(viewer)) continue;
                Profile pr = plugin.profiles().get(o);
                out.add(new Entry(Items.head(o).name("&f" + o.getName()).lore("&7Рейтинг: &e" + (pr == null ? 1000 : pr.stats.getOrDefault("duel_rating", 1000L)),
                        inMatch(o) ? "&cВ бою" : "&aСвободен").build(), c -> new KitMenu(viewer, true, o).open()));
            }
            return out;
        }
    }

    private void stats(Player viewer, Player target) {
        Profile tp = plugin.profiles().get(target);
        if (tp != null) {
            StringBuilder sb = new StringBuilder();
            for (Kit k : kits.values()) {
                Long r = tp.stats.get("duel_rating_" + k.id());
                if (r == null) continue;
                String t = tierOf(r);
                sb.append(Text.strip(k.name())).append(": ").append(tierColor(t)).append(t).append("&7 (").append(r).append(")  ");
            }
            if (sb.length() > 0) Msg.send(viewer, "duels.tiers", "&7Тиры: {t}", "t", sb.toString());
        }
        Profile pr = plugin.profiles().get(target);
        if (pr == null) return;
        long w = pr.stat("duel_wins"), l = pr.stat("duel_losses");
        Msg.send(viewer, "duels.stats", "&fДуэли {p}: &aпобед {w}&7, &cпоражений {l}&7, рейтинг &e{r}&7, винрейт &f{wr}%",
                "p", target.getName(), "w", w, "l", l, "r", pr.stats.getOrDefault("duel_rating", 1000L),
                "wr", w + l == 0 ? 0 : Math.round(100.0 * w / (w + l)));
    }

    private void registerCommands() {
        plugin.commands().add("duels").player().perm("usmp.player.duels").module("duels").cat("Дуэли").usage("/duels")
                .desc("Войти в режим дуэлей / меню дуэлей").exec((s, a) -> openMenu((Player) s));
        plugin.commands().add("duel").player().perm("usmp.player.duels").module("duels").cat("Дуэли").cooldown(1000)
                .usage("/duel <игрок> [набор] | accept | deny | leave | stats [игрок]").desc("Вызов на дуэль").exec((s, a) -> {
                    Player p = (Player) s;
                    if (a.length == 0) { openMenu(p); return; }
                    switch (a[0].toLowerCase()) {
                        case "accept" -> accept(p);
                        case "deny" -> {
                            Challenge c = challenges.remove(p.getUniqueId());
                            if (c != null) {
                                Player from = Bukkit.getPlayer(c.from());
                                if (from != null) Msg.send(from, "duels.denied", "&c{p} отклонил вызов.", "p", p.getName());
                            }
                            Msg.send(p, "duels.deny-ok", "&7Вызов отклонён.");
                        }
                        case "leave" -> leave(p);
                        case "stats" -> {
                            Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : p;
                            if (t == null) { Msg.error(p, "player.offline", "Игрок не в сети."); return; }
                            stats(p, t);
                        }
                        default -> {
                            Player t = Bukkit.getPlayerExact(a[0]);
                            if (!isDuelWorld(p.getWorld())) { Msg.error(p, "duels.not-in", "Сначала войдите в режим дуэлей: /duels"); return; }
                            if (a.length > 1) {
                                Kit k = kits.get(a[1].toLowerCase());
                                if (k == null) { Msg.error(p, "duels.no-kit", "Набор не найден: {k}", "k", String.join(", ", kits.keySet())); return; }
                                challenge(p, t, k);
                            } else if (t != null) new KitMenu(p, true, t).open();
                            else Msg.error(p, "player.offline", "Игрок не в сети.");
                        }
                    }
                }).tab((s, a) -> {
                    if (a.length == 1) {
                        List<String> l = new ArrayList<>(List.of("accept", "deny", "leave", "stats"));
                        l.addAll(Commands.players(a[0]));
                        return l;
                    }
                    if (a.length == 2) return new ArrayList<>(kits.keySet());
                    return List.of();
                });
    }

    /** Для мониторинга. */
    public Map<String, Object> status() {
        Map<String, Object> m = new HashMap<>();
        m.put("matches", matches.size() / 2);
        m.put("arenas", arenas.size());
        m.put("in_world", world == null ? 0 : world.getPlayers().size());
        return m;
    }
}
