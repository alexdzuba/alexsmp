package ru.unknownsmp.world;

import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import ru.unknownsmp.core.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Холст для строительства в локальных координатах: x — вправо (для зрителя, смотрящего на здание),
 * y — вверх, z — вглубь здания. Любое здание можно повернуть в любую из 4 сторон одним параметром.
 * Зритель стоит снаружи (z &lt; 0) и смотрит в направлении F = (fx, fz).
 */
public final class Canvas {
    public final World w;
    public final int ox, oy, oz, rx, rz, fx, fz;

    public Canvas(World w, int ox, int oy, int oz, int rx, int rz, int fx, int fz) {
        this.w = w;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.rx = rx;
        this.rz = rz;
        this.fx = fx;
        this.fz = fz;
    }

    /** Без поворота: локальные координаты = мировые (x вправо = +x, z вглубь = +z). */
    public static Canvas identity(World w) {
        return new Canvas(w, 0, 0, 0, 1, 0, 0, 1);
    }

    /** Начало холста в (x,y,z), зритель смотрит в направлении (fx,fz). */
    public static Canvas facing(World w, int x, int y, int z, int fx, int fz) {
        return new Canvas(w, x, y, z, -fz, fx, fx, fz);
    }

    /** Центр фасада здания шириной width в мировой точке (x,y,z); зритель смотрит в (fx,fz). */
    public static Canvas front(World w, int x, int y, int z, int fx, int fz, int width) {
        int rx = -fz, rz = fx;
        int half = width / 2;
        return new Canvas(w, x - half * rx, y, z - half * rz, rx, rz, fx, fz);
    }

    public int wx(int lx, int lz) { return ox + lx * rx + lz * fx; }

    public int wz(int lx, int lz) { return oz + lx * rz + lz * fz; }

    public Block at(int lx, int ly, int lz) { return w.getBlockAt(wx(lx, lz), oy + ly, wz(lx, lz)); }

    /** Центр блока (x,z) на высоте ly, лицом к зрителю у фасада (для NPC). */
    public Location spot(int lx, int ly, int lz) {
        double yaw = Math.toDegrees(Math.atan2(fx, -fz));
        return new Location(w, wx(lx, lz) + 0.5, oy + ly, wz(lx, lz) + 0.5, (float) yaw, 0f);
    }

    public BlockFace face(int dx, int dz) {
        int x = dx * rx + dz * fx, z = dx * rz + dz * fz;
        if (x > 0) return BlockFace.EAST;
        if (x < 0) return BlockFace.WEST;
        if (z > 0) return BlockFace.SOUTH;
        return BlockFace.NORTH;
    }

    // ------------------------------------------------------------------ Блоки
    // ------------------------------------------------------------------ Соединение стёкол, заборов, стен и решёток

    private static final List<Block> PENDING = new ArrayList<>();

    private static boolean connectable(Material m) {
        String n = m.name();
        return n.endsWith("_PANE") || n.equals("GLASS_PANE") || n.equals("IRON_BARS") || n.endsWith("_FENCE") || (n.endsWith("_WALL") && !n.contains("SIGN"));
    }

    /** Запомнить блок, форма которого (соединения) должна быть пересчитана после постройки. */
    public static void track(Block b) {
        if (connectable(b.getType())) PENDING.add(b);
    }

    /** Блок поставлен без физики, поэтому стёкла/заборы/стены не знают о соседях — пересчитываем соединения вручную. */
    public static void flushConnections() {
        for (Block b : PENDING) fixConnections(b);
        PENDING.clear();
    }

    private static boolean connectsTo(Block self, Block n) {
        Material nm = n.getType();
        if (nm.isOccluding()) return true;
        String sn = self.getType().name(), nn = nm.name();
        if (self.getBlockData() instanceof org.bukkit.block.data.type.Fence) {
            if (n.getBlockData() instanceof org.bukkit.block.data.type.Fence) return sn.contains("NETHER") == nn.contains("NETHER");
            return nn.endsWith("_FENCE_GATE");
        }
        if (self.getBlockData() instanceof org.bukkit.block.data.type.Wall) {
            return n.getBlockData() instanceof org.bukkit.block.data.type.Wall || n.getBlockData() instanceof org.bukkit.block.data.type.GlassPane
                    || nn.equals("IRON_BARS") || nn.endsWith("_FENCE_GATE");
        }
        return n.getBlockData() instanceof org.bukkit.block.data.type.GlassPane || nn.equals("IRON_BARS")
                || n.getBlockData() instanceof org.bukkit.block.data.type.Wall;
    }

    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    private static void fixConnections(Block b) {
        BlockData d = b.getBlockData();
        if (d instanceof org.bukkit.block.data.type.Wall wl) {
            int count = 0;
            boolean ns = false, ew = false;
            for (BlockFace f : SIDES) {
                boolean c = connectsTo(b, b.getRelative(f));
                wl.setHeight(f, c ? org.bukkit.block.data.type.Wall.Height.LOW : org.bukkit.block.data.type.Wall.Height.NONE);
                if (c) {
                    count++;
                    if (f == BlockFace.NORTH || f == BlockFace.SOUTH) ns = true; else ew = true;
                }
            }
            boolean straight = count == 2 && (ns ^ ew);
            wl.setUp(!straight || !b.getRelative(BlockFace.UP).getType().isAir());
            b.setBlockData(wl, false);
        } else if (d instanceof org.bukkit.block.data.MultipleFacing mf) {
            for (BlockFace f : SIDES) if (mf.getAllowedFaces().contains(f)) mf.setFace(f, connectsTo(b, b.getRelative(f)));
            b.setBlockData(mf, false);
        }
    }


