package ru.unknownsmp.npc;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import ru.unknownsmp.core.Keys;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.gui.ConfirmMenu;
import ru.unknownsmp.gui.Items;
import ru.unknownsmp.gui.Menu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NPC средствами Paper: ванильные жители, странствующие торговцы и манекены с обликом игрока.
 * Сущности не сохраняются в мире — создаются из npcs.yml при загрузке чанка, защищены от урона.
 */
public final class NpcService implements Module, Listener {
    public record Trade(String item, long price) {}

    public record Npc(String id, String name, String role, String type, String skin, String profession, String locSpec,
                      String dialogue, String action, List<Trade> trades, String shopCategory) {}

    private final UnknownSMP plugin;
    private final Map<String, Npc> npcs = new LinkedHashMap<>();
    private final Map<String, Entity> entities = new ConcurrentHashMap<>();
    private final Map<UUID, Long> clickCd = new ConcurrentHashMap<>();
    private DialogueService dialogues;

    public NpcService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "npc"; }
    @Override public String title() { return "NPC и диалоги"; }
    @Override public String[] depends() { return new String[]{"profiles", "lobby", "world"}; }

    @Override
    public void enable() {
        dialogues = new DialogueService(plugin, this);
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Tasks.later(40, this::spawnAll);
        Tasks.timer(20 * 30, 20 * 30, this::ensure);
        Tasks.timer(10, 10, this::lookAt);
    }

    @Override
    public void disable() {
        for (Entity e : entities.values()) if (e.isValid()) e.remove();
        entities.clear();
    }

    @Override
    public void reload() {
        disable();
        load();
        dialogues.reload();
        spawnAll();
    }

    public DialogueService dialogues() { return dialogues; }

    private void load() {
        npcs.clear();
        ConfigurationSection s = plugin.configs().get("npcs.yml").getConfigurationSection("npcs");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection n = s.getConfigurationSection(id);
            List<Trade> trades = new ArrayList<>();
            for (Map<?, ?> t : n.getMapList("trades")) {
                Object item = t.get("item");
                Object price = t.get("price");
                if (item != null && price != null) trades.add(new Trade(item.toString(), Long.parseLong(price.toString())));
            }
            npcs.put(id, new Npc(id, n.getString("name", id), n.getString("role", "SERVICE").toUpperCase(Locale.ROOT),
                    n.getString("appearance.type", "VILLAGER").toUpperCase(Locale.ROOT), n.getString("appearance.skin"),
                    n.getString("appearance.profession", "NONE"), n.getString("location"), n.getString("dialogue"), n.getString("action"),
                    trades, n.getString("shop-category")));
        }
    }

    public Map<String, Npc> all() { return npcs; }

    /** Позиция: "мир;x;y;z;yaw;pitch" или относительно спавна SMP: "@spawn;dx;dy;dz;yaw". */
    public Location location(Npc n) {
        if (n.locSpec() == null) return null;
        if (n.locSpec().startsWith("@anchor:")) {
            return ru.unknownsmp.world.Anchors.get(n.locSpec().substring(8).trim());
        }
        if (n.locSpec().startsWith("@spawn")) {
            String[] p = n.locSpec().split(";");
            Location s = plugin.world().spawn().clone();
            try {
                s.add(Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]));
                if (p.length > 4) s.setYaw(Float.parseFloat(p[4]));
            } catch (Exception e) {
                return null;
            }
            return s;
        }
        return Text.loc(n.locSpec());
    }

    // ------------------------------------------------------------------ Сущности

    private void spawnAll() {
        for (Npc n : npcs.values()) {
            Location l = location(n);
            if (l == null || l.getWorld() == null) continue;
            if (l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) spawn(n, l);
        }
    }

    private void ensure() {
        for (Npc n : npcs.values()) {
            Entity e = entities.get(n.id());
            if (e != null && e.isValid()) continue;
            Location l = location(n);
            if (l != null && l.getWorld() != null && l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) spawn(n, l);
        }
    }

    @EventHandler
    public void chunk(ChunkLoadEvent e) {
        Chunk c = e.getChunk();
        // Удаляем «осиротевшие» NPC от прошлых запусков (если сохранились).
        for (Entity ent : c.getEntities()) {
            String id = ent.getPersistentDataContainer().get(Keys.NPC_ID, PersistentDataType.STRING);
            if (id != null && entities.get(id) != ent) ent.remove();
        }
        for (Npc n : npcs.values()) {
            Location l = location(n);
            if (l == null || l.getWorld() == null || !l.getWorld().equals(c.getWorld())) continue;
            if ((l.getBlockX() >> 4) == c.getX() && (l.getBlockZ() >> 4) == c.getZ()) Tasks.later(1, () -> spawn(n, l));
        }
    }

    private void spawn(Npc n, Location l) {
        Entity old = entities.remove(n.id());
        if (old != null && old.isValid()) old.remove();
        Entity e;
        switch (n.type()) {
            case "MANNEQUIN" -> e = l.getWorld().spawn(l, Mannequin.class, m -> {
                if (n.skin() != null && !n.skin().isBlank()) m.setProfile(ResolvableProfile.resolvableProfile().name(n.skin()).build());
                m.setImmovable(true);
                m.setDescription(Text.c(roleName(n.role())));
                setup(m, n);
            });
            case "TRADER", "WANDERING_TRADER" -> e = l.getWorld().spawn(l, WanderingTrader.class, t -> {
                t.setAI(false);
                t.setDespawnDelay(0);
                t.setRecipes(List.of());
                setup(t, n);
            });
            default -> e = l.getWorld().spawn(l, Villager.class, v -> {
                v.setAI(false);
                // Пустые сделки задаём, пока профессия NONE: иначе ванильные торговцы (картограф) синхронно ищут структуры для карты
                // сокровищ и вешают сервер на десятки секунд. Профессию ставим после — только для внешнего вида.
                v.setProfession(Villager.Profession.NONE);
                v.setVillagerLevel(5);
                v.setRecipes(List.of());
                Villager.Profession prof = Registry.VILLAGER_PROFESSION.get(NamespacedKey.minecraft(n.profession().toLowerCase(Locale.ROOT)));
                if (prof != null) v.setProfession(prof);
                setup(v, n);
            });
        }
        entities.put(n.id(), e);
    }

    private void setup(LivingEntity e, Npc n) {
        e.customName(Text.c(n.name()));
        e.setCustomNameVisible(true);
        e.setInvulnerable(true);
        e.setSilent(true);
        e.setCollidable(false);
        e.setPersistent(false);
        e.setRemoveWhenFarAway(false);
        e.setGravity(false);
        e.getPersistentDataContainer().set(Keys.NPC_ID, PersistentDataType.STRING, n.id());
    }

    private static String roleName(String role) {
        return switch (role) {
            case "QUEST" -> "&eЗадания";
            case "TRADER" -> "&6Торговец";
            case "STORY" -> "&dСюжет";
            case "SECRET" -> "&8???";
            case "EVENT" -> "&5Событие";
            default -> "&bСлужба";
        };
    }

    private void lookAt() {
        for (var en : entities.entrySet()) {
            Entity e = en.getValue();
            if (!e.isValid()) continue;
            Player nearest = null;
            double best = 36;
            for (Player p : e.getWorld().getPlayers()) {
                double d = p.getLocation().distanceSquared(e.getLocation());
                if (d < best) { best = d; nearest = p; }
            }
            if (nearest == null) continue;
            Vector dir = nearest.getEyeLocation().toVector().subtract(e.getLocation().add(0, 1.6, 0).toVector());
            Location look = e.getLocation().setDirection(dir);
            e.setRotation(look.getYaw(), look.getPitch());
        }
    }

    public String npcIdOf(Entity e) {
        return e.getPersistentDataContainer().get(Keys.NPC_ID, PersistentDataType.STRING);
    }

    // ------------------------------------------------------------------ Взаимодействие

    @EventHandler(priority = EventPriority.LOWEST)
    public void click(PlayerInteractEntityEvent e) {
        String id = npcIdOf(e.getRightClicked());
        if (id == null) return;
        e.setCancelled(true);
        interact(e.getPlayer(), id);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void clickAt(PlayerInteractAtEntityEvent e) {
        if (npcIdOf(e.getRightClicked()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void damage(EntityDamageEvent e) {
        if (npcIdOf(e.getEntity()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void target(EntityTargetEvent e) {
        if (e.getTarget() != null && npcIdOf(e.getTarget()) != null) e.setCancelled(true);
    }

    public void interact(Player p, String id) {
        if (!plugin.auth().isAuthed(p)) return;
        long now = System.currentTimeMillis();
        Long last = clickCd.get(p.getUniqueId());
        if (last != null && now - last < 400) return;
        clickCd.put(p.getUniqueId(), now);
        Npc n = npcs.get(id);
        if (n == null) return;
        plugin.quests().progress(p, "TALK", id.toUpperCase(Locale.ROOT), 1);
        if (n.role().equals("SECRET")) {
            if (plugin.profiles().unlock(p, "secret", "npc_" + id)) {
                plugin.profiles().addStat(p, "secrets_found", 1);
                Msg.title(p, "&d✦ Секрет найден ✦", Text.strip(n.name()), 5, 40, 10);
            }
        }
        if (n.action() != null && !n.action().isBlank()) {
            runAction(p, n, n.action());
            return;
        }
        if (!n.trades().isEmpty()) { trades(p, n, null); return; }
        if (n.shopCategory() != null) { plugin.shop().openShop(p, null); return; }
        if (n.dialogue() != null) { dialogues.start(p, n, n.dialogue()); return; }
        Msg.send(p, "npc.silent", "&7{n}&7 молча кивает.", "n", n.name());
    }

    /** Действия NPC и вариантов диалога. */
    public void runAction(Player p, Npc n, String action) {
        String[] a = action.split(":", 2);
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "survival" -> { p.closeInventory(); plugin.lobby().enterSurvival(p); }
            case "duels" -> { p.closeInventory(); if (plugin.modules().require(p, "duels")) plugin.duels().enter(p); }
            case "lobby" -> { p.closeInventory(); p.performCommand("lobby"); }
            case "shop" -> plugin.shop().openShop(p, null);
            case "sell" -> plugin.shop().openSell(p, null);
            case "ah" -> plugin.auction().openBrowse(p, null);
            case "orders" -> plugin.orders().open(p, null);
            case "mail" -> plugin.mail().open(p, null);
            case "quests" -> plugin.quests().menus().open(p, null);
            case "clans" -> plugin.clans().menus().open(p, null);
            case "ranks" -> plugin.progression().menus().profile(p, p, null);
            case "top" -> plugin.leaderboards().open(p, null);
            case "bounties" -> plugin.bounties().open(p, null);
            case "events" -> plugin.events().open(p, null);
            case "dimensions" -> plugin.dimensions().open(p, null);
            case "menu" -> p.performCommand("menu");
            case "cosmetics" -> plugin.lobby().cosmetics().open(p, null);
            case "report" -> p.performCommand("report");
            case "trades" -> trades(p, n, null);
            case "dialogue" -> dialogues.start(p, n, a.length > 1 ? a[1] : n.dialogue());
            default -> plugin.getLogger().warning("Неизвестное действие NPC: " + action);
        }
    }

    /** Торговля с NPC по фиксированным ценам. */
    public void trades(Player p, Npc n, Menu parent) {
        new Menu(p, 3, "&8" + Text.strip(n.name())) {
            @Override
            protected void build() {
                fill(Items.filler());
                int slot = 10;
                for (Trade t : n.trades()) {
                    var preview = plugin.items().resolve(t.item().startsWith("unique:") ? null : t.item(), "", "").getNow(null);
                    String name = preview == null ? t.item() : Items.itemName(preview);
                    set(slot++, Items.of(preview == null ? Material.NETHER_STAR : preview.getType()).name("&e" + name)
                            .lore("&7Цена: &6" + Text.money(t.price()), "", "&eНажмите, чтобы купить").amount(preview == null ? 1 : preview.getAmount()).build(),
                            c -> ConfirmMenu.open(viewer, "&8Купить за " + Text.money(t.price()) + "?", null, List.of("&7" + name), () -> buy(viewer, n, t)));
                    if (slot == 17) slot = 19;
                    if (slot > 25) break;
                }
                if (parent != null) parent(parent);
                backButton(22);
            }
        }.open();
    }

    private final java.util.Set<UUID> busy = ConcurrentHashMap.newKeySet();

    private void buy(Player p, Npc n, Trade t) {
        if (!plugin.economy().tradeAllowed(p)) return;
        if (!busy.add(p.getUniqueId())) return;
        Tasks.then(plugin.economy().withdraw(p.getUniqueId(), t.price(), "npc_trade", n.id() + ":" + t.item()), bal ->
                Tasks.then(plugin.items().resolve(t.item(), p.getName(), "npc:" + n.id()), it -> {
                    busy.remove(p.getUniqueId());
                    if (it == null) {
                        plugin.economy().deposit(p.getUniqueId(), t.price(), "npc_refund", n.id());
                        Msg.error(p, "npc.unavailable", "Товар недоступен — деньги возвращены.");
                        return;
                    }
                    plugin.mail().giveOrMail(p, it, "reward");
                    Msg.ok(p, "npc.bought", "Куплено: {i} за {p}.", "i", Items.itemName(it), "p", Text.money(t.price()));
                    plugin.logs().log(p, "market", "npc_trade", p.getLocation(), n.id() + " " + t.item() + " " + t.price());
                }, err -> {
                    busy.remove(p.getUniqueId());
                    plugin.economy().deposit(p.getUniqueId(), t.price(), "npc_refund", n.id());
                }), err -> {
            busy.remove(p.getUniqueId());
            if (err instanceof ru.unknownsmp.db.Database.TxAbort ab) Msg.error(p, ab.key, ab.def);
            else Msg.error(p, "npc.error", "Покупка не выполнена.");
        });
    }

    // ------------------------------------------------------------------ Администрирование

    public void create(Player admin, String id, String type, String skinOrProf) {
        YamlConfiguration cfg = plugin.configs().get("npcs.yml");
        String base = "npcs." + id;
        cfg.set(base + ".name", "&f" + id);
        cfg.set(base + ".role", "SERVICE");
        cfg.set(base + ".appearance.type", type.toUpperCase(Locale.ROOT));
        if (type.equalsIgnoreCase("MANNEQUIN")) cfg.set(base + ".appearance.skin", skinOrProf == null ? admin.getName() : skinOrProf);
        else cfg.set(base + ".appearance.profession", skinOrProf == null ? "NONE" : skinOrProf);
        cfg.set(base + ".location", Text.loc(admin.getLocation()));
        plugin.configs().save("npcs.yml");
        reload();
        plugin.logs().log(admin, "admin", "npc_create", admin.getLocation(), id + " " + type);
    }

    public boolean set(String id, String field, Object value) {
        if (!npcs.containsKey(id)) return false;
        YamlConfiguration cfg = plugin.configs().get("npcs.yml");
        cfg.set("npcs." + id + "." + field, value);
        plugin.configs().save("npcs.yml");
        reload();
        return true;
    }

    public boolean remove(String id) {
        if (!npcs.containsKey(id)) return false;
        YamlConfiguration cfg = plugin.configs().get("npcs.yml");
        cfg.set("npcs." + id, null);
        plugin.configs().save("npcs.yml");
        reload();
        return true;
    }

    public Entity entity(String id) { return entities.get(id); }
}
