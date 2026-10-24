package ru.unknownsmp.events.types;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDeathEvent;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.events.EventService;
import ru.unknownsmp.events.GameEvent;

import java.util.Random;

/** Временная аномалия: из разлома волнами выходят существа. Вклад — победы над ними и активное удержание зоны. */
public final class AnomalyEvent extends GameEvent {
    private static final double RADIUS = 14;
    private final Random rnd = new Random();
    private int seconds;
    private int kills;

    public AnomalyEvent(UnknownSMP plugin, EventService s) {
        super(plugin, s);
    }

    @Override public String type() { return "anomaly"; }
    @Override public String title() { return "Временная аномалия"; }
    @Override public String warning() { return "Воздух дрожит, компасы сходят с ума... Где-то рвётся ткань мира."; }
    @Override public int minutes() { return plugin.configs().get("events.yml").getInt("types.anomaly.minutes", 10); }
    @Override public String description() { return "Сдерживайте существ из разлома."; }

    @Override
    protected boolean setup() { return true; }

    @Override
    protected void tick() {
        seconds++;
        World w = loc.getWorld();
        for (int i = 0; i < 12; i++) {
            double a = (seconds * 0.3 + i * Math.PI / 6);
            w.spawnParticle(Particle.REVERSE_PORTAL, loc.clone().add(Math.cos(a) * RADIUS, 1 + (i % 3), Math.sin(a) * RADIUS), 2, 0, 0, 0, 0);
        }
        w.spawnParticle(Particle.PORTAL, loc.clone().add(0, 1.5, 0), 20, 0.6, 1, 0.6, 0.5);
        int alive = 0;
        for (var e : spawned) if (e.isValid()) alive++;
        int maxAlive = plugin.configs().get("events.yml").getInt("types.anomaly.max-mobs", 10);
        if (seconds % 15 == 0 && alive < maxAlive) wave(Math.min(maxAlive - alive, 3 + seconds / 120));
        if (seconds % 30 == 0) {
            for (Player p : w.getPlayers()) if (near(p, RADIUS) && plugin.auth().isAuthed(p)) contribute(p, 1, "hold");
        }
    }

    private void wave(int n) {
        World w = loc.getWorld();
        EntityType[] types = {EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER, EntityType.ENDERMITE, EntityType.VEX};
        for (int i = 0; i < n; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            Location spot = w.getHighestBlockAt((int) (loc.getX() + Math.cos(a) * 5), (int) (loc.getZ() + Math.sin(a) * 5)).getLocation().add(0.5, 1, 0.5);
            LivingEntity m = (LivingEntity) w.spawnEntity(spot, types[rnd.nextInt(types.length)]);
            m.customName(Text.c("&5Порождение разлома"));
            m.setRemoveWhenFarAway(false);
            mark(m);
            if (m instanceof Monster mon) {
                for (Player p : w.getPlayers()) if (near(p, RADIUS + 10)) { mon.setTarget(p); break; }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void death(EntityDeathEvent e) {
        if (!spawned.contains(e.getEntity())) return;
        e.getDrops().clear();
        e.setDroppedExp(2);
        Player k = e.getEntity().getKiller();
        if (k != null) {
            kills++;
            contribute(k, 2, "kill");
            Msg.actionBar(k, "&5Аномалия: повержено " + kills);
        }
    }

    @Override
    protected boolean succeeded() {
        return kills >= plugin.configs().get("events.yml").getInt("types.anomaly.success-kills", 15);
    }
}
