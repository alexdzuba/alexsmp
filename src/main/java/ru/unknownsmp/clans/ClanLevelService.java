package ru.unknownsmp.clans;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.db.Database;
import ru.unknownsmp.gui.ConfirmMenu;
import ru.unknownsmp.gui.Items;
import ru.unknownsmp.gui.Menu;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Уровни клана: покупаются из банка клана. Дают больше территории, бафы на своей территории и клановый сундук.
 * Уровень и содержимое сундука хранятся в состоянии мира (world_state).
 */
public final class ClanLevelService implements Module, Listener {
    public record Level(int level, long price, int areaBonus, int claimsBonus, int chestRows, List<String> effects) {}

    private final UnknownSMP plugin;
    private final TreeMap<Integer, Level> levels = new TreeMap<>();
    private final Map<Long, Inventory> chests = new ConcurrentHashMap<>();
    private final Set<Long> busy = ConcurrentHashMap.newKeySet();
    private boolean enabled = true;

    public ClanLevelService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "clanlevels"; }
    @Override public String title() { return "Уровни кланов"; }
    @Override public String[] depends() { return new String[]{"clans"}; }

    @Override
    public void enable() {
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Tasks.timer(60, 60, this::buffs);
        // Открытые сундуки сохраняем и по таймеру — на случай падения сервера.
        Tasks.timer(20L * 300, 20L * 300, () -> { for (var e : chests.entrySet()) if (!e.getValue().getViewers().isEmpty()) save(e.getKey(), e.getValue()); });
    }

    @Override
    public void disable() {
        for (var e : chests.entrySet()) {
            try { save(e.getKey(), e.getValue()).get(5, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
        }
    }

    @Override
    public void reload() {
        YamlConfiguration c = plugin.configs().get("clans.yml");
        enabled = c.getBoolean("levels.enabled", true);
        levels.clear();
        levels.put(1, new Level(1, 0, 0, 0, 0, List.of()));
        ConfigurationSection s = c.getConfigurationSection("levels.list");
        if (s != null) for (String k : s.getKeys(false)) {
            try {
                int lv = Integer.parseInt(k);
                ConfigurationSection l = s.getConfigurationSection(k);
                if (lv < 2 || l == null) continue;
                levels.put(lv, new Level(lv, Math.max(0, l.getLong("price")), Math.max(0, l.getInt("area-bonus")),
                        Math.max(0, l.getInt("claims-bonus")), Math.max(0, Math.min(6, l.getInt("chest-rows"))), l.getStringList("effects")));
            } catch (NumberFormatException ignored) {}
        }
    }

    public boolean enabled() { return enabled; }

    public int level(Clan cl) {
        if (cl == null || !enabled) return 1;
        return (int) Math.max(1, Math.min(levels.lastKey(), plugin.state().getLong("clan_level_" + cl.id, 1)));
    }

    public Level info(Clan cl) { return levels.floorEntry(level(cl)).getValue(); }

    /** Бонус площади в процентах и дополнительные территории — для ClaimService. */
    public int areaBonus(Clan cl) { return info(cl).areaBonus(); }

    public int claimsBonus(Clan cl) { return info(cl).claimsBonus(); }

    // ------------------------------------------------------------------ Повышение уровня

    public void menu(Player p, Clan cl, Menu parent) {
        if (!enabled) { Msg.error(p, "clanlevel.disabled", "Уровни кланов отключены."); return; }
        new Menu(p, 3, "&8Уровень клана [" + cl.tag + "]") {
            @Override
            protected void build() {
                fill(Items.filler());
                int cur = level(cl);
                int slot = 10;
                for (Level l : levels.values()) {
                    if (slot > 16) break;
                    boolean have = l.level() <= cur, next = l.level() == cur + 1;
                    List<String> lore = new ArrayList<>(describe(l));
                    lore.add("");
                    if (have) lore.add("&aПолучен");
                    else {
                        lore.add("&7Цена: &6" + Text.money(l.price()) + " &7(из банка клана)");
                        lore.add(next ? (cl.leader.equals(viewer.getUniqueId()) ? "&eНажмите, чтобы купить" : "&7Купить может только лидер") : "&8Сначала предыдущий уровень");
                    }
                    Material m = have ? Material.LIME_STAINED_GLASS_PANE : next ? Material.EXPERIENCE_BOTTLE : Material.GRAY_STAINED_GLASS_PANE;
                    set(slot++, Items.of(m).name((have ? "&a" : next ? "&e" : "&7") + "Уровень " + l.level()).lore(lore).build(),
                            next ? (Menu.Handler) c -> upgrade(viewer, cl) : null);
                }
                if (parent != null) parent(parent);
                backButton(22);
            }
        }.open();
    }

    private List<String> describe(Level l) {
        List<String> out = new ArrayList<>();
        if (l.level() == 1) { out.add("&7Базовый уровень"); return out; }
        if (l.areaBonus() > 0) out.add("&7Площадь территории: &a+" + l.areaBonus() + "%");
        if (l.claimsBonus() > 0) out.add("&7Территорий: &a+" + l.claimsBonus());
        if (l.chestRows() > 0) out.add("&7Клановый сундук: &a" + l.chestRows() * 9 + " ячеек");
        if (!l.effects().isEmpty()) out.add("&7На своей территории: &a" + String.join(", ", l.effects().stream().map(ClanLevelService::effectName).toList()));
        return out;
    }

    private static String effectName(String id) {
        return switch (id.toLowerCase(Locale.ROOT)) {
            case "haste" -> "Спешка";
            case "regeneration" -> "Регенерация";
            case "speed" -> "Скорость";
            case "resistance" -> "Сопротивление";
            case "strength" -> "Сила";
            case "night_vision" -> "Ночное зрение";
            case "fire_resistance" -> "Огнестойкость";
            case "saturation" -> "Насыщение";
            default -> id;
        };
    }

    public void upgrade(Player p, Clan cl) {
        if (!enabled) return;
        if (!cl.leader.equals(p.getUniqueId())) { Msg.error(p, "clan.leader-only", "Только лидер может повысить уровень клана."); return; }
        int cur = level(cl);
        Level next = levels.get(cur + 1);
        if (next == null) { Msg.error(p, "clanlevel.max", "У клана уже максимальный уровень."); return; }
        ConfirmMenu.open(p, "&8Уровень " + next.level() + " за " + Text.money(next.price()) + "?", new ItemStack(Material.EXPERIENCE_BOTTLE),
                describe(next), () -> {
                    if (level(cl) != cur || !busy.add(cl.id)) return;
                    Tasks.then(plugin.db().tx(c -> {
                        int n = Database.update(c, "UPDATE clans SET bank = bank - ? WHERE id=? AND bank >= ?", next.price(), cl.id, next.price());
                        if (n != 1) throw new Database.TxAbort("clanlevel.bank-low", "В банке клана недостаточно средств. Пополните: /clan deposit <сумма>");
                        Database.update(c, "INSERT INTO clan_bank_log(clan_id, uuid, delta, at, reason) VALUES (?,?,?,?,?)",
                                cl.id, p.getUniqueId().toString(), -next.price(), System.currentTimeMillis(), "level_" + next.level());
                        Long bank = Database.longVal(c, "SELECT bank FROM clans WHERE id=?", cl.id);
                        return bank == null ? 0L : bank;
                    }), bank -> {
                        busy.remove(cl.id);
                        cl.bank = bank;
                        plugin.state().set("clan_level_" + cl.id, next.level());
                        resizeChest(cl);
                        plugin.clans().broadcastClan(cl, "clanlevel.up", "&a&lКлан достиг уровня {l}!&r&a {p} оплатил {c} из банка.",
                                "l", next.level(), "p", p.getName(), "c", Text.money(next.price()));
                        for (Clan.Member m : cl.members.values()) {
                            Player mp = Bukkit.getPlayer(m.uuid());
                            if (mp != null) mp.playSound(mp.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
                        }
                        plugin.logs().log(p, "clan", "level_up", p.getLocation(), cl.tag + " -> " + next.level() + " cost=" + next.price());
                    }, err -> {
                        busy.remove(cl.id);
                        if (err instanceof Database.TxAbort ab) Msg.error(p, ab.key, ab.def);
                        else Msg.error(p, "clanlevel.error", "Не удалось повысить уровень. Деньги не списаны.");
                    });
                });
    }

    // ------------------------------------------------------------------ Бафы на территории

    private static PotionEffectType effect(String id) {
        try {
            return io.papermc.paper.registry.RegistryAccess.registryAccess().getRegistry(io.papermc.paper.registry.RegistryKey.MOB_EFFECT)
                    .get(NamespacedKey.minecraft(id.toLowerCase(Locale.ROOT)));
        } catch (Throwable t) {
            return null;
        }
    }

    private void buffs() {
        if (!enabled || plugin.claims() == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Clan cl = plugin.clans().clanOf(p);
            if (cl == null) continue;
            Level l = info(cl);
            if (l.effects().isEmpty() || !plugin.claims().isInsideOwnClaim(p, p.getLocation())) continue;
            for (String id : l.effects()) {
                PotionEffectType t = effect(id);
                if (t == null) continue;
                PotionEffect cur = p.getPotionEffect(t);
                if (cur != null && (cur.getAmplifier() > 0 || cur.getDuration() > 100)) continue; // не трогаем более сильные эффекты
                p.addPotionEffect(new PotionEffect(t, 100, 0, true, false, true));
            }
        }
    }

    // ------------------------------------------------------------------ Клановый сундук

    private static final class ChestHolder implements InventoryHolder {
        final long clanId;
        Inventory inv;
        ChestHolder(long id) { this.clanId = id; }
        @Override public @NotNull Inventory getInventory() { return inv; }
    }

    private Inventory chest(Clan cl) {
        int rows = info(cl).chestRows();
        Inventory inv = chests.get(cl.id);
        if (inv != null && inv.getSize() == rows * 9) return inv;
        ChestHolder h = new ChestHolder(cl.id);
        Inventory fresh = Bukkit.createInventory(h, rows * 9, Text.c("&8Сундук клана [" + cl.tag + "]"));
        h.inv = fresh;
        ItemStack[] items = inv != null ? inv.getContents() : load(cl.id);
        if (inv != null) for (HumanEntity v : new ArrayList<>(inv.getViewers())) v.closeInventory();
        for (int i = 0; i < items.length; i++) {
            if (items[i] == null || items[i].isEmpty()) continue;
            if (i < fresh.getSize()) fresh.setItem(i, items[i]);
            else for (ItemStack left : fresh.addItem(items[i]).values()) {
                plugin.db().tx(c -> { ru.unknownsmp.market.MailService.insert(c, cl.leader, left, "clan_chest"); return null; });
            }
        }
        chests.put(cl.id, fresh);
        return fresh;
    }

    private void resizeChest(Clan cl) {
        if (chests.containsKey(cl.id)) save(cl.id, chest(cl));
    }

    private ItemStack[] load(long id) {
        String s = plugin.state().get("clan_chest_" + id);
        if (s == null || s.isBlank()) return new ItemStack[0];
        try { return ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(s)); } catch (Exception e) {
            plugin.getLogger().warning("[Кланы] Не удалось прочитать сундук клана " + id + ": " + e.getMessage());
            return new ItemStack[0];
        }
    }

    private java.util.concurrent.CompletableFuture<Integer> save(long id, Inventory inv) {
        ItemStack[] c = inv.getContents();
        ItemStack[] copy = new ItemStack[c.length];
        for (int i = 0; i < c.length; i++) copy[i] = c[i] == null ? ItemStack.empty() : c[i].clone();
        return plugin.state().set("clan_chest_" + id, Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(copy)));
    }

    public void openChest(Player p) {
        Clan cl = plugin.clans().clanOf(p);
        if (cl == null) { Msg.error(p, "clan.none", "Вы не состоите в клане."); return; }
        if (!enabled || info(cl).chestRows() <= 0) {
            Integer need = null;
            for (Level l : levels.values()) if (l.chestRows() > 0) { need = l.level(); break; }
            Msg.error(p, "clanlevel.no-chest", "Клановый сундук открывается с уровня клана {l}: /clan level", "l", need == null ? "?" : need);
            return;
        }
        p.openInventory(chest(cl));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void close(InventoryCloseEvent e) {
        if (e.getInventory().getHolder(false) instanceof ChestHolder h) {
            save(h.clanId, e.getInventory());
            if (e.getPlayer() instanceof Player p) plugin.logs().log(p, "clan", "chest_close", p.getLocation(), String.valueOf(h.clanId));
        }
    }

}
