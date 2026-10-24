package ru.unknownsmp.items;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import ru.unknownsmp.core.Keys;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.db.Database;
import ru.unknownsmp.economy.EconomyService;
import ru.unknownsmp.gui.Items;
import ru.unknownsmp.gui.Menu;
import ru.unknownsmp.gui.PagedMenu;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Особые чары плагина. Хранятся в PersistentDataContainer предмета, отображаются строками описания.
 * Книга особых чар накладывается так: взять книгу курсором и нажать по предмету в инвентаре.
 */
public final class CustomEnchantService implements Module, Listener {
    public enum Target {
        PICKAXE("кирка"), DIGGER("кирка/лопата"), AXE("топор"), WEAPON("меч/топор"), SWORD("меч"), TOOL("инструмент/оружие"),
        HELMET("шлем"), BOOTS("ботинки"), MINING("кирка/лопата/топор/мотыга");

        public final String display;

        Target(String d) { display = d; }

        boolean fits(Material m) {
            String n = m.name();
            boolean pick = n.endsWith("_PICKAXE"), shovel = n.endsWith("_SHOVEL"), axe = n.endsWith("_AXE"), hoe = n.endsWith("_HOE"),
                    sword = n.endsWith("_SWORD") || m == Material.MACE || m == Material.TRIDENT;
            return switch (this) {
                case PICKAXE -> pick;
                case DIGGER -> pick || shovel;
                case AXE -> axe;
                case WEAPON -> sword || axe;
                case SWORD -> sword;
                case TOOL -> pick || shovel || axe || hoe || sword || m == Material.SHEARS || m == Material.BOW || m == Material.CROSSBOW;
                case HELMET -> n.endsWith("_HELMET") || m == Material.TURTLE_HELMET;
                case BOOTS -> n.endsWith("_BOOTS");
                case MINING -> pick || shovel || axe || hoe;
            };
        }
    }

    public record Ench(String id, String name, int max, Target target, long basePrice, String desc) {}

    private static final Map<String, Ench> ALL = new LinkedHashMap<>();

    static {
        reg(new Ench("autosmelt", "Автоплавка", 1, Target.DIGGER, 60_000, "Руда сразу выпадает слитками (только руды)"));
        reg(new Ench("telekinesis", "Телекинез", 1, Target.TOOL, 40_000, "Добыча и лут с мобов сразу в инвентарь"));
        reg(new Ench("drill", "Бур", 1, Target.DIGGER, 150_000, "Копает область 3×3. Режим: присесть + колёсико"));
        reg(new Ench("timber", "Лесоруб", 1, Target.AXE, 80_000, "Срубает дерево целиком (не работает приседая)"));
        reg(new Ench("vampire", "Вампиризм", 3, Target.WEAPON, 30_000, "Лечит на 5% урона за уровень"));
        reg(new Ench("thunder", "Громовержец", 2, Target.SWORD, 50_000, "Шанс 6% за уровень ударить молнией"));
        reg(new Ench("nightvision", "Ночное зрение", 1, Target.HELMET, 25_000, "Постоянное ночное зрение"));
        reg(new Ench("springs", "Пружины", 2, Target.BOOTS, 20_000, "Прыжок выше"));
        reg(new Ench("haste", "Спешка", 2, Target.MINING, 40_000, "Быстрее копает, пока в руке"));
        reg(new Ench("wisdom", "Мудрость", 3, Target.TOOL, 25_000, "+50% опыта за уровень"));
    }

    private static void reg(Ench e) { ALL.put(e.id(), e); }

    private static final Map<Material, Material> SMELT = new EnumMap<>(Material.class);

    static {
        SMELT.put(Material.RAW_IRON, Material.IRON_INGOT);
        SMELT.put(Material.RAW_GOLD, Material.GOLD_INGOT);
        SMELT.put(Material.RAW_COPPER, Material.COPPER_INGOT);
        SMELT.put(Material.IRON_ORE, Material.IRON_INGOT);
        SMELT.put(Material.DEEPSLATE_IRON_ORE, Material.IRON_INGOT);
        SMELT.put(Material.GOLD_ORE, Material.GOLD_INGOT);
        SMELT.put(Material.DEEPSLATE_GOLD_ORE, Material.GOLD_INGOT);
        SMELT.put(Material.COPPER_ORE, Material.COPPER_INGOT);
        SMELT.put(Material.DEEPSLATE_COPPER_ORE, Material.COPPER_INGOT);
        SMELT.put(Material.ANCIENT_DEBRIS, Material.NETHERITE_SCRAP);
    }

