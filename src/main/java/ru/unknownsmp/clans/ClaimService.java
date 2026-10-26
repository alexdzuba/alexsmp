package ru.unknownsmp.clans;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Ravager;
import org.bukkit.entity.Wither;
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
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import ru.unknownsmp.core.Keys;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.db.Database;
import ru.unknownsmp.gui.ConfirmMenu;
import ru.unknownsmp.gui.Items;
import ru.unknownsmp.gui.Menu;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Территории кланов. Клан выделяет прямоугольник (на всю высоту мира) клановым жезлом или командами,
 * платит из банка клана за каждый блок площади и получает защиту: чужие не ломают, не строят,
 * не открывают сундуки и двери, не трогают животных, рамки и стойки; взрывы, огонь, поршни и жидкости снаружи
 * территорию не задевают.
 *
 * Совместимость с WorldEdit/FAWE и схематиками: жезл — отдельный предмет с меткой плагина (не деревянный топор),
 * а правки WorldEdit не создают событий игрока, поэтому плагин им не мешает.
 */
public final class ClaimService implements Module, Listener {
    public record Claim(long id, long clanId, String world, int x1, int z1, int x2, int z2) {
        public long area() { return (long) (x2 - x1 + 1) * (z2 - z1 + 1); }
        public boolean contains(String w, int x, int z) { return world.equals(w) && x >= x1 && x <= x2 && z >= z1 && z <= z2; }
        public boolean overlaps(String w, int ax1, int az1, int ax2, int az2) {
            return world.equals(w) && ax1 <= x2 && ax2 >= x1 && az1 <= z2 && az2 >= z1;
        }
        public String describe() { return x1 + "," + z1 + " → " + x2 + "," + z2 + " (" + (x2 - x1 + 1) + "×" + (z2 - z1 + 1) + ")"; }
    }

    private static final class Sel {
        String world;
        Integer x1, z1, x2, z2;
    }

    private final UnknownSMP plugin;
    private final Map<Long, Claim> claims = new ConcurrentHashMap<>();
    /** world -> chunkKey -> claims, пересекающие чанк. Быстрый поиск для частых событий (вода, поршни). */
    private final Map<String, Map<Long, List<Claim>>> index = new ConcurrentHashMap<>();
    private final Map<UUID, Sel> selections = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastDenyMsg = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastClaimAt = new ConcurrentHashMap<>();
    private final Set<Long> busy = ConcurrentHashMap.newKeySet();

    // Настройки.
    private long pricePerBlock = 100;
    private int minSide = 5, maxSide = 200, maxClaims = 5;
    private long maxArea = 40_000, maxTotalArea = 80_000;
    private double refund = 0.5;
    private boolean allyAccess, warBuild = true, warContainers, protectExplosions = true, homeInClaim = true;
    private List<String> worlds = List.of();

