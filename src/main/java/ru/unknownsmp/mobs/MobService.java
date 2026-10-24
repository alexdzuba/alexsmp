package ru.unknownsmp.mobs;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import ru.unknownsmp.core.Keys;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;

import java.util.Random;

/**
 * Ванильные мобы с умеренными усилениями: ночью — редкие «усиленные» варианты, чаще вдали от спавна;
 * вдали от спавна мобы замечают игроков с большего расстояния. Базовый спавн остаётся ванильным.
 */
public final class MobService implements Module, Listener {
    private final UnknownSMP plugin;
    private final Random rnd = new Random();

    public MobService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "mobs"; }
    @Override public String title() { return "Мобы"; }
    @Override public String[] depends() { return new String[]{"world"}; }

    @Override
    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private double distFromSpawn(Location l) {
        Location s = plugin.world().spawn();
        if (s.getWorld() != l.getWorld()) return 0;
        double dx = l.getX() - s.getX(), dz = l.getZ() - s.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void spawn(CreatureSpawnEvent e) {
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) return;
        if (!(e.getEntity() instanceof Monster m)) return;
        World w = e.getLocation().getWorld();
        if (w.getEnvironment() != World.Environment.NORMAL || !w.equals(plugin.survivalWorld())) return;
        var c = plugin.configs().get("pvp.yml");
        double d = distFromSpawn(e.getLocation());
        double farFrom = c.getDouble("mobs.far-distance", 3000);
        if (d > farFrom) {
            AttributeInstance fr = m.getAttribute(Attribute.FOLLOW_RANGE);
            if (fr != null) fr.setBaseValue(fr.getBaseValue() * c.getDouble("mobs.far-follow-multiplier", 1.3));
        }
        long time = w.getTime();
        boolean night = time > 13000 && time < 23000;
        if (!night) return;
        double chance = Math.min(c.getDouble("mobs.enhanced.max-chance", 0.08),
                c.getDouble("mobs.enhanced.base-chance", 0.01) + c.getDouble("mobs.enhanced.chance-per-1000", 0.006) * d / 1000.0);
        if (rnd.nextDouble() > chance) return;
        enhance(m, c.getDouble("mobs.enhanced.health-multiplier", 1.5), c.getDouble("mobs.enhanced.damage-multiplier", 1.25));
    }

    private void enhance(LivingEntity m, double hpMul, double dmgMul) {
        AttributeInstance hp = m.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) {
            hp.setBaseValue(hp.getBaseValue() * hpMul);
            m.setHealth(hp.getBaseValue());
        }
        AttributeInstance dmg = m.getAttribute(Attribute.ATTACK_DAMAGE);
        if (dmg != null) dmg.setBaseValue(dmg.getBaseValue() * dmgMul);
        AttributeInstance sp = m.getAttribute(Attribute.MOVEMENT_SPEED);
        if (sp != null) sp.setBaseValue(sp.getBaseValue() * 1.1);
        m.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 20 * 8, 0, true, false));
        m.customName(Text.c("&cУсиленный " + name(m)));
        m.setCustomNameVisible(false);
        m.getPersistentDataContainer().set(Keys.ENHANCED_MOB, PersistentDataType.BYTE, (byte) 1);
    }

    private static String name(LivingEntity e) {
        return switch (e.getType()) {
            case ZOMBIE -> "зомби";
            case SKELETON -> "скелет";
            case SPIDER -> "паук";
            case CREEPER -> "крипер";
            case ENDERMAN -> "эндермен";
            case WITCH -> "ведьма";
            case HUSK -> "кадавр";
            case STRAY -> "зимогор";
            case DROWNED -> "утопленник";
            default -> "моб";
        };
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void death(EntityDeathEvent e) {
        if (!e.getEntity().getPersistentDataContainer().has(Keys.ENHANCED_MOB, PersistentDataType.BYTE)) return;
        Player k = e.getEntity().getKiller();
        if (k == null) return;
        e.setDroppedExp(e.getDroppedExp() * 3);
        var c = plugin.configs().get("pvp.yml");
        long money = c.getLong("mobs.enhanced.money-min", 50) + rnd.nextInt(Math.max(1, c.getInt("mobs.enhanced.money-extra", 150)));
        plugin.economy().deposit(k.getUniqueId(), money, "enhanced_mob", e.getEntityType().name());
        plugin.profiles().addStat(k, "enhanced_killed", 1);
        Msg.actionBar(k, "&cУсиленный противник повержен: &6+" + Text.money(money));
        if (rnd.nextDouble() < c.getDouble("mobs.enhanced.loot-chance", 0.15)) {
            plugin.items().roll("enhanced_mob", k, "enhanced_mob").thenAccept(d -> {
                if (d != null) ru.unknownsmp.core.Tasks.sync(() -> Msg.ok(k, "mobs.loot", "Ценная находка: {d}", "d", d));
            });
        }
    }
}
