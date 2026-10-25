package ru.unknownsmp.market;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import ru.unknownsmp.core.Keys;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Спавнеры в магазине: пустой спавнер и яйца мобов. Яйцо ставится в спавнер правым кликом.
 * Купленный спавнер можно снять киркой с «Шёлковым касанием» — моб внутри сохраняется.
 */
public final class SpawnerShopService implements Module, Listener {
    private static final NamespacedKey ITEM_KEY = Keys.of("shop_spawner");
    private static final NamespacedKey TYPE_KEY = Keys.of("shop_spawner_type");

    private static final Map<String, String> NAMES = new LinkedHashMap<>();
    static {
        NAMES.put("WITHER_SKELETON", "Скелет-иссушитель");
        NAMES.put("PIGLIN_BRUTE", "Пиглин-громила");
        NAMES.put("EVOKER", "Заклинатель");
        NAMES.put("STRIDER", "Лавомерка");
        NAMES.put("BLAZE", "Ифрит");
        NAMES.put("VINDICATOR", "Поборник");
        NAMES.put("HOGLIN", "Хоглин");
        NAMES.put("GUARDIAN", "Страж");
        NAMES.put("ELDER_GUARDIAN", "Древний страж");
        NAMES.put("GHAST", "Гаст");
        NAMES.put("ZOGLIN", "Зоглин");
        NAMES.put("RAVAGER", "Разоритель");
        NAMES.put("ENDERMITE", "Эндермит");
        NAMES.put("PIGLIN", "Пиглин");
        NAMES.put("CREEPER", "Крипер");
        NAMES.put("ENDERMAN", "Эндермен");
        NAMES.put("SLIME", "Слизень");
        NAMES.put("MAGMA_CUBE", "Магмовый куб");
        NAMES.put("HUSK", "Кадавр");
        NAMES.put("ZOMBIE_VILLAGER", "Зомби-житель");
        NAMES.put("ZOMBIE", "Зомби");
        NAMES.put("SKELETON", "Скелет");
        NAMES.put("SPIDER", "Паук");
        NAMES.put("CAVE_SPIDER", "Пещерный паук");
        NAMES.put("SILVERFISH", "Чешуйница");
        NAMES.put("STRAY", "Зимогор");
        NAMES.put("DROWNED", "Утопленник");
        NAMES.put("WITCH", "Ведьма");
        NAMES.put("PILLAGER", "Разбойник");
    }

    private record Egg(EntityType type, Material egg, long price) {}

    private final UnknownSMP plugin;
    private final List<Egg> eggs = new ArrayList<>();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private boolean enabled = true, anyConditions = true, silkPickup = true;
    private long spawnerPrice = 10_000;