    public ClaimService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "claims"; }
    @Override public String title() { return "Территории кланов"; }
    @Override public String[] depends() { return new String[]{"clans", "economy"}; }

    @Override
    public void enable() throws Exception {
        reload();
        plugin.db().queryBlocking(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, clan_id, world, x1, z1, x2, z2 FROM clan_claims"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) add(new Claim(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7)));
            }
            return null;
        });
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("Территории кланов: загружено " + claims.size() + ".");
    }

    @Override
    public void reload() {
        YamlConfiguration c = plugin.configs().get("clans.yml");
        pricePerBlock = Math.max(0, c.getLong("claims.price-per-block", 100));
        minSide = Math.max(1, c.getInt("claims.min-side", 5));
        maxSide = Math.max(minSide, c.getInt("claims.max-side", 200));
        maxArea = Math.max(1, c.getLong("claims.max-area", 40_000));
        maxTotalArea = Math.max(maxArea, c.getLong("claims.max-total-area", 80_000));
        maxClaims = Math.max(1, c.getInt("claims.max-claims", 5));
        refund = Math.max(0, Math.min(1, c.getDouble("claims.refund-percent", 50) / 100.0));
        allyAccess = c.getBoolean("claims.allies-can-build", false);
        warBuild = c.getBoolean("claims.war.enemies-can-build", true);
        warContainers = c.getBoolean("claims.war.enemies-can-open-containers", false);
        protectExplosions = c.getBoolean("claims.protect-from-explosions", true);
        homeInClaim = c.getBoolean("claims.home-inside-claim", true);
        worlds = c.getStringList("claims.worlds");
    }

    // ------------------------------------------------------------------ Индекс

    private static long chunkKey(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }

    private void add(Claim cl) {
        claims.put(cl.id(), cl);
        Map<Long, List<Claim>> w = index.computeIfAbsent(cl.world(), k -> new ConcurrentHashMap<>());
        for (int cx = cl.x1() >> 4; cx <= cl.x2() >> 4; cx++)
            for (int cz = cl.z1() >> 4; cz <= cl.z2() >> 4; cz++)
                w.computeIfAbsent(chunkKey(cx, cz), k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(cl);
    }

    private void remove(Claim cl) {
        claims.remove(cl.id());
        Map<Long, List<Claim>> w = index.get(cl.world());
        if (w == null) return;
        for (int cx = cl.x1() >> 4; cx <= cl.x2() >> 4; cx++)
            for (int cz = cl.z1() >> 4; cz <= cl.z2() >> 4; cz++) {
                List<Claim> l = w.get(chunkKey(cx, cz));
                if (l != null) { l.remove(cl); if (l.isEmpty()) w.remove(chunkKey(cx, cz)); }
            }
    }

    public Claim claimAt(String world, int x, int z) {
        Map<Long, List<Claim>> w = index.get(world);
        if (w == null) return null;
        List<Claim> l = w.get(chunkKey(x >> 4, z >> 4));
        if (l == null) return null;
        for (Claim c : l) if (c.contains(world, x, z)) return c;
        return null;
    }

    public Claim claimAt(Location l) {
        return l == null || l.getWorld() == null ? null : claimAt(l.getWorld().getName(), l.getBlockX(), l.getBlockZ());
    }

    public Claim claimAt(Block b) { return claimAt(b.getWorld().getName(), b.getX(), b.getZ()); }

    public List<Claim> claimsOf(long clanId) {
        List<Claim> out = new ArrayList<>();
        for (Claim c : claims.values()) if (c.clanId() == clanId) out.add(c);
        out.sort((a, b) -> Long.compare(a.id(), b.id()));
        return out;
    }

    public long areaOf(long clanId) {
        long s = 0;
        for (Claim c : claims.values()) if (c.clanId() == clanId) s += c.area();
        return s;
    }

    /** Общая площадь с учётом уровня клана. */
    public long totalAreaFor(Clan cl) {
        int bonus = plugin.clanLevels() == null ? 0 : plugin.clanLevels().areaBonus(cl);
        return maxTotalArea * (100 + bonus) / 100;
    }

    public int claimsFor(Clan cl) {
        return maxClaims + (plugin.clanLevels() == null ? 0 : plugin.clanLevels().claimsBonus(cl));
    }

    public boolean isInsideOwnClaim(Player p, Location l) {
        Claim c = claimAt(l);
        Clan cl = plugin.clans().clanOf(p);
        return c != null && cl != null && c.clanId() == cl.id;
    }

    public boolean homeMustBeInClaim(Clan cl) {
        return homeInClaim && !claimsOf(cl.id).isEmpty();
    }

    // ------------------------------------------------------------------ Права

    private enum Act { BUILD, CONTAINER, INTERACT, ENTITY }

    /** Может ли игрок совершить действие в точке. */
    public boolean can(Player p, Block b, Act act) {
        Claim c = claimAt(b);
        if (c == null) return true;
        return canIn(p, c, act);
    }

    /** Может ли игрок ломать/бить сущность (стойку, лодку и т.п.) в этой точке. */
    public boolean canTouchEntity(Player p, Location l) {
        Claim c = claimAt(l);
        return c == null || canIn(p, c, Act.ENTITY);
    }

    private boolean canIn(Player p, Claim c, Act act) {
        if (plugin.roles().has(p, "usmp.admin.build")) return true;
        Clan mine = plugin.clans().clanOf(p);
        if (mine != null && mine.id == c.clanId()) return true;
        Clan owner = plugin.clans().byId(c.clanId());
        if (owner == null) return true; // клан распущен, территория бесхозная
        if (mine != null && allyAccess && plugin.clans().allied(mine, owner)) return true;
        if (mine != null && plugin.wars() != null && plugin.wars().activeBetween(mine, owner) != null) {
            if (act == Act.BUILD && warBuild) return true;
            if ((act == Act.CONTAINER || act == Act.INTERACT) && warContainers) return true;
        }
        return false;
    }

    private void deny(Player p, Claim c) {
        long now = System.currentTimeMillis();
        Long last = lastDenyMsg.get(p.getUniqueId());
        if (last != null && now - last < 1500) return;
        lastDenyMsg.put(p.getUniqueId(), now);
        Clan owner = plugin.clans().byId(c.clanId());
        Msg.actionBar(p, Msg.t("claim.denied", "&cТерритория клана &d[{t}]&c — здесь нельзя.", "t", owner == null ? "?" : owner.tag));
    }

    // ------------------------------------------------------------------ Защита

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void brk(BlockBreakEvent e) {
        Claim c = claimAt(e.getBlock());
        if (c != null && !canIn(e.getPlayer(), c, Act.BUILD)) { e.setCancelled(true); deny(e.getPlayer(), c); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void place(BlockPlaceEvent e) {
        Claim c = claimAt(e.getBlock());
        if (c != null && !canIn(e.getPlayer(), c, Act.BUILD)) { e.setCancelled(true); deny(e.getPlayer(), c); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void bucketEmpty(PlayerBucketEmptyEvent e) {
        Claim c = claimAt(e.getBlock());
        if (c != null && !canIn(e.getPlayer(), c, Act.BUILD)) { e.setCancelled(true); deny(e.getPlayer(), c); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void bucketFill(PlayerBucketFillEvent e) {
        Claim c = claimAt(e.getBlock());
        if (c != null && !canIn(e.getPlayer(), c, Act.BUILD)) { e.setCancelled(true); deny(e.getPlayer(), c); }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void interact(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        // Клановый жезл обрабатывается отдельно (выделение территории).
        if (handleWand(e)) return;
        if (b == null) return;
        if (e.getAction() == Action.PHYSICAL) {
            Claim c = claimAt(b);
            if (c != null && !canIn(e.getPlayer(), c, Act.INTERACT)) e.setCancelled(true);
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Material m = b.getType();
        if (m == Material.ENDER_CHEST || !m.isInteractable()) return;
        Claim c = claimAt(b);
        if (c == null) return;
        boolean container = b.getState(false) instanceof InventoryHolder;
        if (!canIn(e.getPlayer(), c, container ? Act.CONTAINER : Act.INTERACT)) {
            e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            if (e.getItem() == null || e.getItem().getType().isBlock()) e.setCancelled(true);
            deny(e.getPlayer(), c);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void interactEntity(PlayerInteractEntityEvent e) {
        Entity t = e.getRightClicked();
        if (!(t instanceof ItemFrame || t instanceof InventoryHolder || t instanceof Allay)) return;
        Claim c = claimAt(t.getLocation());
        if (c != null && !canIn(e.getPlayer(), c, Act.CONTAINER)) { e.setCancelled(true); deny(e.getPlayer(), c); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void armorStand(PlayerArmorStandManipulateEvent e) {
        Claim c = claimAt(e.getRightClicked().getLocation());
        if (c != null && !canIn(e.getPlayer(), c, Act.ENTITY)) { e.setCancelled(true); deny(e.getPlayer(), c); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void entityPlace(EntityPlaceEvent e) {
        if (e.getPlayer() == null) return;
        Claim c = claimAt(e.getBlock());
        if (c != null && !canIn(e.getPlayer(), c, Act.BUILD)) { e.setCancelled(true); deny(e.getPlayer(), c); }
    }

    private static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void damage(EntityDamageByEntityEvent e) {
        Entity v = e.getEntity();
        if (v instanceof Player || v instanceof Monster) return;
        Player p = attacker(e.getDamager());
        if (p == null) return;
        Claim c = claimAt(v.getLocation());
        if (c != null && !canIn(p, c, Act.ENTITY)) { e.setCancelled(true); deny(p, c); }
    }

    /** Вагонетки и лодки на территории: чужие не ломают. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void vehicleDamage(org.bukkit.event.vehicle.VehicleDamageEvent e) {
        Player p = attacker(e.getAttacker());
        if (p == null) return;
        Claim c = claimAt(e.getVehicle().getLocation());
        if (c != null && !canIn(p, c, Act.ENTITY)) { e.setCancelled(true); deny(p, c); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void vehicleDestroy(org.bukkit.event.vehicle.VehicleDestroyEvent e) {
        Player p = attacker(e.getAttacker());
        if (p == null) return;
        Claim c = claimAt(e.getVehicle().getLocation());
        if (c != null && !canIn(p, c, Act.ENTITY)) { e.setCancelled(true); deny(p, c); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void hanging(HangingBreakByEntityEvent e) {
        Player p = attacker(e.getRemover());
        Hanging h = e.getEntity();
        Claim c = claimAt(h.getLocation());
        if (c == null) return;
        if (p == null) { if (protectExplosions) e.setCancelled(true); return; }
        if (!canIn(p, c, Act.BUILD)) { e.setCancelled(true); deny(p, c); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void entityExplode(EntityExplodeEvent e) {
        if (protectExplosions) e.blockList().removeIf(b -> claimAt(b) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void blockExplode(BlockExplodeEvent e) {
        if (protectExplosions) e.blockList().removeIf(b -> claimAt(b) != null);
    }

    /** Взрывы на территории не ранят животных, жителей, стойки и рамки (игроков и монстров — как обычно). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void explosionDamage(org.bukkit.event.entity.EntityDamageEvent e) {
        if (!protectExplosions) return;
        var cause = e.getCause();
        if (cause != org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                && cause != org.bukkit.event.entity.EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) return;
        Entity v = e.getEntity();
        if (v instanceof Player || v instanceof Monster) return;
        if (claimAt(v.getLocation()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void burn(BlockBurnEvent e) {
        if (claimAt(e.getBlock()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void ignite(BlockIgniteEvent e) {
        Claim c = claimAt(e.getBlock());
        if (c == null) return;
        if (e.getPlayer() != null) {
            if (!canIn(e.getPlayer(), c, Act.BUILD)) { e.setCancelled(true); deny(e.getPlayer(), c); }
        } else if (e.getCause() == BlockIgniteEvent.IgniteCause.SPREAD || e.getCause() == BlockIgniteEvent.IgniteCause.LAVA) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void flow(BlockFromToEvent e) {
        Block to = e.getToBlock();
        Claim dst = claimAt(to);
        if (dst == null) return;
        Claim src = claimAt(e.getBlock());
        if (src == null || src.clanId() != dst.clanId()) e.setCancelled(true);
    }

    private boolean pistonCrosses(Block piston, List<Block> blocks, org.bukkit.block.BlockFace dir) {
        Claim pc = claimAt(piston);
        long owner = pc == null ? -1 : pc.clanId();
        for (Block b : blocks) {
            for (Block t : new Block[]{b, b.getRelative(dir)}) {
                Claim c = claimAt(t);
                if (c != null && c.clanId() != owner) return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void pistonOut(BlockPistonExtendEvent e) {
        if (pistonCrosses(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void pistonIn(BlockPistonRetractEvent e) {
        if (pistonCrosses(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void mobGrief(EntityChangeBlockEvent e) {
        Entity en = e.getEntity();
        if (en instanceof Player || en instanceof FallingBlock) return;
        if (!(en instanceof Enderman || en instanceof Wither || en instanceof Ravager)) return;
        if (claimAt(e.getBlock()) != null) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ Вход на территорию

    private final Map<UUID, Long> inClaim = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void move(PlayerMoveEvent e) {
        Location f = e.getFrom(), t = e.getTo();
        if (f.getBlockX() == t.getBlockX() && f.getBlockZ() == t.getBlockZ() && f.getWorld() == t.getWorld()) return;
        Claim c = claimAt(t);
        long now = c == null ? 0 : c.clanId();
        Long before = inClaim.get(e.getPlayer().getUniqueId());
        long prev = before == null ? 0 : before;
        if (now == prev) return;
        inClaim.put(e.getPlayer().getUniqueId(), now);
        if (now != 0) {
            Clan owner = plugin.clans().byId(now);
            if (owner != null) Msg.actionBar(e.getPlayer(), "&7Территория клана &d[" + owner.tag + "] &f" + owner.name);
        } else {
            Msg.actionBar(e.getPlayer(), "&7Ничейная земля");
        }
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        inClaim.remove(u);
        selections.remove(u);
        lastDenyMsg.remove(u);
    }

    // ------------------------------------------------------------------ Жезл и выделение

    public ItemStack wand() {
        ItemStack it = Items.of(Material.GOLDEN_HOE).name("&6&lКлановый жезл")
                .lore("&7ЛКМ по блоку — первый угол", "&7ПКМ по блоку — второй угол", "&7Затем: &e/clan claim buy",
                        "", "&8Не связан с WorldEdit").glow().build();
        var meta = it.getItemMeta();
        // Убираем метку меню, иначе предмет удалялся бы как кнопка интерфейса.
        meta.getPersistentDataContainer().remove(Keys.MENU_ITEM);
        meta.getPersistentDataContainer().set(Keys.of("claim_wand"), PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    public static boolean isWand(ItemStack it) {
        return it != null && it.getType() == Material.GOLDEN_HOE && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(Keys.of("claim_wand"), PersistentDataType.BYTE);
    }

    private boolean handleWand(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !isWand(e.getItem())) return false;
        Block b = e.getClickedBlock();
        if (b == null) return true;
        e.setCancelled(true);
        if (e.getAction() == Action.LEFT_CLICK_BLOCK) setPos(e.getPlayer(), b.getLocation(), true);
        else if (e.getAction() == Action.RIGHT_CLICK_BLOCK) setPos(e.getPlayer(), b.getLocation(), false);
        return true;
    }

    public void setPos(Player p, Location l, boolean first) {
        Sel s = selections.computeIfAbsent(p.getUniqueId(), k -> new Sel());
        String w = l.getWorld().getName();
        if (s.world != null && !s.world.equals(w)) { s.x1 = s.z1 = s.x2 = s.z2 = null; }
        s.world = w;
        if (first) { s.x1 = l.getBlockX(); s.z1 = l.getBlockZ(); } else { s.x2 = l.getBlockX(); s.z2 = l.getBlockZ(); }
        String extra = "";
        if (s.x1 != null && s.x2 != null) {
            int dx = Math.abs(s.x2 - s.x1) + 1, dz = Math.abs(s.z2 - s.z1) + 1;
            long area = (long) dx * dz;
            extra = " &7| " + dx + "×" + dz + " = &f" + Text.num(area) + " &7блоков, цена &6" + Text.money(area * pricePerBlock);
            showRect(p, w, Math.min(s.x1, s.x2), Math.min(s.z1, s.z2), Math.max(s.x1, s.x2), Math.max(s.z1, s.z2), Color.YELLOW);
        }
        Msg.send(p, "claim.pos", "&e{n} угол: &f{x}, {z}{e}", "n", first ? "Первый" : "Второй", "x", l.getBlockX(), "z", l.getBlockZ(), "e", extra);
    }

    /** Подсветка границы частицами на 10 секунд. */
    public void showRect(Player p, String world, int x1, int z1, int x2, int z2, Color color) {
        World w = Bukkit.getWorld(world);
        if (w == null || !p.getWorld().equals(w)) return;
        long perimeter = 2L * ((x2 - x1 + 1) + (z2 - z1 + 1));
        int step = (int) Math.max(1, perimeter / 300);
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.5f);
        int[] runs = {0};
        org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = Tasks.timer(0, 20, () -> {
            if (++runs[0] > 10 || !p.isOnline() || !p.getWorld().equals(w)) { task[0].cancel(); return; }
            double y = p.getLocation().getY() + 1;
            for (int x = x1; x <= x2 + 1; x += step) {
                p.spawnParticle(Particle.DUST, x, y, z1, 1, dust);
                p.spawnParticle(Particle.DUST, x, y, z2 + 1, 1, dust);
            }
            for (int z = z1; z <= z2 + 1; z += step) {
                p.spawnParticle(Particle.DUST, x1, y, z, 1, dust);
                p.spawnParticle(Particle.DUST, x2 + 1, y, z, 1, dust);
            }
        });
    }

    // ------------------------------------------------------------------ Покупка

    private boolean worldAllowed(String w) {
        if (worlds.isEmpty()) return plugin.survivalWorld() != null && plugin.survivalWorld().getName().equals(w);
        return worlds.contains(w);
    }

    /** Проверка выделения. Возвращает текст ошибки или null. */
    private String validate(Clan cl, String w, int x1, int z1, int x2, int z2) {
        if (!worldAllowed(w)) return "В этом мире территории не выдаются.";
        int dx = x2 - x1 + 1, dz = z2 - z1 + 1;
        if (dx < minSide || dz < minSide) return "Слишком маленькая территория: минимум " + minSide + "×" + minSide + ".";
        if (dx > maxSide || dz > maxSide) return "Сторона территории — не больше " + maxSide + " блоков.";
        long area = (long) dx * dz;
        if (area > maxArea) return "Слишком большая территория: максимум " + Text.num(maxArea) + " блоков за раз.";
        if (claimsOf(cl.id).size() >= claimsFor(cl)) return "У клана уже максимум территорий (" + claimsFor(cl) + "). Больше — на новом уровне клана: /clan level";
        if (areaOf(cl.id) + area > totalAreaFor(cl)) return "Превышена общая площадь клана: максимум " + Text.num(totalAreaFor(cl)) + " блоков. Больше — на новом уровне клана: /clan level";
        for (Claim c : claims.values()) if (c.overlaps(w, x1, z1, x2, z2)) {
            Clan o = plugin.clans().byId(c.clanId());
            return "Пересекается с территорией клана [" + (o == null ? "?" : o.tag) + "].";
        }
        // Зона спавна защищена отдельно — приватить её нельзя.
        Location s = plugin.world().spawn();
        if (s.getWorld() != null && s.getWorld().getName().equals(w)) {
            int r = plugin.world().radius();
            double cx = Math.max(x1, Math.min(s.getX(), x2 + 1)), cz = Math.max(z1, Math.min(s.getZ(), z2 + 1));
            double ddx = cx - s.getX(), ddz = cz - s.getZ();
            if (ddx * ddx + ddz * ddz <= (double) r * r) return "Нельзя приватить зону спавна.";
        }
        return null;
    }

    public void buy(Player p) {
        Clan cl = plugin.clans().clanOf(p);
        if (cl == null) { Msg.error(p, "clan.none", "Вы не состоите в клане."); return; }
        if (!cl.can(p.getUniqueId(), Clan.Perm.CLAIM)) { Msg.error(p, "clan.no-perm", "Недостаточно прав в клане."); return; }
        Sel s = selections.get(p.getUniqueId());
        if (s == null || s.x1 == null || s.x2 == null) {
            Msg.error(p, "claim.no-sel", "Сначала выделите два угла: клановый жезл (/clan claim wand) или /clan claim pos1 и pos2.");
            return;
        }
        int x1 = Math.min(s.x1, s.x2), x2 = Math.max(s.x1, s.x2), z1 = Math.min(s.z1, s.z2), z2 = Math.max(s.z1, s.z2);
        String err = validate(cl, s.world, x1, z1, x2, z2);
        if (err != null) { Msg.error(p, "claim.invalid", "{e}", "e", err); return; }
        long area = (long) (x2 - x1 + 1) * (z2 - z1 + 1);
        long cost = area * pricePerBlock;
        ConfirmMenu.open(p, "&8Купить территорию?", new ItemStack(Material.GRASS_BLOCK), List.of(
                "&7Размер: &f" + (x2 - x1 + 1) + "×" + (z2 - z1 + 1) + " = " + Text.num(area) + " блоков",
                "&7Цена: &6" + Text.money(cost) + " &7(" + Text.money(pricePerBlock) + " за блок)",
                "&7Оплата из банка клана: &6" + Text.money(cl.bank),
                "&7Углы: &f" + x1 + "," + z1 + " → " + x2 + "," + z2), () -> purchase(p, cl, s.world, x1, z1, x2, z2, cost));
    }

    private void purchase(Player p, Clan cl, String w, int x1, int z1, int x2, int z2, long cost) {
        String err = validate(cl, w, x1, z1, x2, z2);
        if (err != null) { Msg.error(p, "claim.invalid", "{e}", "e", err); return; }
        if (!busy.add(cl.id)) return;
        final UUID u = p.getUniqueId();
        Tasks.then(plugin.db().tx(c -> {
            int n = Database.update(c, "UPDATE clans SET bank = bank - ? WHERE id=? AND bank >= ?", cost, cl.id, cost);
            if (n != 1) throw new Database.TxAbort("claim.bank-low", "В банке клана недостаточно средств. Пополните: /clan deposit <сумма>");
            long id = Database.insert(c, "INSERT INTO clan_claims(clan_id, world, x1, z1, x2, z2, created_at, cost) VALUES (?,?,?,?,?,?,?,?)",
                    cl.id, w, x1, z1, x2, z2, System.currentTimeMillis(), cost);
            Database.update(c, "INSERT INTO clan_bank_log(clan_id, uuid, delta, at, reason) VALUES (?,?,?,?,?)", cl.id, u.toString(), -cost, System.currentTimeMillis(), "claim");
            Long bank = Database.longVal(c, "SELECT bank FROM clans WHERE id=?", cl.id);
            return new long[]{id, bank == null ? 0 : bank};
        }), r -> {
            busy.remove(cl.id);
            cl.bank = r[1];
            Claim claim = new Claim(r[0], cl.id, w, x1, z1, x2, z2);
            add(claim);
            selections.remove(u);
            plugin.clans().broadcastClan(cl, "claim.bought", "&a{p} купил территорию клана #{id}: {d} за {c}.",
                    "p", p.getName(), "id", r[0], "d", claim.describe(), "c", Text.money(cost));
            if (p.isOnline()) showRect(p, w, x1, z1, x2, z2, Color.LIME);
            plugin.logs().log(p, "clan", "claim_buy", p.getLocation(), cl.tag + " #" + r[0] + " " + claim.describe() + " cost=" + cost);
        }, ex -> {
            busy.remove(cl.id);
            if (ex instanceof Database.TxAbort ab) Msg.error(p, ab.key, ab.def);
            else Msg.error(p, "claim.error", "Ошибка покупки. Деньги не списаны.");
        });
    }

    public void unclaim(Player p, long id) {
        Clan cl = plugin.clans().clanOf(p);
        Claim c = claims.get(id);
        if (cl == null || c == null || c.clanId() != cl.id) { Msg.error(p, "claim.unknown", "У вашего клана нет территории #{id}.", "id", id); return; }
        if (!cl.leader.equals(p.getUniqueId())) { Msg.error(p, "clan.leader-only", "Только лидер может отказаться от территории."); return; }
        long back = Math.round(c.area() * pricePerBlock * refund);
        ConfirmMenu.open(p, "&8Отказаться от территории?", new ItemStack(Material.BARRIER), List.of(
                "&7Территория #" + id + ": &f" + c.describe(), "&7Возврат в банк клана: &6" + Text.money(back)), () -> {
            if (!claims.containsKey(id) || !busy.add(cl.id)) return;
            Tasks.then(plugin.db().tx(con -> {
                Database.update(con, "DELETE FROM clan_claims WHERE id=?", id);
                if (back > 0) {
                    Database.update(con, "UPDATE clans SET bank = bank + ? WHERE id=?", back, cl.id);
                    Database.update(con, "INSERT INTO clan_bank_log(clan_id, uuid, delta, at, reason) VALUES (?,?,?,?,?)", cl.id, p.getUniqueId().toString(), back, System.currentTimeMillis(), "unclaim");
                }
                Long bank = Database.longVal(con, "SELECT bank FROM clans WHERE id=?", cl.id);
                return bank == null ? 0L : bank;
            }), bank -> {
                busy.remove(cl.id);
                cl.bank = bank;
                remove(c);
                plugin.clans().broadcastClan(cl, "claim.removed", "&eТерритория #{id} освобождена. Возврат в банк: {b}.", "id", id, "b", Text.money(back));
                plugin.logs().log(p, "clan", "claim_remove", p.getLocation(), cl.tag + " #" + id + " refund=" + back);
            }, ex -> { busy.remove(cl.id); Msg.error(p, "claim.error", "Ошибка операции."); });
        });
    }

    /** Клан распущен — территории удаляются. */
    public void onDisband(Clan cl) {
        List<Claim> list = claimsOf(cl.id);
        if (list.isEmpty()) return;
        for (Claim c : list) remove(c);
        plugin.db().tx(c -> Database.update(c, "DELETE FROM clan_claims WHERE clan_id=?", cl.id));
    }

    // ------------------------------------------------------------------ Команды и меню

    public void command(Player p, String[] a) {
        String sub = a.length > 1 ? a[1].toLowerCase(java.util.Locale.ROOT) : "";
        switch (sub) {
            case "wand" -> {
                plugin.mail().giveOrMail(p, wand(), "claim");
                Msg.ok(p, "claim.wand", "Клановый жезл выдан. ЛКМ — первый угол, ПКМ — второй.");
            }
            case "pos1" -> setPos(p, p.getLocation(), true);
            case "pos2" -> setPos(p, p.getLocation(), false);
            case "buy" -> buy(p);
            case "list" -> list(p);
            case "show" -> show(p);
            case "remove", "unclaim" -> {
                if (a.length < 3) { Msg.error(p, "claim.remove-usage", "Использование: /clan claim remove <номер>"); return; }
                try { unclaim(p, Long.parseLong(a[2].replace("#", ""))); } catch (NumberFormatException ex) { Msg.error(p, "input.number", "Нужно число."); }
            }
            case "info", "here" -> {
                Claim c = claimAt(p.getLocation());
                if (c == null) { Msg.send(p, "claim.here-none", "&7Здесь ничейная земля."); return; }
                Clan o = plugin.clans().byId(c.clanId());
                Msg.send(p, "claim.here", "&7Территория #{id} клана &d[{t}]&7: {d}", "id", c.id(), "t", o == null ? "?" : o.tag, "d", c.describe());
            }
            default -> menu(p, null);
        }
    }

    private void list(Player p) {
        Clan cl = plugin.clans().clanOf(p);
        if (cl == null) { Msg.error(p, "clan.none", "Вы не состоите в клане."); return; }
        List<Claim> list = claimsOf(cl.id);
        Msg.raw(p, "&8--- &dТерритории [" + cl.tag + "] &7(" + list.size() + "/" + claimsFor(cl) + ", " + Text.num(areaOf(cl.id)) + "/" + Text.num(totalAreaFor(cl)) + " блоков) &8---");
        for (Claim c : list) Msg.raw(p, "&e#" + c.id() + " &f" + c.describe() + " &7(" + c.world() + ")");
        if (list.isEmpty()) Msg.raw(p, "&7Территорий нет. Выделите участок жезлом: &e/clan claim wand");
    }

    private void show(Player p) {
        String w = p.getWorld().getName();
        int n = 0;
        for (Claim c : claims.values()) {
            if (!c.world().equals(w)) continue;
            double dx = Math.max(c.x1() - p.getX(), Math.max(0, p.getX() - c.x2()));
            double dz = Math.max(c.z1() - p.getZ(), Math.max(0, p.getZ() - c.z2()));
            if (dx * dx + dz * dz > 128 * 128) continue;
            Clan mine = plugin.clans().clanOf(p);
            showRect(p, w, c.x1(), c.z1(), c.x2(), c.z2(), mine != null && mine.id == c.clanId() ? Color.LIME : Color.RED);
            n++;
        }
        Msg.send(p, "claim.show", "&7Показаны границы территорий рядом: &f{n}&7 (зелёные — ваши).", "n", n);
    }

    public void menu(Player p, Menu parent) {
        Clan cl = plugin.clans().clanOf(p);
        if (cl == null) { Msg.error(p, "clan.none", "Вы не состоите в клане."); return; }
        new Menu(p, 3, "&8Территория клана") {
            @Override
            protected void build() {
                fill(Items.filler());
                List<Claim> list = claimsOf(cl.id);
                set(10, Items.of(Material.GOLDEN_HOE).name("&6Клановый жезл").lore("&7Выделите участок: ЛКМ и ПКМ по углам.",
                        "&7Цена: &6" + Text.money(pricePerBlock) + " &7за каждый блок площади.", "", "&eПолучить").build(), c -> {
                    viewer.closeInventory();
                    command(viewer, new String[]{"claim", "wand"});
                });
                set(12, Items.of(Material.EMERALD).name("&aКупить выделенное").lore("&7Оплата из банка клана: &6" + Text.money(cl.bank),
                        "&7Территорий: &f" + list.size() + "/" + claimsFor(cl), "&7Площадь: &f" + Text.num(areaOf(cl.id)) + "/" + Text.num(totalAreaFor(cl))).build(), c -> buy(viewer));
                set(14, Items.of(Material.MAP).name("&eСписок территорий").lore("&7Номера, размеры, координаты").build(), c -> { viewer.closeInventory(); list(viewer); });
                set(16, Items.of(Material.SPYGLASS).name("&bПоказать границы").lore("&7Подсветить территории рядом на 10 секунд").build(), c -> { viewer.closeInventory(); show(viewer); });
                set(4, Items.of(Material.RED_BED).name("&dКлановая база").lore("&7Точка клана ставится на вашей территории:",
                        "&e/clan sethome&7, телепорт для всех: &e/clan home").build());
                if (parent != null) parent(parent);
                backButton(22);
            }
        }.open();
    }

    public Map<Long, Claim> all() { return claims; }

    /** Для отладки и /usmp lag: сколько территорий. */
    public int count() { return claims.size(); }
}
