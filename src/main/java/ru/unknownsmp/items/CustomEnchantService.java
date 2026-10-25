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
        HELMET("шлем"), BOOTS("ботинки"), MINING("кирка/лопата/топор/мотыга"), CHESTPLATE("нагрудник"), LEGGINGS("поножи"),
        ARMOR("любая броня"), BOW("лук/арбалет"), ROD("удочка"), ANY("любой предмет с прочностью");

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
                case CHESTPLATE -> n.endsWith("_CHESTPLATE");
                case LEGGINGS -> n.endsWith("_LEGGINGS");
                case ARMOR -> n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS") || m == Material.TURTLE_HELMET;
                case BOW -> m == Material.BOW || m == Material.CROSSBOW;
                case ROD -> m == Material.FISHING_ROD;
                case ANY -> m.getMaxDurability() > 0;
            };
        }
    }

    public record Ench(String id, String name, int max, Target target, long basePrice, String desc) {}

    private static final Map<String, Ench> ALL = new LinkedHashMap<>();

    static {
        reg(new Ench("autosmelt", "Автоплавка", 1, Target.DIGGER, 60_000, "Руда сразу выпадает слитками (только руды)"));
        reg(new Ench("telekinesis", "Телекинез", 1, Target.TOOL, 40_000, "Добыча и лут с мобов сразу в инвентарь"));
        reg(new Ench("drill", "Бур", 1, Target.DIGGER, 150_000, "Копает область 3×3. Режим: присесть + колёсико"));
        reg(new Ench("timber", "Лесоруб", 1, Target.AXE, 80_000, "Срубает дерево целиком"));
        reg(new Ench("vampire", "Вампиризм", 3, Target.WEAPON, 30_000, "Лечит на 10% нанесённого урона за уровень"));
        reg(new Ench("thunder", "Громовержец", 2, Target.SWORD, 50_000, "Шанс 6% за уровень ударить молнией"));
        reg(new Ench("nightvision", "Ночное зрение", 1, Target.HELMET, 25_000, "Постоянное ночное зрение"));
        reg(new Ench("springs", "Пружины", 2, Target.BOOTS, 20_000, "Прыжок выше"));
        reg(new Ench("haste", "Спешка", 2, Target.MINING, 40_000, "Быстрее копает, пока в руке"));
        reg(new Ench("wisdom", "Мудрость", 3, Target.TOOL, 25_000, "+50% опыта за уровень"));
        reg(new Ench("lucky", "Удачливый шахтёр", 3, Target.PICKAXE, 50_000, "Шанс 5% за уровень удвоить добычу с руды"));
        reg(new Ench("execute", "Казнь", 3, Target.SWORD, 40_000, "+10% урона за уровень по врагам ниже 30% здоровья"));
        reg(new Ench("poison", "Яд", 2, Target.SWORD, 30_000, "Шанс 10% за уровень отравить цель на 3 сек"));
        reg(new Ench("behead", "Обезглавливание", 3, Target.WEAPON, 45_000, "Шанс 3/6/10% выбить голову игрока или моба"));
        reg(new Ench("bleed", "Кровотечение", 2, Target.AXE, 35_000, "После удара цель теряет здоровье ещё 3/5 сек"));
        reg(new Ench("frost", "Ледяная стрела", 2, Target.BOW, 30_000, "Стрелы замедляют цель"));
        reg(new Ench("secondwind", "Второе дыхание", 1, Target.CHESTPLATE, 120_000, "Ниже 4 сердец — Регенерация II на 5 сек (откат 2 мин)"));
        reg(new Ench("steelskin", "Стальная кожа", 2, Target.LEGGINGS, 50_000, "−5% входящего урона за уровень"));
        reg(new Ench("pyro", "Пиро", 1, Target.ARMOR, 250_000, "Иммунитет к огню и лаве"));
        reg(new Ench("stealth", "Невидимка", 1, Target.HELMET, 150_000, "Присесть с пустой рукой — невидимость до 10 сек (откат 30 сек); сталактиты и сталагмиты наносят лишь 5% урона"));
        reg(new Ench("fisher", "Рыбак", 3, Target.ROD, 25_000, "Клюёт быстрее, чаще ловятся сокровища"));
        reg(new Ench("bookfisher", "Опытный рыбак", 1, Target.ROD, 100_000, "Небольшой шанс выловить книгу особых чар"));
        reg(new Ench("unbreakable", "Неломайка", 1, Target.ANY, 400_000, "Предмет не ломается: останавливается на 1 прочности"));
        reg(new Ench("autorepair", "Автопочинка", 2, Target.ANY, 120_000, "Чинится на 1/2 ед. прочности каждые 5 сек, пока в инвентаре"));
        reg(new Ench("blind", "Ослепление", 2, Target.SWORD, 30_000, "Шанс 10% за уровень ослепить цель на 2 сек"));
        reg(new Ench("nighthunter", "Ночной охотник", 3, Target.SWORD, 30_000, "+5% урона за уровень ночью"));
        reg(new Ench("robber", "Грабитель", 1, Target.SWORD, 200_000, "Убийство игрока забирает 2% его баланса; один удар ломает все вагонетки, лодки, стойки и рамки в зоне 3x3 вокруг цели"));
        reg(new Ench("berserk", "Берсерк", 1, Target.CHESTPLATE, 100_000, "Ниже 5 сердец — Сила I на 8 сек (откат 1 мин)"));
        reg(new Ench("fireaura", "Огненная аура", 1, Target.CHESTPLATE, 60_000, "Поджигает того, кто бьёт вас вблизи"));
        reg(new Ench("steadfast", "Стойкость", 2, Target.LEGGINGS, 40_000, "−25% отбрасывания за уровень"));
        reg(new Ench("feather", "Перо", 1, Target.BOOTS, 60_000, "Нет урона от падения"));
        reg(new Ench("lavawalker", "Лавоход", 1, Target.BOOTS, 150_000, "Ходьба по лаве: под ногами магма (присесть — выключить)"));
        reg(new Ench("diver", "Водолаз", 1, Target.HELMET, 50_000, "Дыхание, зрение и быстрое копание под водой"));
        reg(new Ench("satiety", "Сытость", 1, Target.HELMET, 40_000, "Голод падает вдвое медленнее"));
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
    private int timberMax = 80;
    private double bookChance = 0.005;
    private double robberPercent = 0.02;
    private long robberCap = 250_000;
    private int robberSweepMax = 2000;
    private double vampirePercent = 0.10;
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
        Tasks.timer(100, 100, this::repairTick);
    }

    @Override
    public void reload() {
        YamlConfiguration c = plugin.configs().get("items.yml");
        enabled = c.getBoolean("custom-enchants.enabled", true);
        shopEnabled = c.getBoolean("custom-enchants.shop", true);
        bookChance = Math.max(0, Math.min(1, c.getDouble("custom-enchants.bookfisher-chance", 0.005)));
        robberPercent = Math.max(0, Math.min(100, c.getDouble("custom-enchants.robber-percent", 2))) / 100.0;
        robberCap = Math.max(0, c.getLong("custom-enchants.robber-max", 250_000));
        robberSweepMax = Math.max(1, c.getInt("custom-enchants.robber-sweep-max", 2000));
        vampirePercent = Math.max(0, Math.min(100, c.getDouble("custom-enchants.vampire-percent-per-level", 10))) / 100.0;
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

    /**
     * Переключаемые чары: то, что иногда мешает (Бур, Автоплавка, Лесоруб, Телекинез). Если на предмете есть хотя бы одни,
     * «присесть + колёсико» перебирает все их сочетания: ничего → … → всё включено.
     */
    private static final List<String> TOGGLES = List.of("drill", "autosmelt", "timber", "telekinesis");

    private static List<String> togglesOf(Map<String, Integer> m) {
        List<String> out = new ArrayList<>();
        for (String t : TOGGLES) if (m.containsKey(t)) out.add(t);
        return out;
    }

    /** Включённые переключаемые чары. По умолчанию включено всё. Поддерживает старые режимы NONE/DRILL/SMELT/BOTH. */
    private static Set<String> enabledSet(PersistentDataContainer pdc, Map<String, Integer> m) {
        List<String> t = togglesOf(m);
        Set<String> out = new HashSet<>(t);
        String s = pdc.get(modeKey(), PersistentDataType.STRING);
        if (s == null) return out;
        switch (s) {
            case "NONE" -> { out.remove("drill"); out.remove("autosmelt"); return out; }
            case "DRILL" -> { out.remove("autosmelt"); return out; }
            case "SMELT" -> { out.remove("drill"); return out; }
            case "BOTH" -> { return out; }
            default -> { }
        }
        out.clear();
        if (!s.equals("-")) for (String id : s.split(",")) if (t.contains(id)) out.add(id);
        return out;
    }

    /** Работают ли чары id на предмете (с учётом режима колёсика). */
    public static boolean on(ItemStack it, Map<String, Integer> m, String id) {
        if (!m.containsKey(id)) return false;
        if (!TOGGLES.contains(id)) return true;
        return enabledSet(it.getItemMeta().getPersistentDataContainer(), m).contains(id);
    }

    private static String modeName(Set<String> set, List<String> order) {
        if (set.isEmpty()) return "&7Ничего";
        StringBuilder sb = new StringBuilder();
        for (String id : order) if (set.contains(id)) { if (!sb.isEmpty()) sb.append(" &7+ "); sb.append("&e").append(ALL.get(id).name()); }
        return sb.toString();
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
        List<String> t = togglesOf(m);
        if (t.isEmpty()) return;
        e.setCancelled(true);
        Set<String> cur = enabledSet(it.getItemMeta().getPersistentDataContainer(), m);
        int mask = 0;
        for (int i = 0; i < t.size(); i++) if (cur.contains(t.get(i))) mask |= 1 << i;
        int total = 1 << t.size();
        mask = Math.floorMod(mask + (diff == 1 ? 1 : -1), total);
        Set<String> next = new HashSet<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < t.size(); i++) if ((mask & (1 << i)) != 0) { next.add(t.get(i)); ids.add(t.get(i)); }
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(modeKey(), PersistentDataType.STRING, ids.isEmpty() ? "-" : String.join(",", ids));
        it.setItemMeta(meta);
        write(it, m);
        p.getInventory().setItem(e.getPreviousSlot(), it);
        Msg.actionBar(p, "&7Режим " + (mask + 1) + "/" + total + ": " + modeName(next, TOGGLES));
        Msg.sound(p, Sound.UI_BUTTON_CLICK, 0.6f, next.isEmpty() ? 0.8f : 1.4f);
    }

    /** Удачливый шахтёр: шанс удвоить добычу с руды (не с «Шёлковым касанием», чтобы не множить сами руды). */
    private boolean luckyRoll(ItemStack tool, Map<String, Integer> m, Material broken) {
        int l = m.getOrDefault("lucky", 0);
        if (l <= 0 || !isOre(broken) || tool.containsEnchantment(org.bukkit.enchantments.Enchantment.SILK_TOUCH)) return false;
        return rnd.nextDouble() < 0.05 * l;
    }

    private static boolean isOre(Material m) {
        return m.name().endsWith("_ORE") || m == Material.ANCIENT_DEBRIS;
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
        if (!togglesOf(map).isEmpty()) ours.add("&7Режим: " + modeName(enabledSet(pdc, map), TOGGLES) + " &8(присесть + колёсико)");
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
        Material broken = e.getBlockState().getType();
        boolean smelt = on(tool, m, "autosmelt") && isOre(broken), tele = on(tool, m, "telekinesis");
        boolean lucky = luckyRoll(tool, m, broken);
        Iterator<Item> it = e.getItems().iterator();
        while (it.hasNext()) {
            Item item = it.next();
            ItemStack st = item.getItemStack();
            if (lucky) { st = st.clone(); st.setAmount(Math.min(st.getMaxStackSize(), st.getAmount() * 2)); item.setItemStack(st); }
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
        if (on(tool, m, "timber") && Tag.LOGS.isTagged(origin.getType())) {
            List<Block> logs = collectTree(origin);
            Tasks.later(1, () -> breakAll(p, logs));
        } else if (on(tool, m, "drill")) {
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
                boolean smelt = on(tool, m, "autosmelt"), tele = on(tool, m, "telekinesis");
                boolean lucky = luckyRoll(tool, m, b.getType());
                if (!ev.isDropItems()) {
                    // Другой обработчик запретил выпадение (например, камень заработка уже вернул себя предметом).
                    b.setType(Material.AIR);
                } else if (!smelt && !tele && !lucky) {
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
                        if (lucky) d.setAmount(Math.min(d.getMaxStackSize(), d.getAmount() * 2));
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
        if (!enabled || !(e.getEntity() instanceof LivingEntity victim)) return;
        // Ледяная стрела: метка на снаряде ставится при выстреле.
        if (e.getDamager() instanceof org.bukkit.entity.Projectile pr) {
            Integer fl = pr.getPersistentDataContainer().get(Keys.of("ce_frost"), PersistentDataType.INTEGER);
            if (fl != null && fl > 0) victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60 + 20 * fl, fl - 1));
            if (pr.getShooter() instanceof Player sh) endStealth(sh);
            return;
        }
        if (!(e.getDamager() instanceof Player p)) return;
        endStealth(p);
        if (damageGuard.contains(p.getUniqueId())) return;
        Map<String, Integer> m = read(p.getInventory().getItemInMainHand());
        if (m.isEmpty()) return;
        int blind = m.getOrDefault("blind", 0);
        if (blind > 0 && rnd.nextDouble() < 0.10 * blind) victim.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0));
        int poison = m.getOrDefault("poison", 0);
        if (poison > 0 && rnd.nextDouble() < 0.10 * poison) victim.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0));
        int bleed = m.getOrDefault("bleed", 0);
        if (bleed > 0) startBleed(victim, bleed == 1 ? 3 : 5);
        int vamp = m.getOrDefault("vampire", 0);
        if (vamp > 0) {
            var attr = p.getAttribute(Attribute.MAX_HEALTH);
            double max = attr == null ? 20 : attr.getValue();
            p.setHealth(Math.min(max, p.getHealth() + e.getFinalDamage() * vampirePercent * vamp));
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
        if (!enabled) return;
        Player k = e.getEntity().getKiller();
        if (k == null) return;
        ItemStack weapon = k.getInventory().getItemInMainHand();
        Map<String, Integer> m = read(weapon);
        if (m.isEmpty()) return;
        int bh = m.getOrDefault("behead", 0);
        if (bh > 0 && rnd.nextDouble() < new double[]{0, 0.03, 0.06, 0.10}[Math.min(3, bh)]) {
            ItemStack head = headOf(e.getEntity());
            if (head != null) e.getDrops().add(head);
        }
        if (e.getEntity() instanceof Player victim) {
            if (m.containsKey("robber") && victim != k) rob(k, victim);
            return;
        }
        int w = m.getOrDefault("wisdom", 0);
        if (w > 0) e.setDroppedExp((int) Math.round(e.getDroppedExp() * (1 + 0.5 * w)));
        if (on(weapon, m, "telekinesis")) {
            Iterator<ItemStack> it = e.getDrops().iterator();
            while (it.hasNext()) {
                ItemStack st = it.next();
                var left = k.getInventory().addItem(st);
                if (left.isEmpty()) it.remove();
                else st.setAmount(left.values().iterator().next().getAmount());
            }
        }
    }

    /** Казнь: больше урона по ослабленным. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void execute(EntityDamageByEntityEvent e) {
        if (!enabled || !(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof LivingEntity v)) return;
        Map<String, Integer> wm = read(p.getInventory().getItemInMainHand());
        int nh = wm.getOrDefault("nighthunter", 0);
        if (nh > 0 && p.getWorld().getEnvironment() == org.bukkit.World.Environment.NORMAL) {
            long t = p.getWorld().getTime();
            if (t >= 13000 && t <= 23000) e.setDamage(e.getDamage() * (1 + 0.05 * nh));
        }
        int ex = wm.getOrDefault("execute", 0);
        if (ex <= 0) return;
        var attr = v.getAttribute(Attribute.MAX_HEALTH);
        double max = attr == null ? 20 : attr.getValue();
        if (v.getHealth() / max < 0.30) e.setDamage(e.getDamage() * (1 + 0.10 * ex));
    }

    /** Стальная кожа: меньше входящего урона. Второе дыхание: регенерация при низком здоровье. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void defend(org.bukkit.event.entity.EntityDamageEvent e) {
        if (!enabled || !(e.getEntity() instanceof Player p)) return;
        if (e.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL && level(p.getInventory().getBoots(), "feather") > 0) {
            e.setCancelled(true);
            return;
        }
        int st = level(p.getInventory().getLeggings(), "steelskin");
        if (st > 0) e.setDamage(e.getDamage() * (1 - 0.05 * st));
        // Невидимка пассивно защищает голову: сталактиты и сталагмиты — только 5% урона.
        if (level(p.getInventory().getHelmet(), "stealth") > 0) {
            var type = e.getDamageSource().getDamageType();
            if (type == org.bukkit.damage.DamageType.FALLING_STALACTITE || type == org.bukkit.damage.DamageType.STALAGMITE)
                e.setDamage(e.getDamage() * 0.05);
        }
        if (e instanceof EntityDamageByEntityEvent be && be.getDamager() instanceof LivingEntity att && att != p
                && level(p.getInventory().getChestplate(), "fireaura") > 0) {
            att.setFireTicks(Math.max(att.getFireTicks(), 60));
        }
    }

    private final Map<UUID, Long> berserkCd = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void berserk(org.bukkit.event.entity.EntityDamageEvent e) {
        if (!enabled || !(e.getEntity() instanceof Player p)) return;
        double after = p.getHealth() - e.getFinalDamage();
        if (after <= 0 || after >= 10) return;
        if (level(p.getInventory().getChestplate(), "berserk") <= 0) return;
        long now = System.currentTimeMillis();
        Long cd = berserkCd.get(p.getUniqueId());
        if (cd != null && cd > now) return;
        berserkCd.put(p.getUniqueId(), now + 60_000);
        Tasks.later(1, () -> {
            if (!p.isOnline() || p.isDead()) return;
            p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 160, 0));
            Msg.actionBar(p, "&c✦ Берсерк! &7Сила I на 8 сек (откат 1 мин)");
            Msg.sound(p, Sound.ENTITY_RAVAGER_ROAR, 0.5f, 1.3f);
        });
    }

    // ------------------------------------------------------------------ Грабитель: ломает «технику» с одного удара

    /** Сущности плагина (NPC, голограммы, события, лобби) не трогаем. */
    private static boolean pluginEntity(org.bukkit.entity.Entity en) {
        var pdc = en.getPersistentDataContainer();
        return en.isInvulnerable() || pdc.has(Keys.NPC_ID) || pdc.has(Keys.HOLOGRAM) || pdc.has(Keys.EVENT_ENTITY)
                || pdc.has(Keys.LOBBY_ENTITY) || pdc.has(Keys.TROLL_ENTITY);
    }

    private static boolean robberTarget(org.bukkit.entity.Entity en) {
        if (en instanceof org.bukkit.entity.ArmorStand as) return !as.isMarker();
        if (en instanceof org.bukkit.entity.ItemFrame fr) return !fr.isFixed();
        return en instanceof org.bukkit.entity.Minecart || en instanceof org.bukkit.entity.Boat;
    }

    private boolean robberHand(Player p) {
        return enabled && level(p.getInventory().getItemInMainHand(), "robber") > 0;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void robberVehicle(org.bukkit.event.vehicle.VehicleDamageEvent e) {
        if (!(e.getAttacker() instanceof Player p) || !robberHand(p)) return;
        if (!robberTarget(e.getVehicle()) || pluginEntity(e.getVehicle())) return;
        e.setCancelled(true);
        robberSweep(p, e.getVehicle());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void robberEntity(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || !robberHand(p)) return;
        org.bukkit.entity.Entity en = e.getEntity();
        if (!(en instanceof org.bukkit.entity.ArmorStand) && !(en instanceof org.bukkit.entity.ItemFrame)) return;
        if (!robberTarget(en) || pluginEntity(en)) return;
        e.setCancelled(true);
        robberSweep(p, en);
    }

    /** Пустая рамка ломается через HangingBreak, а не через урон. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void robberHanging(org.bukkit.event.hanging.HangingBreakByEntityEvent e) {
        if (!(e.getRemover() instanceof Player p) || !robberHand(p)) return;
        if (!(e.getEntity() instanceof org.bukkit.entity.ItemFrame fr) || !robberTarget(fr) || pluginEntity(fr)) return;
        e.setCancelled(true);
        robberSweep(p, fr);
    }

    /**
     * Ломает за один удар всё в зоне 3x3 вокруг цели (по высоте ±2): стойки, рамки, вагонетки и лодки,
     * сколько бы их ни было настакано. Чужой приват клана уважается для каждой сущности.
     */
    private void robberSweep(Player p, org.bukkit.entity.Entity hit) {
        org.bukkit.Location at = hit.getLocation();
        org.bukkit.Location center = new org.bukkit.Location(at.getWorld(), at.getBlockX() + 0.5, at.getY(), at.getBlockZ() + 0.5);
        boolean drop = p.getGameMode() != org.bukkit.GameMode.CREATIVE;
        var claims = plugin.claims();
        List<ItemStack> drops = new ArrayList<>();
        int broken = 0;
        List<org.bukkit.entity.Entity> found = new ArrayList<>(center.getWorld().getNearbyEntities(center, 1.5, 2, 1.5, CustomEnchantService::robberTarget));
        if (!found.contains(hit)) found.add(hit);
        for (org.bukkit.entity.Entity en : found) {
            if (!en.isValid() || pluginEntity(en)) continue;
            if (claims != null && !claims.canTouchEntity(p, en.getLocation())) continue;
            if (broken >= robberSweepMax) break;
            collectDrops(en, drops);
            en.eject();
            en.remove();
            broken++;
        }
        if (broken == 0) return;
        if (drop) for (ItemStack d : merge(drops)) center.getWorld().dropItemNaturally(center, d);
        center.getWorld().playSound(center, Sound.ENTITY_ARMOR_STAND_BREAK, 1f, 1f);
        if (broken > 1) {
            center.getWorld().playSound(center, Sound.ENTITY_ITEM_BREAK, 1f, 0.7f);
            Msg.actionBar(p, Msg.t("ench.robber-sweep", "&6Грабитель: &fсломано &e{n}&f за один удар", "n", String.valueOf(broken)));
        }
        if (broken >= 5) plugin.logs().log(p, "items", "robber_sweep", center, String.valueOf(broken));
    }

    private static void collectDrops(org.bukkit.entity.Entity en, List<ItemStack> out) {
        if (en instanceof org.bukkit.inventory.InventoryHolder h) {
            for (ItemStack it : h.getInventory().getContents()) if (it != null && !it.getType().isAir()) out.add(it.clone());
            h.getInventory().clear();
        }
        if (en instanceof org.bukkit.entity.ArmorStand as) {
            for (EquipmentSlot sl : EquipmentSlot.values()) {
                try {
                    ItemStack it = as.getEquipment().getItem(sl);
                    if (it != null && !it.getType().isAir()) out.add(it.clone());
                } catch (IllegalArgumentException ignored) { /* слот не поддерживается стойкой */ }
            }
            out.add(new ItemStack(Material.ARMOR_STAND));
        } else if (en instanceof org.bukkit.entity.ItemFrame fr) {
            if (!fr.getItem().getType().isAir()) out.add(fr.getItem().clone());
            out.add(new ItemStack(fr instanceof org.bukkit.entity.GlowItemFrame ? Material.GLOW_ITEM_FRAME : Material.ITEM_FRAME));
        } else {
            // Вагонетки и лодки: тип сущности совпадает с названием предмета (OAK_BOAT, HOPPER_MINECART, BAMBOO_RAFT...).
            Material m = Material.matchMaterial(en.getType().name());
            if (m != null && m.isItem() && m != Material.COMMAND_BLOCK_MINECART) out.add(new ItemStack(m));
        }
    }

    /** Склеивает одинаковые предметы, чтобы сотня стоек не превратилась в сотню отдельных дропов. */
    private static List<ItemStack> merge(List<ItemStack> in) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack it : in) {
            int left = it.getAmount();
            for (ItemStack o : out) {
                if (left <= 0) break;
                if (!o.isSimilar(it)) continue;
                int add = Math.min(left, o.getMaxStackSize() - o.getAmount());
                if (add > 0) { o.setAmount(o.getAmount() + add); left -= add; }
            }
            while (left > 0) {
                ItemStack c = it.clone();
                int n = Math.min(left, Math.max(1, c.getMaxStackSize()));
                c.setAmount(n);
                out.add(c);
                left -= n;
            }
        }
        return out;
    }

    /** Грабитель: часть баланса убитого игрока переходит убийце (не в дуэлях и не между соклановцами). */
    private void rob(Player killer, Player victim) {
        if (plugin.duels() != null && plugin.duels().isDuelWorld(victim.getWorld())) return;
        var kc = plugin.clans() == null ? null : plugin.clans().clanOf(killer);
        if (kc != null && kc == plugin.clans().clanOf(victim)) return;
        long bal = plugin.economy().balance(victim);
        long amount = Math.min(robberCap, (long) Math.floor(bal * robberPercent));
        if (amount < 1) return;
        UUID v = victim.getUniqueId(), k = killer.getUniqueId();
        Tasks.then(plugin.db().tx(c -> {
            long vb = EconomyService.debit(c, v, amount, "robbed", killer.getName());
            long kb = EconomyService.credit(c, k, amount, "robber", victim.getName());
            return new long[]{vb, kb};
        }), r -> {
            plugin.economy().apply(v, r[0]);
            plugin.economy().apply(k, r[1]);
            Msg.send(killer, "ce.robbed", "&6Грабитель: &f+{a} &7с {v}", "a", Text.money(amount), "v", victim.getName());
            Msg.send(victim, "ce.robbed-victim", "&c{k} ограбил вас на {a}.", "k", killer.getName(), "a", Text.money(amount));
            plugin.logs().log(killer, "economy", "robber", killer.getLocation(), victim.getName() + " " + amount);
        }, null);
    }

    /** Сытость: голод падает вдвое медленнее. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void hunger(org.bukkit.event.entity.FoodLevelChangeEvent e) {
        if (!enabled || !(e.getEntity() instanceof Player p)) return;
        if (e.getFoodLevel() >= p.getFoodLevel()) return;
        if (level(p.getInventory().getHelmet(), "satiety") > 0 && rnd.nextBoolean()) e.setCancelled(true);
    }

    // Лавоход: временная магма под ногами, через 4 секунды снова лава. Ломать её нельзя.
    private final Set<Block> lavaTemp = ConcurrentHashMap.newKeySet();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void lavaWalk(org.bukkit.event.player.PlayerMoveEvent e) {
        if (!enabled) return;
        org.bukkit.Location f = e.getFrom(), t = e.getTo();
        if (f.getBlockX() == t.getBlockX() && f.getBlockZ() == t.getBlockZ() && f.getBlockY() == t.getBlockY()) return;
        Player p = e.getPlayer();
        if (p.isSneaking() || p.isFlying() || level(p.getInventory().getBoots(), "lavawalker") <= 0) return;
        Block under = t.getBlock().getRelative(BlockFace.DOWN);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            if (dx * dx + dz * dz > 5) continue;
            Block b = under.getRelative(dx, 0, dz);
            if (b.getType() != Material.LAVA || !(b.getBlockData() instanceof org.bukkit.block.data.Levelled lv) || lv.getLevel() != 0) continue;
            if (!b.getRelative(BlockFace.UP).getType().isAir()) continue;
            if (plugin.claims() != null) {
                var cl = plugin.claims().claimAt(b);
                var mine = plugin.clans().clanOf(p);
                if (cl != null && (mine == null || mine.id != cl.clanId())) continue;
            }
            b.setType(Material.MAGMA_BLOCK, false);
            lavaTemp.add(b);
            Tasks.later(80 + rnd.nextInt(20), () -> {
                if (lavaTemp.remove(b) && b.getType() == Material.MAGMA_BLOCK) b.setType(Material.LAVA, true);
            });
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void lavaTempBreak(BlockBreakEvent e) {
        if (lavaTemp.contains(e.getBlock())) e.setCancelled(true);
    }

    /** Автопочинка: каждые 5 секунд чинит предметы в инвентаре. */
    private void repairTick() {
        if (!enabled) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            var inv = p.getInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack it = inv.getItem(i);
                if (it == null || it.getType().getMaxDurability() <= 0 || !it.hasItemMeta()) continue;
                if (!(it.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable d) || d.getDamage() <= 0) continue;
                int lvl = level(it, "autorepair");
                if (lvl <= 0) continue;
                d.setDamage(Math.max(0, d.getDamage() - lvl));
                it.setItemMeta(d);
                inv.setItem(i, it);
            }
        }
    }

    private final Map<UUID, Long> secondWindCd = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void secondWind(org.bukkit.event.entity.EntityDamageEvent e) {
        if (!enabled || !(e.getEntity() instanceof Player p)) return;
        double after = p.getHealth() - e.getFinalDamage();
        if (after <= 0 || after >= 8) return;
        if (level(p.getInventory().getChestplate(), "secondwind") <= 0) return;
        long now = System.currentTimeMillis();
        Long cd = secondWindCd.get(p.getUniqueId());
        if (cd != null && cd > now) return;
        secondWindCd.put(p.getUniqueId(), now + 120_000);
        Tasks.later(1, () -> {
            if (!p.isOnline() || p.isDead()) return;
            p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1));
            Msg.actionBar(p, "&a✦ Второе дыхание! &7(откат 2 мин)");
            Msg.sound(p, Sound.ITEM_TOTEM_USE, 0.4f, 1.6f);
        });
    }

    // Кровотечение: урон без источника, чтобы не срабатывали чары и метка боя повторно.
    private final Map<UUID, int[]> bleeding = new ConcurrentHashMap<>();

    private void startBleed(LivingEntity v, int seconds) {
        int[] left = bleeding.get(v.getUniqueId());
        if (left != null) { left[0] = Math.max(left[0], seconds); return; }
        int[] st = {seconds};
        bleeding.put(v.getUniqueId(), st);
        org.bukkit.scheduler.BukkitTask[] t = new org.bukkit.scheduler.BukkitTask[1];
        t[0] = Tasks.timer(20, 20, () -> {
            if (st[0]-- <= 0 || !v.isValid() || v.isDead()) { bleeding.remove(v.getUniqueId()); t[0].cancel(); return; }
            v.damage(1.0);
            v.getWorld().spawnParticle(org.bukkit.Particle.BLOCK, v.getLocation().add(0, 1, 0), 8, 0.2, 0.3, 0.2,
                    Material.REDSTONE_BLOCK.createBlockData());
        });
    }

    /** Ледяная стрела: помечаем снаряд при выстреле. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void shoot(org.bukkit.event.entity.EntityShootBowEvent e) {
        if (!enabled || !(e.getEntity() instanceof Player)) return;
        int fl = level(e.getBow(), "frost");
        if (fl > 0) e.getProjectile().getPersistentDataContainer().set(Keys.of("ce_frost"), PersistentDataType.INTEGER, fl);
    }

    /** Обезглавливание: голова жертвы. */
    private static ItemStack headOf(LivingEntity v) {
        if (v instanceof Player pl) {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta sm) { sm.setOwningPlayer(pl); head.setItemMeta(sm); }
            return head;
        }
        return switch (v.getType()) {
            case ZOMBIE -> new ItemStack(Material.ZOMBIE_HEAD);
            case SKELETON -> new ItemStack(Material.SKELETON_SKULL);
            case CREEPER -> new ItemStack(Material.CREEPER_HEAD);
            case WITHER_SKELETON -> new ItemStack(Material.WITHER_SKELETON_SKULL);
            case PIGLIN -> new ItemStack(Material.PIGLIN_HEAD);
            case ENDER_DRAGON -> new ItemStack(Material.DRAGON_HEAD);
            default -> null;
        };
    }

    // ------------------------------------------------------------------ Невидимка

    private final Map<UUID, Long> stealthUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Long> stealthCd = new ConcurrentHashMap<>();
    private static final EquipmentSlot[] VISIBLE = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
            EquipmentSlot.HAND, EquipmentSlot.OFF_HAND};

    /** Присесть с пустой рукой (чтобы не мешать колёсику на инструментах) — невидимость до 10 секунд. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void sneak(org.bukkit.event.player.PlayerToggleSneakEvent e) {
        Player p = e.getPlayer();
        if (!e.isSneaking()) { endStealth(p); return; }
        if (!enabled || level(p.getInventory().getHelmet(), "stealth") <= 0) return;
        if (!p.getInventory().getItemInMainHand().getType().isAir()) return;
        long now = System.currentTimeMillis();
        Long cd = stealthCd.get(p.getUniqueId());
        if (cd != null && cd > now) { Msg.actionBar(p, "&7Невидимка перезаряжается: " + ((cd - now) / 1000 + 1) + " сек"); return; }
        stealthUntil.put(p.getUniqueId(), now + 10_000);
        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 200, 0, true, false, true));
        for (Player o : p.getWorld().getPlayers()) if (o != p) for (EquipmentSlot sl : VISIBLE) o.sendEquipmentChange(p, sl, new ItemStack(Material.AIR));
        Msg.actionBar(p, "&8✦ Невидимка: вас не видно (до 10 сек)");
        Tasks.later(200, () -> endStealth(p));
    }

    private void endStealth(Player p) {
        if (stealthUntil.remove(p.getUniqueId()) == null) return;
        p.removePotionEffect(PotionEffectType.INVISIBILITY);
        stealthCd.put(p.getUniqueId(), System.currentTimeMillis() + 30_000);
        if (!p.isOnline()) return;
        for (Player o : p.getWorld().getPlayers()) if (o != p) for (EquipmentSlot sl : VISIBLE) o.sendEquipmentChange(p, sl, p.getEquipment().getItem(sl));
        Msg.actionBar(p, "&7Невидимка закончилась (откат 30 сек)");
    }

    @EventHandler
    public void quitStealth(org.bukkit.event.player.PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        stealthUntil.remove(u);
        bleeding.remove(u);
        secondWindCd.remove(u);
    }

    // ------------------------------------------------------------------ Рыбалка

    private static final Material[] TREASURE = {Material.NAME_TAG, Material.SADDLE, Material.NAUTILUS_SHELL, Material.BOW,
            Material.FISHING_ROD, Material.EXPERIENCE_BOTTLE, Material.GOLDEN_CARROT};

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void fish(org.bukkit.event.player.PlayerFishEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        ItemStack rod = p.getInventory().getItemInMainHand();
        if (rod.getType() != Material.FISHING_ROD) rod = p.getInventory().getItemInOffHand();
        Map<String, Integer> m = read(rod);
        if (m.isEmpty()) return;
        int fisher = m.getOrDefault("fisher", 0);
        if (e.getState() == org.bukkit.event.player.PlayerFishEvent.State.FISHING && fisher > 0) {
            var hook = e.getHook();
            double k = 1 - 0.2 * fisher;
            hook.setMinWaitTime((int) Math.max(20, 100 * k));
            hook.setMaxWaitTime((int) Math.max(40, 600 * k));
        } else if (e.getState() == org.bukkit.event.player.PlayerFishEvent.State.CAUGHT_FISH && e.getCaught() instanceof Item caught) {
            if (m.containsKey("bookfisher") && rnd.nextDouble() < bookChance) {
                List<Ench> pool = new ArrayList<>();
                for (Ench en : ALL.values()) if (!en.id().equals("bookfisher") && !en.id().equals("unbreakable")) pool.add(en);
                Ench en = pool.get(rnd.nextInt(pool.size()));
                caught.setItemStack(book(en, 1));
                Msg.title(p, "&d✦ Улов!", "&fКнига особых чар: " + en.name(), 5, 50, 10);
                plugin.logs().log(p, "items", "ce_fish_book", p.getLocation(), en.id());
            } else if (fisher > 0 && rnd.nextDouble() < 0.05 * fisher) {
                caught.setItemStack(new ItemStack(TREASURE[rnd.nextInt(TREASURE.length)]));
                Msg.actionBar(p, "&6✦ Сокровище!");
            }
        }
    }

    // ------------------------------------------------------------------ Неломайка

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void itemDamage(org.bukkit.event.player.PlayerItemDamageEvent e) {
        if (!enabled || level(e.getItem(), "unbreakable") <= 0) return;
        ItemStack it = e.getItem();
        int max = it.getType().getMaxDurability();
        if (max <= 0 || !(it.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable d)) return;
        int left = max - d.getDamage();
        if (left - e.getDamage() >= 1) return;
        if (left <= 1) e.setCancelled(true); else e.setDamage(left - 1);
        Msg.actionBar(e.getPlayer(), "&eНеломайка: предмет на последней прочности — почините его");
    }

    // ------------------------------------------------------------------ Эффекты: постоянные

    private void effectsTick() {
        if (!enabled) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (level(p.getInventory().getHelmet(), "nightvision") > 0)
                p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 400, 0, true, false, true));
            int spr = level(p.getInventory().getBoots(), "springs");
            if (spr > 0) p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 60, spr - 1, true, false, true));
            for (ItemStack a : p.getInventory().getArmorContents()) {
                if (level(a, "pyro") > 0) { p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 400, 0, true, false, true)); break; }
            }
            if (stealthUntil.containsKey(p.getUniqueId()) && level(p.getInventory().getHelmet(), "stealth") <= 0) endStealth(p);
            if (level(p.getInventory().getHelmet(), "diver") > 0)
                p.addPotionEffect(new PotionEffect(PotionEffectType.CONDUIT_POWER, 100, 0, true, false, true));
            applySteadfast(p, level(p.getInventory().getLeggings(), "steadfast"));
            int haste = level(p.getInventory().getItemInMainHand(), "haste");
            if (haste > 0) p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 60, haste - 1, true, false, true));
        }
    }

    private static final org.bukkit.NamespacedKey STEADFAST_KEY = new org.bukkit.NamespacedKey("unknownsmp", "ce_steadfast");

    /** Стойкость: модификатор сопротивления отбрасыванию, пока надеты поножи с чарами. */
    private void applySteadfast(Player p, int lvl) {
        var attr = p.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        if (attr == null) return;
        org.bukkit.attribute.AttributeModifier cur = null;
        for (var mod : attr.getModifiers()) if (STEADFAST_KEY.equals(mod.getKey())) { cur = mod; break; }
        double want = 0.25 * lvl;
        if (cur != null && Math.abs(cur.getAmount() - want) < 1e-6 && lvl > 0) return;
        if (cur != null) attr.removeModifier(cur);
        if (lvl > 0) attr.addModifier(new org.bukkit.attribute.AttributeModifier(STEADFAST_KEY, want, org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER));
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

    /** Набор бога: незеритовые меч, кирка, топор и броня со всеми обычными и особыми чарами максимального уровня. */
    private static final Set<String> GOD_EXCLUDE = Set.of("telekinesis", "springs");

    public static List<ItemStack> godSet() {
        Object[][] parts = {
                {Material.NETHERITE_SWORD, "Меч бога"}, {Material.NETHERITE_PICKAXE, "Кирка бога"}, {Material.NETHERITE_AXE, "Топор бога"},
                {Material.NETHERITE_HELMET, "Шлем бога"}, {Material.NETHERITE_CHESTPLATE, "Нагрудник бога"},
                {Material.NETHERITE_LEGGINGS, "Поножи бога"}, {Material.NETHERITE_BOOTS, "Ботинки бога"}};
        var registry = io.papermc.paper.registry.RegistryAccess.registryAccess().getRegistry(io.papermc.paper.registry.RegistryKey.ENCHANTMENT);
        List<ItemStack> out = new ArrayList<>();
        for (Object[] part : parts) {
            ItemStack it = new ItemStack((Material) part[0]);
            ItemMeta meta = it.getItemMeta();
            meta.displayName(Text.item("&6&l" + part[1]));
            for (org.bukkit.enchantments.Enchantment e : registry) {
                if (e.isCursed() || !e.canEnchantItem(it)) continue;
                // Шёлковое касание отменяет Удачу — на инструментах бога оставляем Удачу.
                if (e.equals(org.bukkit.enchantments.Enchantment.SILK_TOUCH)) continue;
                if (e.equals(org.bukkit.enchantments.Enchantment.FROST_WALKER)) continue; // без Ледохода
                meta.addEnchant(e, e.getMaxLevel(), true);
            }
            it.setItemMeta(meta);
            Map<String, Integer> custom = new LinkedHashMap<>();
            for (Ench en : ALL.values()) {
                // Без Телекинеза (магнит) и Пружин (прыжки).
                if (GOD_EXCLUDE.contains(en.id())) continue;
                if (en.target().fits(it.getType())) custom.put(en.id(), en.max());
            }
            if (!custom.isEmpty()) write(it, custom);
            out.add(it);
        }
        return out;
    }

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
