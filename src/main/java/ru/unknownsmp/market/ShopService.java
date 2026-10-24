package ru.unknownsmp.market;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.unknownsmp.core.ChatInput;
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
import ru.unknownsmp.gui.MenuListener;
import ru.unknownsmp.gui.PagedMenu;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Магазин и скупка 2.0. Все цены выведены из базовой стоимости предметов ({@link ValueService}),
 * которая считается по рецептам игры: крафт, переплавка и автокрафтеры не создают стоимость из ничего,
 * поэтому заработать на них нельзя. Магазин дороже базовой стоимости, скупка дешевле — арбитраж невозможен.
 *
 * Защита от ферм: дневной лимит заработка скупкой на игрока и мягкий лимит на один вид предмета в день
 * (после него цена скупки этого вида резко падает). Это убирает «бесконечные деньги» с любых ферм.
 */
public final class ShopService implements Module {
    public record Category(String id, String name, Material icon, List<Material> items) {}

    private final UnknownSMP plugin;
    private final ValueService values;
    private final Map<String, Category> categories = new LinkedHashMap<>();
    private final Set<Material> shopBuyable = EnumSet.noneOf(Material.class);
    private final Set<Material> sellBlacklist = EnumSet.noneOf(Material.class);
    private final Map<String, Long> uniqueRarityPrice = new java.util.HashMap<>();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    // Кэш дневной выручки игрока по предметам: uuid -> (item -> coins за сегодня). Снимок, source истины — БД.
    private final Map<UUID, Map<Material, Double>> soldToday = new ConcurrentHashMap<>();
    private final Map<UUID, Double> soldTotalToday = new ConcurrentHashMap<>();
    private volatile long cacheDay;

    private double buyMarkup, sellFraction, enchantBonus, decayHalf = 256, decayFloor = 0.2, decayExpensive = 400;
    private long dailyLimit, itemSoftCap;
    private double softCapFloor;
    private Set<Integer> shopBlockedTags = Set.of();

    public ShopService(UnknownSMP plugin) {
        this.plugin = plugin;
        this.values = new ValueService(plugin);
    }

    @Override public String id() { return "shop"; }
    @Override public String title() { return "Магазин и скупка"; }
    @Override public String[] depends() { return new String[]{"economy", "mail"}; }

    @Override
    public void enable() {
        reload();
        cacheDay = today();
        plugin.commands().add("shop").player().perm("usmp.player.market").module("shop").cat("Экономика")
                .usage("/shop").desc("Серверный магазин").exec((s, a) -> openShop((Player) s, null));
        plugin.commands().add("sell").player().perm("usmp.player.market").module("shop").cat("Экономика")
                .usage("/sell [hand|all]").desc("Скупка предметов сервером").exec((s, a) -> {
                    Player p = (Player) s;
                    if (a.length > 0 && a[0].equalsIgnoreCase("hand")) sellHand(p);
                    else if (a.length > 0 && a[0].equalsIgnoreCase("all")) sellInventory(p);
                    else openSell(p, null);
                });
        plugin.commands().add("worth").player().perm("usmp.player.market").module("shop").cat("Экономика")
                .usage("/worth").desc("Узнать цену предмета в руке").exec((s, a) -> {
                    Player p = (Player) s;
                    ItemStack h = p.getInventory().getItemInMainHand();
                    long sp = sellPrice(h) <= 0 ? -1 : unitPriceNow(p.getUniqueId(), h);
                    long bp = buyPrice(h.getType());
                    if (sp <= 0 && bp <= 0) { Msg.error(p, "worth.none", "У этого предмета нет цены."); return; }
                    Msg.send(p, "worth.line", "&f{i}&7: скупка &6{s}&7, в магазине &6{b}", "i", Items.itemName(h),
                            "s", sp > 0 ? Text.money(sp) : "—", "b", bp > 0 ? Text.money(bp) : "—");
                });
    }