    public void set(int x, int y, int z, Material m) {
        Block b = at(x, y, z);
        b.setType(m, false);
        track(b);
    }

    public void fill(int x1, int y1, int z1, int x2, int y2, int z2, Material m) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) set(x, y, z, m);
    }

    private void data(int x, int y, int z, BlockData d) {
        at(x, y, z).setBlockData(d, false);
    }

    /** Блок с направлением (сундук, печь, наковальня, лестница...). (dx,dz) — локальное направление, куда «смотрит» блок. */
    public void dir(int x, int y, int z, Material m, int dx, int dz) {
        BlockData d = m.createBlockData();
        if (d instanceof Directional dd) {
            try { dd.setFacing(face(dx, dz)); } catch (IllegalArgumentException ignored) {}
        }
        data(x, y, z, d);
    }

    public void stair(int x, int y, int z, Material m, int dx, int dz, boolean top) {
        Stairs s = (Stairs) m.createBlockData();
        s.setFacing(face(dx, dz));
        s.setHalf(top ? Bisected.Half.TOP : Bisected.Half.BOTTOM);
        data(x, y, z, s);
    }

    public void slab(int x, int y, int z, Material m, boolean top) {
        Slab s = (Slab) m.createBlockData();
        s.setType(top ? Slab.Type.TOP : Slab.Type.BOTTOM);
        data(x, y, z, s);
    }

    public void leaves(int x, int y, int z, Material m) {
        Leaves l = (Leaves) m.createBlockData();
        l.setPersistent(true);
        data(x, y, z, l);
    }

    public void lantern(int x, int y, int z, boolean hanging) {
        Lantern l = (Lantern) Material.LANTERN.createBlockData();
        l.setHanging(hanging);
        data(x, y, z, l);
    }

    public void bed(int x, int y, int z, Material m, int dx, int dz) {
        Bed b = (Bed) m.createBlockData();
        b.setFacing(face(dx, dz));
        b.setPart(Bed.Part.FOOT);
        data(x, y, z, b);
        Bed h = (Bed) m.createBlockData();
        h.setFacing(face(dx, dz));
        h.setPart(Bed.Part.HEAD);
        data(x + dx, y, z + dz, h);
    }

    public void door(int x, int y, int z, Material m, int dx, int dz) {
        Door d = (Door) m.createBlockData();
        d.setFacing(face(dx, dz));
        d.setHalf(Bisected.Half.BOTTOM);
        data(x, y, z, d);
        Door t = (Door) m.createBlockData();
        t.setFacing(face(dx, dz));
        t.setHalf(Bisected.Half.TOP);
        data(x, y + 1, z, t);
        // Перемычка над дверью: если стена выше двери оставлена пустой — закрываем, чтобы в стене не было дыры.
        Block above = at(x, y + 2, z);
        if (above.getType().isAir()) {
            Material fill = Material.OAK_PLANKS;
            for (int s : new int[]{-1, 1}) {
                Material nm = at(x + s, y + 2, z).getType();
                if (nm.isOccluding()) { fill = nm; break; }
            }
            above.setType(fill, false);
        }
    }

    public void trapdoor(int x, int y, int z, Material m, int dx, int dz, boolean top, boolean open) {
        TrapDoor t = (TrapDoor) m.createBlockData();
        t.setFacing(face(dx, dz));
        t.setHalf(top ? Bisected.Half.TOP : Bisected.Half.BOTTOM);
        t.setOpen(open);
        data(x, y, z, t);
    }

    /** Настенная табличка с белым светящимся текстом. (dx,dz) — куда смотрит лицевая сторона. */
    public void sign(int x, int y, int z, int dx, int dz, String... lines) {
        Block b = at(x, y, z);
        WallSign ws = (WallSign) Material.DARK_OAK_WALL_SIGN.createBlockData();
        ws.setFacing(face(dx, dz));
        b.setBlockData(ws, false);
        if (b.getState() instanceof Sign s) {
            SignSide side = s.getSide(Side.FRONT);
            for (int i = 0; i < 4; i++) side.line(i, Text.c(i < lines.length ? lines[i] : ""));
            side.setColor(DyeColor.WHITE);
            side.setGlowingText(true);
            s.setWaxed(true);
            s.update(true, false);
        }
    }

    // ------------------------------------------------------------------ Текст и логотип

    /** Пишет текст пиксельным шрифтом 5×5 в плоскости lz. Начало (x0, y0) — левый нижний угол первой буквы. */
    public void text(String s, int x0, int y0, int z, Material m, int scale) {
        int cx = x0;
        for (char ch : s.toUpperCase().toCharArray()) {
            String[] g = Glyphs.FONT.get(ch);
            if (g != null) {
                for (int r = 0; r < 5; r++) for (int c = 0; c < 5; c++) {
                    if (g[r].charAt(c) != '#') continue;
                    int bx = cx + c * scale, by = y0 + (4 - r) * scale;
                    fill(bx, by, z, bx + scale - 1, by + scale - 1, z, m);
                }
            }
            cx += 6 * scale;
        }
    }

    public static int textWidth(String s, int scale) {
        return Math.max(0, (s.length() * 6 - 1) * scale);
    }

    /** Логотип на вертикальной плоскости. mats: левая, левый край, правая, правый край, треугольник. */
    public void logoV(int cx, int y0, int z, int h, int t, Material[] mats) {
        for (int[] px : Glyphs.logo(h, t)) set(cx + px[0], y0 + px[1], z, mats[px[2]]);
    }

    /** Логотип на полу: верх логотипа — вглубь (z+). */
    public void logoFloor(int cx, int y, int z0, int h, int t, Material[] mats) {
        for (int[] px : Glyphs.logo(h, t)) set(cx + px[0], y, z0 + px[1], mats[px[2]]);
    }

    /** Шрифт и геометрия логотипа. */
    public static final class Glyphs {
        private Glyphs() {}

        static final Map<Character, String[]> FONT = new HashMap<>();

        private static void def(char c, String... rows) { FONT.put(c, rows); }

        static {
            def('A', ".###.", "#...#", "#####", "#...#", "#...#");
            def('B', "####.", "#...#", "####.", "#...#", "####.");
            def('C', ".####", "#....", "#....", "#....", ".####");
            def('D', "####.", "#...#", "#...#", "#...#", "####.");
            def('E', "#####", "#....", "###..", "#....", "#####");
            def('F', "#####", "#....", "###..", "#....", "#....");
            def('G', ".####", "#....", "#..##", "#...#", ".###.");
            def('H', "#...#", "#...#", "#####", "#...#", "#...#");
            def('I', "#####", "..#..", "..#..", "..#..", "#####");
            def('J', "..###", "...#.", "...#.", "#..#.", ".##..");
            def('K', "#...#", "#..#.", "###..", "#..#.", "#...#");
            def('L', "#....", "#....", "#....", "#....", "#####");
            def('M', "#...#", "##.##", "#.#.#", "#...#", "#...#");
            def('N', "#...#", "##..#", "#.#.#", "#..##", "#...#");
            def('O', ".###.", "#...#", "#...#", "#...#", ".###.");
            def('P', "####.", "#...#", "####.", "#....", "#....");
            def('Q', ".###.", "#...#", "#.#.#", "#..#.", ".##.#");
            def('R', "####.", "#...#", "####.", "#..#.", "#...#");
            def('S', ".####", "#....", ".###.", "....#", "####.");
            def('T', "#####", "..#..", "..#..", "..#..", "..#..");
            def('U', "#...#", "#...#", "#...#", "#...#", ".###.");
            def('V', "#...#", "#...#", "#...#", ".#.#.", "..#..");
            def('W', "#...#", "#...#", "#.#.#", "##.##", "#...#");
            def('X', "#...#", ".#.#.", "..#..", ".#.#.", "#...#");
            def('Y', "#...#", ".#.#.", "..#..", "..#..", "..#..");
            def('Z', "#####", "...#.", "..#..", ".#...", "#####");
            def('0', ".###.", "#..##", "#.#.#", "##..#", ".###.");
            def('1', "..#..", ".##..", "..#..", "..#..", ".###.");
            def('2', ".###.", "#...#", "..##.", ".#...", "#####");
            def('3', "####.", "....#", ".###.", "....#", "####.");
            def('4', "#..#.", "#..#.", "#####", "...#.", "...#.");
            def('5', "#####", "#....", "####.", "....#", "####.");
            def('!', "..#..", "..#..", "..#..", ".....", "..#..");
            def(' ', ".....", ".....", ".....", ".....", ".....");
        }

        /** Пиксели логотипа {dx, row(0 — низ), kind}: 0 левая, 1 левый край, 2 правая, 3 правый край, 4 треугольник. */
        public static List<int[]> logo(int h, int t) {
            List<int[]> out = new ArrayList<>();
            for (int i = 0; i < h; i++) {
                int off = h - 1 - i;
                for (int k = 0; k < t; k++) {
                    int lx = -(off + 1 + k);
                    out.add(new int[]{lx, i, k == t - 1 ? 1 : 0});
                    int rx = off + 1 + k;
                    out.add(new int[]{rx, i, k == t - 1 ? 3 : 2});
                }
            }
            int th = Math.max(3, h * 4 / 10);
            for (int j = 0; j < th; j++) {
                int half = th - 1 - j;
                for (int x = -half; x <= half; x++) out.add(new int[]{x, j, 4});
            }
            return out;
        }
    }
}