    private final UnknownSMP plugin;
    private final Set<UUID> chainGuard = ConcurrentHashMap.newKeySet();
    private final Set<UUID> damageGuard = ConcurrentHashMap.newKeySet();
    private final java.util.Random rnd = new java.util.Random();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private boolean enabled = true, shopEnabled = true;
    private int maxPerItem = 3, timberMax = 80;
    private final Map<String, Long> prices = new ConcurrentHashMap<>();

    public CustomEnchantService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "enchants"; }
    @Override public String title() { return "Особые чары"; }
    @Override public String[] depends() { return new String[]{"economy"}; }

    @Override
    public void enable() {
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Tasks.timer(40, 40, this::effectsTick);
    }

    @Override
    public void reload() {
        YamlConfiguration c = plugin.configs().get("items.yml");
        enabled = c.getBoolean("custom-enchants.enabled", true);
        shopEnabled = c.getBoolean("custom-enchants.shop", true);
        maxPerItem = Math.max(1, c.getInt("custom-enchants.max-per-item", 3));
        timberMax = Math.max(8, Math.min(256, c.getInt("custom-enchants.timber-max-logs", 80)));
        prices.clear();
        for (Ench e : ALL.values()) prices.put(e.id(), Math.max(1, c.getLong("custom-enchants.prices." + e.id(), e.basePrice())));
    }

    public static Map<String, Ench> all() { return ALL; }

    public static String roman(int n) {
        return switch (n) { case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; case 5 -> "V"; default -> String.valueOf(n); };
    }

    /** Цена книги: базовая цена × уровень². */
    public long price(Ench e, int level) { return prices.getOrDefault(e.id(), e.basePrice()) * level * level; }

    // ------------------------------------------------------------------ Хранение на предмете

    private static org.bukkit.NamespacedKey key() { return Keys.of("ce"); }
    private static org.bukkit.NamespacedKey loreKey() { return Keys.of("ce_n"); }
    private static org.bukkit.NamespacedKey bookKey() { return Keys.of("ce_book"); }
    private static org.bukkit.NamespacedKey modeKey() { return Keys.of("ce_mode"); }

    /** Режим кирки/лопаты с Буром и/или Автоплавкой. Переключается: присесть + колёсико мыши. */
    public enum Mode {
        NONE("&7Ничего"), DRILL("&eБур"), SMELT("&6Автоплавка"), BOTH("&eБур &7+ &6Автоплавка");

        public final String display;

        Mode(String d) { display = d; }
    }

    /** Доступные режимы для предмета (по наложенным чарам). */
    private static List<Mode> modesFor(Map<String, Integer> m) {
        boolean d = m.containsKey("drill"), a = m.containsKey("autosmelt");
        List<Mode> out = new ArrayList<>();
        if (!d && !a) return out;
        out.add(Mode.NONE);
        if (d) out.add(Mode.DRILL);
        if (a) out.add(Mode.SMELT);
        if (d && a) out.add(Mode.BOTH);
        return out;
    }

    /** Текущий режим; по умолчанию включено всё, что есть на предмете. */
    public static Mode mode(ItemStack it, Map<String, Integer> m) {
        List<Mode> av = modesFor(m);
        if (av.isEmpty()) return Mode.NONE;
        String s = it.getItemMeta().getPersistentDataContainer().get(modeKey(), PersistentDataType.STRING);
        if (s != null) {
            try { Mode md = Mode.valueOf(s); if (av.contains(md)) return md; } catch (IllegalArgumentException ignored) {}
        }
        return av.get(av.size() - 1);
    }

    private static boolean drillOn(ItemStack it, Map<String, Integer> m) {
        if (!m.containsKey("drill")) return false;
        Mode md = mode(it, m);
        return md == Mode.DRILL || md == Mode.BOTH;
    }

    private static boolean smeltOn(ItemStack it, Map<String, Integer> m) {
        if (!m.containsKey("autosmelt")) return false;
        Mode md = mode(it, m);
        return md == Mode.SMELT || md == Mode.BOTH;
    }

    private static boolean isOre(Material m) {
        return m.name().endsWith("_ORE") || m == Material.ANCIENT_DEBRIS;
    }

    /** Присесть + прокрутить колёсико: переключение режима вместо смены слота. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void wheel(org.bukkit.event.player.PlayerItemHeldEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        if (!p.isSneaking()) return;
        int diff = (e.getNewSlot() - e.getPreviousSlot() + 9) % 9;
        if (diff != 1 && diff != 8) return; // клавиши 1–9 переключают слот как обычно
        ItemStack it = p.getInventory().getItem(e.getPreviousSlot());
        if (it == null || it.getType().isAir()) return;
        Map<String, Integer> m = read(it);
        List<Mode> av = modesFor(m);
        if (av.isEmpty()) return;
        e.setCancelled(true);
        int idx = av.indexOf(mode(it, m));
        Mode next = av.get(Math.floorMod(idx + (diff == 1 ? 1 : -1), av.size()));
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(modeKey(), PersistentDataType.STRING, next.name());
        it.setItemMeta(meta);
        write(it, m);
        p.getInventory().setItem(e.getPreviousSlot(), it);
        StringBuilder bar = new StringBuilder("&7Режим: ");
        for (Mode md : av) bar.append(md == next ? "&l" + md.display + "&r" : "&8" + Text.strip(md.display)).append("  ");
        Msg.actionBar(p, bar.toString().trim());
        Msg.sound(p, Sound.UI_BUTTON_CLICK, 0.6f, next == Mode.NONE ? 0.8f : 1.4f);
    }

    public static Map<String, Integer> read(ItemStack it) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (it == null || !it.hasItemMeta()) return out;
        String s = it.getItemMeta().getPersistentDataContainer().get(key(), PersistentDataType.STRING);
        if (s == null || s.isBlank()) return out;
        for (String part : s.split(",")) {
            String[] kv = part.split(":");
            if (kv.length != 2 || !ALL.containsKey(kv[0])) continue;
            try { out.put(kv[0], Math.max(1, Math.min(ALL.get(kv[0]).max(), Integer.parseInt(kv[1])))); } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    public static int level(ItemStack it, String id) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return 0;
        return read(it).getOrDefault(id, 0);
    }

    private static void write(ItemStack it, Map<String, Integer> map) {
        ItemMeta meta = it.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        StringBuilder sb = new StringBuilder();
        for (var e : map.entrySet()) { if (!sb.isEmpty()) sb.append(','); sb.append(e.getKey()).append(':').append(e.getValue()); }
        if (sb.isEmpty()) pdc.remove(key()); else pdc.set(key(), PersistentDataType.STRING, sb.toString());
        // Строки описания: наши строки всегда первые, их число хранится отдельно.
        int n = pdc.getOrDefault(loreKey(), PersistentDataType.INTEGER, 0);
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        for (int i = 0; i < n && !lore.isEmpty(); i++) lore.remove(0);
        List<String> ours = new ArrayList<>();
        for (var e : map.entrySet()) {
            Ench en = ALL.get(e.getKey());
            ours.add("&d" + en.name() + (en.max() > 1 ? " " + roman(e.getValue()) : ""));
        }
        if (!modesFor(map).isEmpty()) {
            it.setItemMeta(meta);
            ours.add("&7Режим: " + mode(it, map).display + " &8(присесть + колёсико)");
            meta = it.getItemMeta();
            pdc = meta.getPersistentDataContainer();
        }
        List<Component> res = new ArrayList<>(Text.lore(ours));
        res.addAll(lore);
        meta.lore(res.isEmpty() ? null : res);
        pdc.set(loreKey(), PersistentDataType.INTEGER, ours.size());
        if (!map.isEmpty()) meta.setEnchantmentGlintOverride(true);
        it.setItemMeta(meta);
    }

    // ------------------------------------------------------------------ Книги

    public ItemStack book(Ench e, int level) {
        ItemStack it = Items.of(Material.ENCHANTED_BOOK).name("&dКнига особых чар: " + e.name() + (e.max() > 1 ? " " + roman(level) : ""))
                .lore("&7" + e.desc(), "&7Подходит: &f" + e.target().display, "",
                        "&eВозьмите книгу курсором и нажмите", "&eпо предмету в инвентаре").build();
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().remove(Keys.MENU_ITEM);
        meta.getPersistentDataContainer().set(bookKey(), PersistentDataType.STRING, e.id() + ":" + level);
        it.setItemMeta(meta);
        return it;
    }

    private static String[] bookOf(ItemStack it) {
        if (it == null || it.getType() != Material.ENCHANTED_BOOK || !it.hasItemMeta()) return null;
        String s = it.getItemMeta().getPersistentDataContainer().get(bookKey(), PersistentDataType.STRING);
        if (s == null) return null;
        String[] kv = s.split(":");
        return kv.length == 2 && ALL.containsKey(kv[0]) ? kv : null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void applyBook(InventoryClickEvent e) {
        if (!enabled || !(e.getWhoClicked() instanceof Player p)) return;
        if (e.getView().getTopInventory().getHolder(false) instanceof Menu) return;
        if (e.getClick() != ClickType.LEFT && e.getClick() != ClickType.RIGHT) return;
        String[] b = bookOf(e.getCursor());
        if (b == null) return;
        ItemStack target = e.getCurrentItem();
        if (target == null || target.getType().isAir() || target.getAmount() != 1 || bookOf(target) != null) return;
        Ench en = ALL.get(b[0]);
        int lvl;
        try { lvl = Math.max(1, Math.min(en.max(), Integer.parseInt(b[1]))); } catch (NumberFormatException ex) { return; }
        e.setCancelled(true);
        if (!en.target().fits(target.getType())) { Msg.error(p, "ce.not-fit", "Эти чары подходят только для: {t}.", "t", en.target().display); return; }
        Map<String, Integer> map = read(target);
        int cur = map.getOrDefault(en.id(), 0);
        if (cur == 0 && map.size() >= maxPerItem) { Msg.error(p, "ce.too-many", "На предмете уже максимум особых чар ({n}).", "n", maxPerItem); return; }
        int next = cur == lvl && cur < en.max() ? cur + 1 : Math.max(cur, lvl);
        if (next == cur) { Msg.error(p, "ce.already", "На предмете уже есть эти чары такого уровня."); return; }
        ItemStack res = target.clone();
        map.put(en.id(), next);
        write(res, map);
        if (e.getClickedInventory() != null) e.getClickedInventory().setItem(e.getSlot(), res);
        ItemStack cursor = e.getCursor().clone();
        cursor.setAmount(cursor.getAmount() - 1);
        p.setItemOnCursor(cursor.getAmount() <= 0 ? null : cursor);
        Msg.sound(p, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.2f);
        Msg.ok(p, "ce.applied", "Наложены чары: {n}.", "n", en.name() + (en.max() > 1 ? " " + roman(next) : ""));
        plugin.logs().log(p, "items", "ce_apply", p.getLocation(), en.id() + ":" + next + " -> " + res.getType());
    }

    /** На наковальне книги особых чар не используются — иначе они «съедались» бы без эффекта. */
    @EventHandler(priority = EventPriority.HIGH)
    public void anvil(PrepareAnvilEvent e) {
        if (bookOf(e.getInventory().getItem(1)) != null || bookOf(e.getInventory().getItem(0)) != null) e.setResult(null);
    }

    // ------------------------------------------------------------------ Эффекты: добыча

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void drops(BlockDropItemEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        ItemStack tool = p.getInventory().getItemInMainHand();
        Map<String, Integer> m = read(tool);
        if (m.isEmpty()) return;
        boolean smelt = smeltOn(tool, m) && isOre(e.getBlockState().getType()), tele = m.containsKey("telekinesis");
        Iterator<Item> it = e.getItems().iterator();
        while (it.hasNext()) {
            Item item = it.next();
            ItemStack st = item.getItemStack();
            if (smelt) {
                Material to = SMELT.get(st.getType());
                if (to != null) { st = new ItemStack(to, st.getAmount()); item.setItemStack(st); }
            }
            if (tele) {
                var left = p.getInventory().addItem(st);
                if (left.isEmpty()) it.remove();
                else item.setItemStack(left.values().iterator().next());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void wisdomBlock(BlockBreakEvent e) {
        int w = level(e.getPlayer().getInventory().getItemInMainHand(), "wisdom");
        if (w > 0 && e.getExpToDrop() > 0) e.setExpToDrop((int) Math.round(e.getExpToDrop() * (1 + 0.5 * w)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void chainBreak(BlockBreakEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        if (chainGuard.contains(p.getUniqueId())) return;
        ItemStack tool = p.getInventory().getItemInMainHand();
        Map<String, Integer> m = read(tool);
        if (m.isEmpty()) return;
        Block origin = e.getBlock();
        if (m.containsKey("timber") && Tag.LOGS.isTagged(origin.getType()) && !p.isSneaking()) {
            List<Block> logs = collectTree(origin);
            Tasks.later(1, () -> breakAll(p, logs));
        } else if (drillOn(tool, m)) {
            BlockFace face = p.getTargetBlockFace(6);
            if (face == null) face = BlockFace.UP;
            List<Block> area = new ArrayList<>();
            for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) continue;
                Block n = switch (face) {
                    case UP, DOWN -> origin.getRelative(a, 0, b);
                    case NORTH, SOUTH -> origin.getRelative(a, b, 0);
                    default -> origin.getRelative(0, b, a);
                };
                if (n.getType().isAir() || n.isLiquid()) continue;
                float h = n.getType().getHardness();
                if (h < 0 || h >= 50 || !n.isPreferredTool(tool)) continue;
                if (n.getState(false) instanceof Container) continue;
                area.add(n);
            }
            Tasks.later(1, () -> breakAll(p, area));
        }
    }

    private List<Block> collectTree(Block start) {
        Material type = start.getType();
        List<Block> out = new ArrayList<>();
        Set<Block> seen = new HashSet<>();
        ArrayDeque<Block> q = new ArrayDeque<>();
        q.add(start);
        seen.add(start);
        while (!q.isEmpty() && out.size() < timberMax) {
            Block b = q.poll();
            for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                Block n = b.getRelative(dx, dy, dz);
                if (n.getType() != type || !seen.add(n)) continue;
                out.add(n);
                q.add(n);
            }
        }
        return out;
    }

    /** Ломает блоки от имени игрока: каждое разрушение проходит через события (приваты, журнал, войны). */
    /** Опыт за руду как в ванилле (с «Шёлковым касанием» опыта нет). */
    private int oreXp(Material type, ItemStack tool) {
        if (tool.containsEnchantment(org.bukkit.enchantments.Enchantment.SILK_TOUCH)) return 0;
        String n = type.name();
        if (n.contains("COAL_ORE")) return rnd.nextInt(3);
        if (n.contains("DIAMOND_ORE") || n.contains("EMERALD_ORE")) return 3 + rnd.nextInt(5);
        if (n.contains("LAPIS_ORE") || n.contains("NETHER_QUARTZ_ORE")) return 2 + rnd.nextInt(4);
        if (n.contains("REDSTONE_ORE")) return 1 + rnd.nextInt(5);
        if (n.equals("NETHER_GOLD_ORE")) return rnd.nextInt(2);
        return 0;
    }

    private void breakAll(Player p, List<Block> blocks) {
        if (!p.isOnline() || blocks.isEmpty()) return;
        chainGuard.add(p.getUniqueId());
        try {
            for (Block b : blocks) {
                ItemStack tool = p.getInventory().getItemInMainHand();
                if (tool.getType().isAir()) break;
                if (b.getType().isAir()) continue;
                BlockBreakEvent ev = new BlockBreakEvent(b, p);
                Bukkit.getPluginManager().callEvent(ev);
                if (ev.isCancelled()) continue;
                Map<String, Integer> m = read(tool);
                boolean smelt = smeltOn(tool, m), tele = m.containsKey("telekinesis");
                if (!ev.isDropItems()) {
                    // Другой обработчик запретил выпадение (например, камень заработка уже вернул себя предметом).
                    b.setType(Material.AIR);
                } else if (!smelt && !tele) {
                    b.breakNaturally(tool, true, true);
                } else {
                    // Сами собираем добычу, чтобы Автоплавка и Телекинез работали на всей области Бура.
                    Material type = b.getType();
                    var data = b.getBlockData();
                    var drops = b.getDrops(tool, p);
                    org.bukkit.Location at = b.getLocation().add(0.5, 0.5, 0.5);
                    b.setType(Material.AIR);
                    b.getWorld().spawnParticle(org.bukkit.Particle.BLOCK, at, 12, 0.3, 0.3, 0.3, data);
                    b.getWorld().playSound(at, data.getSoundGroup().getBreakSound(), 0.8f, 1f);
                    for (ItemStack d : drops) {
                        if (smelt && isOre(type)) {
                            Material to = SMELT.get(d.getType());
                            if (to != null) d = new ItemStack(to, d.getAmount());
                        }
                        if (tele) {
                            var left = p.getInventory().addItem(d);
                            for (ItemStack l : left.values()) b.getWorld().dropItemNaturally(at, l);
                        } else {
                            b.getWorld().dropItemNaturally(at, d);
                        }
                    }
                    int xp = oreXp(type, tool);
                    if (xp > 0) b.getWorld().spawn(at, org.bukkit.entity.ExperienceOrb.class, o -> o.setExperience(xp));
                }
                p.damageItemStack(EquipmentSlot.HAND, 1);
            }
        } finally {
            chainGuard.remove(p.getUniqueId());
        }
    }

    // ------------------------------------------------------------------ Эффекты: бой

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void combat(EntityDamageByEntityEvent e) {
        if (!enabled || !(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof LivingEntity victim)) return;
        if (damageGuard.contains(p.getUniqueId())) return;
        Map<String, Integer> m = read(p.getInventory().getItemInMainHand());
        if (m.isEmpty()) return;
        int vamp = m.getOrDefault("vampire", 0);
        if (vamp > 0) {
            var attr = p.getAttribute(Attribute.MAX_HEALTH);
            double max = attr == null ? 20 : attr.getValue();
            p.setHealth(Math.min(max, p.getHealth() + e.getFinalDamage() * 0.05 * vamp));
        }
        int thunder = m.getOrDefault("thunder", 0);
        if (thunder > 0 && rnd.nextDouble() < 0.06 * thunder) {
            victim.getWorld().strikeLightningEffect(victim.getLocation());
            damageGuard.add(p.getUniqueId());
            try { victim.damage(3.0 * thunder, p); } finally { damageGuard.remove(p.getUniqueId()); }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void death(EntityDeathEvent e) {
        if (!enabled || e.getEntity() instanceof Player) return;
        Player k = e.getEntity().getKiller();
        if (k == null) return;
        Map<String, Integer> m = read(k.getInventory().getItemInMainHand());
        if (m.isEmpty()) return;
        int w = m.getOrDefault("wisdom", 0);
        if (w > 0) e.setDroppedExp((int) Math.round(e.getDroppedExp() * (1 + 0.5 * w)));
        if (m.containsKey("telekinesis")) {
            Iterator<ItemStack> it = e.getDrops().iterator();
            while (it.hasNext()) {
                ItemStack st = it.next();
                var left = k.getInventory().addItem(st);
                if (left.isEmpty()) it.remove();
                else st.setAmount(left.values().iterator().next().getAmount());
            }
        }
    }

    // ------------------------------------------------------------------ Эффекты: постоянные

    private void effectsTick() {
        if (!enabled) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (level(p.getInventory().getHelmet(), "nightvision") > 0)
                p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 400, 0, true, false, true));
            int spr = level(p.getInventory().getBoots(), "springs");
            if (spr > 0) p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 60, spr - 1, true, false, true));
            int haste = level(p.getInventory().getItemInMainHand(), "haste");
            if (haste > 0) p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 60, haste - 1, true, false, true));
        }
    }

    // ------------------------------------------------------------------ Магазин

    public boolean shopEnabled() { return enabled && shopEnabled; }

    public void openShop(Player p, Menu parent) {
        if (!shopEnabled()) return;
        new PagedMenu(p, "&8Особые чары") {
            @Override
            protected List<Entry> entries() {
                List<Entry> out = new ArrayList<>();
                for (Ench en : ALL.values()) {
                    List<String> lore = new ArrayList<>(List.of("&7" + en.desc(), "&7Подходит: &f" + en.target().display, ""));
                    for (int l = 1; l <= en.max(); l++) lore.add("&7Уровень " + roman(l) + ": &6" + Text.money(price(en, l)) + (en.max() > 1 ? " &8(ЛКМ=" + l + ")" : ""));
                    lore.add("");
                    lore.add(en.max() > 1 ? "&eЛКМ — I, ПКМ — II, Shift+ЛКМ — III" : "&eНажмите, чтобы купить книгу");
                    out.add(new Entry(Items.of(Material.ENCHANTED_BOOK).name("&d" + en.name()).lore(lore).build(), c -> {
                        int lvl = 1;
                        if (c.shift()) lvl = 3; else if (c.right()) lvl = 2;
                        lvl = Math.min(lvl, en.max());
                        buy(viewer, en, lvl);
                    }));
                }
                return out;
            }
        }.parent(parent).open();
    }

    public void buy(Player p, Ench en, int level) {
        if (!plugin.economy().tradeAllowed(p)) return;
        if (ru.unknownsmp.market.MailService.freeSlots(p) < 1) { Msg.error(p, "shop.no-space", "Недостаточно места в инвентаре (нужно {n} свободных ячеек).", "n", 1); return; }
        long price = price(en, level);
        UUID u = p.getUniqueId();
        if (!busy.add(u)) return;
        String what = en.id() + ":" + level;
        Tasks.then(plugin.db().tx(c -> EconomyService.debit(c, u, price, "shop_ce", what)), bal -> {
            busy.remove(u);
            plugin.economy().apply(u, bal);
            plugin.mail().giveOrMail(p, book(en, level), "shop");
            Msg.ok(p, "ce.bought", "Куплена книга «{n}» за {t}.", "n", en.name() + (en.max() > 1 ? " " + roman(level) : ""), "t", Text.money(price));
            plugin.logs().log(p, "market", "shop_ce", p.getLocation(), what + " = " + price);
        }, err -> {
            busy.remove(u);
            if (err instanceof Database.TxAbort ab) Msg.error(p, ab.key, ab.def);
            else Msg.error(p, "shop.error", "Покупка не выполнена. Деньги не списаны.");
        });
    }

    // ------------------------------------------------------------------ Админ: /usmp ench

    public void admin(CommandSender s, String[] r) {
        if (r.length == 0 || r[0].equalsIgnoreCase("list")) {
            Msg.raw(s, "&8--- &dОсобые чары &8---");
            for (Ench e : ALL.values()) Msg.raw(s, "&e" + e.id() + " &f" + e.name() + " &7(макс. " + e.max() + ", " + e.target().display + ")");
            Msg.raw(s, "&7/usmp ench give <игрок> <id> [уровень] — выдать книгу");
            return;
        }
        if (r[0].equalsIgnoreCase("give") && r.length >= 3) {
            Player t = Bukkit.getPlayerExact(r[1]);
            Ench e = ALL.get(r[2].toLowerCase(Locale.ROOT));
            if (t == null || e == null) { Msg.error(s, "ce.give-bad", "Игрок не в сети или нет таких чар."); return; }
            int lvl = 1;
            if (r.length > 3) try { lvl = Math.max(1, Math.min(e.max(), Integer.parseInt(r[3]))); } catch (NumberFormatException ignored) {}
            plugin.mail().giveOrMail(t, book(e, lvl), "admin");
            Msg.ok(s, "ce.given", "Книга выдана.");
            return;
        }
        Msg.error(s, "ce.usage", "Использование: /usmp ench list | give <игрок> <id> [уровень]");
    }
}
