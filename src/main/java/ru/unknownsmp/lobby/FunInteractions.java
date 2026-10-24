package ru.unknownsmp.lobby;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Display;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import ru.unknownsmp.core.Keys;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.gui.Items;
import ru.unknownsmp.world.Anchors;
import ru.unknownsmp.world.Fun;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Игровая логика развлечений лобби и города: кликер, колесо удачи, колодец желаний, тир, снежные бои, салют,
 * дискотека и подписи-голограммы. Работает в лобби и в зоне спавна SMP.
 */
public final class FunInteractions implements Listener {
    private static final String[] SFX = {"_l", "_c"};
    private static final Material[] DISCO = {Material.RED_CONCRETE, Material.ORANGE_CONCRETE, Material.YELLOW_CONCRETE, Material.LIME_CONCRETE,
            Material.CYAN_CONCRETE, Material.LIGHT_BLUE_CONCRETE, Material.BLUE_CONCRETE, Material.PURPLE_CONCRETE, Material.MAGENTA_CONCRETE,
            Material.PINK_CONCRETE, Material.WHITE_CONCRETE};

    private final UnknownSMP plugin;
    private final LobbyService lobby;
    private final Random rnd = new Random();
    private final NamespacedKey tirKey = Keys.of("tir_item");
    private final Map<UUID, Long> clickCd = new ConcurrentHashMap<>();
    private final Map<UUID, Long> actionCd = new ConcurrentHashMap<>();
    private final Map<String, TextDisplay> labels = new ConcurrentHashMap<>();
    private volatile long lastFirework;

    FunInteractions(UnknownSMP plugin, LobbyService lobby) {
        this.plugin = plugin;
        this.lobby = lobby;
    }

    void start() {
        Tasks.timer(60, 200, this::labelTick);
        Tasks.timer(20, 10, this::discoTick);
        Tasks.timer(100, 100, this::cleanup);
    }

    boolean inPlay(Location l) {
        if (l == null || l.getWorld() == null) return false;
        return lobby.isLobby(l.getWorld()) || (plugin.world() != null && plugin.world().inSpawnZone(l));
    }

    private static boolean match(String anchor, Block b) {
        Location a = Anchors.get(anchor);
        return a != null && a.getWorld() != null && a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getX() && a.getBlockY() == b.getY() && a.getBlockZ() == b.getZ();
    }

    private boolean cooldown(Player p, long ms) {
        long now = System.currentTimeMillis();
        Long last = actionCd.get(p.getUniqueId());
        if (last != null && now - last < ms) return true;
        actionCd.put(p.getUniqueId(), now);
        return false;
    }

    // ------------------------------------------------------------------ Клики

