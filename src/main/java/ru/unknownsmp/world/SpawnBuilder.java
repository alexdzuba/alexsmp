package ru.unknownsmp.world;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Stairs;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.world.Structures.Pal;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BiConsumer;

/**
 * Город спавна SMP: площадь с фонтаном, дороги, стена с воротами и башнями, кузница, алхимик, банк, библиотека,
 * таверна, охотничий домик, рынок, ферма, мельница, пруд с мостом, лабиринт и огромный монумент с логотипом.
 * Радиус стены — {@link #R}. Строится один раз; места NPC сохраняются как «якоря» (Anchors).
 */
public final class SpawnBuilder {
    public static final int R = 116;
    public static final int SLOPE = 14;

    private static final Material[] FLOWERS = {Material.DANDELION, Material.POPPY, Material.AZURE_BLUET, Material.CORNFLOWER,
            Material.ALLIUM, Material.OXEYE_DAISY, Material.RED_TULIP, Material.PINK_TULIP, Material.LILY_OF_THE_VALLEY};

    private final World w;
    private final int cx, cz;
    private final BiConsumer<String, Location> anchor;
    private final Random rnd;
    private final List<int[]> reserved = new ArrayList<>();
    private int y;
    private Canvas g; // абсолютные x/z, ly = высота над уровнем города

    private SpawnBuilder(World w, int cx, int cz, BiConsumer<String, Location> anchor) {
        this.w = w;
        this.cx = cx;
        this.cz = cz;
        // Под точками NPC убираем растения, чтобы жители не стояли «в цветах».
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
        this.rnd = new Random(cx * 341L + cz * 17L + 7);
    }

    /**
     * Строит город по шагам (по одному шагу за тик), чтобы не вешать сервер на слабом хостинге.
     * Нужные чанки должны быть заранее загружены. По окончании вызывает done с точкой появления игроков.
     */
    public static void buildAsync(World w, int cx, int cz, BiConsumer<String, Location> anchor, java.util.function.Consumer<Location> done) {
        SpawnBuilder b = new SpawnBuilder(w, cx, cz, anchor);
        java.util.List<Runnable> steps = b.steps();
        runStep(b, steps, 0, done);
    }

    private static void runStep(SpawnBuilder b, java.util.List<Runnable> steps, int i, java.util.function.Consumer<Location> done) {
        if (i >= steps.size()) {
            done.accept(new Location(b.w, b.cx + 0.5, b.y + 1, b.cz + 0.5 + 16, 180, 0));
            return;
        }
        ru.unknownsmp.core.Tasks.later(2, () -> {
            try {
                steps.get(i).run();
            } catch (Throwable t) {
                UnknownSMP.get().getLogger().warning("Город: шаг " + i + " завершился ошибкой: " + t);
            }
            runStep(b, steps, i + 1, done);
        });
    }

    private java.util.List<Runnable> steps() {
        java.util.List<Runnable> s = new ArrayList<>();
        s.add(() -> {
            y = computeY();
            g = new Canvas(w, 0, y, 0, 1, 0, 0, 1);
        });
        int lim = R + SLOPE;
        for (int a = -lim; a <= lim; a += 12) {
            final int from = a, to = Math.min(lim, a + 11);
            s.add(() -> terraform(from, to));
        }
        s.add(this::clearMargin);
        s.add(this::fountain);
        s.add(this::plazaDecor);
        s.add(this::wall);
        s.add(this::buildings);
        s.add(this::stalls);
        s.add(this::monument);
        s.add(this::windmill);
        s.add(this::pond);
        s.add(this::farm);
        s.add(this::maze);
        s.add(this::extras);
        s.add(this::funPark);
        s.add(this::roadLamps);
        s.add(this::trees);
        s.add(this::vegetation);
        s.add(() -> {
            anchor.accept("herald", new Location(w, cx + 0.5, y + 1, cz + 9.5, 0, 0));
            anchor.accept("rank_keeper", new Location(w, cx + 14.5, y + 1, cz - 3.5, 90, 0));
            anchor.accept("judge", new Location(w, cx - 14.5, y + 1, cz + 5.5, -90, 0));
            anchor.accept("guard_s1", new Location(w, cx - 6.5, y + 1, cz + R - 8.5, 180, 0));
            anchor.accept("guard_s2", new Location(w, cx + 7.5, y + 1, cz + R - 8.5, 180, 0));
            anchor.accept("guard_e", new Location(w, cx + R - 8.5, y + 1, cz + 6.5, 90, 0));
            anchor.accept("board_smp", new Location(w, cx + 0.5 + 13, y + 3.2, cz + 0.5 + 9));
            anchor.accept("tips_smp", new Location(w, cx + 0.5, y + 4.4, cz + 0.5 + 11));
            Canvas.flushConnections();
        });
        return s;
    }

    // ------------------------------------------------------------------ Рельеф

    private int computeY() {
        int sum = 0, n = 0;
        for (int dx = -R / 2; dx <= R / 2; dx += 4) for (int dz = -R / 2; dz <= R / 2; dz += 4) {
            if (dx * dx + dz * dz > (R / 2) * (R / 2)) continue;
            sum += groundY(w, cx + dx, cz + dz);
            n++;
        }
        int yy = n == 0 ? groundY(w, cx, cz) : Math.round(sum / (float) n);
        return Math.max(w.getSeaLevel() + 1, Math.min(150, yy));
    }

    /** Верхний блок земли: пропускаем воздух, листву, стволы и растения. */
    static int groundY(World w, int x, int z) {
        int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        while (y > w.getMinHeight() + 1) {
            Material m = w.getBlockAt(x, y, z).getType();
            if (m.isAir() || replaceable(m) || m.name().endsWith("_LOG") || m.name().endsWith("_WOOD") || m == Material.MUSHROOM_STEM
                    || m == Material.BROWN_MUSHROOM_BLOCK || m == Material.RED_MUSHROOM_BLOCK || m == Material.BAMBOO) {
                y--;
                continue;
            }
            break;
        }
        return y;
    }

    private static boolean replaceable(Material m) {
        String n = m.name();
        return m.isAir() || m == Material.WATER || m == Material.LAVA || n.endsWith("_LEAVES") || n.endsWith("GRASS") || n.contains("FERN")
                || n.endsWith("_FLOWER") || m == Material.SNOW || m == Material.DEAD_BUSH || m == Material.VINE || m == Material.KELP
                || m == Material.KELP_PLANT || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS || m == Material.SWEET_BERRY_BUSH
                || m == Material.DANDELION || m == Material.POPPY || n.endsWith("_TULIP") || m == Material.SUGAR_CANE || m == Material.BAMBOO_SAPLING;
    }

    private static int hash(int dx, int dz, int mod) {
        return Math.floorMod(dx * 73856093 ^ dz * 19349663, mod);
    }

