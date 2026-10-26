package ru.unknownsmp.admin;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.util.Vector;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.UnknownSMP;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Маскировка креатива для игроков из quiet-commands.players:
 * удары по ним выглядят настоящими (анимация, звук, отбрасывание), а летать нельзя, пока их видит другой игрок.
 */
final class CreativeDisguise implements Listener {
    private final UnknownSMP plugin;
    private final AdminService admin;
    /** Кому мы сами выключили полёт — чтобы вернуть, когда никто не смотрит. */
    private final Set<UUID> grounded = ConcurrentHashMap.newKeySet();

    CreativeDisguise(UnknownSMP plugin, AdminService admin) {
        this.plugin = plugin;
        this.admin = admin;
        Tasks.timer(20, 5, this::tick);
    }

    private boolean enabled() {
        return plugin.configs().main().getBoolean("quiet-commands.creative-disguise", true);
    }

    private boolean disguised(Player p) {
        return enabled() && p.getGameMode() == GameMode.CREATIVE && QuietLog.quietPlayer(p.getName()) && !admin.isVanished(p);
    }

    /** Видит ли игрока кто-то из остальных (в радиусе и без стен между ними). */
    private boolean watched(Player p) {
        double r = plugin.configs().main().getDouble("quiet-commands.creative-view-distance", 64);
        double r2 = r * r;
        for (Player v : p.getWorld().getPlayers()) {
            if (v == p || v.getGameMode() == GameMode.SPECTATOR || !v.canSee(p)) continue;
            if (v.getLocation().distanceSquared(p.getLocation()) > r2) continue;
            if (v.hasLineOfSight(p)) return true;
        }
        return false;
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!disguised(p)) {
                if (grounded.remove(p.getUniqueId()) && p.getGameMode() == GameMode.CREATIVE) p.setAllowFlight(true);
                continue;
            }
            if (watched(p)) {
                if (p.isFlying() || p.getAllowFlight()) {
                    p.setFlying(false);
                    p.setAllowFlight(false);
                    grounded.add(p.getUniqueId());
                }
            } else if (grounded.remove(p.getUniqueId())) {
                p.setAllowFlight(true);
            }
        }
    }

    /** Не даём взлететь двойным прыжком, если кто-то смотрит (между проверками таймера). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void fly(PlayerToggleFlightEvent e) {
        Player p = e.getPlayer();
        if (e.isFlying() && disguised(p) && watched(p)) {
            e.setCancelled(true);
            p.setAllowFlight(false);
            grounded.add(p.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void mode(PlayerGameModeChangeEvent e) {
        if (e.getNewGameMode() != GameMode.CREATIVE) grounded.remove(e.getPlayer().getUniqueId());
    }

    /** Удар по игроку в креативе: сервер его игнорирует, поэтому рисуем попадание сами. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void attack(io.papermc.paper.event.player.PrePlayerAttackEntityEvent e) {
        if (!(e.getAttacked() instanceof Player target) || !disguised(target)) return;
        Player attacker = e.getPlayer();
        if (target.getNoDamageTicks() > target.getMaximumNoDamageTicks() / 2) return; // как у обычного игрока — не чаще раза в полсекунды
        target.setNoDamageTicks(target.getMaximumNoDamageTicks());
        float yaw = (float) Math.toDegrees(Math.atan2(attacker.getLocation().getZ() - target.getLocation().getZ(),
                attacker.getLocation().getX() - target.getLocation().getX())) - target.getLocation().getYaw();
        target.playHurtAnimation(yaw);
        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 1f);
        Vector dir = target.getLocation().toVector().subtract(attacker.getLocation().toVector()).setY(0);
        if (dir.lengthSquared() < 1e-4) dir = attacker.getLocation().getDirection().setY(0);
        double strength = attacker.isSprinting() ? 0.9 : 0.4;
        Vector kb = dir.normalize().multiply(strength).setY(target.isOnGround() ? 0.36 : 0.1);
        target.setVelocity(target.getVelocity().multiply(0.5).add(kb));
    }
}
