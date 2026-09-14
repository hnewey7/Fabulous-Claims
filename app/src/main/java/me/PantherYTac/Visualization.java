package me.PantherYTac;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.*;

public class Visualization implements Listener {
    private record BlockKey(Location loc, BlockData data) {}

    private static final Set<UUID> enabled = new HashSet<>();
    private static final Map<UUID, String> themes = new HashMap<>();
    private static final Map<UUID, Set<BlockKey>> shown = new HashMap<>();
    private static BukkitRunnable task;
    private static boolean block_theme;

    public static void init(ClaimPlugin plugin) {
        enabled.clear();
        themes.clear();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (plugin.getManager().isVisualizationEnabled(p.getUniqueId())) {
                enabled.add(p.getUniqueId());
            }
        }
        if (!enabled.isEmpty()) {
            start();
        }
    }

    private static boolean isBlockThemeActive(ClaimPlugin plugin) {
        return !plugin.feature("particle_themes", true) && plugin.feature("block_theme", false);
    }

    public static void setTheme(UUID uuid, String theme) {
        themes.put(uuid, theme.toUpperCase(Locale.ROOT));
    }

    public static String getTheme(UUID uuid) {
        return themes.getOrDefault(uuid, "DEFAULT");
    }

    public static void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        enabled.clear();
        themes.clear();
    }

    public static void toggle(Player p) {
        UUID uuid = p.getUniqueId();
        boolean newState;
        if (enabled.contains(uuid)) {
            enabled.remove(uuid);
            newState = false;
            revertAll(p);
            p.sendMessage("§eClaim visualization disabled.");
            stopIfNone();
        } else {
            enabled.add(uuid);
            newState = true;
            p.sendMessage("§aClaim visualization enabled (" + getTheme(uuid) + " theme).");
            start();
        }
        ClaimPlugin.getInstance().getManager().setVisualizationEnabled(uuid, newState);
    }

    private static void start() {
        if (task != null) return;
        task = new BukkitRunnable() {
            @Override
            public void run() {
                for (UUID id : new HashSet<>(enabled)) {
                    Player player = ClaimPlugin.getInstance().getServer().getPlayer(id);
                    if (player == null) {
                        enabled.remove(id);
                        continue;
                    }
                    var cm = ClaimPlugin.getInstance().getManager();
                    Location pLoc = player.getLocation();
                    if (pLoc.getWorld() == null) continue;

                    for (Claim c : cm.getClaims()) {
                        if (c.getWorldName().equals(pLoc.getWorld().getName())) {
                            if (c.isInside(pLoc)) {
                                if (!isBlockThemeActive(ClaimPlugin.getInstance())) {
                                    render_particle(player, c);
                                } else {
                                    render_block(player, c);
                                }   
                            } else {
                                revertAll(player);
                            }
                        }
                    }
                }
            }
        };
        task.runTaskTimer(ClaimPlugin.getInstance(), 0L, 20L);
    }

    private static void stopIfNone() {
        if (enabled.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        boolean isEnabled = ClaimPlugin.getInstance().getManager().isVisualizationEnabled(p.getUniqueId());
        if (isEnabled) {
            enabled.add(p.getUniqueId());
            start();
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        enabled.remove(e.getPlayer().getUniqueId());
        themes.remove(e.getPlayer().getUniqueId());
        stopIfNone();
    }

    private static Particle getParticleForTheme(String theme) {
        switch (theme.toUpperCase(Locale.ROOT)) {
            case "CYAN":
                try { return Particle.valueOf("SOUL_FIRE_FLAME"); } catch (Exception e) { return Particle.FLAME; }
            case "ENCHANTMENT":
                try { return Particle.valueOf("ENCHANTMENT_TABLE"); } catch (Exception e) { return Particle.CRIT; }
            case "HEART":
                return Particle.HEART;
            case "PORTAL":
                return Particle.PORTAL;
            case "DEFAULT":
            default:
                return getHappyVillagerParticle();
        }
    }

    private static Particle getHappyVillagerParticle() {
        try {
            return Particle.valueOf("HAPPY_VILLAGER");
        } catch (IllegalArgumentException e) {
            try {
                return Particle.valueOf("VILLAGER_HAPPY");
            } catch (IllegalArgumentException ex) {
                return Particle.HEART;
            }
        }
    }

    private static void render_particle(Player p, Claim c) {
        Particle part = getParticleForTheme(getTheme(p.getUniqueId()));
        int halfX = c.getSizeX() / 2, halfZ = c.getSizeZ() / 2;
        int cx = c.getCenterX(), cz = c.getCenterZ();
        int minY = c.getCenterY() - c.getSizeY() / 2;
        int maxY = c.getCenterY() + c.getSizeY() / 2;

        // 1. Render 4 vertical corner lines (from minY to maxY)
        int[] xs = {cx - halfX, cx + halfX};
        int[] zs = {cz - halfZ, cz + halfZ};
        for (int x : xs) {
            for (int z : zs) {
                for (double y = minY; y <= maxY; y += 2.0) {
                    p.spawnParticle(part, x + 0.5, y + 0.5, z + 0.5, 1, 0, 0, 0, 0);
                }
            }
        }

        // 2. Render horizontal grid at player's height (clamped to claim bounds)
        int playerY = p.getLocation().getBlockY();
        double targetY = playerY + 0.5;
        if (targetY < minY) targetY = minY + 0.5;
        if (targetY > maxY) targetY = maxY - 0.5;

        // Draw boundaries at targetY
        for (double x = cx - halfX; x <= cx + halfX; x += 1.0) {
            p.spawnParticle(part, x + 0.5, targetY, cz - halfZ + 0.5, 1, 0, 0, 0, 0);
            p.spawnParticle(part, x + 0.5, targetY, cz + halfZ + 0.5, 1, 0, 0, 0, 0);
        }
        for (double z = cz - halfZ; z <= cz + halfZ; z += 1.0) {
            p.spawnParticle(part, cx - halfX + 0.5, targetY, z + 0.5, 1, 0, 0, 0, 0);
            p.spawnParticle(part, cx + halfX + 0.5, targetY, z + 0.5, 1, 0, 0, 0, 0);
        }
    }

    private static void render_block(Player p, Claim c) {
        // Get world
        World world = p.getWorld();

        // Get half size and center of claim
        int halfX = c.getSizeX() / 2;
        int halfZ = c.getSizeZ() / 2;
        int cx = c.getCenterX();
        int cz = c.getCenterZ();

        // Calculate corners
        int[] x_corners = {
            cx - halfX,
            cx - halfX, 
            cx + halfX, 
            cx + halfX
        };
        int[] z_corners = {
            cz - halfZ,
            cz + halfZ,
            cz - halfZ,
            cz + halfZ
        };

        // Set air transitions around player to marking block
        int playerY = p.getLocation().getBlockY();
        for (int i = 0; i < 4; i++) {
            // Get air transitions 5 blocks above and below player.
            List<Integer> transitions = airTransitions(world, x_corners[i], z_corners[i], playerY - 5, playerY + 5);
            for (int transY : transitions) {
                // Get block at transition
                Block block = world.getBlockAt(x_corners[i], transY, z_corners[i]);

                // Get data for caching
                UUID pID = p.getUniqueId();
                Location loc = block.getLocation();
                BlockData data = block.getBlockData();
                BlockKey new_block = new BlockKey(loc, data);

                // Update hashmap
                if (shown.containsKey(pID)) {
                    shown.get(pID).add(new_block);
                } else {
                    Set<BlockKey> set = new HashSet<BlockKey>();
                    set.add(new_block);
                    shown.put(pID, set);
                }

                // Send block change to player.
                p.sendBlockChange(loc, Material.GOLD_BLOCK.createBlockData());
            }
        }
    }

    private static List<Integer> airTransitions(World world, int x, int z, int minHeight, int maxHeight) {
        ChunkSnapshot snapshot = world.getChunkAt(x >> 4, z >> 4).getChunkSnapshot();
        return airTransitions(snapshot, x & 15, z & 15, world.getMinHeight(), maxHeight);
    }

    private static List<Integer> airTransitions(ChunkSnapshot snapshot, int lx, int lz, int minHeight, int maxHeight) {
        List<Integer> result = new ArrayList<>();
        Material below = snapshot.getBlockType(lx, minHeight, lz);

        for (int y = minHeight; y < maxHeight; y++) {
            Material here = snapshot.getBlockType(lx, y, lz);
            if (below.isSolid() && here.isAir()) {
                result.add(y - 1);
            }
            below = here;
        }
        return result;
    }

    private static void revert(Player p, BlockKey block) {
        // Send original block data back to player.
        p.sendBlockChange(block.loc(), block.data());
    }

    private static void revertAll(Player p) {
        // Get all original blocks
        Set<BlockKey> set = shown.get(p.getUniqueId());
        for (BlockKey b : set) {
            revert(p, b);
        }
    }

}
