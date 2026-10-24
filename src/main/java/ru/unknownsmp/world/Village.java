package ru.unknownsmp.world;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.type.Farmland;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.UnknownSMP;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Компактная обнесённая стеной деревня (радиус 56): круглая площадь с фонтаном, две крестовые улицы, ратуша на севере,
 * ряды разных построек вдоль улиц, торговый ряд, парк развлечений, ферма с мельницей, пруд, тренировочный двор.
 * Строится по шагам (по одному за тик); все места NPC записываются как «якоря».
 */
public final class Village {
    public static final int R = 64;
    public static final int SLOPE = 12;
    private static final int PLAZA = 17;

    private final World w;
    private final int cx, cz;
    private final BiConsumer<String, Location> anchor;
    private final Random rnd;
    private final List<int[]> reserved = new ArrayList<>();
    private final boolean flat;
    private int y;
    private Canvas g;

    private Village(World w, int cx, int cz, BiConsumer<String, Location> anchor, int fixedY) {
        this.w = w;
        this.cx = cx;
        this.cz = cz;
        this.anchor = (name, l) -> {
            for (int dy = 0; dy <= 1; dy++) {
                Block b = l.getBlock().getRelative(0, dy, 0);
                String n = b.getType().name();
                if (org.bukkit.Tag.FLOWERS.isTagged(b.getType()) || org.bukkit.Tag.SAPLINGS.isTagged(b.getType()) || n.contains("GRASS")
                        || n.contains("FERN") || n.contains("POTTED") || n.contains("BUSH") || n.contains("TULIP") || n.contains("MUSHROOM")) {
                    b.setType(Material.AIR, false);
                }
            }
            anchor.accept(name, l);
        };
        this.rnd = new Random(cx * 7919L + cz * 104729L + 11);
        this.flat = fixedY > 0;
        this.y = fixedY;
    }

    /** Асинхронная (по шагам) постройка. fixedY &gt; 0 — тестовый режим в пустом мире. */
    public static void buildAsync(World w, int cx, int cz, int fixedY, BiConsumer<String, Location> anchor, Consumer<Location> done) {
        Village v = new Village(w, cx, cz, anchor, fixedY);
        List<Runnable> steps = v.steps();
        run(v, steps, 0, done);
    }

    private static void run(Village v, List<Runnable> steps, int i, Consumer<Location> done) {
        if (i >= steps.size()) {
            done.accept(new Location(v.w, v.cx + 0.5, v.y + 1, v.cz + PLAZA + 3.5, 180, 0));
            return;
        }
        Tasks.later(2, () -> {
            try {
                steps.get(i).run();
            } catch (Throwable t) {
                UnknownSMP.get().getLogger().warning("Деревня: шаг " + i + " завершился ошибкой: " + t);
                t.printStackTrace();
            }
            run(v, steps, i + 1, done);
        });
    }

    private List<Runnable> steps() {
        List<Runnable> s = new ArrayList<>();
        s.add(() -> {
            if (!flat) y = computeY();
            g = new Canvas(w, 0, y, 0, 1, 0, 0, 1);
        });
        int lim = R + SLOPE;
        for (int a = -lim; a <= lim; a += 12) {
            final int from = a, to = Math.min(lim, a + 11);
            s.add(() -> terraform(from, to));
        }
        if (!flat) s.add(this::clearMargin);
        s.add(this::plaza);
        s.add(this::wall);
        s.add(this::streetHouses);
        s.add(this::market);
        s.add(this::funPark);
        s.add(this::farmland);
        s.add(this::pond);
        s.add(this::trainingGrounds);
        s.add(this::cottages);
        s.add(this::roadDecor);
        s.add(this::trees);
        s.add(this::sky);
        s.add(this::vegetation);
        s.add(() -> {
            anchor.accept("herald", new Location(w, cx + 0.5, y + 1, cz + PLAZA - 4.5, 0, 0));
            anchor.accept("rank_keeper", new Location(w, cx + 12.5, y + 1, cz + 1.5, 90, 0));
            anchor.accept("board_smp", new Location(w, cx - 12.5, y + 3.2, cz + 8.5));
            anchor.accept("tips_smp", new Location(w, cx + 0.5, y + 4.4, cz + PLAZA + 1.5));
            Canvas.flushConnections();
        });
        return s;
    }

