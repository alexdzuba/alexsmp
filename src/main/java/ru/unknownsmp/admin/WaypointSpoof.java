package ru.unknownsmp.admin;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;

/**
 * Поддельная метка игрока на панели локатора (над хотбаром): показывает viewer'у метку target в заданной точке.
 * Используется, чтобы метка наблюдающего персонала «стояла» там, где он вошёл в режим наблюдателя.
 * Если сервер изменит внутренности пакета, подмена тихо отключится.
 */
final class WaypointSpoof {
    private WaypointSpoof() {}

    private static volatile boolean broken;
    private static boolean ready;
    private static Class<?> packetClass;
    private static Method addPosition, remove;
    private static Constructor<?> vecCtor, iconCtor;

    private static synchronized void init() throws Exception {
        if (ready) return;
        packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket");
        Class<?> vec = Class.forName("net.minecraft.core.Vec3i");
        Class<?> icon = Class.forName("net.minecraft.world.waypoints.Waypoint$Icon");
        vecCtor = vec.getConstructor(int.class, int.class, int.class);
        iconCtor = icon.getDeclaredConstructor();
        iconCtor.setAccessible(true);
        for (Method m : packetClass.getMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] t = m.getParameterTypes();
            if (m.getName().equals("addWaypointPosition") && t.length == 3 && t[0] == UUID.class) addPosition = m;
            if (m.getName().equals("removeWaypoint") && t.length == 1 && t[0] == UUID.class) remove = m;
        }
        if (addPosition == null || remove == null) throw new NoSuchMethodException("ClientboundTrackedWaypointPacket");
        ready = true;
    }

    private static Object handle(Player p) throws Exception {
        return p.getClass().getMethod("getHandle").invoke(p);
    }

    /** Иконка метки как у самого игрока (цвет/стиль), иначе стандартная. */
    private static Object iconOf(Player target) {
        try {
            Object h = handle(target);
            Method m = h.getClass().getMethod("waypointIcon");
            Object ic = m.invoke(h);
            if (ic != null) return ic;
        } catch (Throwable ignored) {}
        try { return iconCtor.newInstance(); } catch (Throwable t) { return null; }
    }

    private static void sendPacket(Player viewer, Object packet) throws Exception {
        Object vsp = handle(viewer);
        Object conn = vsp.getClass().getField("connection").get(vsp);
        for (Method m : conn.getClass().getMethods()) {
            if (m.getName().equals("send") && m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(packetClass)) {
                m.invoke(conn, packet);
                return;
            }
        }
        throw new NoSuchMethodException("send");
    }

    /** Показать viewer'у метку target в точке at. */
    static void show(Player viewer, Player target, Location at) {
        if (broken || viewer.equals(target) || at.getWorld() == null || !at.getWorld().equals(viewer.getWorld())) return;
        try {
            init();
            Object pos = vecCtor.newInstance(at.getBlockX(), at.getBlockY(), at.getBlockZ());
            sendPacket(viewer, addPosition.invoke(null, target.getUniqueId(), iconOf(target), pos));
        } catch (Throwable t) {
            broken = true;
            org.bukkit.Bukkit.getLogger().warning("[UnknownSMP] Подмена метки на локаторе недоступна: " + t);
        }
    }

    /** Убрать у viewer'а метку игрока с этим UUID. */
    static void hide(Player viewer, UUID target) {
        if (broken) return;
        try {
            init();
            sendPacket(viewer, remove.invoke(null, target));
        } catch (Throwable t) {
            broken = true;
            org.bukkit.Bukkit.getLogger().warning("[UnknownSMP] Подмена метки на локаторе недоступна: " + t);
        }
    }
}
