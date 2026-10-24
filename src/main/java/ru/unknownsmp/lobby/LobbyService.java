package ru.unknownsmp.lobby;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.data.Orientable;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Keys;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.profile.Profile;
import ru.unknownsmp.world.Anchors;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Лобби: отдельный пустой мир с платформой, порталом в выживание, правилами, паркуром и секретами.
 * Неавторизованные игроки находятся в изолированной камере лобби.
 */
public final class LobbyService implements Module, Listener {
    private static final int BUILD_VERSION = 4;
    /** Секретные кнопки лобби (пасхалки): id секрета -> имя якоря кнопки. */
    public static final Map<String, String> SECRETS = Map.of(
            "lobby_portal_top", "secret_portal_top",
            "lobby_under", "secret_under",
            "lobby_parkour_end", "secret_hard_end",
            "smp_maze", "maze_button"
    );

    private final UnknownSMP plugin;
    private World world;
    private ParkourService parkour;
    private LobbyInteractions interactions;
    private FunInteractions fun;
    private CosmeticsService cosmetics;
    private final Map<UUID, Long> portalCooldown = new ConcurrentHashMap<>();
    private final Set<UUID> entering = ConcurrentHashMap.newKeySet();

    public LobbyService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "lobby"; }
    @Override public String title() { return "Лобби"; }
    @Override public String[] depends() { return new String[]{"profiles", "state"}; }

    @Override
    public void enable() {
        String name = plugin.lobbyWorldName();
        world = Bukkit.getWorld(name);
        if (world == null) {
            world = new WorldCreator(name).generator(new VoidGenerator())
                    .generateStructures(false).environment(World.Environment.NORMAL).createWorld();
        }
        if (world == null) throw new IllegalStateException("Не удалось создать мир лобби " + name);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        world.setGameRule(GameRule.DO_FIRE_TICK, false);
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setTime(6000);
        world.setStorm(false);
        boolean autoBuild = plugin.configs().main().getBoolean("lobby.build", true);
        if (autoBuild) world.setSpawnLocation(0, 101, 0);
        if (autoBuild) for (int x = -4; x <= 3; x++) for (int z = -4; z <= 3; z++) world.addPluginChunkTicket(x, z, plugin);
        world.addPluginChunkTicket(1000 >> 4, 1000 >> 4, plugin);
        long built = plugin.state().getLong("lobby_built", 0);
        // Мир могли удалить, а база осталась: если платформы нет на деле, строим заново.
        if (autoBuild && built > 0 && world.getBlockAt(0, 100, 0).getType().isAir() && world.getBlockAt(0, 99, 0).getType().isAir()) {
            plugin.getLogger().warning("Лобби: в мире нет построек (мир был пересоздан) — строю заново.");
            built = 0;
        }
        if (!autoBuild) {
            if (plugin.state().getLong("lobby_auth_built", 0) < 1) {
                LobbyArchitect.buildAuthRoomOnly(world);
                plugin.state().set("lobby_auth_built", 1);
            }
        } else if (built < BUILD_VERSION) {
            if (built > 0) LobbyArchitect.clearOld(world);
            LobbyArchitect.build(world, Anchors::put);
            plugin.state().set("lobby_built", BUILD_VERSION);
            plugin.getLogger().info("Лобби построено в мире " + name);
        }
        spawnHolograms();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        parkour = new ParkourService(plugin, this);
        Bukkit.getPluginManager().registerEvents(parkour, plugin);
        interactions = new LobbyInteractions(plugin, this);
        Bukkit.getPluginManager().registerEvents(interactions, plugin);
        interactions.start();
        fun = new FunInteractions(plugin, this);
        Bukkit.getPluginManager().registerEvents(fun, plugin);
        fun.start();
        cosmetics = new CosmeticsService(plugin);
        cosmetics.start();
        plugin.auth().onJoin(this::onPreAuthJoin);
        plugin.auth().onLogin(this::afterLogin);
        registerCommands();
    }

    public World world() { return world; }

    public ParkourService parkour() { return parkour; }

    public CosmeticsService cosmetics() { return cosmetics; }

    public boolean isLobby(World w) { return w != null && world != null && w.equals(world); }

    public Location spawn() {
        Location l = Text.loc(plugin.configs().main().getString("lobby.spawn", ""));
        if (l != null) return l;
        if (!plugin.configs().main().getBoolean("lobby.build", true)) return world.getSpawnLocation();
        return new Location(world, 0.5, 101, 0.5, 180, 0);
    }

    public void setSpawn(Location l) {
        plugin.configs().main().set("lobby.spawn", Text.loc(l));
        plugin.configs().save("config.yml");
    }

    public Location authSpot() {
        return world == null ? null : new Location(world, 1000.5, 150, 1000.5, 0, 0);
    }

    // ------------------------------------------------------------------ Переходы

    private void onPreAuthJoin(Player p) {
        // Новичок появляется на ванильной точке мира — её не запоминаем, чтобы первый вход был на площади спавна.
        if (p.hasPlayedBefore()) rememberSurvival(p, p.getLocation());
    }

    /** Запомнить позицию выживания, если игрок сейчас не в лобби. */
    public void rememberSurvival(Player p, Location l) {
        if (l == null || isLobby(l.getWorld()) || (plugin.duels() != null && plugin.duels().isDuelWorld(l.getWorld()))) return;
        Profile pr = plugin.profiles().get(p);
        if (pr == null) return;
        pr.lastLoc = Text.loc(l);
        pr.dirty = true;
    }

    private void afterLogin(Player p) {
        Profile pr = plugin.profiles().get(p);
        boolean useLobby = plugin.configs().main().getBoolean("lobby.enabled", true);
        if (useLobby && !plugin.configs().main().getBoolean("lobby.skip-after-login", false)) {
            toLobby(p);
            Msg.title(p, Msg.t("lobby.title", "&b&lALEX SMP"), Msg.t("lobby.subtitle", "&7Войдите в портал или нажмите на &fПроводника"), 10, 70, 20);
            if (pr != null && pr.lastLoc == null) {
                Msg.send(p, "lobby.first", "&fДобро пожаловать! Здесь можно прочитать правила, пройти паркур и найти секреты. Портал ведёт в выживание. Меню сервера: &e/menu");
            }
        } else {
            enterSurvival(p);
        }
    }

    public void toLobby(Player p) {
        if (!isLobby(p.getWorld())) rememberSurvival(p, p.getLocation());
        if (p.getGameMode() == GameMode.SURVIVAL) p.setGameMode(GameMode.ADVENTURE);
        p.teleportAsync(spawn(), PlayerTeleportEvent.TeleportCause.PLUGIN);
        p.setFoodLevel(20);
        p.setFireTicks(0);
    }

    /** Вход в выживание: последняя позиция или общий спавн при первом входе. */
    public void enterSurvival(Player p) {
        if (plugin.world() != null && plugin.world().building()) {
            Msg.error(p, "lobby.building", "Город SMP ещё строится (при первом запуске это занимает несколько минут). Подождите и попробуйте снова.");
            return;
        }
        if (!entering.add(p.getUniqueId())) return;
        Profile pr = plugin.profiles().get(p);
        Location target = pr == null ? null : Text.loc(pr.lastLoc);
        boolean first = target == null;
        if (target == null || isLobby(target.getWorld()) || (plugin.duels() != null && plugin.duels().isDuelWorld(target.getWorld()))) target = plugin.world().spawn();
        if (plugin.dimensions() != null && !plugin.dimensions().canBeIn(target.getWorld())) target = plugin.world().spawn();
        if (p.getGameMode() == GameMode.ADVENTURE) p.setGameMode(GameMode.SURVIVAL);
        p.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((ok, t) -> Tasks.sync(() -> {
            entering.remove(p.getUniqueId());
            if (!p.isOnline()) return;
            if (first) {
                Msg.title(p, Msg.t("lobby.welcome-title", "&bALEX SMP"), Msg.t("lobby.welcome-sub", "&7Выживайте. Доверяйте осторожно."), 10, 80, 20);
                Msg.send(p, "lobby.welcome", "&fВы на общем спавне. PvP разрешено везде, кроме зоны спавна. Приватов нет — берегите ценности. &e/menu&f — меню сервера.");
            }
        }));
    }

    private void registerCommands() {
        plugin.commands().add("lobby").player().perm("usmp.player.lobby").module("lobby").cat("Перемещение")
                .usage("/lobby").desc("Вернуться в лобби").exec((s, a) -> {
                    Player p = (Player) s;
                    if (plugin.combat() != null && plugin.combat().isTagged(p)) {
                        Msg.error(p, "combat.blocked", "Нельзя во время боя.");
                        return;
                    }
                    if (isLobby(p.getWorld())) { Msg.error(p, "lobby.already", "Вы уже в лобби."); return; }
                    plugin.teleports().teleportWithWarmup(p, spawn(), "лобби", () -> {
                        rememberSurvival(p, p.getLocation());
                        if (p.getGameMode() == GameMode.SURVIVAL) p.setGameMode(GameMode.ADVENTURE);
                    });
                });
        plugin.commands().add("play").player().perm("usmp.player.lobby").module("lobby").cat("Перемещение")
                .usage("/play").desc("Войти в выживание из лобби").exec((s, a) -> {
                    Player p = (Player) s;
                    if (!isLobby(p.getWorld())) { Msg.error(p, "lobby.not-in", "Вы уже в мире выживания."); return; }
                    enterSurvival(p);
                });
    }

    // ------------------------------------------------------------------ Голограммы

    private void spawnHolograms() {
        for (Entity e : world.getEntities()) {
            if (e.getPersistentDataContainer().has(Keys.LOBBY_ENTITY, PersistentDataType.BYTE)) e.remove();
        }
        List<String> rules = plugin.configs().main().getStringList("lobby.rules");
        if (rules.isEmpty()) rules = List.of("&b&lПРАВИЛА ALEX SMP", "&7PvP разрешено везде, кроме спавна", "&7Приватов нет — грабить можно",
                "&7Выход из боя = потеря вещей и 10% денег", "&7Читы, дюпы и обход защиты запрещены", "&7Уважайте игроков в чате");
        String brand = plugin.configs().main().getString("branding.sign", "ALEX") + " " + plugin.configs().main().getString("branding.sign-sub", "SMP");
        holoAt("holo_rules", String.join("\n", rules));
        holoAt("holo_smp", "&b&lРЕЖИМ SMP\n&7Выживание, кланы, экономика\n&7Войдите в портал или /play");
        holoAt("holo_duels", "&c&lРЕЖИМ ДУЭЛИ\n&7Честные бои 1 на 1 с наборами\n&7Вещи SMP в безопасности\n&7Самый страшный игрок на века: &calexdzuba812\n&7Войдите в портал или /duels");
        holoAt("holo_tips", "&b&lПОДСКАЗКИ\n&7/menu — главное меню\n&7/help — все команды\n&7/shop /ah /sell — рынок\n&7/clan — кланы\n&7/quests — задания\n&7/daily — подарок дня\n&7Острова по мостам: дискотека, кликер,\n&7батуты, колесо удачи, тир, снежки");
        holoAt("holo_piano", "&d&lПИАНИНО\n&7Нажмите на клавиши");
        holoAt("holo_daily", "&6&lПОДАРОК ДНЯ\n&7Нажмите на ящик Края\n&7или введите &e/daily");
        holoAt("holo_launch0", "&b&lКАТАПУЛЬТА");
        holoAt("holo_launch1", "&b&lКАТАПУЛЬТА");
        for (ParkourCourses.Course c : ParkourCourses.all()) {
            holoAt("park_" + c.id(), c.color() + "&lПАРКУР: " + c.title().toUpperCase() + "\n&7Встаньте на плиту, чтобы начать\n&7Чекпоинтов: " + (c.jumps().size() / 8));
            holoAt("park_" + c.id() + "_end", c.color() + "&lФИНИШ");
        }
        Location logo = Anchors.get("holo_logo");
        if (logo != null) {
            world.spawn(logo, TextDisplay.class, td -> {
                td.text(Text.c("&b&l" + brand));
                td.setBillboard(Display.Billboard.CENTER);
                td.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                td.setShadowed(true);
                td.setViewRange(4f);
                td.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.AxisAngle4f(),
                        new org.joml.Vector3f(7f, 7f, 7f), new org.joml.AxisAngle4f()));
                td.setPersistent(false);
                td.getPersistentDataContainer().set(Keys.LOBBY_ENTITY, PersistentDataType.BYTE, (byte) 1);
            });
        }
        holo(new Location(world, 1000.5, 152.6, 1004.2), "&b&l" + brand + "\n&fДобро пожаловать!\n\n&7Новый игрок: &e/register <пароль> <пароль>\n&7Уже играли: &e/login <пароль>\n\n&8На вход даётся 60 секунд");
    }

    private void holoAt(String anchor, String text) {
        Location l = Anchors.get(anchor);
        if (l != null && l.getWorld() != null && l.getWorld().equals(world)) holo(l, text);
    }

    public LobbyInteractions interactions() { return interactions; }

    private void holo(Location l, String text) {
        world.spawn(l, TextDisplay.class, td -> {
            td.text(Text.c(text));
            td.setBillboard(Display.Billboard.CENTER);
            td.setBackgroundColor(Color.fromARGB(120, 10, 0, 20));
            td.setShadowed(true);
            td.setPersistent(false);
            td.getPersistentDataContainer().set(Keys.LOBBY_ENTITY, PersistentDataType.BYTE, (byte) 1);
        });
    }

    // ------------------------------------------------------------------ Защита лобби

    private boolean staffBuild(Player p) {
        return p.getGameMode() == GameMode.CREATIVE && plugin.roles().has(p, "usmp.admin.build");
    }

    @EventHandler(priority = EventPriority.LOW)
    public void damage(EntityDamageEvent e) {
        if (!isLobby(e.getEntity().getWorld())) return;
        if (e.getEntity() instanceof Player p) {
            e.setCancelled(true);
            if (e.getCause() == EntityDamageEvent.DamageCause.VOID) {
                Tasks.sync(() -> p.teleport(plugin.auth().isAuthed(p) ? spawn() : authSpot()));
            }
        } else if (e.getEntity().getPersistentDataContainer().has(Keys.NPC_ID, PersistentDataType.STRING)
                || e.getEntity().getPersistentDataContainer().has(Keys.LOBBY_ENTITY, PersistentDataType.BYTE)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void food(FoodLevelChangeEvent e) {
        if (isLobby(e.getEntity().getWorld())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void brk(BlockBreakEvent e) {
        if (isLobby(e.getBlock().getWorld()) && !staffBuild(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void place(BlockPlaceEvent e) {
        if (isLobby(e.getBlock().getWorld()) && !staffBuild(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void drop(PlayerDropItemEvent e) {
        if (isLobby(e.getPlayer().getWorld()) && !staffBuild(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void pickup(EntityPickupItemEvent e) {
        if (isLobby(e.getEntity().getWorld()) && !(e.getEntity() instanceof Player p && staffBuild(p))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void interact(PlayerInteractEvent e) {
        if (!isLobby(e.getPlayer().getWorld()) || staffBuild(e.getPlayer())) return;
        Block b = e.getClickedBlock();
        if (b != null && e.getAction() == Action.RIGHT_CLICK_BLOCK && b.getType().name().endsWith("_BUTTON")) {
            if (checkSecret(e.getPlayer(), b)) return;
        }
        if (b != null && e.getAction() != Action.PHYSICAL && interactions != null && interactions.click(e.getPlayer(), b, e.getAction())) {
            e.setCancelled(true);
            return;
        }
        if (e.getAction() == Action.PHYSICAL && b != null && b.getType().name().endsWith("PRESSURE_PLATE")) return; // паркур
        if (b != null && e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            String bn = b.getType().name();
            if (!bn.equals("IRON_DOOR") && !bn.equals("IRON_TRAPDOOR") && (bn.endsWith("_DOOR") || bn.endsWith("_TRAPDOOR") || bn.endsWith("_FENCE_GATE") || bn.equals("LEVER"))) {
                if (e.getItem() != null && e.getItem().getType().isBlock()) e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
                return;
            }
        }
        // Предметы использовать в лобби нельзя (жемчуг, еда, блоки и т.д.).
        if (e.getAction() != Action.PHYSICAL) e.setCancelled(true);
    }

    /** Нажата ли кнопка-секрет (по якорям). */
    public boolean checkSecret(Player p, Block b) {
        for (var en : SECRETS.entrySet()) {
            Location a = Anchors.get(en.getValue());
            if (a != null && a.getWorld() != null && a.getWorld().equals(b.getWorld())
                    && a.getBlockX() == b.getX() && a.getBlockY() == b.getY() && a.getBlockZ() == b.getZ()) {
                foundSecret(p, en.getKey());
                return true;
            }
        }
        return false;
    }

    public void foundSecret(Player p, String id) {
        if (plugin.profiles().unlock(p, "secret", id)) {
            plugin.profiles().addStat(p, "secrets_found", 1);
            Msg.title(p, "&d✦ Секрет найден ✦", "&7" + Msg.t("secret." + id, switch (id) {
                case "lobby_portal_top" -> "Над вратами SMP";
                case "lobby_under" -> "Колодец под островом";
                case "lobby_parkour_end" -> "Покоритель сложного паркура";
                case "smp_maze" -> "Сердце лабиринта";
                default -> id;
            }), 5, 50, 15);
            Msg.sound(p, org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
            plugin.logs().log(p, "progress", "secret", p.getLocation(), id);
        } else {
            Msg.actionBar(p, "&7Этот секрет вы уже нашли.");
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void move(PlayerMoveEvent e) {
        if (!isLobby(e.getTo().getWorld())) return;
        Player p = e.getPlayer();
        if (e.getTo().getY() < plugin.configs().main().getInt("lobby.min-y", 60)) {
            e.setTo(plugin.auth().isAuthed(p) ? (parkour != null && parkour.isRunning(p) ? parkour.checkpoint(p) : spawn()) : authSpot());
            return;
        }
        // Зоны входа для своих карт (вместо порталов).
        if (!e.hasChangedBlock() || !plugin.auth().isAuthed(p)) return;
        String smp = plugin.configs().main().getString("lobby.portals.smp", "");
        String duel = plugin.configs().main().getString("lobby.portals.duels", "");
        if (smp.isEmpty() && duel.isEmpty()) return;
        boolean toSmp = inBox(smp, e.getTo()), toDuels = !toSmp && inBox(duel, e.getTo());
        if (!toSmp && !toDuels) return;
        long now = System.currentTimeMillis();
        Long last = portalCooldown.get(p.getUniqueId());
        if (last != null && now - last < 3000) return;
        portalCooldown.put(p.getUniqueId(), now);
        Tasks.sync(() -> {
            if (toDuels) { if (plugin.duels() != null && plugin.modules().require(p, "duels")) plugin.duels().enter(p); }
            else enterSurvival(p);
        });
    }

    private static boolean inBox(String spec, Location l) {
        if (spec == null || spec.isBlank()) return false;
        String[] a = spec.split(";");
        if (a.length < 6) return false;
        try {
            double x1 = Double.parseDouble(a[0].trim()), y1 = Double.parseDouble(a[1].trim()), z1 = Double.parseDouble(a[2].trim());
            double x2 = Double.parseDouble(a[3].trim()), y2 = Double.parseDouble(a[4].trim()), z2 = Double.parseDouble(a[5].trim());
            return l.getX() >= Math.min(x1, x2) && l.getX() <= Math.max(x1, x2) + 1 && l.getY() >= Math.min(y1, y2) && l.getY() <= Math.max(y1, y2) + 1
                    && l.getZ() >= Math.min(z1, z2) && l.getZ() <= Math.max(z1, z2) + 1;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @EventHandler
    public void portalEnter(EntityPortalEnterEvent e) {
        if (!(e.getEntity() instanceof Player p) || !isLobby(p.getWorld())) return;
        long now = System.currentTimeMillis();
        Long last = portalCooldown.get(p.getUniqueId());
        if (last != null && now - last < 3000) return;
        portalCooldown.put(p.getUniqueId(), now);
        if (!plugin.auth().isAuthed(p)) return;
        boolean duels = p.getLocation().getZ() > 0;
        Tasks.sync(() -> {
            if (duels) {
                if (plugin.duels() != null && plugin.modules().require(p, "duels")) plugin.duels().enter(p);
            } else enterSurvival(p);
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void portal(PlayerPortalEvent e) {
        if (isLobby(e.getFrom().getWorld())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent e) {
        if (isLobby(e.getTo().getWorld()) && !isLobby(e.getFrom().getWorld())) {
            rememberSurvival(e.getPlayer(), e.getFrom());
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void quit(PlayerQuitEvent e) {
        rememberSurvival(e.getPlayer(), e.getPlayer().getLocation());
        portalCooldown.remove(e.getPlayer().getUniqueId());
        entering.remove(e.getPlayer().getUniqueId());
    }
}
