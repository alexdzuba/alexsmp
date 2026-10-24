package ru.unknownsmp.world;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;

/** Основной мир: граница, общий спавн и его безопасная зона. Приватов игроков нет. */
public final class WorldService implements Module, Listener {
    private final UnknownSMP plugin;
    private int radius;
    private boolean blockMonsters;

    public WorldService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "world"; }
    @Override public String title() { return "Мир и спавн"; }

    @Override
    public String[] depends() { return new String[]{"state"}; }

    @Override
    public void enable() {
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        applyBorder();
        World w0 = plugin.survivalWorld();
        w0.setGameRule(org.bukkit.GameRule.SPAWN_RADIUS, 0);
        String seed = String.valueOf(w0.getSeed());
        boolean sameWorld = seed.equals(plugin.state().get("smp_spawn_seed"));
        if (plugin.configs().main().getBoolean("survival.build-spawn", true) && (plugin.state().getLong("smp_spawn_built", 0) < 4 || !sameWorld)) {
            buildCity(w0, seed);
        } else {
            spawnHologram();
        }
    }

    private volatile boolean building;

    /** Идёт ли постройка города (пока идёт, вход в выживание закрыт). */
    public boolean building() { return building; }

    /**
     * Строит город в фоне: сначала асинхронно подгружаются чанки (без блокировки основного потока),
     * затем постройка идёт по шагам по тикам. Так сервер не зависает даже на слабом хостинге.
     */
    private void buildCity(World w0, String seed) {
        Location base = w0.getSpawnLocation();
        int[] site = findSite(w0, base.getBlockX(), base.getBlockZ());
        int bx = site[0], bz = site[1];
        building = true;
        int lim = Village.R + Village.SLOPE + 12;
        int c0x = (bx - lim) >> 4, c1x = (bx + lim) >> 4, c0z = (bz - lim) >> 4, c1z = (bz + lim) >> 4;
        java.util.List<java.util.concurrent.CompletableFuture<?>> futs = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger loaded = new java.util.concurrent.atomic.AtomicInteger();
        int total = (c1x - c0x + 1) * (c1z - c0z + 1);
        plugin.getLogger().info("Город SMP: подготовка территории в " + bx + ", " + bz + " (" + total + " чанков, в фоне — это может занять несколько минут на слабом хостинге)...");
        long t0 = System.currentTimeMillis();
        for (int x = c0x; x <= c1x; x++) for (int z = c0z; z <= c1z; z++) {
            w0.addPluginChunkTicket(x, z, plugin);
            futs.add(w0.getChunkAtAsync(x, z, true).thenAccept(c -> {
                int n = loaded.incrementAndGet();
                if (n % 40 == 0 || n == total) plugin.getLogger().info("Город SMP: чанков готово " + n + "/" + total);
            }));
        }
        java.util.concurrent.CompletableFuture.allOf(futs.toArray(new java.util.concurrent.CompletableFuture[0])).whenComplete((v, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.getLogger().info("Город SMP: территория готова за " + (System.currentTimeMillis() - t0) / 1000 + " с, строю...");
            Village.buildAsync(w0, bx, bz, 0, Anchors::put, sp -> {
                setSpawn(safeSpot(sp));
                plugin.state().set("smp_spawn_built", 4);
                plugin.state().set("smp_spawn_seed", seed);
                plugin.state().set("smp_center", bx + ";" + bz);
                for (int x = c0x; x <= c1x; x++) for (int z = c0z; z <= c1z; z++) w0.removePluginChunkTicket(x, z, plugin);
                building = false;
                plugin.getLogger().info("Город SMP построен за " + (System.currentTimeMillis() - t0) / 1000 + " с: " + Text.coords(sp));
                spawnHologram();
            });
        }));
    }

    /**
     * Ищет место для города в биоме равнин (спиралью вокруг точки спавна мира). Работает только по биомам —
     * чанки не грузятся, поэтому поиск мгновенный даже на слабом сервере.
     */
    private int[] findSite(World w, int sx, int sz) {
        int step = 192;
        int[] best = {sx, sz};
        int bestScore = Integer.MAX_VALUE;
        int[][] samples = new int[13][2];
        int k = 0;
        samples[k++] = new int[]{0, 0};
        for (int a = 0; a < 8; a++) samples[k++] = new int[]{(int) Math.round(Math.cos(a * Math.PI / 4) * 62), (int) Math.round(Math.sin(a * Math.PI / 4) * 62)};
        for (int a = 0; a < 4; a++) samples[k++] = new int[]{(int) Math.round(Math.cos(a * Math.PI / 2) * 30), (int) Math.round(Math.sin(a * Math.PI / 2) * 30)};
        for (int ring = 0; ring <= 6; ring++) {
            for (int i = -ring; i <= ring; i++) for (int j = -ring; j <= ring; j++) {
                if (Math.max(Math.abs(i), Math.abs(j)) != ring) continue;
                int x = sx + i * step, z = sz + j * step;
                int score = 0;
                for (int[] s : samples) {
                    org.bukkit.block.Biome b = w.getComputedBiome(x + s[0], 70, z + s[1]);
                    String n = b.getKey().getKey();
                    if (n.contains("ocean") || n.contains("river") || n.contains("beach")) score += 30;
                    else if (!n.equals("plains") && !n.equals("sunflower_plains") && !n.equals("meadow")) score += 8;
                }
                if (score < bestScore) {
                    bestScore = score;
                    best = new int[]{x, z};
                }
                if (score == 0) {
                    plugin.getLogger().info("Место для города: " + best[0] + ", " + best[1] + " (идеальные равнины)");
                    return best;
                }
            }
        }
        plugin.getLogger().info("Место для города: " + best[0] + ", " + best[1] + " (оценка " + bestScore + ")");
        return best;
    }

    private void spawnHologram() {
        Location s = spawn();
        if (s.getWorld() == null) return;
        for (org.bukkit.entity.Entity e : s.getWorld().getNearbyEntities(s, 12, 12, 12)) {
            if (e.getPersistentDataContainer().has(ru.unknownsmp.core.Keys.HOLOGRAM, org.bukkit.persistence.PersistentDataType.STRING)) e.remove();
        }
        Location h = Anchors.get("tips_smp");
        if (h == null) h = s.clone().add(0, 3.2, -3.5);
        s.getWorld().spawn(h, org.bukkit.entity.TextDisplay.class, td -> {
            td.text(Text.c(plugin.configs().main().getString("survival.spawn-hologram",
                    "&b&lALEX SMP\n&7Территория города безопасна: PvP и разрушение запрещены\n&7За стенами — всё по-настоящему\n&e/menu &7— меню  &e/help &7— команды  &e/daily &7— награда")));
            td.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            td.setBackgroundColor(org.bukkit.Color.fromARGB(110, 10, 0, 20));
            td.setPersistent(false);
            td.getPersistentDataContainer().set(ru.unknownsmp.core.Keys.HOLOGRAM, org.bukkit.persistence.PersistentDataType.STRING, "spawn");
        });
    }

    @Override
    public void reload() {
        YamlConfiguration c = plugin.configs().main();
        radius = c.getInt("spawn-zone.radius", 48);
        blockMonsters = c.getBoolean("spawn-zone.block-monsters", true);
    }

    public void applyBorder() {
        YamlConfiguration c = plugin.configs().main();
        if (!c.getBoolean("border.enabled", true)) return;
        double size = c.getDouble("border.size", 50000);
        double cx = c.getDouble("border.center-x", 0), cz = c.getDouble("border.center-z", 0);
        World w = plugin.survivalWorld();
        setBorder(w, cx, cz, size);
        for (World other : Bukkit.getWorlds()) {
            if (other == w || other.getName().equals(plugin.lobbyWorldName())) continue;
            if (other.getEnvironment() == World.Environment.NETHER && c.getBoolean("border.nether-scaled", true)) {
                setBorder(other, cx / 8, cz / 8, size / 8);
            } else if (other.getEnvironment() == World.Environment.THE_END) {
                setBorder(other, 0, 0, size);
            }
        }
    }

    private void setBorder(World w, double x, double z, double size) {
        WorldBorder b = w.getWorldBorder();
        b.setCenter(x, z);
        b.setSize(size);
        b.setWarningDistance(16);
    }

    public Location spawn() {
        Location l = Text.loc(plugin.configs().main().getString("survival.spawn", ""));
        if (l != null) return l;
        World w = plugin.survivalWorld();
        Location s = w.getSpawnLocation();
        return s.add(0.5, 0, 0.5);
    }

    /** Подбирает ближайшую безопасную точку на уровне площади: твёрдый пол, два свободных блока и открытое небо (не крыша и не внутри постройки). */
    private Location safeSpot(Location l) {
        World w = l.getWorld();
        int bx = l.getBlockX(), by = l.getBlockY(), bz = l.getBlockZ();
        for (int r = 0; r <= 10; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
            for (int dy = 0; dy >= -1; dy--) {
                int x = bx + dx, y = by + dy, z = bz + dz;
                var feet = w.getBlockAt(x, y, z);
                var head = w.getBlockAt(x, y + 1, z);
                var floor = w.getBlockAt(x, y - 1, z);
                if (!feet.isPassable() || feet.isLiquid() || !head.isPassable() || head.isLiquid() || !floor.getType().isSolid() || floor.getType().name().contains("SLAB")) continue;
                if (w.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING) > y + 1) continue;
                return new Location(w, x + 0.5, y, z + 0.5, l.getYaw(), l.getPitch());
            }
        }
        return l;
    }

    public void setSpawn(Location l) {
        plugin.configs().main().set("survival.spawn", Text.loc(l));
        plugin.configs().save("config.yml");
        l.getWorld().setSpawnLocation(l);
        l.getWorld().setGameRule(org.bukkit.GameRule.SPAWN_RADIUS, 0);
    }

    public int radius() { return radius; }

    public boolean inSpawnZone(Location l) {
        if (l == null || l.getWorld() == null) return false;
        Location s = spawn();
        if (s.getWorld() == null || !s.getWorld().equals(l.getWorld())) return false;
        double dx = l.getX() - s.getX(), dz = l.getZ() - s.getZ();
        return dx * dx + dz * dz <= (double) radius * radius;
    }

    private boolean bypass(Player p) {
        return plugin.roles().has(p, "usmp.admin.build");
    }

    // ---------- Защита зоны спавна ----------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void brk(BlockBreakEvent e) {
        if (inSpawnZone(e.getBlock().getLocation()) && !bypass(e.getPlayer())) {
            e.setCancelled(true);
            Msg.actionBar(e.getPlayer(), Msg.t("spawn.protected", "&cЗона спавна защищена"));
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void place(BlockPlaceEvent e) {
        if (inSpawnZone(e.getBlock().getLocation()) && !bypass(e.getPlayer())) {
            e.setCancelled(true);
            Msg.actionBar(e.getPlayer(), Msg.t("spawn.protected", "&cЗона спавна защищена"));
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void bucket(PlayerBucketEmptyEvent e) {
        if (inSpawnZone(e.getBlock().getLocation()) && !bypass(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void interact(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.PHYSICAL && e.getAction() != Action.LEFT_CLICK_BLOCK) return;
        if (!inSpawnZone(e.getClickedBlock().getLocation()) || bypass(e.getPlayer())) return;
        // Двери, калитки, люки, кнопки и рычаги работают; остальное в зоне — запрещено.
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            String bn = e.getClickedBlock().getType().name();
            boolean usable = !bn.equals("IRON_DOOR") && !bn.equals("IRON_TRAPDOOR")
                    && (bn.endsWith("_DOOR") || bn.endsWith("_TRAPDOOR") || bn.endsWith("_FENCE_GATE") || bn.endsWith("_BUTTON")
                    || bn.equals("LEVER") || bn.equals("BELL") || bn.equals("NOTE_BLOCK"));
            if (usable) {
                if (e.getItem() != null && e.getItem().getType().isBlock()) e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
                return;
            }
        }
        e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getItem() != null && e.getItem().getType().isBlock()) {
            e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void interactEntity(PlayerInteractEntityEvent e) {
        if (e.getRightClicked() instanceof org.bukkit.entity.ItemFrame && inSpawnZone(e.getRightClicked().getLocation()) && !bypass(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void hanging(HangingBreakByEntityEvent e) {
        if (inSpawnZone(e.getEntity().getLocation()) && !(e.getRemover() instanceof Player p && bypass(p))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void explode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> inSpawnZone(b.getLocation()));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void explodeBlock(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> inSpawnZone(b.getLocation()));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void flow(BlockFromToEvent e) {
        if (inSpawnZone(e.getToBlock().getLocation()) && !inSpawnZone(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void piston(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            if (inSpawnZone(b.getRelative(e.getDirection()).getLocation()) && !inSpawnZone(e.getBlock().getLocation())) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void burn(BlockBurnEvent e) {
        if (inSpawnZone(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void ignite(BlockIgniteEvent e) {
        if (inSpawnZone(e.getBlock().getLocation()) && !(e.getPlayer() != null && bypass(e.getPlayer()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void changeBlock(EntityChangeBlockEvent e) {
        if (!(e.getEntity() instanceof Player) && inSpawnZone(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void spawnMob(CreatureSpawnEvent e) {
        if (!blockMonsters || !(e.getEntity() instanceof Monster)) return;
        if (e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM || e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.COMMAND) return;
        if (inSpawnZone(e.getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void respawn(PlayerRespawnEvent e) {
        if (!e.isBedSpawn() && !e.isAnchorSpawn()) {
            e.setRespawnLocation(spawn());
        }
    }
}
