package ru.unknownsmp.admin;

import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import ru.unknownsmp.core.Keys;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.gui.ConfirmMenu;
import ru.unknownsmp.gui.Items;
import ru.unknownsmp.gui.Menu;
import ru.unknownsmp.gui.PagedMenu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Меню троллинга персонала. Три категории: безобидные розыгрыши, неприятные эффекты без урона и жёсткие наказания
 * (для читеров — с подтверждением и отдельным правом usmp.admin.troll.extreme). Каждое применение пишется в журнал.
 */
final class TrollMenus {
    private record Troll(String id, String name, Material icon, String lore, int level, Consumer<Player> run) {}

    private final UnknownSMP plugin;
    private final Random rnd = new Random();
    private final List<Troll> all = new ArrayList<>();

    TrollMenus(UnknownSMP plugin) {
        this.plugin = plugin;
        register();
    }

    private void add(String id, String name, Material icon, String lore, int level, Consumer<Player> run) {
        all.add(new Troll(id, name, icon, lore, level, run));
    }

    private static void fx(Player t, PotionEffectType type, int seconds, int amp) {
        t.addPotionEffect(new PotionEffect(type, seconds * 20, amp, true, false));
    }

    private Location behind(Player t, double d) {
        return t.getLocation().add(t.getLocation().getDirection().setY(0).normalize().multiply(-d));
    }

    private Firework firework(Location l, Color c1, Color c2, FireworkEffect.Type type) {
        return l.getWorld().spawn(l, Firework.class, f -> {
            FireworkMeta fm = f.getFireworkMeta();
            fm.addEffect(FireworkEffect.builder().withColor(c1, c2).with(type).flicker(true).build());
            fm.setPower(1);
            f.setFireworkMeta(fm);
            f.getPersistentDataContainer().set(Keys.TROLL_ENTITY, PersistentDataType.BYTE, (byte) 1);
        });
    }