    /** Вырубает деревья в кольце вокруг площадки, чтобы от срезанных деревьев не оставалась висящая в воздухе листва. */
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
                } else if (!b.getType().isAir() && !replaceable(b.getType())) {
                    break;
                }
            }
        }
    }

    private void terraform(int from, int to) {
        int lim = R + SLOPE;
        for (int dx = from; dx <= to; dx++) for (int dz = -lim; dz <= lim; dz++) {
            double d = Math.hypot(dx, dz);
            if (d > lim + 0.5) continue;
            int x = cx + dx, z = cz + dz;
            int gr = groundY(w, x, z);
            int target = d <= R ? y : (int) Math.round(y + (gr - y) * ((d - R) / SLOPE));
            int top = Math.max(w.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE), gr + 3);
            for (int yy = target + 1; yy <= top; yy++) {
                Block b = w.getBlockAt(x, yy, z);
                if (!b.getType().isAir()) b.setType(Material.AIR, false);
            }
            for (int yy = gr + 1; yy < target; yy++) w.getBlockAt(x, yy, z).setType(target - yy > 3 ? Material.STONE : Material.DIRT, false);
            if (d <= R + 0.5) {
                w.getBlockAt(x, target, z).setType(surface(dx, dz), false);
            } else {
                boolean path = (Math.abs(dz) <= 1 && dx != 0) || (Math.abs(dx) <= 1 && dz > 0);
                w.getBlockAt(x, target, z).setType(path ? Material.DIRT_PATH : Material.GRASS_BLOCK, false);
            }
            if (target > gr) w.getBlockAt(x, target - 1, z).setType(Material.DIRT, false);
        }
    }

    private Material surface(int dx, int dz) {
        double d = Math.hypot(dx, dz);
        int ax = Math.abs(dx), az = Math.abs(dz);
        if (d <= 20.5) return plazaFloor(dx, dz, d);
        boolean roadX = az <= 2;
        boolean roadZ = ax <= 2 && dz >= -66;
        if (roadX || roadZ) {
            int o = roadX && roadZ ? Math.min(az, ax) : roadX ? az : ax;
            if (o == 2) return Material.POLISHED_ANDESITE;
            int h = hash(dx, dz, 100);
            return h < 55 ? Material.STONE_BRICKS : h < 75 ? Material.COBBLESTONE : h < 88 ? Material.CRACKED_STONE_BRICKS : Material.MOSSY_STONE_BRICKS;
        }
        int h = hash(dx, dz, 100);
        return h < 4 ? Material.PODZOL : h < 7 ? Material.COARSE_DIRT : h < 9 ? Material.MOSS_BLOCK : Material.GRASS_BLOCK;
    }

    private Material plazaFloor(int dx, int dz, double d) {
        if (d <= 7.5) return Material.STONE_BRICKS;
        if (d < 8.8) return Material.CHISELED_STONE_BRICKS;
        if (d >= 19 && d < 20.5) return Material.DEEPSLATE_TILES;
        if (d >= 14 && d < 15) return Material.POLISHED_ANDESITE;
        if (d >= 10 && d < 11) return Material.DEEPSLATE_BRICKS;
        if (Math.abs(dx) <= 2 || Math.abs(dz) <= 2) return hash(dx, dz, 3) == 0 ? Material.SMOOTH_STONE : Material.STONE_BRICKS;
        int h = hash(dx, dz, 100);
        return h < 45 ? Material.STONE_BRICKS : h < 70 ? Material.ANDESITE : h < 85 ? Material.POLISHED_ANDESITE : h < 94 ? Material.MOSSY_STONE_BRICKS : Material.CRACKED_STONE_BRICKS;
    }

    // ------------------------------------------------------------------ Площадь

    private void fountain() {
        for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++) {
            double d = Math.hypot(dx, dz);
            int x = cx + dx, z = cz + dz;
            if (d <= 6.3) {
                g.set(x, 0, z, Material.WATER);
                g.set(x, -1, z, Material.STONE_BRICKS);
            } else if (d <= 7.5) {
                g.set(x, 0, z, Material.CHISELED_STONE_BRICKS);
                g.set(x, 1, z, Material.STONE_BRICK_WALL);
            }
            if (d > 2.3 && d <= 3.3) {
                g.set(x, 0, z, Material.STONE_BRICKS);
                g.set(x, 1, z, Material.STONE_BRICK_WALL);
            } else if (d <= 2.3) {
                g.set(x, 0, z, Material.STONE_BRICKS);
                g.set(x, 1, z, Material.WATER);
            }
        }
        for (int dy = 1; dy <= 7; dy++) g.set(cx, dy, cz, dy == 7 ? Material.CHISELED_QUARTZ_BLOCK : Material.QUARTZ_PILLAR);
        g.set(cx, 8, cz, Material.SEA_LANTERN);
        g.set(cx, 9, cz, Material.AMETHYST_CLUSTER);
        for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) g.set(cx + o[0], 7, cz + o[1], Material.LANTERN);
        // Ступени у выходов к дорогам: проходы в бортике на осях.
        for (int s = -1; s <= 1; s++) {
            for (int r = 7; r <= 8; r++) {
                g.set(cx + s, 1, cz + r, Material.AIR);
                g.set(cx + s, 1, cz - r, Material.AIR);
                g.set(cx + r, 1, cz + s, Material.AIR);
                g.set(cx - r, 1, cz + s, Material.AIR);
            }
        }
    }

    private void plazaDecor() {
        for (int k = 0; k < 8; k++) {
            double rad = Math.toRadians(22.5 + 45 * k);
            lamp(cx + (int) Math.round(Math.cos(rad) * 17), cz + (int) Math.round(Math.sin(rad) * 17));
            lamp(cx + (int) Math.round(Math.cos(rad) * 11.5), cz + (int) Math.round(Math.sin(rad) * 11.5));
        }
        for (int k = 0; k < 4; k++) {
            double rad = Math.toRadians(45 + 90 * k);
            int bx = cx + (int) Math.round(Math.cos(rad) * 13), bz = cz + (int) Math.round(Math.sin(rad) * 13);
            flowerBed(bx, bz);
            int sx = cx + (int) Math.round(Math.cos(rad) * 15.5), sz = cz + (int) Math.round(Math.sin(rad) * 15.5);
            int ddx = sx - cx, ddz = sz - cz;
            if (Math.abs(ddx) >= Math.abs(ddz)) bench(sx, sz, (int) Math.signum(ddx), 0);
            else bench(sx, sz, 0, (int) Math.signum(ddz));
        }
        // Скамейки южнее фонтана (спинкой на юг, лицом к фонтану).
        for (int s : new int[]{-1, 1}) bench(cx + s * 6, cz + 12, 0, 1);
        reserved.add(new int[]{cx - 21, cz - 21, cx + 21, cz + 21});
    }

    private void lamp(int x, int z) {
        g.set(x, 1, z, Material.DEEPSLATE_BRICKS);
        for (int dy = 2; dy <= 4; dy++) g.set(x, dy, z, Material.DEEPSLATE_BRICK_WALL);
        g.lantern(x, 5, z, false);
    }

    private void bench(int x, int z, int fx, int fz) {
        // Скамья из 3 ступенек, спинкой от центра: поворачиваем вдоль перпендикуляра к направлению на центр.
        int px = fz != 0 ? 1 : 0, pz = fx != 0 ? 1 : 0;
        for (int s = -1; s <= 1; s++) g.stair(x + px * s, 1, z + pz * s, Material.SPRUCE_STAIRS, fx, fz, false);
    }

    private void flowerBed(int x, int z) {
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
            if (edge) { g.set(x + dx, 0, z + dz, Material.SPRUCE_PLANKS); g.set(x + dx, 1, z + dz, Material.AIR); }
            else {
                g.set(x + dx, 0, z + dz, Material.MOSS_BLOCK);
                g.set(x + dx, 1, z + dz, FLOWERS[rnd.nextInt(FLOWERS.length)]);
            }
        }
        g.set(x, 1, z, Material.FLOWERING_AZALEA);
    }

    // ------------------------------------------------------------------ Стена, ворота, башни

    private void wall() {
        java.util.Set<Long> done = new java.util.HashSet<>();
        for (double a = 0; a < 360; a += 0.4) {
            double rad = Math.toRadians(a);
            for (double rr : new double[]{R - 1, R}) {
                int dx = (int) Math.round(Math.cos(rad) * rr), dz = (int) Math.round(Math.sin(rad) * rr);
                if (!done.add(((long) dx << 32) ^ (dz & 0xffffffffL))) continue;
                if (dz > 0 && Math.abs(dx) <= 7) continue;       // южные ворота
                if (Math.abs(dz) <= 7 && dx != 0) continue;     // восточные и западные ворота
                int x = cx + dx, z = cz + dz;
                for (int dy = 1; dy <= 6; dy++) g.set(x, dy, z, hash(dx, dz + dy, 7) == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
                g.set(x, 7, z, (dx + dz) % 2 == 0 ? Material.STONE_BRICK_WALL : Material.AIR);
                g.set(x, 0, z, Material.COBBLESTONE);
            }
        }
        gatehouse(cx, cz + R - 1, 0, 1, "&6&lЮжные ворота");
        gatehouse(cx + R - 1, cz, 1, 0, "&6&lВосточные ворота");
        gatehouse(cx - R + 1, cz, -1, 0, "&6&lЗападные ворота");
        for (int k = 0; k < 4; k++) {
            double rad = Math.toRadians(45 + 90 * k);
            roundTower(cx + (int) Math.round(Math.cos(rad) * (R - 1)), cz + (int) Math.round(Math.sin(rad) * (R - 1)));
        }
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
            if (shell && (dx + dz) % 2 == 0) g.set(x + dx, h + 1, z + dz, Material.STONE_BRICK_WALL);
        }
        for (int dy = 4; dy <= 10; dy += 3) for (int[] o : new int[][]{{r - 1, 0}, {-(r - 1), 0}, {0, r - 1}, {0, -(r - 1)}}) g.set(x + o[0], dy, z + o[1], Material.IRON_BARS);
        g.lantern(x, h - 1, z, true);
        g.set(x, h + 1, z, Material.LIGHTNING_ROD);
        reserved.add(new int[]{x - 6, z - 6, x + 6, z + 6});
    }

    // ------------------------------------------------------------------ Здания

    private void building(int fxw, int fzw, int fx, int fz, int bw, int bd, int bh, Pal p, String type, String sign, String npc, int npcZ) {
        Canvas c = Canvas.front(w, fxw, y, fzw, fx, fz, bw);
        Structures.house(c, bw, bd, bh, p, type, new String[]{"", sign, "", ""});
        if (npc != null) anchor.accept(npc, c.spot(bw / 2, 1, npcZ));
        int x1 = c.wx(0, 0), z1 = c.wz(0, 0), x2 = c.wx(bw - 1, bd - 1), z2 = c.wz(bw - 1, bd - 1);
        reserved.add(new int[]{Math.min(x1, x2) - 3, Math.min(z1, z2) - 3, Math.max(x1, x2) + 3, Math.max(z1, z2) + 3});
    }

    private void buildings() {
        // Восточная дорога: фасады на расстоянии 4 от оси, смотрят на дорогу.
        building(cx + 26, cz - 4, 0, -1, 11, 9, 4, Structures.STONE, "SMITH", "&6&lКузница", "smith", 4);
        building(cx + 42, cz - 4, 0, -1, 9, 8, 4, Structures.SPRUCE, "ALCHEMY", "&5&lАлхимик", "alchemist", 4);
        building(cx + 26, cz + 4, 0, 1, 11, 9, 4, Structures.TIMBER, "BANK", "&e&lБанк", "banker", 4);
        building(cx + 42, cz + 4, 0, 1, 9, 8, 4, Structures.BRICK, "HOME", "&7Дом", null, 0);
        // Западная дорога.
        building(cx - 26, cz - 4, 0, -1, 13, 10, 5, Structures.TIMBER, "LIBRARY", "&b&lБиблиотека", "elder", 4);
        building(cx - 42, cz - 4, 0, -1, 9, 8, 4, Structures.SPRUCE, "HOME", "&7Дом", null, 0);
        building(cx - 26, cz + 4, 0, 1, 15, 11, 5, Structures.SPRUCE, "TAVERN", "&6&lТаверна", "bartender", 8);
        building(cx - 42, cz + 4, 0, 1, 9, 8, 4, Structures.CHERRY, "LODGE", "&c&lОхотник", "hunter", 4);
    }

    private void stalls() {
        Material[][] aw = {{Material.RED_WOOL, Material.WHITE_WOOL}, {Material.BLUE_WOOL, Material.WHITE_WOOL}, {Material.GREEN_WOOL, Material.WHITE_WOOL},
                {Material.YELLOW_WOOL, Material.ORANGE_WOOL}, {Material.PURPLE_WOOL, Material.WHITE_WOOL}, {Material.CYAN_WOOL, Material.WHITE_WOOL}};
        int i = 0;
        for (int zc : new int[]{30, 39, 48, 57, 66, 75, 84, 93}) {
            stall(cx - 4, cz + zc, -1, 0, aw[i % 6], null);
            stall(cx + 4, cz + zc, 1, 0, aw[(i + 3) % 6], i == 0 ? "trader_market" : null);
            i++;
        }
    }

    private void stall(int xw, int zw, int fx, int fz, Material[] awn, String npc) {
        Canvas c = Canvas.front(w, xw, y, zw, fx, fz, 7);
        int W = 7, D = 4;
        c.fill(0, 0, 0, W - 1, 0, D - 1, Material.SPRUCE_PLANKS);
        for (int x : new int[]{0, W - 1}) for (int z : new int[]{0, D - 1}) c.fill(x, 1, z, x, 3, z, Material.SPRUCE_FENCE);
        for (int x = -1; x <= W; x++) for (int z = -1; z <= D; z++) c.set(x, 4, z, (x + 2) % 2 == 0 ? awn[0] : awn[1]);
        c.fill(1, 1, 1, W - 2, 1, 1, Material.OAK_PLANKS);
        Material[] goods = {Material.MELON, Material.PUMPKIN, Material.CAKE, Material.HAY_BLOCK, Material.BARREL, Material.POTTED_RED_TULIP, Material.COMPOSTER};
        for (int x = 1; x <= W - 2; x++) c.set(x, 2, 1, goods[rnd.nextInt(goods.length)]);
        for (int x : new int[]{1, 2, W - 3, W - 2}) c.dir(x, 1, D - 2, rnd.nextBoolean() ? Material.BARREL : Material.CHEST, 0, -1);
        c.lantern(W / 2, 3, 1, true);
        c.lantern(1, 3, 2, true);
        c.lantern(W - 2, 3, 2, true);
        if (npc != null) anchor.accept(npc, c.spot(W / 2, 1, 2));
        int x1 = c.wx(0, 0), z1 = c.wz(0, 0), x2 = c.wx(W - 1, D - 1), z2 = c.wz(W - 1, D - 1);
        reserved.add(new int[]{Math.min(x1, x2) - 2, Math.min(z1, z2) - 2, Math.max(x1, x2) + 2, Math.max(z1, z2) + 2});
    }

    // ------------------------------------------------------------------ Монумент ALEX SMP

    private void monument() {
        var cfg = UnknownSMP.get().configs().main();
        String title = cfg.getString("branding.sign", "ALEX").toUpperCase();
        String sub = cfg.getString("branding.sign-sub", "SMP").toUpperCase();
        if (title.length() > 9) title = title.substring(0, 9);
        if (sub.length() > 6) sub = sub.substring(0, 6);
        // Вывеска парит высоко над озером на севере города и видна издалека.
        Canvas c = Canvas.facing(w, cx, y + 70, cz - 104, 0, -1);
        BigSign.build(c, title, sub);
        anchor.accept("monument", new Location(w, cx + 0.5, y + 1, cz - 40.5, 0, 0));
    }

    // ------------------------------------------------------------------ Мельница, пруд, ферма, лабиринт

    private void windmill() {
        int mx = cx + 46, mz = cz - 30;
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            boolean shell = Math.abs(dx) == 3 || Math.abs(dz) == 3;
            g.set(mx + dx, 0, mz + dz, Material.SPRUCE_PLANKS);
            for (int dy = 1; dy <= 10; dy++) {
                Material m = dy <= 3 ? Material.STONE_BRICKS : Material.WHITE_TERRACOTTA;
                g.set(mx + dx, dy, mz + dz, shell ? m : Material.AIR);
            }
        }
        for (int dy = 1; dy <= 10; dy += 3) for (int[] o : new int[][]{{3, 0}, {-3, 0}, {0, -3}}) g.set(mx + o[0], dy + 1, mz + o[1], Material.GLASS_PANE);
        for (int[] o : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) g.fill(mx + o[0], 1, mz + o[1], mx + o[0], 10, mz + o[1], Material.DARK_OAK_LOG);
        for (int l = 0; l < 5; l++) {
            int half = 4 - l;
            for (int dx = -half; dx <= half; dx++) for (int dz = -half; dz <= half; dz++) g.set(mx + dx, 11 + l, mz + dz, Material.DARK_OAK_PLANKS);
        }
        g.set(mx, 16, mz, Material.LIGHTNING_ROD);
        g.fill(mx, 1, mz + 3, mx, 2, mz + 3, Material.AIR);
        g.door(mx, 1, mz + 3, Material.DARK_OAK_DOOR, 0, 1);
        g.dir(mx - 2, 1, mz - 2, Material.CHEST, 1, 0);
        g.set(mx + 2, 1, mz - 2, Material.BARREL);
        g.set(mx + 2, 2, mz - 2, Material.BARREL);
        g.set(mx - 2, 1, mz + 2, Material.HAY_BLOCK);
        g.lantern(mx, 3, mz, true);
        // Лопасти на южной стороне.
        int hz = mz + 4, hy = 8;
        g.set(mx, hy, hz, Material.DARK_OAK_LOG);
        g.set(mx, hy, hz - 1, Material.DARK_OAK_LOG);
        for (int k = 1; k <= 7; k++) {
            g.set(mx + k, hy + k, hz, Material.DARK_OAK_FENCE);
            g.set(mx - k, hy + k, hz, Material.DARK_OAK_FENCE);
            g.set(mx - k, hy - k, hz, Material.DARK_OAK_FENCE);
            g.set(mx + k, hy - k, hz, Material.DARK_OAK_FENCE);
            if (k >= 2) {
                g.set(mx + k + 1, hy + k, hz, Material.WHITE_WOOL);
                g.set(mx + k + 2, hy + k, hz, Material.WHITE_WOOL);
                g.set(mx - k, hy + k + 1, hz, Material.WHITE_WOOL);
                g.set(mx - k, hy + k + 2, hz, Material.WHITE_WOOL);
                g.set(mx - k - 1, hy - k, hz, Material.WHITE_WOOL);
                g.set(mx - k - 2, hy - k, hz, Material.WHITE_WOOL);
                g.set(mx + k, hy - k - 1, hz, Material.WHITE_WOOL);
                g.set(mx + k, hy - k - 2, hz, Material.WHITE_WOOL);
            }
        }
        anchor.accept("miller", new Location(w, mx + 0.5, y + 1, mz + 5.5, 0, 0));
        reserved.add(new int[]{mx - 12, mz - 8, mx + 12, mz + 10});
    }

    private void pond() {
        int px = cx - 38, pz = cz + 34, r = 8;
        for (int dx = -r - 2; dx <= r + 2; dx++) for (int dz = -r - 2; dz <= r + 2; dz++) {
            double d = Math.hypot(dx, dz * 1.2);
            int x = px + dx, z = pz + dz;
            if (d <= r) {
                g.set(x, 0, z, Material.WATER);
                g.set(x, -1, z, d > r - 2 ? Material.WATER : Material.WATER);
                g.set(x, -2, z, Material.CLAY);
                if (rnd.nextInt(9) == 0) g.set(x, 1, z, Material.LILY_PAD);
            } else if (d <= r + 1.3) {
                g.set(x, 0, z, rnd.nextInt(3) == 0 ? Material.MOSS_BLOCK : Material.GRASS_BLOCK);
                if (rnd.nextInt(5) == 0) g.set(x, 1, z, Material.SUGAR_CANE);
                if (rnd.nextInt(7) == 0) g.set(x, 1, z, Material.COBBLESTONE_SLAB);
            }
        }
        // Мостик через пруд вдоль x.
        for (int dx = -r - 2; dx <= r + 2; dx++) {
            int arch = Math.abs(dx) <= 3 ? 1 : 0;
            for (int dz = -1; dz <= 1; dz++) g.set(px + dx, arch, pz + dz, Material.SPRUCE_PLANKS);
            if (arch == 0 && Math.abs(dx) <= 4) { /* подъём */ }
            g.set(px + dx, arch + 1, pz - 1, Material.SPRUCE_FENCE);
            g.set(px + dx, arch + 1, pz + 1, Material.SPRUCE_FENCE);
            if (Math.abs(dx) <= 4 && dx % 3 == 0) g.lantern(px + dx, arch + 2, pz - 1, false);
        }
        for (int dz = -1; dz <= 1; dz++) { g.stair(px - 4, 0, pz + dz, Material.SPRUCE_STAIRS, 1, 0, false); g.stair(px + 4, 0, pz + dz, Material.SPRUCE_STAIRS, -1, 0, false); }
        Canvas gg = g;
        Structures.tree(gg, px - 6, pz - 7, "cherry", rnd);
        Structures.tree(gg, px + 7, pz + 7, "cherry", rnd);
        bench(px, pz + 8, 0, -1);
        anchor.accept("fisher", new Location(w, px + 0.5, y + 2, pz + 0.5, 0, 0));
        reserved.add(new int[]{px - 14, pz - 14, px + 14, pz + 14});
    }

    private void farm() {
        String[] crops = {"WHEAT", "CARROTS", "POTATOES", "BEETROOTS"};
        int idx = 0;
        for (int j = 0; j < 2; j++) for (int i = 0; i < 3; i++) {
            int ox = cx + 13 + i * 11, oz = cz + 20 + j * 10;
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
            g.set(ox + 4, 1, oz - 1, Material.AIR);
            g.set(ox + 4, 0, oz + 1, Material.COARSE_DIRT);
            g.fill(ox + 4, 1, oz + 1, ox + 4, 2, oz + 1, Material.OAK_FENCE);
            g.set(ox + 4, 3, oz + 1, Material.HAY_BLOCK);
            g.set(ox + 4, 4, oz + 1, Material.CARVED_PUMPKIN);
            g.set(ox + 3, 2, oz + 1, Material.OAK_FENCE);
            g.set(ox + 5, 2, oz + 1, Material.OAK_FENCE);
        }
        Canvas barn = Canvas.front(w, cx + 31, y, cz + 40, 0, 1, 13);
        Structures.house(barn, 13, 8, 5, Structures.BRICK, "EMPTY", new String[]{"", "&6&lАмбар", "", ""});
        for (int x = 2; x <= 10; x += 2) for (int z = 2; z <= 5; z += 3) { barn.set(x, 1, z, Material.HAY_BLOCK); if (x % 4 == 0) barn.set(x, 2, z, Material.HAY_BLOCK); }
        barn.dir(11, 1, 1, Material.CHEST, -1, 0);
        barn.dir(11, 1, 6, Material.BARREL, -1, 0);
        anchor.accept("farmer", barn.spot(6, 1, 3));
        reserved.add(new int[]{cx + 11, cz + 17, cx + 46, cz + 50});
    }

    /** Лабиринт из живой изгороди; в центре спрятан секретный камень. */
    private void maze() {
        int N = 8, S = 2 * N + 1;
        int mx = cx - 48, mz = cz - 36;
        boolean[][] open = new boolean[S][S];
        int[] stack = new int[N * N * 2];
        int sp = 0;
        boolean[][] seen = new boolean[N][N];
        int[] dxs = {1, -1, 0, 0}, dzs = {0, 0, 1, -1};
        int sx = N - 1, sz = N - 1;
        seen[sx][sz] = true;
        open[2 * sx + 1][2 * sz + 1] = true;
        stack[sp++] = sx;
        stack[sp++] = sz;
        while (sp > 0) {
            int x = stack[sp - 2], z = stack[sp - 1];
            List<Integer> dirs = new ArrayList<>(List.of(0, 1, 2, 3));
            java.util.Collections.shuffle(dirs, rnd);
            boolean moved = false;
            for (int dI : dirs) {
                int nx = x + dxs[dI], nz = z + dzs[dI];
                if (nx < 0 || nz < 0 || nx >= N || nz >= N || seen[nx][nz]) continue;
                seen[nx][nz] = true;
                open[2 * nx + 1][2 * nz + 1] = true;
                open[x * 2 + 1 + dxs[dI]][z * 2 + 1 + dzs[dI]] = true;
                stack[sp++] = nx;
                stack[sp++] = nz;
                moved = true;
                break;
            }
            if (!moved) sp -= 2;
        }
        open[1][S - 1] = true; // вход с юга
        for (int i = 0; i < S; i++) for (int j = 0; j < S; j++) {
            int x = mx + i, z = mz + j;
            g.set(x, 0, z, Material.GRASS_BLOCK);
            for (int dy = 1; dy <= 3; dy++) g.set(x, dy, z, Material.AIR);
            if (!open[i][j]) for (int dy = 1; dy <= 3; dy++) g.leaves(x, dy, z, Material.AZALEA_LEAVES);
        }
        int cxm = mx + 2 * sx + 1, czm = mz + 2 * sz + 1;
        g.set(cxm, 0, czm, Material.CHISELED_STONE_BRICKS);
        g.set(cxm, 1, czm, Material.STONE_BRICKS);
        Block btn = g.at(cxm, 2, czm);
        btn.setType(Material.STONE_BUTTON, false);
        if (btn.getBlockData() instanceof org.bukkit.block.data.FaceAttachable fa) {
            fa.setAttachedFace(org.bukkit.block.data.FaceAttachable.AttachedFace.FLOOR);
            btn.setBlockData(fa, false);
        }
        for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) g.set(cxm + o[0], 0, czm + o[1], Material.SEA_LANTERN);
        anchor.accept("maze_button", new Location(w, cxm + 0.5, y + 2, czm + 0.5));
        anchor.accept("maze_entrance", new Location(w, mx + 1.5, y + 1, mz + S + 1.5, 180, 0));
        // Табличка у входа.
        g.set(mx + 1, 1, mz + S + 1, Material.AIR);
        reserved.add(new int[]{mx - 3, mz - 3, mx + S + 3, mz + S + 4});
    }

    // ------------------------------------------------------------------ Фонари, деревья, растительность

    private void roadLamps() {
        for (int t = 24; t <= R - 6; t += 8) {
            for (int s : new int[]{-3, 3}) {
                lamp(cx + t, cz + s);
                lamp(cx - t, cz + s);
                lamp(cx + s, cz + t);
                if (t <= 40) lamp(cx + s, cz - t);
            }
        }
    }

    // ------------------------------------------------------------------ Развлечения и дополнительные постройки

    private void extras() {
        building(cx + 58, cz - 4, 0, -1, 9, 8, 4, Structures.CHERRY, "HOME", "&7Дом", null, 0);
        building(cx + 58, cz + 4, 0, 1, 9, 8, 4, Structures.TIMBER, "HOME", "&7Дом", null, 0);
        building(cx - 58, cz - 4, 0, -1, 9, 8, 4, Structures.BRICK, "HOME", "&7Дом", null, 0);
        building(cx - 58, cz + 4, 0, 1, 9, 8, 4, Structures.STONE, "HOME", "&7Дом", null, 0);
        for (int sgn : new int[]{-1, 1}) for (int xx : new int[]{74, 90}) {
            building(cx + sgn * xx, cz - 4, 0, -1, 9, 8, 4, xx == 74 ? Structures.SPRUCE : Structures.CHERRY, "HOME", "&7Дом", null, 0);
            building(cx + sgn * xx, cz + 4, 0, 1, 9, 8, 4, xx == 74 ? Structures.BRICK : Structures.TIMBER, "HOME", "&7Дом", null, 0);
        }
        lake(cx, cz - 92, 22);
        watchtower(cx + 66, cz - 40);
        trainingYard(cx + 66, cz + 30);
        grove(cx - 64, cz + 36);
        well(cx + 20, cz + 14);
        carousel(cx - 20, cz - 26);
        scatterHouses();
    }

    /** Большое озеро с песчаным берегом и причалом. */
    private void lake(int x, int z, int r) {
        for (int dx = -r - 4; dx <= r + 4; dx++) for (int dz = -r - 4; dz <= r + 4; dz++) {
            double d = Math.hypot(dx, dz * 1.25) + Math.sin(dx * 0.35) * 1.5 + Math.cos(dz * 0.4) * 1.5;
            int px = x + dx, pz = z + dz;
            if (d <= r) {
                g.set(px, 0, pz, Material.WATER);
                g.set(px, -1, pz, Material.WATER);
                if (d < r - 6) g.set(px, -2, pz, Material.WATER);
                g.set(px, d < r - 6 ? -3 : -2 + (d < r - 6 ? 0 : 0), pz, d < r - 6 ? Material.CLAY : Material.WATER);
                if (d >= r - 6) g.set(px, -2, pz, Material.SAND);
                for (int dy = 1; dy <= 3; dy++) g.set(px, dy, pz, Material.AIR);
                if (rnd.nextInt(22) == 0) g.set(px, 1, pz, Material.LILY_PAD);
            } else if (d <= r + 2.5) {
                g.set(px, 0, pz, rnd.nextInt(4) == 0 ? Material.GRAVEL : Material.SAND);
                for (int dy = 1; dy <= 3; dy++) g.set(px, dy, pz, Material.AIR);
                if (rnd.nextInt(18) == 0) g.fill(px, 1, pz, px, 2, pz, Material.SUGAR_CANE);
            }
        }
        // Причал на юге.
        for (int dz = 0; dz <= r - 4; dz++) for (int dx = -1; dx <= 1; dx++) {
            int px = x + dx, pz = z + r - 2 - dz;
            g.set(px, 1, pz, Material.SPRUCE_PLANKS);
            if (dz % 4 == 0 && Math.abs(dx) == 1) { g.fill(px, 0, pz, px, 0, pz, Material.SPRUCE_LOG); g.set(px, 2, pz, Material.SPRUCE_FENCE); g.lantern(px, 3, pz, false); }
        }
        reserved.add(new int[]{x - r - 6, z - r - 6, x + r + 6, z + r + 6});
    }

    // ------------------------------------------------------------------ Парк развлечений на севере и мини-игры

    private void funPark() {
        Fun f = new Fun(w, g, anchor, rnd, "_c");
        f.trampolines(cx - 30, cz - 62, 15, 11);
        f.disco(cx + 24, cz - 58);
        f.clicker(cx, cz - 60);
        f.wheel(cx + 11, cz - 60, true);
        f.launchPad(cx - 8, cz - 50);
        f.launchPad(cx + 8, cz - 50);
        f.snowPile(cx - 16, cz + 27, true);
        f.snowPile(cx + 16, cz + 27, false);
        f.fireworkPedestal(cx - 12, cz + 19);
        f.torii(cx + 36, cz, true);
        f.torii(cx - 36, cz, true);
        f.torii(cx, cz + 22, false);
        f.torii(cx, cz - 46, false);
        int[][] isl = {{-60, 34, -20, 7}, {58, 40, 8, 8}, {-36, 30, 58, 7}, {44, 36, -62, 6}, {10, 48, -80, 6}, {-75, 42, 30, 7}};
        for (int i = 0; i < isl.length; i++) f.floatingIsland(cx + isl[i][0], y + isl[i][1], cz + isl[i][2], isl[i][3], i % 2 == 0);
        reserved.add(new int[]{cx - 36, cz - 68, cx + 36, cz - 46});
        reserved.add(new int[]{cx - 22, cz + 17, cx - 2, cz + 31});
        reserved.add(new int[]{cx + 8, cz + 21, cx + 24, cz + 31});
    }

    /** Жилые дома в свободных кварталах города. */
    private void scatterHouses() {
        Pal[] pals = {Structures.TIMBER, Structures.SPRUCE, Structures.BRICK, Structures.CHERRY, Structures.STONE};
        int[][] dirs = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
        int placed = 0;
        for (int tries = 0; tries < 1800 && placed < 44; tries++) {
            int dx = rnd.nextInt(2 * (R - 14) + 1) - (R - 14), dz = rnd.nextInt(2 * (R - 14) + 1) - (R - 14);
            if (Math.hypot(dx, dz) < 26 || Math.hypot(dx, dz) > R - 12 || Math.abs(dx) < 7 || Math.abs(dz) < 7) continue;
            int[] f = dirs[rnd.nextInt(4)];
            int bw = 7 + rnd.nextInt(3) * 2, bd = 7 + rnd.nextInt(2) * 2;
            Canvas c = Canvas.front(w, cx + dx, y, cz + dz, f[0], f[1], bw);
            int x1 = c.wx(0, 0), z1 = c.wz(0, 0), x2 = c.wx(bw - 1, bd - 1), z2 = c.wz(bw - 1, bd - 1);
            int ax = Math.min(x1, x2) - 3, az = Math.min(z1, z2) - 3, bx = Math.max(x1, x2) + 3, bz = Math.max(z1, z2) + 3;
            boolean ok = true;
            for (int px = ax; px <= bx && ok; px += 3) for (int pz = az; pz <= bz; pz += 3) if (!free(px, pz, 0)) { ok = false; break; }
            if (ok && (!free(bx, bz, 0) || !free(ax, az, 0) || !free(ax, bz, 0) || !free(bx, az, 0))) ok = false;
            if (!ok) continue;
            Structures.house(c, bw, bd, 4, pals[rnd.nextInt(pals.length)], "HOME", new String[]{"", "&7Дом", "", ""});
            reserved.add(new int[]{ax, az, bx, bz});
            placed++;
        }
    }

    private void ladder(int x, int dy, int z, org.bukkit.block.BlockFace face) {
        Block b = g.at(x, dy, z);
        b.setType(Material.LADDER, false);
        if (b.getBlockData() instanceof org.bukkit.block.data.type.Ladder l) {
            l.setFacing(face);
            b.setBlockData(l, false);
        }
    }

    /** Сторожевая башня с лестницей и смотровой площадкой. */
    private void watchtower(int x, int z) {
        int top = 24;
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            boolean shell = Math.abs(dx) == 3 || Math.abs(dz) == 3;
            boolean corner = Math.abs(dx) == 3 && Math.abs(dz) == 3;
            g.set(x + dx, 0, z + dz, Material.COBBLESTONE);
            for (int dy = 1; dy <= top; dy++) {
                Material m = Material.AIR;
                if (shell) m = corner ? Material.DARK_OAK_LOG : dy <= 4 ? Material.COBBLESTONE : (hash(dx + 5, dz + dy, 6) == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
                else if (dy % 8 == 0) m = Material.SPRUCE_PLANKS;
                g.set(x + dx, dy, z + dz, m);
            }
        }
        for (int dy = 4; dy <= top - 2; dy += 8) for (int o = -1; o <= 1; o++) {
            g.set(x + o, dy, z - 3, Material.GLASS_PANE);
            g.set(x + o, dy, z + 3, Material.GLASS_PANE);
            g.set(x - 3, dy, z + o, Material.GLASS_PANE);
            g.set(x + 3, dy, z + o, Material.GLASS_PANE);
        }
        for (int dy = 1; dy <= top; dy++) {
            if (dy % 8 == 0) { g.set(x, dy, z - 2, Material.AIR); }
            ladder(x, dy, z - 2, org.bukkit.block.BlockFace.SOUTH);
            if (dy % 8 == 0) { g.set(x, dy, z - 2, Material.AIR); ladder(x, dy, z - 2, org.bukkit.block.BlockFace.SOUTH); }
        }
        g.fill(x, 1, z + 3, x, 2, z + 3, Material.AIR);
        g.door(x, 1, z + 3, Material.DARK_OAK_DOOR, 0, 1);
        // Смотровая площадка и крыша.
        for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) {
            g.set(x + dx, top, z + dz, Material.SPRUCE_PLANKS);
            boolean rail = Math.abs(dx) == 5 || Math.abs(dz) == 5;
            if (rail) g.set(x + dx, top + 1, z + dz, Material.SPRUCE_FENCE);
        }
        ladder(x, top, z - 2, org.bukkit.block.BlockFace.SOUTH);
        for (int[] o : new int[][]{{-5, -5}, {5, -5}, {-5, 5}, {5, 5}}) {
            g.fill(x + o[0], top + 1, z + o[1], x + o[0], top + 4, z + o[1], Material.DARK_OAK_LOG);
            g.lantern(x + o[0], top + 5, z + o[1], false);
        }
        for (int l = 0; l < 4; l++) {
            int half = 5 - l;
            for (int dx = -half; dx <= half; dx++) for (int dz = -half; dz <= half; dz++) {
                if (Math.abs(dx) == half || Math.abs(dz) == half) g.set(x + dx, top + 5 + l, z + dz, Material.DARK_OAK_PLANKS);
            }
        }
        g.set(x, top + 9, z, Material.LIGHTNING_ROD);
        g.lantern(x, top - 1, z + 1, true);
        anchor.accept("scout", new Location(w, x + 0.5, y + 1, z + 6.5, 0, 0));
        reserved.add(new int[]{x - 8, z - 8, x + 8, z + 9});
    }

    /** Тренировочный двор: мишени, стойки с оружием, забор. */
    private void trainingYard(int x, int z) {
        int hx = 8, hz = 7;
        for (int dx = -hx; dx <= hx; dx++) for (int dz = -hz; dz <= hz; dz++) {
            g.set(x + dx, 0, z + dz, hash(dx, dz, 5) == 0 ? Material.PACKED_MUD : Material.COARSE_DIRT);
            for (int dy = 1; dy <= 4; dy++) g.set(x + dx, dy, z + dz, Material.AIR);
            boolean edge = Math.abs(dx) == hx || Math.abs(dz) == hz;
            if (edge) g.set(x + dx, 1, z + dz, Material.OAK_FENCE);
        }
        for (int dx = -1; dx <= 1; dx++) { g.set(x + dx, 1, z - hz, Material.AIR); }
        for (int dx = -1; dx <= 1; dx++) { g.set(x + dx, 1, z + hz, Material.AIR); }
        for (int[] o : new int[][]{{-hx, -hz}, {hx, -hz}, {-hx, hz}, {hx, hz}}) {
            g.fill(x + o[0], 1, z + o[1], x + o[0], 3, z + o[1], Material.DARK_OAK_LOG);
            g.lantern(x + o[0], 4, z + o[1], false);
        }
        for (int dx = -6; dx <= 6; dx += 3) {
            g.set(x + dx, 1, z - 5, Material.HAY_BLOCK);
            g.set(x + dx, 2, z - 5, Material.TARGET);
            g.set(x + dx, 3, z - 5, Material.CARVED_PUMPKIN);
        }
        for (int dx = -6; dx <= 6; dx += 4) {
            g.fill(x + dx, 1, z + 2, x + dx, 2, z + 2, Material.OAK_FENCE);
            g.set(x + dx, 3, z + 2, Material.HAY_BLOCK);
        }
        g.dir(x + hx - 1, 1, z, Material.BARREL, -1, 0);
        g.dir(x + hx - 1, 1, z + 1, Material.CHEST, -1, 0);
        g.set(x - hx + 1, 1, z, Material.ANVIL);
        g.set(x - hx + 1, 1, z + 1, Material.GRINDSTONE);
        anchor.accept("archer", new Location(w, x + 0.5, y + 1, z + 4.5, 0, 0));
        new Fun(w, g, anchor, rnd, "_c").tirLectern(x + 6, z + 5);
        reserved.add(new int[]{x - 11, z - 10, x + 11, z + 10});
    }

    /** Поляна с костром и скамьями. */
    private void grove(int x, int z) {
        for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++) {
            double d = Math.hypot(dx, dz);
            if (d > 8) continue;
            if (d <= 6.5) g.set(x + dx, 0, z + dz, d <= 1.5 ? Material.COBBLESTONE : hash(dx, dz, 4) == 0 ? Material.COARSE_DIRT : Material.PODZOL);
            for (int dy = 1; dy <= 4; dy++) g.set(x + dx, dy, z + dz, Material.AIR);
        }
        g.set(x, 1, z, Material.CAMPFIRE);
        for (int k = 0; k < 8; k++) {
            double rad = Math.toRadians(45 * k);
            int bx = x + (int) Math.round(Math.cos(rad) * 3.5), bz = z + (int) Math.round(Math.sin(rad) * 3.5);
            int ox = (int) Math.signum(bx - x), oz = (int) Math.signum(bz - z);
            if (Math.abs(bx - x) >= Math.abs(bz - z)) oz = 0; else ox = 0;
            g.stair(bx, 1, bz, Material.SPRUCE_STAIRS, ox, oz, false);
        }
        for (int k = 0; k < 4; k++) {
            double rad = Math.toRadians(22.5 + 90 * k);
            int lx = x + (int) Math.round(Math.cos(rad) * 6), lz = z + (int) Math.round(Math.sin(rad) * 6);
            g.fill(lx, 1, lz, lx, 3, lz, Material.OAK_FENCE);
            g.lantern(lx, 4, lz, false);
        }
        anchor.accept("chronicler", new Location(w, x + 5.5, y + 1, z + 0.5, 90, 0));
        reserved.add(new int[]{x - 10, z - 10, x + 10, z + 10});
    }

    /** Колодец желаний. */
    private void well(int x, int z) {
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            g.set(x + dx, 0, z + dz, Material.STONE_BRICKS);
            boolean ring = Math.max(Math.abs(dx), Math.abs(dz)) == 1;
            if (ring) g.set(x + dx, 1, z + dz, Material.COBBLESTONE_WALL);
        }
        g.set(x, 0, z, Material.WATER);
        g.set(x, -1, z, Material.WATER);
        for (int[] o : new int[][]{{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
            if (o[0] == 1 && o[1] == 1) continue;
            g.fill(x + o[0], 2, z + o[1], x + o[0], 3, z + o[1], Material.OAK_FENCE);
        }
        g.fill(x + 1, 1, z + 1, x + 1, 3, z + 1, Material.OAK_FENCE);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -1; dz <= 1; dz++) g.slab(x + dx, 4, z + dz, Material.SPRUCE_SLAB, false);
        g.lantern(x, 3, z, true);
        anchor.accept("well_c", new Location(w, x + 0.5, y + 1, z + 0.5));
        anchor.accept("holo_well_c", new Location(w, x + 0.5, y + 5.6, z + 0.5));
        reserved.add(new int[]{x - 4, z - 4, x + 4, z + 4});
    }

    /** Карусель: вращающаяся «видимая» часть — платформа с лошадками из шерсти и ступенями. */
    private void carousel(int x, int z) {
        for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) {
            double d = Math.hypot(dx, dz);
            if (d > 5.4) continue;
            g.set(x + dx, 0, z + dz, d > 4.4 ? Material.RED_TERRACOTTA : (dx + dz) % 2 == 0 ? Material.WHITE_TERRACOTTA : Material.PINK_TERRACOTTA);
            for (int dy = 1; dy <= 6; dy++) g.set(x + dx, dy, z + dz, Material.AIR);
        }
        g.fill(x, 1, z, x, 5, z, Material.OAK_FENCE);
        Material[] wool = {Material.RED_WOOL, Material.YELLOW_WOOL, Material.LIGHT_BLUE_WOOL, Material.LIME_WOOL, Material.PINK_WOOL, Material.ORANGE_WOOL};
        for (int k = 0; k < 6; k++) {
            double rad = Math.toRadians(60 * k);
            int hx = x + (int) Math.round(Math.cos(rad) * 3), hz = z + (int) Math.round(Math.sin(rad) * 3);
            g.fill(hx, 1, hz, hx, 4, hz, Material.OAK_FENCE);
            g.set(hx, 2, hz, wool[k]);
            g.stair(hx, 1, hz, Material.QUARTZ_STAIRS, 1, 0, false);
        }
        for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) {
            double d = Math.hypot(dx, dz);
            if (d > 5.4) continue;
            g.set(x + dx, 6, z + dz, d > 4.4 ? Material.RED_WOOL : (dx + dz) % 2 == 0 ? Material.WHITE_WOOL : Material.YELLOW_WOOL);
        }
        g.set(x, 7, z, Material.LANTERN);
        reserved.add(new int[]{x - 8, z - 8, x + 8, z + 8});
    }

    private boolean free(int x, int z, int margin) {
        for (int[] r : reserved) if (x >= r[0] - margin && x <= r[2] + margin && z >= r[1] - margin && z <= r[3] + margin) return false;
        return true;
    }

    private void trees() {
        String[] kinds = {"oak", "oak", "birch", "spruce", "cherry"};
        int placed = 0;
        for (int tries = 0; tries < 3500 && placed < 320; tries++) {
            int dx = rnd.nextInt(2 * (R - 5) + 1) - (R - 5), dz = rnd.nextInt(2 * (R - 5) + 1) - (R - 5);
            double d = Math.hypot(dx, dz);
            if (d < 24 || d > R - 6 || Math.abs(dx) < 5 || Math.abs(dz) < 5) continue;
            int x = cx + dx, z = cz + dz;
            if (!free(x, z, 3)) continue;
            if (g.at(x, 0, z).getType() != Material.GRASS_BLOCK) continue;
            Structures.tree(g, x, z, kinds[rnd.nextInt(kinds.length)], rnd);
            reserved.add(new int[]{x - 2, z - 2, x + 2, z + 2});
            placed++;
        }
    }

    private void vegetation() {
        for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
            if (Math.hypot(dx, dz) > R - 2) continue;
            int x = cx + dx, z = cz + dz;
            Block b = g.at(x, 0, z);
            if (b.getType() != Material.GRASS_BLOCK) continue;
            if (!g.at(x, 1, z).getType().isAir()) continue;
            int h = rnd.nextInt(100);
            if (h < 9) g.set(x, 1, z, Material.SHORT_GRASS);
            else if (h < 11) g.set(x, 1, z, FLOWERS[rnd.nextInt(FLOWERS.length)]);
            else if (h < 12 && free(x, z, 0)) g.set(x, 1, z, Material.FERN);
        }
    }
}