    @Override
    public void reload() {
        YamlConfiguration c = plugin.configs().get("market.yml");
        buyMarkup = Math.max(1.0, c.getDouble("pricing.buy-markup", 1.6));
        sellFraction = clamp(c.getDouble("pricing.sell-fraction", 0.35), 0.01, 0.95);
        enchantBonus = Math.max(0, c.getDouble("pricing.enchant-bonus-per-level", 25));
        dailyLimit = Math.max(0, c.getLong("sell.daily-limit-per-player", 2_000_000));
        itemSoftCap = Math.max(0, c.getLong("sell.item-soft-cap", 150_000));
        softCapFloor = clamp(c.getDouble("sell.over-cap-fraction", 0.1), 0, 1);
        decayHalf = Math.max(1, c.getDouble("sell.volume-decay.half-items", 256));
        decayFloor = clamp(c.getDouble("sell.volume-decay.floor", 0.2), 0.01, 1);
        // С какой цены скупки (за 1 шт.) цена дорогих предметов падает быстрее. По умолчанию масштабируется вместе с value-scale,
        // иначе при value-scale: 10 почти всё считается «дорогим» и цена падает в разы быстрее задуманного.
        double scale = Math.max(0.0001, c.getDouble("pricing.value-scale", 1.0));
        decayExpensive = Math.max(1, c.getDouble("sell.volume-decay.expensive-price", 40 * scale));
        uniqueRarityPrice.clear();
        var ur = c.getConfigurationSection("sell.unique-rarity-prices");
        if (ur != null) for (String k : ur.getKeys(false)) uniqueRarityPrice.put(k, Math.max(0, ur.getLong(k)));
        sellBlacklist.clear();
        for (String b : c.getStringList("sell.blacklist")) {
            Material m = Material.matchMaterial(b);
            if (m != null) sellBlacklist.add(m);
        }
        Set<Integer> blocked = new java.util.HashSet<>();
        for (String t : c.getStringList("shop.buyable-tags-blocked")) {
            switch (t.toLowerCase()) {
                case "nether" -> blocked.add(ValueService.TAG_NETHER);
                case "end" -> blocked.add(ValueService.TAG_END);
                case "premium" -> blocked.add(ValueService.TAG_PREMIUM);
            }
        }
        shopBlockedTags = blocked;
        Set<Material> shopBlack = EnumSet.noneOf(Material.class);
        for (String b : c.getStringList("shop.blacklist")) {
            Material m = Material.matchMaterial(b);
            if (m != null) shopBlack.add(m);
        }
        // Пересчёт стоимости и построение магазина выполняем после полной загрузки сервера,
        // когда все рецепты (в т.ч. из датапаков) доступны.
        Tasks.later(1, () -> {
            values.compute();
            buildShop(shopBlack);
        });
    }

    private static double clamp(double v, double a, double b) {
        if (!Double.isFinite(v)) return a;
        return Math.max(a, Math.min(b, v));
    }

    // ------------------------------------------------------------------ Построение магазина

    // id, название, иконка категории.
    private static final String[][] CAT_META = {
            {"blocks", "&6Строительные блоки", "BRICKS"},
            {"nature", "&aПрирода и фермы", "OAK_SAPLING"},
            {"decor", "&bДекор", "PEONY"},
            {"redstone", "&cРедстоун", "REDSTONE"},
            {"transport", "&eТранспорт", "MINECART"},
            {"tools", "&fИнструменты", "IRON_PICKAXE"},
            {"combat", "&4Бой и броня", "IRON_SWORD"},
            {"food", "&eЕда", "BREAD"},
            {"misc", "&7Разное", "CHEST"},
    };

    private void buildShop(Set<Material> shopBlack) {
        shopBuyable.clear();
        Map<String, List<Material>> byCat = new LinkedHashMap<>();
        for (String[] meta : CAT_META) byCat.put(meta[0], new ArrayList<>());
        for (var e : values.all().entrySet()) {
            Material m = e.getKey();
            if (!m.isItem() || m.isAir() || shopBlack.contains(m)) continue;
            int tag = values.tags(m);
            boolean blocked = false;
            for (int bt : shopBlockedTags) if ((tag & bt) != 0) { blocked = true; break; }
            if (blocked) continue;
            shopBuyable.add(m);
            byCat.get(classify(m)).add(m);
        }
        categories.clear();
        for (String[] meta : CAT_META) {
            List<Material> list = byCat.get(meta[0]);
            if (list == null || list.isEmpty()) continue;
            list.sort((a, b) -> Double.compare(values.value(a), values.value(b)));
            Material icon = Material.matchMaterial(meta[2]);
            categories.put(meta[0], new Category(meta[0], meta[1], icon == null ? Material.CHEST : icon, list));
        }
        int total = categories.values().stream().mapToInt(c -> c.items().size()).sum();
        plugin.getLogger().info("Магазин: " + total + " товаров в " + categories.size() + " категориях.");
    }