    // ------------------------------------------------------------------ Рельеф

    private int computeY() {
        int sum = 0, n = 0;
        for (int dx = -R / 2; dx <= R / 2; dx += 4) for (int dz = -R / 2; dz <= R / 2; dz += 4) {
            if (dx * dx + dz * dz > (R / 2) * (R / 2)) continue;
            sum += SpawnBuilder.groundY(w, cx + dx, cz + dz);
            n++;
        }
        int yy = n == 0 ? SpawnBuilder.groundY(w, cx, cz) : Math.round(sum / (float) n);
        return Math.max(w.getSeaLevel() + 1, Math.min(150, yy));
    }

    private static int hash(int dx, int dz, int mod) {
        return Math.floorMod(dx * 73856093 ^ dz * 19349663, mod);
    }

    private void terraform(int from, int to) {
        int lim = R + SLOPE;
        for (int dx = from; dx <= to; dx++) for (int dz = -lim; dz <= lim; dz++) {
            double d = Math.hypot(dx, dz);
            if (d > lim + 0.5) continue;
            int x = cx + dx, z = cz + dz;
            int gr = flat ? y - 4 : SpawnBuilder.groundY(w, x, z);
            int target = d <= R ? y : (int) Math.round(y + (gr - y) * ((d - R) / SLOPE));
            if (flat) target = d <= R ? y : y - 1;
            int top = flat ? y + 45 : Math.max(w.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE), gr + 3);
            for (int yy = target + 1; yy <= top; yy++) {
                Block b = w.getBlockAt(x, yy, z);
                if (!b.getType().isAir()) b.setType(Material.AIR, false);
            }
            for (int yy = gr + 1; yy < target; yy++) w.getBlockAt(x, yy, z).setType(target - yy > 3 ? Material.STONE : Material.DIRT, false);
            if (d <= R + 0.5) w.getBlockAt(x, target, z).setType(surface(dx, dz), false);
            else {
                boolean path = (Math.abs(dz) <= 2) || (Math.abs(dx) <= 2 && dz > 0);
                w.getBlockAt(x, target, z).setType(path ? Material.DIRT_PATH : Material.GRASS_BLOCK, false);
            }
            if (target > gr) w.getBlockAt(x, target - 1, z).setType(Material.DIRT, false);
        }
    }

    private void clearMargin() {
        int lim = R + SLOPE;
        for (int dx = -lim - 9; dx <= lim + 9; dx++) for (int dz = -lim - 9; dz <= lim + 9; dz++) {
            double d = Math.hypot(dx, dz);
            if (d <= lim - 2 || d > lim + 9) continue;
            int x = cx + dx, z = cz + dz;
            int top = w.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
            for (int yy = top; yy > top - 40 && yy > w.getMinHeight(); yy--) {
                Block b = w.getBlockAt(x, yy, z);
                String n = b.getType().name();
                if (n.endsWith("_LEAVES") || n.endsWith("_LOG") || n.endsWith("_WOOD") || b.getType() == Material.VINE || n.contains("AZALEA")
                        || n.equals("MANGROVE_ROOTS") || n.equals("BEE_NEST") || n.contains("MUSHROOM_BLOCK") || n.equals("MUSHROOM_STEM")) {
                    b.setType(Material.AIR, false);
                } else if (!b.getType().isAir() && !n.contains("GRASS") && !n.contains("FERN") && !n.endsWith("_FLOWER") && b.getType() != Material.SNOW) {
                    break;
                }
            }
        }
    }

    /** Покрытие улиц, площади и газонов. */
    private Material surface(int dx, int dz) {
        double d = Math.hypot(dx, dz);
        int ax = Math.abs(dx), az = Math.abs(dz);
        if (d <= PLAZA + 0.5) return plazaFloor(dx, dz, d);
        boolean avNS = ax <= 5 && dz >= -PLAZA - 8 && dz <= R;
        boolean avEW = az <= 5 && ax <= R;
        if (avNS || avEW) {
            int o = avNS && avEW ? Math.min(ax, az) : avNS ? ax : az;
            if (o == 3) return Material.POLISHED_ANDESITE;
            if (o >= 4) return hash(dx, dz, 3) == 0 ? Material.ANDESITE : Material.SMOOTH_STONE;
            int h = hash(dx, dz, 100);
            return h < 55 ? Material.STONE_BRICKS : h < 78 ? Material.COBBLESTONE : h < 90 ? Material.CRACKED_STONE_BRICKS : Material.MOSSY_STONE_BRICKS;
        }
        int h = hash(dx, dz, 100);
        return h < 3 ? Material.PODZOL : h < 5 ? Material.COARSE_DIRT : h < 6 ? Material.MOSS_BLOCK : Material.GRASS_BLOCK;
    }

    private Material plazaFloor(int dx, int dz, double d) {
        if (d <= 8) return Material.STONE_BRICKS;
        if (d < 9) return Material.CHISELED_STONE_BRICKS;
        if (d >= 16) return Material.DEEPSLATE_TILES;
        if (d >= 14 && d < 15) return Material.POLISHED_ANDESITE;
        double ang = Math.atan2(dz, dx);
        int sector = (int) Math.floor((ang + Math.PI) / (Math.PI / 8));
        if (d >= 10 && d < 14) return sector % 2 == 0 ? Material.POLISHED_ANDESITE : Material.STONE_BRICKS;
        return hash(dx, dz, 100) < 60 ? Material.STONE_BRICKS : Material.ANDESITE;
    }

    // ------------------------------------------------------------------ Площадь и стена

    private void plaza() {
        // Двухъярусный фонтан.
        for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++) {
            double d = Math.hypot(dx, dz);
            int x = cx + dx, z = cz + dz;
            if (d <= 6.3) { g.set(x, 0, z, Material.WATER); g.set(x, -1, z, Material.STONE_BRICKS); }
            else if (d <= 7.5) { g.set(x, 0, z, Material.CHISELED_STONE_BRICKS); g.set(x, 1, z, Material.STONE_BRICK_WALL); }
            if (d > 2.3 && d <= 3.3) { g.set(x, 0, z, Material.STONE_BRICKS); g.set(x, 1, z, Material.STONE_BRICK_WALL); }
            else if (d <= 2.3) { g.set(x, 0, z, Material.STONE_BRICKS); g.set(x, 1, z, Material.WATER); }
        }
        for (int dy = 1; dy <= 7; dy++) g.set(cx, dy, cz, dy == 7 ? Material.CHISELED_QUARTZ_BLOCK : Material.QUARTZ_PILLAR);
        g.set(cx, 8, cz, Material.SEA_LANTERN);
        g.set(cx, 9, cz, Material.AMETHYST_CLUSTER);
        for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) g.set(cx + o[0], 7, cz + o[1], Material.LANTERN);
        for (int s = -1; s <= 1; s++) for (int r = 7; r <= 8; r++) {
            g.set(cx + s, 1, cz + r, Material.AIR);
            g.set(cx + s, 1, cz - r, Material.AIR);
            g.set(cx + r, 1, cz + s, Material.AIR);
            g.set(cx - r, 1, cz + s, Material.AIR);
        }
        // Фонари и клумбы по кругу, скамьи.
        for (int k = 0; k < 8; k++) {
            double rad = Math.toRadians(22.5 + 45 * k);
            lamp(cx + (int) Math.round(Math.cos(rad) * 15.5), cz + (int) Math.round(Math.sin(rad) * 15.5));
        }
        for (int k = 0; k < 4; k++) {
            double rad = Math.toRadians(45 + 90 * k);
            flowerBed(cx + (int) Math.round(Math.cos(rad) * 11.5), cz + (int) Math.round(Math.sin(rad) * 11.5));
        }
        for (int s : new int[]{-6, 6}) { bench(cx + s, cz + 12, 0, 1); bench(cx + s, cz - 12, 0, -1); }
        reserved.add(new int[]{cx - PLAZA - 1, cz - PLAZA - 1, cx + PLAZA + 1, cz + PLAZA + 1});
    }

    private void lamp(int x, int z) {
        g.set(x, 1, z, Material.DEEPSLATE_BRICKS);
        for (int dy = 2; dy <= 4; dy++) g.set(x, dy, z, Material.DEEPSLATE_BRICK_WALL);
        g.lantern(x, 5, z, false);
    }

    private void bench(int x, int z, int fx, int fz) {
        int px = fz != 0 ? 1 : 0, pz = fx != 0 ? 1 : 0;
        for (int s = -1; s <= 1; s++) g.stair(x + px * s, 1, z + pz * s, Material.SPRUCE_STAIRS, fx, fz, false);
    }

    private void flowerBed(int x, int z) {
        Material[] fl = {Material.POPPY, Material.DANDELION, Material.CORNFLOWER, Material.ALLIUM, Material.PINK_TULIP, Material.AZURE_BLUET, Material.RED_TULIP, Material.OXEYE_DAISY};
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
            if (edge) { g.set(x + dx, 0, z + dz, Material.SPRUCE_PLANKS); g.set(x + dx, 1, z + dz, Material.AIR); }
            else { g.set(x + dx, 0, z + dz, Material.MOSS_BLOCK); g.set(x + dx, 1, z + dz, fl[rnd.nextInt(fl.length)]); }
        }
        g.set(x, 1, z, Material.FLOWERING_AZALEA);
    }

    private void wall() {
        java.util.Set<Long> done = new java.util.HashSet<>();
        for (double a = 0; a < 360; a += 0.5) {
            double rad = Math.toRadians(a);
            for (double rr : new double[]{R - 1, R}) {
                int dx = (int) Math.round(Math.cos(rad) * rr), dz = (int) Math.round(Math.sin(rad) * rr);
                if (!done.add(((long) dx << 32) ^ (dz & 0xffffffffL))) continue;
                if (dz > 0 && Math.abs(dx) <= 6) continue;
                if (Math.abs(dz) <= 6 && dx != 0) continue;
                int x = cx + dx, z = cz + dz;
                for (int dy = 1; dy <= 6; dy++) g.set(x, dy, z, hash(dx, dz + dy, 7) == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
                g.set(x, 7, z, (dx + dz) % 2 == 0 ? Material.STONE_BRICK_WALL : Material.AIR);
                g.set(x, 0, z, Material.COBBLESTONE);
            }
        }
        gatehouse(cx, cz + R - 1, 0, 1, "&6Южные ворота");
        gatehouse(cx + R - 1, cz, 1, 0, "&6Восточные ворота");
        gatehouse(cx - R + 1, cz, -1, 0, "&6Западные ворота");
        for (int k = 0; k < 4; k++) {
            double rad = Math.toRadians(45 + 90 * k);
            roundTower(cx + (int) Math.round(Math.cos(rad) * (R - 1)), cz + (int) Math.round(Math.sin(rad) * (R - 1)));
        }
        roundTower(cx, cz - R + 1);
        // Часовые у ворот.
        anchor.accept("guard_s1", new Location(w, cx - 5.5, y + 1, cz + R - 7.5, 180, 0));
        anchor.accept("guard_s2", new Location(w, cx + 6.5, y + 1, cz + R - 7.5, 180, 0));
        anchor.accept("guard_e", new Location(w, cx + R - 7.5, y + 1, cz + 5.5, 90, 0));
    }

    private void gatehouse(int gx, int gz, int fx, int fz, String title) {
        Canvas c = Canvas.facing(w, gx, y, gz, fx, fz);
        for (int s : new int[]{-1, 1}) {
            int a = s > 0 ? 3 : -7, b = s > 0 ? 7 : -3;
            c.fill(a, 0, -1, b, 12, 3, Material.STONE_BRICKS);
            c.fill(a + 1, 1, 0, b - 1, 11, 2, Material.AIR);
            for (int x = a; x <= b; x++) for (int z = -1; z <= 3; z++) {
                boolean edge = x == a || x == b || z == -1 || z == 3;
                if (edge && (x + z) % 2 == 0) c.set(x, 13, z, Material.STONE_BRICK_WALL);
            }
            c.set(s > 0 ? 3 : -3, 6, 1, Material.IRON_BARS);
            c.set(s > 0 ? 7 : -7, 6, 1, Material.IRON_BARS);
            c.fill(a + 1, 12, 0, b - 1, 12, 2, Material.SPRUCE_PLANKS);
            c.lantern((a + b) / 2, 11, 1, true);
        }
        c.fill(-2, 7, -1, 2, 9, 3, Material.STONE_BRICKS);
        for (int z = -1; z <= 3; z++) {
            c.stair(-2, 6, z, Material.STONE_BRICK_STAIRS, 1, 0, true);
            c.stair(2, 6, z, Material.STONE_BRICK_STAIRS, -1, 0, true);
        }
        for (int x = -2; x <= 2; x++) { c.set(x, 6, 1, Material.IRON_BARS); c.set(x, 5, 1, x % 2 == 0 ? Material.IRON_BARS : Material.AIR); }
        c.lantern(0, 6, 0, true);
        c.sign(0, 8, -2, 0, -1, "", title, "&7ALEX SMP", "");
        for (int dy = 7; dy <= 9; dy++) c.dir(0, dy, -2, Material.RED_WALL_BANNER, 0, -1);
        reserved.add(new int[]{Math.min(c.wx(-8, -1), c.wx(8, 4)) - 1, Math.min(c.wz(-8, -1), c.wz(8, 4)) - 1,
                Math.max(c.wx(-8, -1), c.wx(8, 4)) + 1, Math.max(c.wz(-8, -1), c.wz(8, 4)) + 1});
    }

    private void roundTower(int x, int z) {
        int r = 4, h = 13;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            double d = Math.hypot(dx, dz);
            if (d > r + 0.4) continue;
            boolean shell = d > r - 1.2;
            for (int dy = 0; dy <= h; dy++) g.set(x + dx, dy, z + dz, shell ? Material.STONE_BRICKS : (dy == 0 || dy == 7 || dy == h ? Material.SPRUCE_PLANKS : Material.AIR));
        }
        for (int dy = 4; dy <= 10; dy += 3) for (int[] o : new int[][]{{r - 1, 0}, {-(r - 1), 0}, {0, r - 1}, {0, -(r - 1)}}) g.set(x + o[0], dy, z + o[1], Material.IRON_BARS);
        int top = Kit.cone(g, x, z, r, h + 1, Material.DEEPSLATE_TILE_STAIRS, Material.DEEPSLATE_TILES);
        g.set(x, top, z, Material.LIGHTNING_ROD);
        reserved.add(new int[]{x - 6, z - 6, x + 6, z + 6});
    }

    // ------------------------------------------------------------------ Дома вдоль улиц

    private void place(String name, int fx, int fz, int dx, int dz, String npc, int lx, int lz) {
        Houses.Spec s = Houses.ALL.get(name);
        Canvas c = Canvas.front(w, cx + fx, y, cz + fz, dx, dz, s.w());
        s.build().accept(c, rnd);
        Interior.furnish(c, name, s.w(), s.d(), rnd);
        for (int[] q : new int[][]{{0, 0}, {s.w() - 1, 0}, {0, s.d() - 1}, {s.w() - 1, s.d() - 1}}) {
            double dd = Math.hypot(c.wx(q[0], q[1]) - cx, c.wz(q[0], q[1]) - cz);
            if (dd > R - 3) UnknownSMP.get().getLogger().warning("Деревня: постройка " + name + " слишком близко к стене/за стеной (" + (int) dd + ")");
        }
        int x1 = c.wx(-4, -8), z1 = c.wz(-4, -8), x2 = c.wx(s.w() + 4, s.d() + 4), z2 = c.wz(s.w() + 4, s.d() + 4);
        reserved.add(new int[]{Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2)});
        if (npc != null) anchor.accept(npc, c.spot(lx, 1, lz));
    }

    private void streetHouses() {
        // Восточная улица, северный ряд (смотрят на юг), южный ряд (смотрят на север).
        place("smithy", 28, -6, 0, -1, "smith", 3, 4);
        place("cottage_tower", 45, -6, 0, -1, null, 0, 0);
        place("bank", 29, 12, 0, 1, "banker", 7, 5);
        place("alchemist", 42, 7, 0, 1, "alchemist", 9, 4);
        // Западная улица.
        place("library", -27, -6, 0, -1, "chronicler", 6, 4);
        place("chalet", -43, -6, 0, -1, null, 0, 0);
        place("tavern", -30, 6, 0, 1, "bartender", 6, 10);
        place("lodge", -44, 6, 0, 1, "hunter", 6, 4);
        // Северная ось: ратуша и крылья-соседи.
        place("townhall", 0, -22, 0, -1, "elder", 11, 8);
        place("guardhouse", 25, -23, 0, -1, "judge", 4, 4);
        place("bakery", -25, -23, 0, -1, null, 0, 0);
    }

    // ------------------------------------------------------------------ Торговый ряд на южной улице

    private void market() {
        place("markethall", -6, 32, -1, 0, "trader_market", 8, 3);
        int[] zs = {25, 33, 41, 49};
        for (int i = 0; i < zs.length; i++) place("stall" + i, 10, zs[i], 1, 0, null, 0, 0);
    }

    // ------------------------------------------------------------------ Парк развлечений на северо-востоке

    private void funPark() {
        Fun f = new Fun(w, g, anchor, rnd, "_c");
        f.disco(cx + 42, cz - 30);
        f.clicker(cx + 36, cz - 46);
        f.wheel(cx + 23, cz - 48, true);
        f.launchPad(cx + 31, cz - 38);
        f.launchPad(cx + 31, cz - 34);
        f.fireworkPedestal(cx - 12, cz + 22);
        reserved.add(new int[]{cx + 14, cz - 56, cx + 54, cz - 18});
    }

    // ------------------------------------------------------------------ Ферма и мельница на юго-востоке

    private void farmland() {
        String[] crops = {"WHEAT", "CARROTS", "POTATOES", "BEETROOTS"};
        int idx = 0;
        for (int j = 0; j < 2; j++) for (int i = 0; i < 2 && j * 2 + i < 3; i++) {
            int ox = cx + 17, oz = cz + 26 + (j * 2 + i) * 10;
            Material crop = Material.valueOf(crops[idx++ % crops.length]);
            for (int dx = 0; dx < 10; dx++) for (int dz = 0; dz < 8; dz++) {
                if (dz == 3) { g.set(ox + dx, 0, oz + dz, Material.WATER); continue; }
                Farmland fl = (Farmland) Material.FARMLAND.createBlockData();
                fl.setMoisture(7);
                g.at(ox + dx, 0, oz + dz).setBlockData(fl, false);
                Ageable a = (Ageable) crop.createBlockData();
                a.setAge(a.getMaximumAge());
                g.at(ox + dx, 1, oz + dz).setBlockData(a, false);
            }
            for (int dx = -1; dx <= 10; dx++) { g.set(ox + dx, 1, oz - 1, Material.OAK_FENCE); g.set(ox + dx, 1, oz + 8, Material.OAK_FENCE); }
            for (int dz = -1; dz <= 8; dz++) { g.set(ox - 1, 1, oz + dz, Material.OAK_FENCE); g.set(ox + 10, 1, oz + dz, Material.OAK_FENCE); }
            g.set(ox + 4, 1, oz - 1, Material.OAK_FENCE_GATE);
            g.set(ox + 4, 0, oz + 1, Material.COARSE_DIRT);
            g.fill(ox + 4, 1, oz + 1, ox + 4, 2, oz + 1, Material.OAK_FENCE);
            g.set(ox + 4, 3, oz + 1, Material.HAY_BLOCK);
            g.set(ox + 4, 4, oz + 1, Material.CARVED_PUMPKIN);
        }
        place("windmill", 31, 32, 1, 0, "miller", 6, -3);
        anchor.accept("farmer", new Location(w, cx + 29.5, y + 1, cz + 41.5, 90, 0));
        reserved.add(new int[]{cx + 14, cz + 24, cx + 50, cz + 58});
    }

    // ------------------------------------------------------------------ Пруд с причалом на северо-западе

    private void pond() {
        int px = cx - 38, pz = cz - 34, r = 8;
        for (int dx = -r - 2; dx <= r + 2; dx++) for (int dz = -r - 2; dz <= r + 2; dz++) {
            double d = Math.hypot(dx, dz * 1.2) + Math.sin(dx * 0.6) * 0.8;
            int x = px + dx, z = pz + dz;
            if (d <= r) {
                g.set(x, 0, z, Material.WATER);
                g.set(x, -1, z, Material.WATER);
                g.set(x, -2, z, d > r - 2.5 ? Material.SAND : Material.CLAY);
                if (rnd.nextInt(10) == 0) g.set(x, 1, z, Material.LILY_PAD);
            } else if (d <= r + 1.6) {
                g.set(x, 0, z, rnd.nextInt(3) == 0 ? Material.MOSS_BLOCK : Material.GRASS_BLOCK);
                if (rnd.nextInt(5) == 0) g.set(x, 1, z, Material.SUGAR_CANE);
            }
        }
        for (int dz = 0; dz <= 9; dz++) for (int dx = -1; dx <= 1; dx++) {
            g.set(px + dx, 1, pz + 6 - dz, Material.SPRUCE_PLANKS);
            if (Math.abs(dx) == 1 && dz % 3 == 0) { g.fill(px + dx, 0, pz + 6 - dz, px + dx, 0, pz + 6 - dz, Material.SPRUCE_LOG); g.set(px + dx, 2, pz + 6 - dz, Material.SPRUCE_FENCE); g.lantern(px + dx, 3, pz + 6 - dz, false); }
        }
        anchor.accept("fisher", new Location(w, px + 0.5, y + 2, pz - 3 + 0.5, 0, 0));
        Structures.tree(g, px - 11, pz - 3, "cherry", rnd);
        Structures.tree(g, px + 11, pz + 2, "cherry", rnd);
        bench(px + 4, pz + 12, 0, -1);
        // Колодец желаний рядом.
        new Fun(w, g, anchor, rnd, "_c").wishingWell(cx - 22, cz - 48);
        reserved.add(new int[]{px - 16, pz - 16, px + 16, pz + 16});
        reserved.add(new int[]{cx - 26, cz - 52, cx - 18, cz - 44});
    }

    // ------------------------------------------------------------------ Тренировочный двор, снежки, дозор на юго-западе

    private void trainingGrounds() {
        int x = cx - 34, z = cz + 34;
        int hx = 8, hz = 7;
        for (int dx = -hx; dx <= hx; dx++) for (int dz = -hz; dz <= hz; dz++) {
            g.set(x + dx, 0, z + dz, hash(dx, dz, 5) == 0 ? Material.PACKED_MUD : Material.COARSE_DIRT);
            for (int dy = 1; dy <= 4; dy++) g.set(x + dx, dy, z + dz, Material.AIR);
            if (Math.abs(dx) == hx || Math.abs(dz) == hz) g.set(x + dx, 1, z + dz, Material.OAK_FENCE);
        }
        for (int dx = -1; dx <= 1; dx++) { g.set(x + dx, 1, z - hz, Material.AIR); g.set(x + dx, 1, z + hz, Material.AIR); }
        for (int[] o : new int[][]{{-hx, -hz}, {hx, -hz}, {-hx, hz}, {hx, hz}}) { g.fill(x + o[0], 1, z + o[1], x + o[0], 3, z + o[1], Material.DARK_OAK_LOG); g.lantern(x + o[0], 4, z + o[1], false); }
        for (int dx = -6; dx <= 6; dx += 3) { g.set(x + dx, 1, z - 5, Material.HAY_BLOCK); g.set(x + dx, 2, z - 5, Material.TARGET); g.set(x + dx, 3, z - 5, Material.CARVED_PUMPKIN); }
        g.dir(x + hx - 1, 1, z, Material.BARREL, -1, 0);
        g.dir(x + hx - 1, 1, z + 1, Material.CHEST, -1, 0);
        g.set(x - hx + 1, 1, z, Material.ANVIL);
        Fun f = new Fun(w, g, anchor, rnd, "_c");
        f.tirLectern(x + 6, z + 4);
        anchor.accept("archer", new Location(w, x + 0.5, y + 1, z + 3.5, 0, 0));
        f.snowPile(cx - 21, cz + 30, true);
        f.snowPile(cx - 21, cz + 36, false);
        place("watchtower", -20, 52, 0, -1, "scout", 4, 11);
        reserved.add(new int[]{x - 11, z - 10, x + 11, z + 10});
        reserved.add(new int[]{cx - 24, cz + 36, cx - 16, cz + 50});
    }

    // ------------------------------------------------------------------ Жилые дома во втором ряду

    private void cottages() {
        place("cottage_round", -48, -18, -1, 0, null, 0, 0);
        place("cottage_cherry", -46, 22, -1, 0, null, 0, 0);
        place("cottage_thatch", 43, -24, 1, 0, null, 0, 0);
    }

    // ------------------------------------------------------------------ Фонари, гирлянды

    private void roadDecor() {
        for (int t = 22; t <= R - 8; t += 9) {
            for (int s : new int[]{-6, 6}) {
                lamp(cx + t, cz + s);
                lamp(cx - t, cz + s);
                lamp(cx + s, cz + t);
            }
        }
        for (int s : new int[]{-6, 6}) for (int t = -PLAZA - 6; t >= -PLAZA - 6; t--) lamp(cx + s, cz + t);
        // Тории над дорогами.
        Fun f = new Fun(w, g, anchor, rnd, "_c");
        f.torii(cx + 38, cz, true);
        f.torii(cx - 38, cz, true);
        f.torii(cx, cz + 36, false);
    }

    // ------------------------------------------------------------------ Деревья и растительность

    private boolean free(int x, int z, int margin) {
        for (int[] r : reserved) if (x >= r[0] - margin && x <= r[2] + margin && z >= r[1] - margin && z <= r[3] + margin) return false;
        return true;
    }

    private void trees() {
        String[] kinds = {"oak", "oak", "birch", "spruce", "cherry", "cherry"};
        int placed = 0;
        for (int tries = 0; tries < 3000 && placed < 130; tries++) {
            int dx = rnd.nextInt(2 * (R - 5) + 1) - (R - 5), dz = rnd.nextInt(2 * (R - 5) + 1) - (R - 5);
            double d = Math.hypot(dx, dz);
            if (d < 20 || d > R - 5 || Math.abs(dx) < 7 || Math.abs(dz) < 7) continue;
            int x = cx + dx, z = cz + dz;
            if (!free(x, z, 2)) continue;
            if (g.at(x, 0, z).getType() != Material.GRASS_BLOCK) continue;
            Structures.tree(g, x, z, kinds[rnd.nextInt(kinds.length)], rnd);
            reserved.add(new int[]{x - 2, z - 2, x + 2, z + 2});
            placed++;
        }
    }

    private void vegetation() {
        Material[] fl = {Material.DANDELION, Material.POPPY, Material.AZURE_BLUET, Material.CORNFLOWER, Material.ALLIUM, Material.OXEYE_DAISY, Material.RED_TULIP, Material.PINK_TULIP, Material.LILY_OF_THE_VALLEY};
        for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
            if (Math.hypot(dx, dz) > R - 2) continue;
            int x = cx + dx, z = cz + dz;
            if (g.at(x, 0, z).getType() != Material.GRASS_BLOCK || !g.at(x, 1, z).getType().isAir()) continue;
            int h = rnd.nextInt(100);
            if (h < 9) g.set(x, 1, z, Material.SHORT_GRASS);
            else if (h < 11) g.set(x, 1, z, fl[rnd.nextInt(fl.length)]);
            else if (h < 12 && free(x, z, 0)) g.set(x, 1, z, Material.FERN);
        }
    }

    // ------------------------------------------------------------------ Небо: вывеска и острова

    private void sky() {
        var cfg = UnknownSMP.get().configs().main();
        String title = cfg.getString("branding.sign", "ALEX").toUpperCase();
        String sub = cfg.getString("branding.sign-sub", "SMP").toUpperCase();
        if (title.length() > 9) title = title.substring(0, 9);
        if (sub.length() > 6) sub = sub.substring(0, 6);
        Canvas c = Canvas.facing(w, cx, y + 58, cz - 76, 0, -1);
        BigSign.build(c, title, sub);
        Fun f = new Fun(w, g, anchor, rnd, "_c");
        int[][] isl = {{-52, 36, -20, 7}, {50, 40, 12, 7}, {-30, 34, 55, 6}, {30, 44, -64, 6}};
        for (int i = 0; i < isl.length; i++) f.floatingIsland(cx + isl[i][0], y + isl[i][1], cz + isl[i][2], isl[i][3], i % 2 == 0);
        anchor.accept("monument", new Location(w, cx + 0.5, y + 1, cz - 40.5, 0, 0));
    }
}