    private void register() {
        // ---------- Уровень 0: безобидные розыгрыши ----------
        add("creeper", "&aФантомный крипер", Material.CREEPER_HEAD, "Звук шипения за спиной", 0,
                t -> t.playSound(behind(t, 1.5), Sound.ENTITY_CREEPER_PRIMED, 1f, 1f));
        add("fake_explosion", "&cЛожный взрыв", Material.TNT, "Только звук и частицы, без урона", 0, t -> {
            t.playSound(t.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f);
            t.spawnParticle(Particle.EXPLOSION, t.getLocation(), 3);
        });
        add("fireworks", "&dСалют в честь игрока", Material.FIREWORK_ROCKET, "5 залпов вокруг игрока", 0, t -> {
            for (int i = 0; i < 5; i++) Tasks.later(i * 8L, () -> firework(t.getLocation().add(rnd.nextDouble() * 4 - 2, 1, rnd.nextDouble() * 4 - 2), Color.PURPLE, Color.FUCHSIA, FireworkEffect.Type.BALL_LARGE));
        });
        add("chickens", "&eКуриный дождь", Material.CHICKEN_SPAWN_EGG, "Куры исчезнут через 20 сек.", 0, t -> {
            List<Entity> spawned = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                Chicken ch = t.getWorld().spawn(t.getLocation().add(rnd.nextDouble() * 6 - 3, 8 + rnd.nextDouble() * 4, rnd.nextDouble() * 6 - 3), Chicken.class);
                ch.setPersistent(false);
                ch.setInvulnerable(true);
                ch.getPersistentDataContainer().set(Keys.TROLL_ENTITY, PersistentDataType.BYTE, (byte) 1);
                spawned.add(ch);
            }
            Tasks.later(400, () -> spawned.forEach(Entity::remove));
        });
        add("title", "&fЗагадочный заголовок", Material.PAPER, "«За тобой наблюдают...»", 0, t -> Msg.title(t, "&8...", "&7За тобой наблюдают", 10, 50, 20));
        add("melody", "&bМелодия", Material.NOTE_BLOCK, "Короткая мелодия колокольчиков", 0, t -> {
            float[] notes = {0.5f, 0.6f, 0.7f, 0.8f, 1f, 1.2f, 1.5f};
            for (int i = 0; i < notes.length; i++) { final float n = notes[i]; Tasks.later(i * 4L, () -> t.playSound(t.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, n)); }
        });
        add("hearts", "&cСердечки", Material.RED_DYE, "Сердечки вокруг игрока 5 сек.", 0, t -> {
            for (int i = 0; i < 20; i++) Tasks.later(i * 5L, () -> t.getWorld().spawnParticle(Particle.HEART, t.getLocation().add(0, 2.2, 0), 4, 0.5, 0.3, 0.5, 0));
        });
        add("notes", "&aНоты", Material.JUKEBOX, "Ноты вокруг игрока", 0, t -> {
            for (int i = 0; i < 20; i++) Tasks.later(i * 5L, () -> t.getWorld().spawnParticle(Particle.NOTE, t.getLocation().add(0, 2.2, 0), 5, 0.6, 0.4, 0.6, 1));
        });
        add("footsteps", "&7Шаги за спиной", Material.LEATHER_BOOTS, "Шаги по траве позади", 0, t -> {
            for (int i = 0; i < 8; i++) Tasks.later(i * 6L, () -> t.playSound(behind(t, 2.5), Sound.BLOCK_GRASS_STEP, 1f, 0.9f));
        });
        add("herobrine", "&eHerobrine вошёл в игру", Material.PLAYER_HEAD, "Фальшивое сообщение чата (только игроку)", 0,
                t -> Msg.raw(t, "&eHerobrine присоединился к игре"));
        add("jump", "&aБатут", Material.SLIME_BLOCK, "Подбросить на 6 блоков, мягкое приземление", 0, t -> {
            t.setVelocity(new Vector(0, 1.1, 0));
            fx(t, PotionEffectType.SLOW_FALLING, 8, 0);
        });
        add("kick_soft", "&fПинок", Material.LEATHER, "Лёгкий толчок в сторону", 0,
                t -> t.setVelocity(new Vector(rnd.nextDouble() * 2 - 1, 0.4, rnd.nextDouble() * 2 - 1).normalize().multiply(0.9).setY(0.4)));
        add("spin", "&bРазворот", Material.COMPASS, "Резко развернуть камеру", 0, t -> {
            Location l = t.getLocation();
            l.setYaw(l.getYaw() + 180);
            t.teleport(l);
        });
        add("moon", "&fЛунная гравитация", Material.END_STONE, "Высокие прыжки 20 сек.", 0, t -> {
            fx(t, PotionEffectType.JUMP_BOOST, 20, 4);
            fx(t, PotionEffectType.SLOW_FALLING, 25, 0);
        });
        add("glow", "&eПодсветка", Material.GLOWSTONE_DUST, "Игрок светится 15 сек.", 0, t -> fx(t, PotionEffectType.GLOWING, 15, 0));
        add("totem", "&6Тотем", Material.TOTEM_OF_UNDYING, "Эффект тотема без траты", 0,
                t -> t.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, t.getLocation().add(0, 1, 0), 60, 0.4, 0.8, 0.4, 0.3));
        add("dark_soft", "&8Тьма", Material.COAL, "Затемнение экрана 6 сек.", 0, t -> fx(t, PotionEffectType.DARKNESS, 6, 0));
        add("cave", "&8Звуки пещеры", Material.DEEPSLATE, "Жуткий звук из темноты", 0,
                t -> t.playSound(t.getLocation(), Sound.AMBIENT_CAVE, 1f, 0.8f));
        add("bell", "&6Колокол", Material.BELL, "Звон колокола над головой", 0,
                t -> t.playSound(t.getLocation().add(0, 3, 0), Sound.BLOCK_BELL_USE, 1f, 0.7f));
        add("thunder_sound", "&7Гром", Material.LIGHTNING_ROD, "Только звук грома", 0,
                t -> t.playSound(t.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1f, 1f));
        add("confetti", "&dКонфетти", Material.PINK_DYE, "Разноцветные частицы", 0,
                t -> t.getWorld().spawnParticle(Particle.FIREWORK, t.getLocation().add(0, 1.5, 0), 80, 1, 1, 1, 0.1));

        // ---------- Уровень 1: неприятно, но без урона ----------
        add("nausea", "&7Головокружение", Material.PHANTOM_MEMBRANE, "Тошнота 6 сек. (вне боя)", 1, t -> fx(t, PotionEffectType.NAUSEA, 6, 0));
        add("blind", "&8Слепота", Material.INK_SAC, "8 секунд без зрения", 1, t -> fx(t, PotionEffectType.BLINDNESS, 8, 0));
        add("slow", "&9Замедление", Material.COBWEB, "Сильное замедление 10 сек.", 1, t -> fx(t, PotionEffectType.SLOWNESS, 10, 3));
        add("fatigue", "&6Усталость", Material.WOODEN_PICKAXE, "Копать почти невозможно 20 сек.", 1, t -> fx(t, PotionEffectType.MINING_FATIGUE, 20, 3));
        add("hunger", "&cГолод", Material.ROTTEN_FLESH, "Быстро растущий голод 15 сек.", 1, t -> fx(t, PotionEffectType.HUNGER, 15, 4));
        add("levitate", "&dЛевитация", Material.SHULKER_SHELL, "Взлёт на 5 сек. (с мягким падением)", 1, t -> {
            fx(t, PotionEffectType.LEVITATION, 5, 1);
            fx(t, PotionEffectType.SLOW_FALLING, 15, 0);
        });
        add("freeze", "&bЗаморозка", Material.POWDER_SNOW_BUCKET, "Иней и замедление 8 сек.", 1, t -> {
            fx(t, PotionEffectType.SLOWNESS, 8, 5);
            t.setFreezeTicks(t.getMaxFreezeTicks() + 100);
        });
        add("shuffle", "&eПеретасовать хотбар", Material.HOPPER, "Предметы в хотбаре меняются местами", 1, t -> {
            List<ItemStack> hot = new ArrayList<>();
            for (int i = 0; i < 9; i++) hot.add(t.getInventory().getItem(i));
            Collections.shuffle(hot, rnd);
            for (int i = 0; i < 9; i++) t.getInventory().setItem(i, hot.get(i));
        });
        add("drop_one", "&6Выронить из рук", Material.DROPPER, "Игрок роняет предмет из руки", 1, t -> t.dropItem(false));
        add("web", "&fПаутина", Material.STRING, "Паутина вокруг на 12 сек.", 1, t -> {
            List<Block> placed = new ArrayList<>();
            Location l = t.getLocation();
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = 0; dy <= 1; dy++) {
                Block b = l.clone().add(dx, dy, dz).getBlock();
                if (b.getType().isAir()) { b.setType(Material.COBWEB, false); placed.add(b); }
            }
            Tasks.later(240, () -> placed.forEach(b -> { if (b.getType() == Material.COBWEB) b.setType(Material.AIR, false); }));
        });
        add("random_tp", "&5Случайный телепорт", Material.ENDER_EYE, "Перенос на 30-60 блоков по поверхности", 1, t -> {
            World w = t.getWorld();
            for (int i = 0; i < 12; i++) {
                double a = rnd.nextDouble() * Math.PI * 2, r = 30 + rnd.nextDouble() * 30;
                int x = (int) (t.getLocation().getX() + Math.cos(a) * r), z = (int) (t.getLocation().getZ() + Math.sin(a) * r);
                if (!w.getWorldBorder().isInside(new Location(w, x, 64, z))) continue;
                int y = w.getHighestBlockYAt(x, z);
                Block ground = w.getBlockAt(x, y, z);
                if (ground.isLiquid() || ground.getType() == Material.LAVA || y < w.getMinHeight() + 2) continue;
                t.teleport(new Location(w, x + 0.5, y + 1, z + 0.5, t.getLocation().getYaw(), t.getLocation().getPitch()));
                return;
            }
        });
        add("fake_kick", "&cФальшивый вылет", Material.BARRIER, "Выкинуть с ошибкой соединения (можно зайти сразу)", 1,
                t -> t.kick(ru.unknownsmp.core.Text.c("&fInternal Exception: java.io.IOException: Connection reset by peer")));
        add("scare", "&4Страх", Material.WITHER_SKELETON_SKULL, "Тьма, замедление и звук варденa 6 сек.", 1, t -> {
            fx(t, PotionEffectType.DARKNESS, 8, 0);
            fx(t, PotionEffectType.SLOWNESS, 6, 1);
            t.playSound(t.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 1f);
            t.playSound(t.getLocation(), Sound.ENTITY_WARDEN_NEARBY_CLOSER, 1f, 0.8f);
        });
        add("weak", "&8Слабость", Material.WOODEN_SWORD, "Слабость 20 сек. (удары почти без урона)", 1, t -> fx(t, PotionEffectType.WEAKNESS, 20, 3));
        add("fire_sound", "&6Фальшивый пожар", Material.FLINT_AND_STEEL, "Огонь и звук, без урона", 1, t -> {
            t.playSound(t.getLocation(), Sound.ITEM_FLINTANDSTEEL_USE, 1f, 1f);
            t.getWorld().spawnParticle(Particle.FLAME, t.getLocation().add(0, 1, 0), 60, 0.4, 0.6, 0.4, 0.02);
        });

        // ---------- Уровень 2: жёсткие наказания для читеров ----------
        add("lightning_hit", "&eУдар молнии", Material.LIGHTNING_ROD, "Молния и 6 урона, поджог 3 сек.", 2, t -> {
            t.getWorld().strikeLightningEffect(t.getLocation());
            t.damage(6);
            t.setFireTicks(60);
        });
        add("explode", "&cВзрыв под ногами", Material.TNT_MINECART, "Взрыв без разрушения блоков, сильный урон", 2,
                t -> t.getWorld().createExplosion(t.getLocation(), 4f, false, false));
        add("launch", "&bВ стратосферу", Material.ELYTRA, "Подбросить на ~70 блоков без мягкого падения", 2,
                t -> t.setVelocity(new Vector(0, 3.6, 0)));
        add("sky_drop", "&fПадение с небес", Material.FEATHER, "Телепорт на 130 блоков вверх и падение", 2, t -> {
            Location l = t.getLocation();
            double y = Math.min(l.getY() + 130, t.getWorld().getMaxHeight() - 4);
            l.setY(y);
            t.teleport(l);
            t.removePotionEffect(PotionEffectType.SLOW_FALLING);
        });
        add("meteor", "&6Метеорит", Material.MAGMA_BLOCK, "Огненный след и взрыв на игроке", 2, t -> {
            Location target = t.getLocation();
            for (int i = 0; i < 12; i++) {
                final int k = i;
                Tasks.later(i * 2L, () -> t.getWorld().spawnParticle(Particle.FLAME, target.clone().add(0, 24 - k * 2, 0), 40, 0.6, 0.6, 0.6, 0.05));
            }
            Tasks.later(26, () -> {
                t.getWorld().createExplosion(t.getLocation(), 3.5f, true, false);
                t.setFireTicks(80);
            });
        });
        add("wrath", "&4Гнев богов", Material.NETHER_STAR, "5 ударов молнии подряд (смертельно)", 2, t -> {
            for (int i = 0; i < 5; i++) {
                Tasks.later(i * 10L, () -> {
                    if (!t.isOnline() || t.isDead()) return;
                    t.getWorld().strikeLightningEffect(t.getLocation());
                    t.damage(8);
                });
            }
        });
        add("plague", "&2Чума", Material.SPIDER_EYE, "Яд, иссушение и слабость 12 сек.", 2, t -> {
            fx(t, PotionEffectType.POISON, 12, 1);
            fx(t, PotionEffectType.WITHER, 12, 1);
            fx(t, PotionEffectType.WEAKNESS, 20, 1);
        });
        add("burn", "&6Поджечь", Material.BLAZE_POWDER, "Горит 10 секунд", 2, t -> t.setFireTicks(200));
        add("anvil", "&8Наковальня", Material.ANVIL, "Падающая наковальня на голову", 2, t -> {
            Location l = t.getLocation().add(0, 12, 0);
            org.bukkit.entity.FallingBlock fb = t.getWorld().spawn(l, org.bukkit.entity.FallingBlock.class, f -> {
                f.setBlockData(Material.ANVIL.createBlockData());
                f.setDropItem(false);
                f.setHurtEntities(true);
                f.setCancelDrop(true);
                f.getPersistentDataContainer().set(Keys.TROLL_ENTITY, PersistentDataType.BYTE, (byte) 1);
            });
            Tasks.later(100, fb::remove);
        });
        add("void_scare", "&5Порча", Material.WITHER_ROSE, "Слепота, тошнота, замедление и яд 10 сек.", 2, t -> {
            fx(t, PotionEffectType.BLINDNESS, 10, 0);
            fx(t, PotionEffectType.NAUSEA, 10, 0);
            fx(t, PotionEffectType.SLOWNESS, 10, 2);
            fx(t, PotionEffectType.POISON, 10, 0);
        });
        add("execute", "&4Казнь", Material.NETHERITE_SWORD, "Молния и мгновенная смерть", 2, t -> {
            t.getWorld().strikeLightningEffect(t.getLocation());
            t.setHealth(0.0);
        });
    }

    // ------------------------------------------------------------------ Меню

    void open(Player admin, Player target, Menu parent) {
        if (!plugin.roles().has(admin, "usmp.admin.troll")) return;
        new Menu(admin, 3, "&8Троллинг: " + target.getName()) {
            @Override
            protected void build() {
                fill(Items.filler());
                set(4, Items.head(target).name("&f" + target.getName()).build());
                set(11, Items.of(Material.FIREWORK_ROCKET).name("&aБезобидные розыгрыши").lore("&7Звуки, частицы, шутки (" + count(0) + ")").build(), c -> category(admin, target, 0, this));
                set(13, Items.of(Material.COBWEB).name("&eНеприятные эффекты").lore("&7Без урона: слепота, заморозка, паутина... (" + count(1) + ")").build(), c -> category(admin, target, 1, this));
                set(15, Items.of(Material.TNT).name("&4Жёсткие (для читеров)").lore("&7Урон и смерть. Нужно подтверждение.", "&7Право: usmp.admin.troll.extreme (" + count(2) + ")").build(), c -> category(admin, target, 2, this));
                if (parent != null) parent(parent);
                backButton(22);
            }
        }.open();
    }

    private int count(int level) {
        return (int) all.stream().filter(t -> t.level() == level).count();
    }

    private void category(Player admin, Player target, int level, Menu parent) {
        if (level == 2 && !plugin.roles().has(admin, "usmp.admin.troll.extreme")) {
            Msg.error(admin, "troll.noperm", "Для жёстких наказаний нужно право usmp.admin.troll.extreme.");
            return;
        }
        String title = level == 0 ? "&aБезобидные" : level == 1 ? "&eНеприятные" : "&4Жёсткие";
        new PagedMenu(admin, "&8" + title + ": " + target.getName()) {
            @Override
            protected List<Entry> entries() {
                List<Entry> out = new ArrayList<>();
                for (Troll t : all) {
                    if (t.level() != level) continue;
                    ItemStack icon = Items.of(t.icon()).name(t.name()).lore("&7" + t.lore(), level == 2 ? "&c⚠ потребуется подтверждение" : "").build();
                    out.add(new Entry(icon, c -> apply(admin, target, t, this)));
                }
                return out;
            }
        }.parent(parent).open();
    }

    private void apply(Player admin, Player target, Troll t, Menu parent) {
        if (!target.isOnline()) { Msg.error(admin, "troll.offline", "Игрок вышел."); return; }
        if (plugin.duels() != null && plugin.duels().isDuelWorld(target.getWorld())) {
            Msg.error(admin, "troll.duel", "Игрок на арене дуэли — не трогаем.");
            return;
        }
        GameMode gm = target.getGameMode();
        if (t.level() >= 1 && (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR)) {
            Msg.error(admin, "troll.gm", "Эффект применим только к игрокам в выживании/приключении.");
            return;
        }
        Runnable go = () -> {
            if (!target.isOnline()) return;
            t.run().accept(target);
            plugin.logs().log(admin, "admin", "troll_" + t.id(), target.getLocation(), target.getName());
            Msg.ok(admin, "troll.done", "Готово: {n} → {t}", "n", t.id(), "t", target.getName());
        };
        if (t.level() == 2) {
            ConfirmMenu.open(admin, "&4Применить «" + t.id() + "»?", Items.head(target).name("&f" + target.getName()).build(),
                    List.of("&7" + t.lore(), "&cМожет убить игрока.", "&7Действие попадёт в журнал."), () -> { admin.closeInventory(); go.run(); });
        } else {
            go.run();
        }
    }
}