    /** Категория предмета по его типу и имени. */
    private static String classify(Material m) {
        String n = m.name();
        if (m.isEdible() || n.endsWith("_APPLE") || n.equals("BREAD") || n.equals("CAKE") || n.endsWith("_STEW") || n.endsWith("_SOUP")
                || n.equals("COOKIE") || n.equals("PUMPKIN_PIE") || n.endsWith("BERRIES") || n.equals("HONEY_BOTTLE")) return "food";
        if (n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS")
                || n.endsWith("_BOOTS") || n.equals("BOW") || n.equals("CROSSBOW") || n.equals("SHIELD") || n.equals("ARROW")
                || n.endsWith("_ARROW") || n.equals("TRIDENT") || n.endsWith("HORSE_ARMOR") || n.equals("WOLF_ARMOR")) return "combat";
        if (n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE") || n.equals("SHEARS") || n.equals("FLINT_AND_STEEL")
                || n.equals("FISHING_ROD") || n.equals("COMPASS") || n.equals("CLOCK") || n.equals("SPYGLASS") || n.equals("BRUSH")
                || n.endsWith("BUCKET") || n.equals("LEAD") || n.equals("NAME_TAG") || n.equals("SADDLE")) return "tools";
        if (n.contains("REDSTONE") || n.equals("REPEATER") || n.equals("COMPARATOR") || n.contains("PISTON") || n.equals("OBSERVER")
                || n.equals("HOPPER") || n.equals("DISPENSER") || n.equals("DROPPER") || n.endsWith("_BUTTON") || n.equals("LEVER")
                || n.endsWith("PRESSURE_PLATE") || n.endsWith("_RAIL") || n.equals("RAIL") || n.contains("MINECART") || n.equals("TARGET")
                || n.equals("TRIPWIRE_HOOK") || n.endsWith("_TORCH") && n.contains("REDSTONE") || n.equals("SCULK_SENSOR") || n.equals("CALIBRATED_SCULK_SENSOR")
                || n.equals("LIGHTNING_ROD") || n.equals("DAYLIGHT_DETECTOR") || n.equals("NOTE_BLOCK")) return "redstone";
        if (n.contains("BOAT") || n.contains("MINECART") || n.equals("RAFT") || n.endsWith("_RAFT")) return "transport";
        if (n.endsWith("_SAPLING") || n.endsWith("_LOG") || n.endsWith("_SEEDS") || n.endsWith("_SPROUTS") || n.equals("WHEAT")
                || n.equals("CARROT") || n.equals("POTATO") || n.equals("BEETROOT") || n.equals("SUGAR_CANE") || n.equals("BAMBOO")
                || n.equals("CACTUS") || n.equals("KELP") || n.equals("MELON") || n.equals("PUMPKIN") || n.equals("BONE_MEAL")
                || n.equals("COMPOSTER") || n.equals("DIRT") || n.equals("GRASS_BLOCK") || n.equals("FARMLAND") || n.endsWith("_PROPAGULE")
                || n.equals("MANGROVE_ROOTS") || n.equals("MUD") || n.endsWith("_EGG") && n.equals("EGG") || n.equals("HAY_BLOCK")) return "nature";
        if (n.endsWith("_DYE") || n.endsWith("_WOOL") || n.endsWith("_CARPET") || n.endsWith("_BANNER") || n.endsWith("_BED")
                || n.endsWith("STAINED_GLASS") || n.endsWith("STAINED_GLASS_PANE") || n.endsWith("CANDLE") || n.equals("CANDLE")
                || n.endsWith("_HEAD") || n.endsWith("_SKULL") || n.contains("FLOWER") || n.endsWith("_TULIP") || n.equals("POPPY")
                || n.equals("DANDELION") || n.equals("PAINTING") || n.equals("ITEM_FRAME") || n.equals("GLOW_ITEM_FRAME") || n.equals("ARMOR_STAND")
                || n.equals("FLOWER_POT") || n.equals("LANTERN") || n.equals("SOUL_LANTERN") || n.equals("TORCH") || n.endsWith("_SIGN")
                || n.endsWith("POTTED") || n.contains("GLAZED_TERRACOTTA") || n.endsWith("_SHULKER_BOX") || n.equals("DECORATED_POT")
                || n.contains("CORAL") || n.equals("BOOKSHELF") || n.contains("PICTURE") || n.endsWith("_LEAVES")) return "decor";
        if (n.contains("STONE") || n.contains("BRICK") || n.contains("PLANK") || n.contains("DEEPSLATE") || n.contains("BLACKSTONE")
                || n.contains("COBBLE") || n.contains("CONCRETE") && !n.endsWith("POWDER") || n.endsWith("_STAIRS") || n.endsWith("_SLAB")
                || n.endsWith("_WALL") || n.endsWith("_FENCE") || n.contains("TERRACOTTA") && !n.contains("GLAZED") || n.contains("SANDSTONE")
                || n.contains("QUARTZ") && n.contains("BLOCK") || n.contains("PRISMARINE") || n.contains("COPPER") && n.contains("BLOCK")
                || n.contains("PURPUR") || n.contains("GLASS") || n.equals("GRAVEL") || n.equals("SAND") || n.equals("RED_SAND")
                || n.contains("PLANKS") || n.endsWith("_DOOR") || n.endsWith("_TRAPDOOR") || n.equals("LADDER") || n.equals("SCAFFOLDING")
                || n.endsWith("_WOOD") || n.contains("TUFF") || n.contains("MUD_BRICK") || n.contains("CALCITE") || n.contains("DRIPSTONE")
                || n.contains("AMETHYST") && n.contains("BLOCK")) return "blocks";
        return "misc";
    }

