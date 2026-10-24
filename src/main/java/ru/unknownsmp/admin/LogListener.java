package ru.unknownsmp.admin;

import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.gui.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Сбор событий для журналов. */
final class LogListener implements Listener {
    private static final Set<String> REDACT = Set.of("login", "l", "log", "register", "reg", "changepassword", "unknownsmp:login",
            "unknownsmp:register", "unknownsmp:changepassword");

    private final UnknownSMP plugin;
    private final LogService logs;
    private final Map<UUID, Snapshot> open = new ConcurrentHashMap<>();

    private record Snapshot(Inventory inv, List<ItemStack> protos, List<Integer> counts, org.bukkit.Location loc) {}

    LogListener(UnknownSMP plugin, LogService logs) {
        this.plugin = plugin;
        this.logs = logs;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void place(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        logs.block(e.getPlayer(), null, "PLACE", b.getLocation(), e.getBlockReplacedState().getBlockData().getAsString(),
                b.getBlockData().getAsString(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void brk(BlockBreakEvent e) {
        Block b = e.getBlock();
        byte[] extra = null;
        BlockState st = b.getState(false);
        if (st instanceof Container ct) {
            ItemStack[] contents = ct.getInventory().getContents();
            if (ct.getInventory().getHolder() instanceof DoubleChest) {
                // В двойном сундуке ломается только половина — сохраняем содержимое этой половины.
                contents = ((org.bukkit.block.Chest) st).getBlockInventory().getContents();
            }
            extra = ItemStack.serializeItemsAsBytes(nonNull(contents));
        }
        logs.block(e.getPlayer(), null, "BREAK", b.getLocation(), b.getBlockData().getAsString(), "minecraft:air", extra);
    }

    private static ItemStack[] nonNull(ItemStack[] in) {
        ItemStack[] out = new ItemStack[in.length];
        for (int i = 0; i < in.length; i++) out[i] = in[i] == null ? ItemStack.empty() : in[i];
        return out;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void explode(EntityExplodeEvent e) {
        Player source = null;
        String actor = "#" + e.getEntityType().name().toLowerCase();
        Entity ent = e.getEntity();
        if (ent instanceof TNTPrimed tnt && tnt.getSource() instanceof Player p) source = p;
        for (Block b : e.blockList()) {
            byte[] extra = null;
            if (b.getState(false) instanceof Container ct) extra = ItemStack.serializeItemsAsBytes(nonNull(ct.getInventory().getContents()));
            logs.block(source, source == null ? actor : null, "EXPLODE", b.getLocation(), b.getBlockData().getAsString(), "minecraft:air", extra);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void explodeBlock(BlockExplodeEvent e) {
        for (Block b : e.blockList()) {
            logs.block(null, "#" + e.getExplodedBlockState().getType().name().toLowerCase(), "EXPLODE", b.getLocation(),
                    b.getBlockData().getAsString(), "minecraft:air", null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void openInv(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        Inventory top = e.getView().getTopInventory();
        InventoryHolder h = top.getHolder(false);
        if (!(h instanceof Container) && !(h instanceof DoubleChest)) return;
        List<ItemStack> protos = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        count(top, protos, counts);
        org.bukkit.Location loc = top.getLocation();
        open.put(p.getUniqueId(), new Snapshot(top, protos, counts, loc));
        logs.log(p, "container", "open", loc, top.getType().name());
    }

    private static void count(Inventory inv, List<ItemStack> protos, List<Integer> counts) {
        for (ItemStack it : inv.getContents()) {
            if (it == null || it.getType().isAir()) continue;
            boolean found = false;
            for (int i = 0; i < protos.size(); i++) {
                if (protos.get(i).isSimilar(it)) {
                    counts.set(i, counts.get(i) + it.getAmount());
                    found = true;
                    break;
                }
            }
            if (!found) {
                protos.add(it.asOne());
                counts.add(it.getAmount());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void closeInv(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        Snapshot s = open.remove(p.getUniqueId());
        if (s == null || s.inv != e.getInventory()) return;
        List<ItemStack> protos = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        count(s.inv, protos, counts);
        StringBuilder took = new StringBuilder(), put = new StringBuilder();
        // Сравнение «до» и «после».
        for (int i = 0; i < s.protos.size(); i++) {
            int before = s.counts.get(i);
            int after = 0;
            for (int j = 0; j < protos.size(); j++) if (protos.get(j).isSimilar(s.protos.get(i))) after = counts.get(j);
            if (after < before) took.append(before - after).append("x ").append(Items.itemName(s.protos.get(i))).append("; ");
            if (after > before) put.append(after - before).append("x ").append(Items.itemName(s.protos.get(i))).append("; ");
        }
        for (int j = 0; j < protos.size(); j++) {
            boolean existed = false;
            for (ItemStack pr : s.protos) if (pr.isSimilar(protos.get(j))) { existed = true; break; }
            if (!existed) put.append(counts.get(j)).append("x ").append(Items.itemName(protos.get(j))).append("; ");
        }
        if (!took.isEmpty()) logs.log(p, "container", "take", s.loc, took.toString());
        if (!put.isEmpty()) logs.log(p, "container", "put", s.loc, put.toString());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent e) {
        ItemStack it = e.getItemDrop().getItemStack();
        logs.log(e.getPlayer(), "item", "drop", e.getPlayer().getLocation(), it.getAmount() + "x " + Items.itemName(it)
                + uid(it));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        ItemStack it = e.getItem().getItemStack();
        String thrower = e.getItem().getThrower() == null ? "" : " от " + e.getItem().getThrower();
        logs.log(p, "item", "pickup", p.getLocation(), it.getAmount() + "x " + Items.itemName(it) + uid(it) + thrower);
    }

    private String uid(ItemStack it) {
        String u = plugin.items() == null ? null : plugin.items().uidOf(it);
        return u == null ? "" : " [uid " + u + "]";
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void command(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage();
        String cmd = msg.substring(1).split(" ")[0].toLowerCase();
        if (REDACT.contains(cmd)) msg = "/" + cmd + " ***";
        logs.log(e.getPlayer(), "command", cmd, e.getPlayer().getLocation(), msg);
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        open.remove(e.getPlayer().getUniqueId());
    }
}
