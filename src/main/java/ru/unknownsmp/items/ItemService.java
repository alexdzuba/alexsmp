package ru.unknownsmp.items;

import com.google.common.collect.Multimap;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import ru.unknownsmp.core.ItemSpec;
import ru.unknownsmp.core.Keys;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.db.Database;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Уникальные предметы, улучшенные зелья и таблицы добычи.
 * Подлинность: внутренний UID + тип + HMAC-подпись в PersistentDataContainer и запись в реестре unique_items.
 * Название, описание и цвет текста подлинность не подтверждают.
 */
public final class ItemService implements Module, Listener {
    public record Passive(String type, PotionEffectType effect, int amplifier, int duration, double chance, int cooldownSec) {}

    public record Def(String id, Material material, String name, String rarity, List<String> lore, int limit,
                      Map<Attribute, Double> attributes, List<Passive> passives, boolean story) {}

    public record PotionDef(String id, String name, String rarity, Color color, List<PotionEffect> effects) {}

    public record LootEntry(String item, long money, int weight) {}

    private final UnknownSMP plugin;
    private final Map<String, Def> defs = new LinkedHashMap<>();
    private final Map<String, PotionDef> potions = new LinkedHashMap<>();
    private final Map<String, List<LootEntry>> loot = new HashMap<>();
    private final Map<String, String[]> rarities = new LinkedHashMap<>();
    private final Map<String, String> status = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private final Random rnd = new Random();
    private byte[] secret;