    // ------------------------------------------------------------------ Цены

    public long buyPrice(Material m) {
        if (!shopBuyable.contains(m)) return -1;
        Double v = values.value(m);
        if (v == null || v <= 0) return -1;
        return Math.max(1, Math.round(v * buyMarkup));
    }

    /** Аудит экономики (команда /usmp dev econ): подозрительные рецепты и цепочки с прибылью. */
    public List<String> audit() {
        List<String> out = values.audit(this::buyPrice, m -> Math.max(0, sellExact(new ItemStack(m))));
        List<Material> top = new ArrayList<>(values.all().keySet());
        top.sort((a, b) -> Long.compare(Math.max(0, sellPrice(new ItemStack(b))), Math.max(0, sellPrice(new ItemStack(a)))));
        for (int i = 0; i < Math.min(40, top.size()); i++) out.add("TOP " + top.get(i) + " скупка=" + sellPrice(new ItemStack(top.get(i))) + " магазин=" + buyPrice(top.get(i)));
        return out;
    }

    public boolean isBuyable(Material m) {
        return shopBuyable.contains(m);
    }

    /** Базовая цена скупки одной штуки (без дневного лимита). -1 — не принимается. */
    public long sellPrice(ItemStack it) {
        double d = sellExact(it);
        return d <= 0 ? -1 : Math.max(1, (long) Math.floor(d));
    }

    /** Точная (дробная) цена скупки одной штуки: округляется только итог продажи, поэтому крафт не даёт прибыли на округлении. -1 — не принимается. */
    public double sellExact(ItemStack it) {
        if (it == null || it.getType().isAir()) return -1;
        Material m = it.getType();
        if (sellBlacklist.contains(m) || MenuListener.isMenuItem(it) || hasContents(it)) return -1;
        if (it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(Keys.of("duel_item"), org.bukkit.persistence.PersistentDataType.BYTE)) return -1;
        String type = plugin.items() == null ? null : plugin.items().typeOf(it);
        double base;
        if (type != null && type.startsWith("potion:")) {
            var pd = plugin.items().potions().get(type.substring(7));
            base = pd == null ? 0 : uniqueRarityPrice.getOrDefault(pd.rarity(), 0L) / 10.0;
        } else if (type != null) {
            var d = plugin.items().defs().get(type);
            if (d == null || d.story() || !plugin.items().isAuthentic(it)) return -1;
            base = uniqueRarityPrice.getOrDefault(d.rarity(), 0L);
        } else {
            Double v = values.value(m);
            boolean book = m == Material.ENCHANTED_BOOK && it.getItemMeta() instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta bm && !bm.getStoredEnchants().isEmpty();
            if ((v == null || v <= 0) && !book) return -1;
            // Зачарованная книга скупается по уровням чар: базовая цена листа и надбавка за каждый уровень (enchant-bonus-per-level).
            if (v == null || v <= 0) v = 10.0 / Math.max(0.01, sellFraction);
            base = v * sellFraction;
        }
        if (base <= 0) return -1;
        double mul = 1.0;
        short max = m.getMaxDurability();
        if (max > 0 && it.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dm && dm.hasDamage()) {
            mul = Math.max(0.1, 1.0 - dm.getDamage() / (double) max);
        }
        long levels = 0;
        if (it.hasItemMeta()) {
            for (int lvl : it.getItemMeta().getEnchants().values()) levels += lvl;
            if (it.getItemMeta() instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta es)
                for (int lvl : es.getStoredEnchants().values()) levels += lvl;
        }
        return base * mul + levels * enchantBonus;
    }