    public SpawnerShopService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "spawners"; }
    @Override public String title() { return "Спавнеры"; }
    @Override public String[] depends() { return new String[]{"economy"}; }

    @Override
    public void enable() {
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void disable() {}

    @Override
    public void reload() {
        YamlConfiguration c = plugin.configs().get("economy.yml");
        enabled = c.getBoolean("spawners.enabled", true);
        spawnerPrice = Math.max(1, c.getLong("spawners.price", 10_000));
        anyConditions = c.getBoolean("spawners.any-conditions", true);
        silkPickup = c.getBoolean("spawners.silk-touch-pickup", true);
        eggs.clear();
        ConfigurationSection s = c.getConfigurationSection("spawners.eggs");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            long price = s.getLong(k);
            if (price <= 0) continue; // 0 — яйцо не продаётся
            EntityType t;
            try { t = EntityType.valueOf(k.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("[Спавнеры] Неизвестный моб: " + k);
                continue;
            }
            Material egg = Material.matchMaterial(t.name() + "_SPAWN_EGG");
            if (egg == null) { plugin.getLogger().warning("[Спавнеры] Нет яйца для моба: " + k); continue; }
            eggs.add(new Egg(t, egg, price));
        }
    }

    public boolean enabled() { return enabled; }

    public static String mobName(EntityType t) {
        String n = NAMES.get(t.name());
        if (n != null) return n;
        String s = t.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ Предметы

    /** Спавнер из магазина. type == null — пустой. */
    public ItemStack spawnerItem(EntityType type) {
        List<String> lore = new ArrayList<>();
        if (type == null) {
            lore.add("&7Поставьте и кликните по нему ПКМ");
            lore.add("&7яйцом моба — он начнёт его спавнить.");
        } else {
            lore.add("&7Моб: &f" + mobName(type));
        }
        if (silkPickup) { lore.add(""); lore.add("&8Снимается киркой с Шёлковым касанием."); }
        ItemStack it = Items.of(Material.SPAWNER).name(type == null ? "&eПустой спавнер" : "&eСпавнер: &f" + mobName(type)).lore(lore).build();
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().remove(Keys.MENU_ITEM);
        meta.getPersistentDataContainer().set(ITEM_KEY, PersistentDataType.BYTE, (byte) 1);
        if (type != null) meta.getPersistentDataContainer().set(TYPE_KEY, PersistentDataType.STRING, type.name());
        it.setItemMeta(meta);
        return it;
    }

    private static boolean isShopSpawner(ItemStack it) {
        return it != null && it.getType() == Material.SPAWNER && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(ITEM_KEY, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ Магазин

    public void open(Player p, Menu parent) {
        new Menu(p, 6, "&8Спавнеры") {
            @Override
            protected void build() {
                fill(Items.filler());
                set(4, Items.of(Material.SPAWNER).name("&eПустой спавнер")
                        .lore("&7Цена: &6" + Text.money(spawnerPrice), "&7В него ставится яйцо моба (ПКМ яйцом).", "",
                                "&eЛКМ &7— купить 1", "&eShift+ЛКМ &7— купить 8").build(),
                        cl -> buySpawner(viewer, cl.shift() ? 8 : 1));
                int[] slots = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
                int i = 0;
                for (Egg e : eggs) {
                    if (i >= slots.length) break;
                    set(slots[i++], Items.of(e.egg()).name("&fЯйцо: &e" + mobName(e.type()))
                            .lore("&7Цена: &6" + Text.money(e.price()), "&7Кликните им по спавнеру (ПКМ).", "", "&eНажмите, чтобы купить").build(),
                            cl -> ConfirmMenu.open(viewer, "&8Купить яйцо?", new ItemStack(e.egg()),
                                    List.of("&7" + mobName(e.type()), "&7Цена: &6" + Text.money(e.price())), () -> buyEgg(viewer, e)));
                }
                if (parent != null) parent(parent);
                backButton(49);
            }
        }.open();
    }

    private void buySpawner(Player p, int amount) {
        ItemStack it = spawnerItem(null);
        it.setAmount(amount);
        pay(p, spawnerPrice * amount, "spawner_buy", it, "Куплено спавнеров: " + amount + " за {c}.");
    }

    private void buyEgg(Player p, Egg e) {
        pay(p, e.price(), "spawn_egg_buy", new ItemStack(e.egg()), "Куплено яйцо «" + mobName(e.type()) + "» за {c}.");
    }

    private void pay(Player p, long price, String reason, ItemStack give, String okText) {
        if (!enabled) { Msg.error(p, "spawners.disabled", "Продажа спавнеров отключена."); return; }
        if (!plugin.economy().tradeAllowed(p)) return;
        if (MailService.freeSlots(p) < 1) { Msg.error(p, "shop.no-space", "Недостаточно места в инвентаре (нужно {n} свободных ячеек).", "n", 1); return; }
        UUID u = p.getUniqueId();
        if (!busy.add(u)) return;
        Tasks.then(plugin.db().tx(c -> EconomyService.debit(c, u, price, reason, null)), bal -> {
            busy.remove(u);
            plugin.economy().apply(u, bal);
            plugin.mail().giveOrMail(p, give, "shop");
            Msg.ok(p, "spawners.bought", okText, "c", Text.money(price));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            plugin.logs().log(p, "economy", reason, p.getLocation(), give.getType() + " x" + give.getAmount() + " " + price);
        }, err -> {
            busy.remove(u);
            if (err instanceof Database.TxAbort ab) Msg.error(p, ab.key, ab.def);
            else Msg.error(p, "shop.error", "Покупка не выполнена. Деньги не списаны.");
        });
    }

    // ------------------------------------------------------------------ Яйцо в спавнер

    @EventHandler(priority = EventPriority.HIGHEST)
    public void eggOnSpawner(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.SPAWNER) return;
        ItemStack hand = e.getPlayer().getInventory().getItemInMainHand();
        if (hand.getType().isAir() || !hand.getType().name().endsWith("_SPAWN_EGG")) return;
        Player p = e.getPlayer();
        // Ванильную обработку отключаем всегда: делаем сами, с проверкой прав.
        boolean denied = e.useInteractedBlock() == org.bukkit.event.Event.Result.DENY;
        e.setCancelled(true);
        if (denied) return;
        if (plugin.claims() != null && !plugin.claims().canTouchEntity(p, b.getLocation())) {
            Msg.error(p, "spawners.claim", "Это спавнер на чужой территории клана.");
            return;
        }
        EntityType type;
        try { type = EntityType.valueOf(hand.getType().name().substring(0, hand.getType().name().length() - "_SPAWN_EGG".length())); }
        catch (IllegalArgumentException ex) { return; }
        if (!(b.getState() instanceof CreatureSpawner cs)) return;
        if (cs.getSpawnedType() == type) {
            Msg.error(p, "spawners.same", "В этом спавнере уже {m}.", "m", mobName(type));
            return;
        }
        setMob(cs, type);
        if (p.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
        b.getWorld().playSound(b.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1f, 1.2f);
        Msg.actionBar(p, "&aСпавнер теперь спавнит: &f" + mobName(type));
        plugin.logs().log(p, "items", "spawner_egg", b.getLocation(), type.name());
    }

    /** Ставит моба в спавнер. С any-conditions моб спавнится при любом свете и без воды/лавы под ним. */
    private void setMob(CreatureSpawner cs, EntityType type) {
        boolean done = false;
        if (anyConditions) {
            try {
                var snap = Bukkit.getEntityFactory().createEntitySnapshot("{id:\"" + type.getKey() + "\"}");
                var entry = new org.bukkit.block.spawner.SpawnerEntry(snap, 1, new org.bukkit.block.spawner.SpawnRule(0, 15, 0, 15));
                cs.setPotentialSpawns(List.of(entry));
                cs.setSpawnedEntity(entry);
                cs.update(true, false);
                done = ((CreatureSpawner) cs.getBlock().getState()).getSpawnedType() == type;
            } catch (Throwable t) {
                plugin.getLogger().warning("[Спавнеры] Не удалось задать условия спавна: " + t);
            }
        }
        if (!done) {
            CreatureSpawner fresh = (CreatureSpawner) cs.getBlock().getState();
            fresh.setSpawnedType(type);
            fresh.update(true, false);
        }
    }

    // ------------------------------------------------------------------ Установка и снятие

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void place(BlockPlaceEvent e) {
        ItemStack it = e.getItemInHand();
        if (!isShopSpawner(it)) return;
        String t = it.getItemMeta().getPersistentDataContainer().get(TYPE_KEY, PersistentDataType.STRING);
        Block b = e.getBlockPlaced();
        Tasks.later(1, () -> {
            if (b.getType() != Material.SPAWNER || !(b.getState() instanceof CreatureSpawner cs)) return;
            cs.getPersistentDataContainer().set(ITEM_KEY, PersistentDataType.BYTE, (byte) 1);
            cs.update(true, false);
            if (t != null) {
                try { setMob((CreatureSpawner) b.getState(), EntityType.valueOf(t)); } catch (IllegalArgumentException ignored) {}
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void brk(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!silkPickup || b.getType() != Material.SPAWNER || !(b.getState() instanceof CreatureSpawner cs)) return;
        if (!cs.getPersistentDataContainer().has(ITEM_KEY, PersistentDataType.BYTE)) return;
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE) return;
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (!tool.getType().name().endsWith("_PICKAXE") || !tool.containsEnchantment(org.bukkit.enchantments.Enchantment.SILK_TOUCH)) return;
        EntityType type = cs.getSpawnedType();
        e.setExpToDrop(0);
        e.setDropItems(false);
        b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), spawnerItem(type));
        plugin.logs().log(p, "items", "spawner_pickup", b.getLocation(), type == null ? "empty" : type.name());
    }
}
