package ru.unknownsmp.events.types;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.events.EventService;
import ru.unknownsmp.events.GameEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Цепочка секретов: три знака-магнетита. Каждый найденный знак публикует подсказку к следующему.
 * Первый нашедший знак получает больший вклад, остальные — меньший (один раз за знак).
 */
public final class SecretChainEvent extends GameEvent {
    private final List<Block> signs = new ArrayList<>();
    private final List<Set<UUID>> finders = new ArrayList<>();
    private int revealed; // сколько знаков открыто
    private final Random rnd = new Random();

    public SecretChainEvent(UnknownSMP plugin, EventService s) {
        super(plugin, s);
    }

    @Override public String type() { return "secrets"; }
    @Override public String title() { return "Цепочка секретов"; }
    @Override public String warning() { return "Старые камни начинают светиться... Кто-то оставил следы для внимательных."; }
    @Override public int minutes() { return plugin.configs().get("events.yml").getInt("types.secrets.minutes", 25); }
    @Override public String description() { return "Найдите знак (магнетит) и нажмите на него — он укажет путь к следующему."; }

    @Override
    protected boolean setup() {
        World w = loc.getWorld();
        Location cur = loc.clone();
        for (int i = 0; i < 3; i++) {
            Block b;
            if (i == 0) b = cur.getBlock();
            else {
                double a = rnd.nextDouble() * Math.PI * 2;
                int d = 60 + rnd.nextInt(80);
                int x = (int) (cur.getX() + Math.cos(a) * d), z = (int) (cur.getZ() + Math.sin(a) * d);
                w.getChunkAt(x >> 4, z >> 4);
                b = w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES).getRelative(0, 1, 0);
            }
            b.setType(Material.LODESTONE, false);
            b.getRelative(0, -1, 0).setType(Material.CHISELED_STONE_BRICKS, false);
            signs.add(b);
            finders.add(new HashSet<>());
            cur = b.getLocation();
        }
        revealed = 1;
        return true;
    }

    @Override
    protected void tick() {
        for (int i = 0; i < revealed && i < signs.size(); i++) {
            Location l = signs.get(i).getLocation().add(0.5, 1.2, 0.5);
            l.getWorld().spawnParticle(Particle.ENCHANT, l, 10, 0.3, 0.5, 0.3, 0.5);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void click(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        int idx = signs.indexOf(e.getClickedBlock());
        if (idx < 0) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (idx >= revealed) { Msg.error(p, "secrets.locked", "Знак молчит. Сначала найдите предыдущий."); return; }
        if (!finders.get(idx).add(p.getUniqueId())) { Msg.actionBar(p, "&7Вы уже разгадали этот знак."); return; }
        boolean first = finders.get(idx).size() == 1;
        contribute(p, first ? 6 : 2, "sign" + idx);
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.2f);
        if (idx == signs.size() - 1) {
            if (first) {
                announce("secrets.final", "&d{p} разгадал последний знак цепочки секретов!", "p", p.getName());
                ru.unknownsmp.core.Tasks.later(20 * 60, this::finishNow);
                Msg.send(p, "secrets.final-hint", "&7У остальных есть минута, чтобы тоже коснуться знака.");
            }
            return;
        }
        if (first) {
            revealed = Math.max(revealed, idx + 2);
            Block next = signs.get(idx + 1);
            int hx = (next.getX() / 20) * 20, hz = (next.getZ() / 20) * 20;
            announce("secrets.next", "&d{p} разгадал знак {n}/3! &fСледующий знак — около &e{x}, {z}&f.", "p", p.getName(), "n", idx + 1, "x", hx, "z", hz);
        } else {
            Block next = signs.get(idx + 1);
            Msg.send(p, "secrets.next-private", "&7Знак шепчет: следующий — около {x}, {z}.", "x", (next.getX() / 20) * 20, "z", (next.getZ() / 20) * 20);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void brk(BlockBreakEvent e) {
        if (signs.contains(e.getBlock())) e.setCancelled(true);
    }

    @Override
    protected boolean succeeded() { return !finders.get(finders.size() - 1).isEmpty(); }

    @Override
    protected void cleanup() {
        super.cleanup();
        for (Block b : signs) if (b.getType() == Material.LODESTONE) b.setType(Material.CHISELED_STONE_BRICKS, false);
    }
}