    @EventHandler(priority = EventPriority.HIGH)
    public void interact(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_BLOCK && a != Action.LEFT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!inPlay(b.getLocation()) || !plugin.auth().isAuthed(p)) return;
        for (String sfx : SFX) {
            if (match("clicker" + sfx, b)) { e.setCancelled(true); clicker(p, b); return; }
            if (a == Action.RIGHT_CLICK_BLOCK && match("wheel_lever" + sfx, b)) { e.setCancelled(true); wheel(p, b); return; }
            if (a == Action.RIGHT_CLICK_BLOCK && match("fw_lever" + sfx, b)) { e.setCancelled(true); fireworks(p, sfx); return; }
            if (a == Action.RIGHT_CLICK_BLOCK && match("tir" + sfx, b)) { e.setCancelled(true); giveBow(p); return; }
            if (a == Action.RIGHT_CLICK_BLOCK) {
                Location w = Anchors.get("well" + sfx);
                if (w != null && w.getWorld() != null && w.getWorld().equals(b.getWorld())
                        && Math.hypot(w.getX() - b.getX() - 0.5, w.getZ() - b.getZ() - 0.5) <= 2.6 && Math.abs(w.getY() - b.getY()) <= 4) {
                    e.setCancelled(true);
                    well(p, w);
                    return;
                }
            }
        }
        if (a == Action.RIGHT_CLICK_BLOCK && b.getType() == Material.SNOW_BLOCK) {
            e.setCancelled(true);
            snowballs(p, b);
            return;
        }
        if (a == Action.RIGHT_CLICK_BLOCK && !lobby.isLobby(b.getWorld()) && b.getType().name().endsWith("_STAIRS")
                && p.getInventory().getItemInMainHand().getType().isAir() && !p.isSneaking() && lobby.interactions() != null) {
            e.setCancelled(true);
            lobby.interactions().sit(p, b);
        }
    }

    private void clicker(Player p, Block b) {
        long now = System.currentTimeMillis();
        Long last = clickCd.get(p.getUniqueId());
        if (last != null && now - last < 90) return;
        clickCd.put(p.getUniqueId(), now);
        plugin.profiles().addStat(p, "clicks", 1);
        long n = plugin.profiles().get(p) == null ? 0 : plugin.profiles().get(p).stat("clicks");
        Msg.actionBar(p, "&6Кликов: &e" + n);
        p.playSound(b.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 0.6f, 0.8f + (n % 12) * 0.08f);
        b.getWorld().spawnParticle(Particle.WAX_OFF, b.getLocation().add(0.5, 1.2, 0.5), 3, 0.2, 0.2, 0.2, 0);
        if (n % 100 == 0 && n > 0) {
            Msg.title(p, "&6&l" + n + " кликов!", "&7Так держать", 5, 30, 10);
            p.getWorld().spawnParticle(Particle.FIREWORK, b.getLocation().add(0.5, 2, 0.5), 40, 0.5, 0.8, 0.5, 0.1);
            p.playSound(b.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        }
    }

    // ------------------------------------------------------------------ Колесо удачи

    private record Prize(int weight, String text, long coins, PotionEffectType effect, int seconds, int amp) {}

    private static final Prize[] PRIZES = {
            new Prize(25, "&7Пусто... Колесо смеётся над вами.", 0, null, 0, 0),
            new Prize(20, "&e+100 монет", 100, null, 0, 0),
            new Prize(14, "&e+250 монет", 250, null, 0, 0),
            new Prize(8, "&6+600 монет", 600, null, 0, 0),
            new Prize(2, "&6&lДЖЕКПОТ! +5000 монет", 5000, null, 0, 0),
            new Prize(7, "&bСкорость II на минуту", 0, PotionEffectType.SPEED, 60, 1),
            new Prize(7, "&aПрыгучесть на минуту", 0, PotionEffectType.JUMP_BOOST, 60, 2),
            new Prize(6, "&9Ночное зрение на 5 минут", 0, PotionEffectType.NIGHT_VISION, 300, 0),
            new Prize(4, "&eСвечение на 5 минут", 0, PotionEffectType.GLOWING, 300, 0),
            new Prize(4, "&dРегенерация на 30 секунд", 0, PotionEffectType.REGENERATION, 30, 1),
            new Prize(3, "&cСалют в вашу честь!", 0, null, 0, 0)};

    private void wheel(Player p, Block lever) {
        long cdMs = plugin.configs().main().getLong("lobby.wheel-cooldown-min", 20) * 60_000L;
        String key = "wheel." + p.getUniqueId();
        long last = plugin.state().getLong(key, 0);
        long left = last + cdMs - System.currentTimeMillis();
        if (left > 0) {
            Msg.error(p, "wheel.cd", "Колесо перезаряжается: ещё {m} мин.", "m", String.valueOf(left / 60_000 + 1));
            return;
        }
        plugin.state().set(key, System.currentTimeMillis());
        Msg.actionBar(p, "&eКолесо крутится...");
        for (int i = 0; i < 12; i++) {
            final int k = i;
            Tasks.later(i * 3L, () -> p.playSound(lever.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1f + k * 0.08f));
        }
        Tasks.later(40, () -> {
            if (!p.isOnline()) return;
            int total = 0;
            for (Prize pr : PRIZES) total += pr.weight();
            int roll = rnd.nextInt(total);
            Prize win = PRIZES[0];
            for (Prize pr : PRIZES) { roll -= pr.weight(); if (roll < 0) { win = pr; break; } }
            Msg.title(p, "&e&lКОЛЕСО УДАЧИ", win.text(), 5, 50, 15);
            if (win.coins() > 0) {
                plugin.economy().deposit(p.getUniqueId(), win.coins(), "wheel", null);
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            }
            if (win.effect() != null) p.addPotionEffect(new PotionEffect(win.effect(), win.seconds() * 20, win.amp(), true, true));
            if (win.text().contains("Салют")) {
                for (int i = 0; i < 4; i++) Tasks.later(i * 8L, () -> launch(p.getLocation().add(rnd.nextDouble() * 4 - 2, 1, rnd.nextDouble() * 4 - 2)));
            }
            plugin.logs().log(p, "economy", "wheel", p.getLocation(), win.text());
        });
    }

    // ------------------------------------------------------------------ Колодец желаний

    private void well(Player p, Location well) {
        if (cooldown(p, 4000)) return;
        final long price = 100;
        if (plugin.economy().balance(p) < price) {
            Msg.error(p, "well.poor", "Нужна монета ценой {n}.", "n", String.valueOf(price));
            return;
        }
        plugin.economy().withdraw(p.getUniqueId(), price, "wishing_well", null);
        p.getWorld().playSound(well, Sound.ENTITY_PLAYER_SPLASH, 1f, 1.4f);
        p.getWorld().spawnParticle(Particle.SPLASH, well.clone().add(0, 0.6, 0), 30, 0.4, 0.1, 0.4, 0.1);
        Tasks.later(25, () -> {
            if (!p.isOnline()) return;
            int r = rnd.nextInt(100);
            long win = r < 2 ? 1500 : r < 10 ? 300 : r < 30 ? 120 : 0;
            if (win > 0) {
                plugin.economy().deposit(p.getUniqueId(), win, "wishing_well", null);
                Msg.title(p, "&b&lЖелание сбылось!", "&e+" + win + " монет", 5, 40, 10);
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.3f);
            } else {
                Msg.actionBar(p, "&7Колодец молчит. Может, в другой раз?");
            }
        });
    }

    // ------------------------------------------------------------------ Салют

    private void launch(Location l) {
        Color[] cs = {Color.RED, Color.ORANGE, Color.YELLOW, Color.LIME, Color.AQUA, Color.BLUE, Color.FUCHSIA, Color.WHITE};
        Firework f = l.getWorld().spawn(l, Firework.class, fw -> {
            FireworkMeta fm = fw.getFireworkMeta();
            fm.addEffect(FireworkEffect.builder().withColor(cs[rnd.nextInt(cs.length)], cs[rnd.nextInt(cs.length)])
                    .with(FireworkEffect.Type.values()[rnd.nextInt(FireworkEffect.Type.values().length)]).flicker(true).trail(true).build());
            fm.setPower(1 + rnd.nextInt(2));
            fw.setFireworkMeta(fm);
            fw.getPersistentDataContainer().set(Keys.TROLL_ENTITY, PersistentDataType.BYTE, (byte) 1);
        });
        f.setSilent(false);
    }

    private void fireworks(Player p, String sfx) {
        long now = System.currentTimeMillis();
        if (now - lastFirework < 45_000) {
            Msg.error(p, "fw.cd", "Салют уже запускали. Подождите немного.");
            return;
        }
        lastFirework = now;
        for (Player o : p.getWorld().getPlayers()) if (o.getLocation().distanceSquared(p.getLocation()) < 120 * 120) Msg.actionBar(o, "&c" + p.getName() + " запускает салют!");
        for (int wave = 0; wave < 6; wave++) {
            Tasks.later(wave * 14L, () -> {
                for (int i = 0; i < 4; i++) {
                    Location l = Anchors.get("fw" + i + sfx);
                    if (l != null) launch(l);
                }
            });
        }
    }

    // ------------------------------------------------------------------ Тир

    private boolean isTirItem(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(tirKey, PersistentDataType.BYTE);
    }

    private void giveBow(Player p) {
        if (cooldown(p, 1500)) return;
        boolean has = false;
        int arrows = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (!isTirItem(it)) continue;
            if (it.getType() == Material.BOW) has = true;
            if (it.getType() == Material.ARROW) arrows += it.getAmount();
        }
        if (!has) {
            ItemStack bow = Items.of(Material.BOW).name("&cТирный лук").lore("&7Работает только на спавне").build();
            tag(bow);
            p.getInventory().addItem(bow);
        }
        if (arrows < 16) {
            ItemStack ar = Items.of(Material.ARROW).name("&7Тирная стрела").build();
            ar.setAmount(16 - arrows);
            tag(ar);
            p.getInventory().addItem(ar);
        }
        Msg.send(p, "tir.given", "&cТир: &7стреляйте по мишеням. Ближе к центру — больше очков. Лук исчезнет, когда вы уйдёте со спавна.");
    }

    private void tag(ItemStack it) {
        var m = it.getItemMeta();
        m.getPersistentDataContainer().set(tirKey, PersistentDataType.BYTE, (byte) 1);
        m.setUnbreakable(true);
        it.setItemMeta(m);
    }

    private void cleanup() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (inPlay(p.getLocation())) continue;
            var inv = p.getInventory();
            for (int i = 0; i < inv.getSize(); i++) if (isTirItem(inv.getItem(i))) inv.setItem(i, null);
            if (isTirItem(inv.getItemInOffHand())) inv.setItemInOffHand(null);
        }
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        var inv = e.getPlayer().getInventory();
        for (int i = 0; i < inv.getSize(); i++) if (isTirItem(inv.getItem(i))) inv.setItem(i, null);
        clickCd.remove(e.getPlayer().getUniqueId());
        actionCd.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void hit(ProjectileHitEvent e) {
        Projectile pr = e.getEntity();
        if (!(pr.getShooter() instanceof Player p) || !inPlay(pr.getLocation())) return;
        if (pr instanceof Snowball && e.getHitEntity() instanceof Player v && !v.equals(p)) {
            v.setVelocity(pr.getVelocity().clone().setY(0).normalize().multiply(0.7).setY(0.35));
            Msg.actionBar(v, "&b" + p.getName() + " попал в вас снежком!");
            Msg.actionBar(p, "&aПопадание!");
            v.getWorld().playSound(v.getLocation(), Sound.BLOCK_SNOW_BREAK, 1f, 1.2f);
            v.getWorld().spawnParticle(Particle.SNOWFLAKE, v.getLocation().add(0, 1.2, 0), 25, 0.3, 0.4, 0.3, 0.05);
            plugin.profiles().addStat(p, "snow_hits", 1);
        } else if (pr instanceof AbstractArrow ar && e.getHitBlock() != null && e.getHitBlock().getType() == Material.TARGET) {
            Block t = e.getHitBlock();
            Vector c = new Vector(t.getX() + 0.5, t.getY() + 0.5, t.getZ() + 0.5);
            Vector v = ar.getLocation().toVector().subtract(c);
            BlockFace face = e.getHitBlockFace();
            if (face != null) {
                if (face.getModX() != 0) v.setX(0);
                if (face.getModY() != 0) v.setY(0);
                if (face.getModZ() != 0) v.setZ(0);
            }
            int points = Math.max(1, 10 - (int) (v.length() * 14));
            plugin.profiles().addStat(p, "tir_points", points);
            long total = plugin.profiles().get(p) == null ? points : plugin.profiles().get(p).stat("tir_points");
            Msg.actionBar(p, (points >= 9 ? "&6&lЯБЛОЧКО! " : "&e") + "+" + points + " &7(всего " + total + ")");
            p.playSound(p.getLocation(), points >= 9 ? Sound.ENTITY_PLAYER_LEVELUP : Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 0.8f + points * 0.1f);
            t.getWorld().spawnParticle(Particle.CRIT, ar.getLocation(), 12, 0.2, 0.2, 0.2, 0.2);
            ar.remove();
        }
    }

    private void snowballs(Player p, Block b) {
        if (cooldown(p, 800)) return;
        int have = 0;
        for (ItemStack it : p.getInventory().getContents()) if (it != null && it.getType() == Material.SNOWBALL) have += it.getAmount();
        if (have >= 16) { Msg.actionBar(p, "&7У вас уже достаточно снежков."); return; }
        p.getInventory().addItem(new ItemStack(Material.SNOWBALL, 16 - have));
        p.playSound(b.getLocation(), Sound.BLOCK_SNOW_STEP, 1f, 1f);
        Msg.actionBar(p, "&bСнежки пополнены. Бросайте в друзей!");
    }

    // ------------------------------------------------------------------ Дискотека и подписи

    private void discoTick() {
        for (String sfx : SFX) {
            Location c = Anchors.get("disco" + sfx);
            if (c == null || c.getWorld() == null || !c.getWorld().isChunkLoaded(c.getBlockX() >> 4, c.getBlockZ() >> 4)) continue;
            boolean near = false;
            for (Player p : c.getWorld().getPlayers()) if (p.getLocation().distanceSquared(c) < 26 * 26) { near = true; break; }
            if (!near) continue;
            int h = Fun.DISCO_SIZE / 2, y = c.getBlockY() - 1;
            for (int dx = -h; dx <= h; dx++) for (int dz = -h; dz <= h; dz++) {
                c.getWorld().getBlockAt(c.getBlockX() + dx, y, c.getBlockZ() + dz).setType(DISCO[rnd.nextInt(DISCO.length)], false);
            }
        }
    }

    private void labelTick() {
        for (var en : Fun.TEXT.entrySet()) for (String sfx : SFX) {
            String name = "holo_" + en.getKey() + sfx;
            Location l = Anchors.get(name);
            if (l == null || l.getWorld() == null || !l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) continue;
            TextDisplay cur = labels.get(name);
            if (cur != null && cur.isValid()) continue;
            TextDisplay td = l.getWorld().spawn(l, TextDisplay.class, d -> {
                d.text(Text.c(en.getValue()));
                d.setBillboard(Display.Billboard.CENTER);
                d.setBackgroundColor(Color.fromARGB(110, 10, 0, 20));
                d.setShadowed(true);
                d.setPersistent(false);
                d.getPersistentDataContainer().set(Keys.LOBBY_ENTITY, PersistentDataType.BYTE, (byte) 1);
            });
            labels.put(name, td);
        }
    }
}