    /** Сумма множителей цены для a штук, если уже продано n штук: интеграл 1/(1+x/h), не ниже пола. */
    private double volumeFactor(double n, int a, double unit) {
        // Чем дороже предмет, тем быстрее падает цена: редкие дорогие вещи (трезубец, звезда Незера) нельзя продавать стаками по полной цене.
        double h = Math.max(0.5, decayHalf * Math.min(1.0, decayExpensive / Math.max(1.0, unit)));
        double integral = h * Math.log((1 + (n + a) / h) / (1 + n / h));
        return Math.max(integral, decayFloor * a);
    }

    /** Расчёт продажи без побочных эффектов. Один и тот же расчёт используется и для показа цены, и для самой продажи. */
    public record Quote(long total, boolean hitLimit, List<ItemStack> sold, List<ItemStack> rejected,
                        Map<Material, Integer> counts, Map<Material, Double> addByItem) {}

    public Quote quote(UUID u, List<ItemStack> items) {
        rolloverIfNeeded();
        Map<Material, Double> mine = soldToday.getOrDefault(u, Map.of());
        double totalBefore = soldTotalToday.getOrDefault(u, 0.0);
        long total = 0;
        boolean hitLimit = false;
        Map<Material, Integer> counts = new LinkedHashMap<>();
        Map<Material, Double> addByItem = new EnumMap<>(Material.class);
        List<ItemStack> sold = new ArrayList<>();
        List<ItemStack> rejected = new ArrayList<>();
        for (ItemStack it : items) {
            if (it == null || it.getType().isAir()) continue;
            double base = sellExact(it);
            if (base <= 0) { rejected.add(it); continue; }
            double earnedSoFar = mine.getOrDefault(it.getType(), 0.0) + addByItem.getOrDefault(it.getType(), 0.0);
            double perD = base;
            if (itemSoftCap > 0 && earnedSoFar >= itemSoftCap) perD *= softCapFloor;
            // Чем больше предметов этого вида продано за сутки, тем ниже цена (плавное падение, но не ниже пола).
            // Сколько «штук» уже продано, считаем по полной цене: иначе после мягкого лимита падение удваивалось.
            double soldUnits = earnedSoFar / Math.max(0.01, base);
            // Округляется только итог по стеку, а не каждая штука: иначе дешёвые предметы (кость → костная мука) дают прибыль на крафте.
            long line = (long) Math.floor(perD * volumeFactor(soldUnits, it.getAmount(), base));
            if (line < 1) { rejected.add(it); continue; }
            if (dailyLimit > 0 && totalBefore + total + line > dailyLimit) {
                hitLimit = true;
                rejected.add(it);
                continue;
            }
            total += line;
            counts.merge(it.getType(), it.getAmount(), Integer::sum);
            addByItem.merge(it.getType(), (double) line, Double::sum);
            sold.add(it);
        }
        return new Quote(total, hitLimit, sold, rejected, counts, addByItem);
    }

    /** Цена скупки одной штуки прямо сейчас для этого игрока (с учётом уже проданного за день). */
    public long unitPriceNow(UUID u, ItemStack it) {
        ItemStack one = it.clone();
        one.setAmount(1);
        Quote q = quote(u, List.of(one));
        return q.total() > 0 ? q.total() : -1;
    }