    public ItemService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "items"; }
    @Override public String title() { return "Уникальные предметы"; }
    @Override public String[] depends() { return new String[]{"database", "state"}; }

    @Override
    public void enable() throws Exception {
        String s = plugin.state().get("item_secret");
        if (s == null) {
            byte[] b = new byte[32];
            new SecureRandom().nextBytes(b);
            s = Base64.getEncoder().encodeToString(b);
            plugin.state().set("item_secret", s).get();
        }
        secret = Base64.getDecoder().decode(s);
        plugin.db().queryBlocking(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT uid, status FROM unique_items"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) status.put(rs.getString(1), rs.getString(2));
            }
            return null;
        });
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Tasks.timer(40, 40, this::holdEffects);
        Tasks.timer(20 * 300, 20 * 300, this::scanDuplicates);
    }

    @Override
    public void reload() {
        YamlConfiguration c = plugin.configs().get("items.yml");
        rarities.clear();
        ConfigurationSection rs = c.getConfigurationSection("rarities");
        if (rs != null) for (String k : rs.getKeys(false)) rarities.put(k, new String[]{rs.getString(k + ".name", k), rs.getString(k + ".color", "&f")});
        defs.clear();
        ConfigurationSection is = c.getConfigurationSection("items");
        if (is != null) for (String id : is.getKeys(false)) {
            ConfigurationSection d = is.getConfigurationSection(id);
            Material m = Material.matchMaterial(d.getString("material", "STICK"));
            if (m == null) { plugin.getLogger().warning("items.yml: неизвестный материал у " + id); continue; }
            Map<Attribute, Double> attrs = new LinkedHashMap<>();
            ConfigurationSection as = d.getConfigurationSection("attributes");
            if (as != null) for (String a : as.getKeys(false)) {
                Attribute at = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(a.toLowerCase(Locale.ROOT)));
                if (at == null) { plugin.getLogger().warning("items.yml: неизвестный атрибут " + a + " у " + id); continue; }
                attrs.put(at, as.getDouble(a));
            }
            List<Passive> passives = new ArrayList<>();
            for (Map<?, ?> pm : d.getMapList("passives")) {
                Object eff = pm.get("effect");
                PotionEffectType t = eff == null ? null : Registry.EFFECT.get(NamespacedKey.minecraft(eff.toString().toLowerCase(Locale.ROOT)));
                if (t == null) { plugin.getLogger().warning("items.yml: неизвестный эффект у " + id); continue; }
                passives.add(new Passive(String.valueOf(pm.containsKey("type") ? pm.get("type") : "HOLD").toUpperCase(Locale.ROOT), t,
                        num(pm.get("amplifier"), 0), num(pm.get("duration"), 60), dbl(pm.get("chance"), 1.0), num(pm.get("cooldown"), 0)));
            }
            defs.put(id, new Def(id, m, d.getString("name", id), d.getString("rarity", "common"), d.getStringList("lore"),
                    d.getInt("limit", 0), attrs, passives, d.getBoolean("story", false)));
        }
        potions.clear();
        ConfigurationSection ps = c.getConfigurationSection("potions");
        if (ps != null) for (String id : ps.getKeys(false)) {
            ConfigurationSection d = ps.getConfigurationSection(id);
            List<PotionEffect> effs = new ArrayList<>();
            for (Map<?, ?> em : d.getMapList("effects")) {
                PotionEffectType t = Registry.EFFECT.get(NamespacedKey.minecraft(String.valueOf(em.get("effect")).toLowerCase(Locale.ROOT)));
                if (t == null) continue;
                effs.add(new PotionEffect(t, num(em.get("duration"), 600), num(em.get("amplifier"), 0)));
            }
            Color col;
            try { col = Color.fromRGB(Integer.parseInt(d.getString("color", "#8800FF").replace("#", ""), 16)); } catch (Exception e) { col = Color.PURPLE; }
            potions.put(id, new PotionDef(id, d.getString("name", id), d.getString("rarity", "rare"), col, effs));
        }
        loot.clear();
        ConfigurationSection ls = c.getConfigurationSection("loot-tables");
        if (ls != null) for (String t : ls.getKeys(false)) {
            List<LootEntry> list = new ArrayList<>();
            for (Map<?, ?> e : ls.getMapList(t)) {
                list.add(new LootEntry(e.containsKey("item") ? String.valueOf(e.get("item")) : null,
                        e.containsKey("money") ? Long.parseLong(String.valueOf(e.get("money"))) : 0, num(e.get("weight"), 1)));
            }
            loot.put(t, list);
        }
    }

    private static int num(Object o, int def) {
        if (o == null) return def;
        try { return (int) Double.parseDouble(o.toString()); } catch (NumberFormatException e) { return def; }
    }

    private static double dbl(Object o, double def) {
        if (o == null) return def;
        try { return Double.parseDouble(o.toString()); } catch (NumberFormatException e) { return def; }
    }

    public Map<String, Def> defs() { return defs; }

    public Map<String, PotionDef> potions() { return potions; }

    public String rarityName(String r) {
        String[] v = rarities.get(r);
        return v == null ? r : v[0];
    }

    public String rarityColor(String r) {
        String[] v = rarities.get(r);
        return v == null ? "&f" : v[1];
    }

    // ------------------------------------------------------------------ Подпись

    private String sign(String uid, String type) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] h = mac.doFinal((uid + ":" + type).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(h).substring(0, 22);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String uidOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(Keys.ITEM_UID, PersistentDataType.STRING);
    }

    public String typeOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(Keys.ITEM_TYPE, PersistentDataType.STRING);
    }

    /** Подлинный и активный уникальный предмет. */
    public boolean isAuthentic(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return false;
        PersistentDataContainer pdc = it.getItemMeta().getPersistentDataContainer();
        String uid = pdc.get(Keys.ITEM_UID, PersistentDataType.STRING);
        String type = pdc.get(Keys.ITEM_TYPE, PersistentDataType.STRING);
        String sig = pdc.get(Keys.ITEM_SIG, PersistentDataType.STRING);
        if (uid == null || type == null || sig == null) return false;
        if (!sign(uid, type).equals(sig)) return false;
        return "ACTIVE".equals(status.get(uid));
    }

    public boolean isType(ItemStack it, String type) {
        return type.equals(typeOf(it)) && isAuthentic(it);
    }

    public boolean canDrop(ItemStack it) { return true; }

    // ------------------------------------------------------------------ Создание

    /** Создать уникальный предмет: регистрирует UID в реестре (с проверкой лимита) и возвращает предмет. */
    public CompletableFuture<ItemStack> create(String id, String holder, String note) {
        Def d = defs.get(id);
        if (d == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Неизвестный предмет: " + id));
        String uid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return plugin.db().tx(c -> {
            if (d.limit() > 0) {
                Long cnt = Database.longVal(c, "SELECT COUNT(*) FROM unique_items WHERE type=? AND status IN ('ACTIVE','RITUAL')", id);
                if (cnt != null && cnt >= d.limit()) throw new Database.TxAbort("items.limit", "Достигнут лимит экземпляров предмета.");
            }
            Database.update(c, "INSERT INTO unique_items(uid, type, status, created_at, holder, note) VALUES (?,?,?,?,?,?)",
                    uid, id, "ACTIVE", System.currentTimeMillis(), holder, note);
            return uid;
        }).thenApply(u -> {
            status.put(u, "ACTIVE");
            plugin.logs().log((java.util.UUID) null, holder, "item", "unique_create", id + " uid=" + u + " " + note);
            return build(d, u);
        });
    }

    private ItemStack build(Def d, String uid) {
        ItemStack it = new ItemStack(d.material());
        ItemMeta meta = it.getItemMeta();
        meta.itemName(Text.item(rarityColor(d.rarity()) + d.name()));
        List<String> lore = new ArrayList<>();
        lore.add(rarityName(d.rarity()));
        lore.add("");
        lore.addAll(d.lore());
        if (!d.attributes().isEmpty()) {
            lore.add("");
            for (var e : d.attributes().entrySet()) lore.add("&9+" + trim(e.getValue()) + " " + attrName(e.getKey()));
        }
        for (Passive p : d.passives()) {
            lore.add("&5◆ " + passiveText(p));
        }
        lore.add("");
        lore.add("&8ID: " + uid);
        meta.lore(Text.lore(lore));
        applyAttributes(meta, d);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(Keys.ITEM_UID, PersistentDataType.STRING, uid);
        pdc.set(Keys.ITEM_TYPE, PersistentDataType.STRING, d.id());
        pdc.set(Keys.ITEM_SIG, PersistentDataType.STRING, sign(uid, d.id()));
        if (d.story()) meta.setEnchantmentGlintOverride(true);
        it.setItemMeta(meta);
        return it;
    }

    private void applyAttributes(ItemMeta meta, Def d) {
        if (d.attributes().isEmpty()) return;
        EquipmentSlot slot = d.material().getEquipmentSlot();
        // Пользовательские модификаторы заменяют ванильные — сохраняем ванильные значения предмета.
        try {
            Multimap<Attribute, AttributeModifier> def = d.material().getDefaultAttributeModifiers(slot);
            for (var e : def.entries()) meta.addAttributeModifier(e.getKey(), e.getValue());
        } catch (Throwable ignored) {}
        EquipmentSlotGroup group = slot.getGroup();
        int i = 0;
        for (var e : d.attributes().entrySet()) {
            meta.addAttributeModifier(e.getKey(), new AttributeModifier(new NamespacedKey(plugin, "u_" + d.id() + "_" + (i++)),
                    e.getValue(), AttributeModifier.Operation.ADD_NUMBER, group));
        }
    }

    private static String trim(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.2f", d);
    }

    private static String attrName(Attribute a) {
        String k = a.getKey().getKey();
        return switch (k) {
            case "attack_damage" -> "к урону";
            case "attack_speed" -> "к скорости атаки";
            case "armor" -> "к броне";
            case "armor_toughness" -> "к прочности брони";
            case "movement_speed" -> "к скорости";
            case "max_health" -> "к здоровью";
            case "knockback_resistance" -> "к сопротивлению отбрасыванию";
            case "luck" -> "к удаче";
            default -> k;
        };
    }

    private static String effName(PotionEffectType t) {
        String k = t.getKey().getKey();
        return switch (k) {
            case "night_vision" -> "Ночное зрение";
            case "slowness" -> "Замедление";
            case "speed" -> "Скорость";
            case "regeneration" -> "Регенерация";
            case "fire_resistance" -> "Огнестойкость";
            case "water_breathing" -> "Подводное дыхание";
            case "haste" -> "Спешка";
            case "weakness" -> "Слабость";
            case "poison" -> "Отравление";
            case "strength" -> "Сила";
            case "resistance" -> "Сопротивление";
            case "absorption" -> "Поглощение";
            case "jump_boost" -> "Прыгучесть";
            case "glowing" -> "Свечение";
            case "luck" -> "Удача";
            case "saturation" -> "Насыщение";
            case "slow_falling" -> "Плавное падение";
            case "wither" -> "Иссушение";
            default -> k;
        };
    }

    private String passiveText(Passive p) {
        String lvl = p.amplifier() > 0 ? " " + (p.amplifier() + 1) : "";
        String cd = p.cooldownSec() > 0 ? " &8(перезарядка " + p.cooldownSec() + " с)" : "";
        return switch (p.type()) {
            case "ON_HIT" -> "При ударе: " + (int) Math.round(p.chance() * 100) + "% наложить " + effName(p.effect()) + lvl + " на " + p.duration() / 20 + " с" + cd;
            case "ON_KILL" -> "При убийстве: " + effName(p.effect()) + lvl + " на " + p.duration() / 20 + " с" + cd;
            default -> "Пока в руке/надето: " + effName(p.effect()) + lvl;
        };
    }

    public ItemStack potion(String id) {
        PotionDef d = potions.get(id);
        if (d == null) return null;
        ItemStack it = new ItemStack(Material.POTION);
        PotionMeta pm = (PotionMeta) it.getItemMeta();
        pm.itemName(Text.item(rarityColor(d.rarity()) + d.name()));
        pm.setColor(d.color());
        for (PotionEffect e : d.effects()) pm.addCustomEffect(e, true);
        List<String> lore = new ArrayList<>();
        lore.add(rarityName(d.rarity()));
        for (PotionEffect e : d.effects()) lore.add("&9" + effName(e.getType()) + (e.getAmplifier() > 0 ? " " + (e.getAmplifier() + 1) : "") + " &7(" + Text.duration(e.getDuration() * 50L) + ")");
        pm.lore(Text.lore(lore));
        pm.getPersistentDataContainer().set(Keys.ITEM_TYPE, PersistentDataType.STRING, "potion:" + id);
        it.setItemMeta(pm);
        return it;
    }

    public void setStatus(String uid, String st, String holder) {
        status.put(uid, st);
        plugin.db().tx(c -> Database.update(c, "UPDATE unique_items SET status=?, holder=? WHERE uid=?", st, holder, uid));
    }

    public String status(String uid) { return status.get(uid); }

    // ------------------------------------------------------------------ Таблицы добычи

    /** Бросок таблицы добычи: выдаёт предмет (в инвентарь или почту) или деньги. Возвращает описание. */
    public CompletableFuture<String> roll(String table, Player p, String source) {
        List<LootEntry> list = loot.get(table);
        if (list == null || list.isEmpty()) return CompletableFuture.completedFuture(null);
        int total = 0;
        for (LootEntry e : list) total += Math.max(0, e.weight());
        if (total <= 0) return CompletableFuture.completedFuture(null);
        int r = rnd.nextInt(total);
        LootEntry pick = list.get(0);
        for (LootEntry e : list) { r -= Math.max(0, e.weight()); if (r < 0) { pick = e; break; } }
        return give(p, pick, source);
    }

    public CompletableFuture<String> give(Player p, LootEntry e, String source) {
        if (e.money() > 0) {
            return plugin.economy().deposit(p.getUniqueId(), e.money(), "loot", source).thenApply(b -> Text.money(e.money()));
        }
        return resolve(e.item(), p.getName(), source).thenApply(it -> {
            if (it == null) return null;
            Tasks.sync(() -> plugin.mail().giveOrMail(p, it, "reward"));
            return it.getAmount() + "x " + ru.unknownsmp.gui.Items.itemName(it);
        });
    }

    /** "unique:id", "potion:id" или обычный предмет "MATERIAL:кол-во". */
    public CompletableFuture<ItemStack> resolve(String spec, String holder, String note) {
        if (spec == null) return CompletableFuture.completedFuture(null);
        if (spec.startsWith("unique:")) {
            return create(spec.substring(7), holder, note).exceptionally(t -> null);
        }
        if (spec.startsWith("potion:")) {
            String[] p = spec.substring(7).split(":");
            ItemStack it = potion(p[0]);
            if (it != null && p.length > 1) try { it.setAmount(Integer.parseInt(p[1])); } catch (NumberFormatException ignored) {}
            return CompletableFuture.completedFuture(it);
        }
        return CompletableFuture.completedFuture(ItemSpec.parse(spec));
    }

    // ------------------------------------------------------------------ Пассивные эффекты

    private void holdEffects() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            List<ItemStack> worn = new ArrayList<>();
            worn.add(p.getInventory().getItemInMainHand());
            for (ItemStack a : p.getInventory().getArmorContents()) if (a != null) worn.add(a);
            for (ItemStack it : worn) {
                String type = typeOf(it);
                if (type == null) continue;
                Def d = defs.get(type);
                if (d == null || d.passives().isEmpty()) continue;
                if (!isAuthentic(it)) continue;
                for (Passive ps : d.passives()) {
                    if (!ps.type().equals("HOLD")) continue;
                    p.addPotionEffect(new PotionEffect(ps.effect(), 100, ps.amplifier(), true, false, true));
                }
            }
        }
    }

    private boolean cooldown(Player p, String key, int sec) {
        if (sec <= 0) return false;
        String k = p.getUniqueId() + ":" + key;
        long now = System.currentTimeMillis();
        Long until = cooldowns.get(k);
        if (until != null && until > now) return true;
        cooldowns.put(k, now + sec * 1000L);
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof LivingEntity victim)) return;
        ItemStack it = p.getInventory().getItemInMainHand();
        String type = typeOf(it);
        if (type == null) return;
        Def d = defs.get(type);
        if (d == null || !isAuthentic(it)) return;
        for (Passive ps : d.passives()) {
            if (!ps.type().equals("ON_HIT")) continue;
            if (rnd.nextDouble() > ps.chance()) continue;
            if (cooldown(p, type + ":hit", ps.cooldownSec())) continue;
            victim.addPotionEffect(new PotionEffect(ps.effect(), ps.duration(), ps.amplifier()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent e) {
        Player p = e.getEntity().getKiller();
        if (p == null) return;
        ItemStack it = p.getInventory().getItemInMainHand();
        String type = typeOf(it);
        if (type == null) return;
        Def d = defs.get(type);
        if (d == null || !isAuthentic(it)) return;
        for (Passive ps : d.passives()) {
            if (!ps.type().equals("ON_KILL")) continue;
            if (cooldown(p, type + ":kill", ps.cooldownSec())) continue;
            p.addPotionEffect(new PotionEffect(ps.effect(), ps.duration(), ps.amplifier()));
        }
    }

    // ------------------------------------------------------------------ Сохранность и подделки

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void itemSpawn(ItemSpawnEvent e) {
        ItemStack it = e.getEntity().getItemStack();
        String type = typeOf(it);
        if (type == null || type.startsWith("potion:")) return;
        Def d = defs.get(type);
        if (d != null && (d.story() || d.limit() > 0)) {
            // Ограниченные и сюжетные предметы не сгорают и не исчезают.
            Item ent = e.getEntity();
            ent.setInvulnerable(true);
            ent.setUnlimitedLifetime(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void despawn(ItemDespawnEvent e) {
        String type = typeOf(e.getEntity().getItemStack());
        if (type == null) return;
        Def d = defs.get(type);
        if (d != null && (d.story() || d.limit() > 0)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void itemDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Item item)) return;
        String type = typeOf(item.getItemStack());
        if (type == null) return;
        Def d = defs.get(type);
        if (d != null && (d.story() || d.limit() > 0)) {
            e.setCancelled(true);
            if (e.getCause() == EntityDamageEvent.DamageCause.VOID) {
                // Упал в пустоту — возвращаем на поверхность.
                var w = item.getWorld();
                var top = w.getHighestBlockAt(item.getLocation()).getLocation().add(0.5, 1, 0.5);
                item.teleport(top);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        ItemStack it = e.getItem().getItemStack();
        String uid = uidOf(it);
        if (uid == null) return;
        if (!isAuthentic(it)) {
            plugin.logs().suspicious(p, "counterfeit_item", "uid=" + uid + " type=" + typeOf(it) + " status=" + status.get(uid));
            Msg.error(p, "items.counterfeit", "Этот предмет не прошёл проверку подлинности — его свойства не действуют.");
        } else {
            plugin.db().tx(c -> Database.update(c, "UPDATE unique_items SET holder=? WHERE uid=?", p.getName(), uid));
        }
    }

    /** Поиск дубликатов UID у игроков онлайн (журнал + уведомление персонала, без автоматических наказаний). */
    private void scanDuplicates() {
        Map<String, String> seen = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            // Пустые ячейки — null, поэтому Arrays.asList (List.of падал с NullPointerException каждые 5 минут).
            List<ItemStack> all = new ArrayList<>(java.util.Arrays.asList(p.getInventory().getContents()));
            all.addAll(java.util.Arrays.asList(p.getEnderChest().getContents()));
            for (ItemStack it : all) {
                String uid = uidOf(it);
                if (uid == null) continue;
                String prev = seen.put(uid, p.getName());
                if (prev != null) plugin.logs().suspicious(p, "duplicate_uid", "uid=" + uid + " также у " + prev);
            }
        }
    }

    // ------------------------------------------------------------------ Исследование мира

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void lootGen(LootGenerateEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (e.isPlugin()) return;
        var cfg = plugin.configs().get("items.yml");
        double base = cfg.getDouble("exploration.base-chance", 0.06);
        double perKm = cfg.getDouble("exploration.chance-per-1000-blocks", 0.02);
        double max = cfg.getDouble("exploration.max-chance", 0.25);
        var spawn = plugin.world().spawn();
        double dist = spawn.getWorld() == p.getWorld() ? Math.sqrt(Math.pow(p.getX() - spawn.getX(), 2) + Math.pow(p.getZ() - spawn.getZ(), 2)) : 5000;
        double chance = Math.min(max, base + perKm * dist / 1000.0);
        plugin.quests().progress(p, "OPEN_LOOT", "any", 1);
        plugin.profiles().addStat(p, "loot_chests", 1);
        if (rnd.nextDouble() > chance) return;
        String table = dist > cfg.getDouble("exploration.far-distance", 6000) ? "exploration_far" : "exploration";
        List<LootEntry> list = loot.get(table);
        if (list == null || list.isEmpty()) return;
        int total = list.stream().mapToInt(x -> Math.max(0, x.weight())).sum();
        int r = rnd.nextInt(Math.max(1, total));
        LootEntry pick = list.get(0);
        for (LootEntry x : list) { r -= Math.max(0, x.weight()); if (r < 0) { pick = x; break; } }
        if (pick.money() > 0 || pick.item() == null) return;
        if (pick.item().startsWith("unique:")) {
            // Уникальная находка создаётся асинхронно и выдаётся игроку напрямую (сундук уже сгенерирован).
            final LootEntry fp = pick;
            Tasks.then(resolve(fp.item(), p.getName(), "exploration"), it -> {
                if (it == null) return;
                plugin.mail().giveOrMail(p, it, "reward");
                Msg.title(p, "&d✦ Редкая находка ✦", Text.plain(it.getItemMeta().itemName()), 5, 60, 15);
                plugin.logs().log(p, "progress", "exploration_unique", p.getLocation(), typeOf(it));
            }, null);
        } else {
            ItemStack it = resolve(pick.item(), p.getName(), "exploration").getNow(null);
            if (it != null) e.getLoot().add(it);
        }
    }
}