    private static boolean hasContents(ItemStack it) {
        try {
            var cont = it.getData(io.papermc.paper.datacomponent.DataComponentTypes.CONTAINER);
            if (cont != null) for (ItemStack x : cont.contents()) if (x != null && !x.isEmpty()) return true;
            var bundle = it.getData(io.papermc.paper.datacomponent.DataComponentTypes.BUNDLE_CONTENTS);
            if (bundle != null) for (ItemStack x : bundle.contents()) if (x != null && !x.isEmpty()) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean isPlain(ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        return it.isSimilar(new ItemStack(it.getType()));
    }

    private long today() {
        return LocalDate.now(Text.zone()).toEpochDay();
    }

    private void rolloverIfNeeded() {
        long d = today();
        if (d != cacheDay) {
            cacheDay = d;
            soldToday.clear();
            soldTotalToday.clear();
        }
    }

    // ------------------------------------------------------------------ Покупка

    public void buy(Player p, Material m, int amount, Runnable after) {
        if (amount <= 0 || amount > 64 * 36) return;
        if (!plugin.economy().tradeAllowed(p)) return;
        long each = buyPrice(m);
        if (each <= 0) { Msg.error(p, "shop.not-sold", "Этот предмет не продаётся в магазине."); return; }
        long total;
        try { total = Math.multiplyExact(each, (long) amount); } catch (ArithmeticException e) { return; }
        ItemStack proto = new ItemStack(m);
        int stacks = MailService.slotsNeeded(proto, amount);
        if (MailService.freeSlots(p) < stacks) {
            Msg.error(p, "shop.no-space", "Недостаточно места в инвентаре (нужно {n} свободных ячеек).", "n", stacks);
            return;
        }
        if (!busy.add(p.getUniqueId())) return;
        final long t = total;
        final UUID u = p.getUniqueId();
        Tasks.then(plugin.db().tx(c -> EconomyService.debit(c, u, t, "shop_buy", amount + "x" + m)), bal -> {
            busy.remove(u);
            plugin.economy().apply(u, bal);
            int left = amount;
            while (left > 0) {
                int a = Math.min(proto.getMaxStackSize(), left);
                ItemStack x = proto.clone();
                x.setAmount(a);
                plugin.mail().giveOrMail(p, x, "shop");
                left -= a;
            }
            p.saveData();
            Msg.ok(p, "shop.bought", "Куплено {n}x {i} за {t}.", "n", amount, "i", Items.materialName(m), "t", Text.money(t));
            plugin.logs().log(p, "market", "shop_buy", p.getLocation(), amount + "x " + m + " = " + t);
            if (after != null) after.run();
        }, err -> {
            busy.remove(u);
            if (err instanceof Database.TxAbort ab) Msg.error(p, ab.key, ab.def);
            else Msg.error(p, "shop.error", "Покупка не выполнена. Деньги не списаны.");
        });
    }

    // ------------------------------------------------------------------ Продажа

    /** Продать набор предметов (уже изъятых). Учитывает дневной лимит. При ошибке возвращает. */
    public void sellItems(Player p, List<ItemStack> items, Runnable onDone) {
        final UUID u = p.getUniqueId();
        Quote q = quote(u, items);
        Map<Material, Double> mine = soldToday.computeIfAbsent(u, k -> new ConcurrentHashMap<>());
        long total = q.total();
        boolean hitLimit = q.hitLimit();
        Map<Material, Integer> counts = q.counts();
        Map<Material, Double> addByItem = q.addByItem();
        List<ItemStack> sold = q.sold();
        List<ItemStack> rejected = q.rejected();
        for (ItemStack it : rejected) plugin.mail().giveOrMail(p, it, "return");
        if (total <= 0) {
            if (hitLimit) Msg.error(p, "sell.limit", "Дневной лимит скупки достигнут ({l}). Продайте остальное завтра или игрокам через /ah.", "l", Text.money(dailyLimit));
            if (onDone != null) onDone.run();
            return;
        }
        final long t = total;
        final long day = cacheDay;
        final boolean limited = hitLimit;
        Tasks.then(plugin.db().tx(c -> {
            long bal = EconomyService.credit(c, u, t, "sell", counts.toString());
            try (var ps = c.prepareStatement("INSERT INTO sell_quota(uuid, day, item, coins) VALUES (?,?,?,?) ON CONFLICT(uuid, day, item) DO UPDATE SET coins = sell_quota.coins + excluded.coins")) {
                for (var e : addByItem.entrySet()) {
                    ps.setString(1, u.toString());
                    ps.setLong(2, day);
                    ps.setString(3, e.getKey().name());
                    ps.setDouble(4, e.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return bal;
        }), bal -> {
            plugin.economy().apply(u, bal);
            for (var e : addByItem.entrySet()) mine.merge(e.getKey(), e.getValue(), Double::sum);
            soldTotalToday.merge(u, (double) t, Double::sum);
            for (ItemStack it : sold) {
                String uid = plugin.items() == null ? null : plugin.items().uidOf(it);
                if (uid != null) plugin.items().setStatus(uid, "SOLD_SERVER", p.getName());
            }
            Msg.ok(p, "sell.done", "Продано на {t}.", "t", Text.money(t));
            if (limited) Msg.send(p, "sell.limit-note", "&7Часть не принята: достигнут дневной лимит скупки.");
            plugin.logs().log(p, "market", "sell", p.getLocation(), counts + " = " + t);
            plugin.quests().progress(p, "EARN", "sell", t);
            for (var e : counts.entrySet()) plugin.quests().progress(p, "SELL", e.getKey().name(), e.getValue());
            p.saveData();
            if (onDone != null) onDone.run();
        }, err -> {
            for (ItemStack it : sold) plugin.mail().giveOrMail(p, it, "return");
            Msg.error(p, "sell.error", "Продажа не выполнена — предметы возвращены.");
            if (onDone != null) onDone.run();
        });
    }

    private void sellHand(Player p) {
        if (!plugin.economy().tradeAllowed(p)) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (sellPrice(hand) <= 0) { Msg.error(p, "sell.not-accepted", "Сервер не скупает этот предмет."); return; }
        if (!busy.add(p.getUniqueId())) return;
        ItemStack taken = hand.clone();
        p.getInventory().setItemInMainHand(null);
        sellItems(p, List.of(taken), () -> busy.remove(p.getUniqueId()));
    }

    private void sellInventory(Player p) {
        if (!plugin.economy().tradeAllowed(p)) return;
        if (!busy.add(p.getUniqueId())) return;
        List<ItemStack> toSell = new ArrayList<>();
        ItemStack[] cont = p.getInventory().getStorageContents();
        for (int i = 0; i < cont.length; i++) {
            if (cont[i] != null && sellPrice(cont[i]) > 0) { toSell.add(cont[i].clone()); cont[i] = null; }
        }
        p.getInventory().setStorageContents(cont);
        if (toSell.isEmpty()) { busy.remove(p.getUniqueId()); Msg.error(p, "sell.nothing", "Нечего продавать."); return; }
        sellItems(p, toSell, () -> busy.remove(p.getUniqueId()));
    }

    // ------------------------------------------------------------------ Меню

    public void openShop(Player p, Menu parent) {
        new Menu(p, 4, "&8Серверный магазин") {
            @Override
            protected void build() {
                fill(Items.filler());
                int slot = 10;
                for (Category c : categories.values()) {
                    set(slot++, Items.of(c.icon()).name(c.name()).lore("&7Товаров: &f" + c.items().size(), "", "&eОткрыть").build(), cl -> openCategory(viewer, c, this));
                    if (slot == 17) slot = 19;
                    if (slot > 25) break;
                }
                set(30, Items.of(Material.HOPPER).name("&6Скупка").lore("&7Сервер принимает почти любые предметы").build(), cl -> openSell(viewer, this));
                set(32, Items.of(Material.NAME_TAG).name("&eЦена предмета").lore("&7Возьмите в руку и нажмите /worth").build());
                if (parent != null) parent(parent);
                backButton(31);
            }
        }.open();
    }

    private void openCategory(Player p, Category c, Menu parent) {
        new PagedMenu(p, "&8" + Text.strip(c.name())) {
            @Override
            protected List<Entry> entries() {
                List<Entry> out = new ArrayList<>();
                for (Material m : c.items()) {
                    long price = buyPrice(m);
                    if (price <= 0) continue;
                    out.add(new Entry(Items.of(m).lore("&7Цена: &6" + Text.money(price) + " &7за шт.", "",
                            "&eЛКМ &7— купить 1", "&eShift+ЛКМ &7— купить стак (" + m.getMaxStackSize() + ")",
                            "&eПКМ &7— указать количество").build(), cl -> {
                        if (cl.right()) {
                            ChatInput.ask(viewer, Msg.t("shop.ask-amount", "&fСколько купить?"), s -> {
                                int n;
                                try { n = Integer.parseInt(s.trim()); } catch (NumberFormatException e) { Msg.error(viewer, "input.number", "Нужно число."); return; }
                                buy(viewer, m, n, null);
                            });
                        } else {
                            buy(viewer, m, cl.shift() ? m.getMaxStackSize() : 1, null);
                        }
                    }));
                }
                return out;
            }
        }.parent(parent).open();
    }

    public void openSell(Player p, Menu parent) {
        if (!plugin.economy().tradeAllowed(p)) return;
        new SellMenu(p).parent(parent).open();
    }

    private final class SellMenu extends Menu {
        private boolean done;

        SellMenu(Player p) {
            super(p, 6, "&8Скупка — положите предметы");
        }

        @Override protected boolean allowInput(int rawSlot) { return !done && rawSlot >= 0 && rawSlot < 45; }

        @Override
        protected void onBottomClick(org.bukkit.event.inventory.InventoryClickEvent e) {
            if (!done) e.setCancelled(false);
            onInputChange();
        }

        @Override protected void onInputChange() { Tasks.later(1, this::updateButton); }

        @Override
        protected void build() {
            for (int i = 45; i < 54; i++) set(i, Items.filler(Material.GRAY_STAINED_GLASS_PANE));
            updateButton();
            set(45, Items.of(Material.BOOK).name("&eКак работает скупка").lore("&7Цена зависит от предмета.", "&7Крафт не повышает цену.",
                    "&7Есть дневной лимит заработка", "&7— фермы не дают бесконечных денег.").build());
            set(53, Items.of(Material.BARRIER).name("&cЗакрыть").lore("&7Предметы вернутся в инвентарь").build(), c -> viewer.closeInventory());
        }

        private void updateButton() {
            if (inv == null) return;
            List<ItemStack> content = new ArrayList<>();
            for (int i = 0; i < 45; i++) {
                ItemStack it = inv.getItem(i);
                if (it != null && !it.getType().isAir()) content.add(it.clone());
            }
            // Тот же расчёт, что и при продаже: показанная сумма совпадает с начисленной.
            Quote q = quote(viewer.getUniqueId(), content);
            int accepted = 0, rejected = 0;
            for (ItemStack it : q.sold()) accepted += it.getAmount();
            for (ItemStack it : q.rejected()) rejected += it.getAmount();
            set(49, Items.of(Material.EMERALD).name("&a&lПродать за " + Text.money(q.total()))
                    .lore("&7Принимается: &f" + accepted + " шт.", rejected > 0 ? "&cНе принимается: " + rejected + " шт. (вернутся)" : "",
                            q.hitLimit() ? "&cЧасть сверх дневного лимита скупки" : "",
                            "&7Чем больше одного вида за день,", "&7тем ниже цена за штуку.",
                            "", "&eНажмите для продажи").build(), c -> sell());
        }

        private void sell() {
            if (done) return;
            if (!busy.add(viewer.getUniqueId())) return;
            List<ItemStack> toSell = new ArrayList<>();
            for (int i = 0; i < 45; i++) {
                ItemStack it = inv.getItem(i);
                if (it == null || it.getType().isAir()) continue;
                if (sellPrice(it) > 0) { toSell.add(it.clone()); inv.setItem(i, null); }
            }
            if (toSell.isEmpty()) { busy.remove(viewer.getUniqueId()); Msg.error(viewer, "sell.nothing", "Нечего продавать."); return; }
            lock();
            sellItems(viewer, toSell, () -> {
                busy.remove(viewer.getUniqueId());
                unlock();
                if (viewer.getOpenInventory().getTopInventory() == inv) updateButton();
            });
        }

        @Override
        protected void onClose() {
            done = true;
            for (int i = 0; i < 45; i++) {
                ItemStack it = inv.getItem(i);
                if (it == null || it.getType().isAir()) continue;
                inv.setItem(i, null);
                var left = viewer.getInventory().addItem(it);
                for (ItemStack l : left.values()) viewer.getWorld().dropItemNaturally(viewer.getLocation(), l);
            }
            viewer.saveData();
        }
    }

    public Map<String, Category> categories() { return categories; }

    public ValueService values() { return values; }
}
