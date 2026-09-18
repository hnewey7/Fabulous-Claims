package me.PantherYTac;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.ChunkSnapshot;
import org.bukkit.entity.Player;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData; 
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;

public class Visualization implements Listener {
    private record BlockKey(Location loc, BlockData data) {}

    public static Set<UUID> enabled = new HashSet<>();
    private static Map<UUID, Set<BlockKey>> updates = new HashMap<>();
    private static Map<UUID, Set<BlockKey>> existing = new HashMap<>();

    private static Object mutex = new Object();

    private static BukkitRunnable sendBlockUpdatesTask;
    private static BukkitRunnable checkPlayerLocationTask;

    public static void init(ClaimPlugin plugin) {
        enabled.clear();
        updates.clear();
        existing.clear();
        // Check online players and add to enabled
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (plugin.getManager().isVisualizationEnabled(p.getUniqueId())) {
                enabled.add(p.getUniqueId());
            }
        }
        // Create tasks
        createTasks();
        // Start tasks
        sendBlockUpdatesTask.runTaskTimer(plugin, 0L, 20L);
        checkPlayerLocationTask.runTaskTimer(plugin, 0L, 20L);
    }

    public static void toggle(Player p) {
        UUID uuid = p.getUniqueId();
        boolean newState;
        if (enabled.contains(uuid)) {
            enabled.remove(uuid);
            newState = false;
            Bukkit.getScheduler().runTask(ClaimPlugin.getInstance(), new RevertAllBlocksTask(uuid));
            p.sendMessage("§eClaim visualization disabled.");
        } else {
            enabled.add(uuid);
            newState = true;
            p.sendMessage("§aClaim visualization enabled.");
        }
        ClaimPlugin.getInstance().getManager().setVisualizationEnabled(uuid, newState);
    }

    public static void shutdown() {
        if (sendBlockUpdatesTask != null) {
            sendBlockUpdatesTask.cancel();
            sendBlockUpdatesTask = null;
        }
        if (checkPlayerLocationTask != null) {
            checkPlayerLocationTask.cancel();
            checkPlayerLocationTask = null;
        }
        enabled.clear();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        boolean isEnabled = ClaimPlugin.getInstance().getManager().isVisualizationEnabled(p.getUniqueId());
        if (isEnabled) {
            enabled.add(p.getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        enabled.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler 
    private void onBlockInteract(PlayerInteractEvent e) {
        // Get player and player id
        Player player = e.getPlayer();
        UUID player_id = player.getUniqueId();

        // Get block interacted with and create block key
        Block block = e.getClickedBlock();
        if (block == null) {
            return;
        }
        BlockKey interacted = new BlockKey(block.getLocation(), block.getBlockData());

        // Get existing blocks for player
        Set<BlockKey> existing_set = existing.get(player_id);
        if (existing_set == null) {
            return;
        }

        // Check if interacting with a modified block
        if (existing_set.contains(interacted)) {
            // Send back original data
            player.sendBlockChange(interacted.loc(), interacted.data());

            // Start a delayed task to remove block data from existing
            Bukkit.getScheduler().runTaskLater(ClaimPlugin.getInstance(), new RemoveExistingBlockTask(player_id, interacted), 60L);
        }
    }

    private static void createTasks() {
        // Task for sending block updates
        sendBlockUpdatesTask = createSendBlockUpdatesTask();
        // Task for checking player location
        checkPlayerLocationTask = createCheckPlayerLocationTask();
    }

    private static BukkitRunnable createSendBlockUpdatesTask() {
        return new BukkitRunnable() {
            @Override
            public void run() {
                synchronized (mutex) {
                    if (updates.isEmpty()) {
                        return;
                    }

                    for (Map.Entry<UUID, Set<BlockKey>> entry : new HashMap<>(updates).entrySet()) {
                        UUID player_id = entry.getKey();
                        Set<BlockKey> update_set = entry.getValue();

                        // Get the player
                        Player player = Bukkit.getPlayer(player_id);
                        if (player == null) {
                            continue;
                        }

                        // Send each update to the player
                        for (BlockKey update : update_set) {
                            player.sendBlockChange(update.loc(), update.data());
                        }
                        updates.remove(player_id);
                    }
                }
            }
        };
    }

    private static BukkitRunnable createCheckPlayerLocationTask() {
        return new BukkitRunnable() {
            @Override
            public void run() {
                // Get claim manager
                ClaimManager claim_manager = ClaimPlugin.getInstance().getManager();

                // Iterate through all players
                for (UUID player_id : enabled) {
                    // Get player and location
                    Player player = Bukkit.getPlayer(player_id);
                    Location player_location = player.getLocation();

                    // Check if location is in any claims
                    for (Claim claim : claim_manager.getClaims()) {
                        // Check location is inside claim and the world is correct
                        if (claim.isInside(player_location) && claim.getWorldName().equals(player_location.getWorld().getName())) {
                            // Render corners of claim
                            renderClaimCorners(player, claim);
                        } else {
                            Bukkit.getScheduler().runTask(ClaimPlugin.getInstance(), new RevertClaimBlocksTask(player_id, claim));
                        }
                    }
                }
            }
        };
    }

    private class RemoveExistingBlockTask implements Runnable {
        private UUID player_id;
        private BlockKey block;

        public RemoveExistingBlockTask(UUID player_id, BlockKey block) {
            this.player_id = player_id;
            this.block = block;
        }

        @Override
        public void run() {
            // Get existing set
            Set<BlockKey> existing_set = existing.get(this.player_id);
            if (existing_set == null) {
                return;
            }

            // Remove existing block
            existing_set.remove(this.block);
            if (existing_set.isEmpty()) {
                existing.remove(this.player_id);
            }
        }
    }

    public static class RevertClaimBlocksTask implements Runnable {
        private UUID player_id;
        private Claim claim;

        public RevertClaimBlocksTask(UUID player_id, Claim claim) {
            this.player_id = player_id;
            this.claim = claim;
        }

        @Override 
        public void run() {
            synchronized (mutex) {
                // Get existing set
                Set<BlockKey> existing_set = existing.get(this.player_id);
                if (existing_set == null) {
                    return;
                }
                
                // Get player
                Player player = Bukkit.getPlayer(this.player_id);
                if (player == null) {
                    return;
                }
                
                // Send all updates
                for (BlockKey update : new HashSet<>(existing_set)) {
                    if (claim.isInside(update.loc())) {
                        player.sendBlockChange(update.loc(), update.data());
                        existing_set.remove(update);
                    }
                }

                // Clean up pending updates
                Set<BlockKey> pending = updates.get(player_id);
                if (pending != null) {
                    for (BlockKey old_update : new HashSet<>(updates.get(player_id))) {
                        if (claim.isInside(old_update.loc())) {
                            updates.get(player_id).remove(old_update);
                        }
                    }
                }
            }
        }
    }

    private static class RevertAllBlocksTask implements Runnable {
        private UUID player_id;

        public RevertAllBlocksTask(UUID player_id) {
            this.player_id = player_id;
        }

        @Override 
        public void run() {
            synchronized (mutex) {
                // Get existing set
                Set<BlockKey> existing_set = existing.get(this.player_id);
                if (existing_set == null) {
                    return;
                }
                
                // Get player
                Player player = Bukkit.getPlayer(this.player_id);
                if (player == null) {
                    return;
                }
                
                // Send all updates
                for (BlockKey update : existing_set) {
                    player.sendBlockChange(update.loc(), update.data());
                }
                existing.remove(this.player_id);
                
                // Clean up pending updates
                updates.remove(this.player_id);
            }
        }
    }

    private static void renderClaimCorners(Player player, Claim claim) {
        synchronized (mutex) {
            // Get world, player location and player id
            World world = player.getWorld();
            Location player_location = player.getLocation();
            UUID player_id = player.getUniqueId();

            // Set corner block type
            BlockData corner_type;
            if (claim.isOwner(player_id)) {
                corner_type = Material.GOLD_BLOCK.createBlockData();
            } else if (claim.isTrusted(player_id)) {
                corner_type = Material.DIAMOND_BLOCK.createBlockData();
            } else {
                corner_type = Material.REDSTONE_BLOCK.createBlockData();
            }

            // Get updates and existing shown blocks for player
            Set<BlockKey> update_set = updates.get(player_id);
            Set<BlockKey> existing_shown = existing.get(player_id);

            // Calculate corner locations
            int cx = claim.getCenterX();
            int cz = claim.getCenterZ();
            int sx = claim.getSizeX();
            int sz = claim.getSizeZ();

            int[] x_corners = {
                cx - sx / 2,
                cx - sx / 2,
                cx + sx / 2,
                cx + sx / 2
            };
            int[] z_corners = {
                cz - sz / 2,
                cz + sz / 2,
                cz - sz / 2,
                cz + sz / 2
            };

            List<BlockKey> new_existing = new ArrayList<>();
            List<BlockKey> new_updates = new ArrayList<>();

            for (int i = 0; i < 4; i++) {
                int x = x_corners[i];
                int z = z_corners[i];

                // Find block-to-air transitions
                List<Integer> transitions = getAirTransitions(world, x, z, player_location.getBlockY());
                
                // Iterate through transitions
                for (int y : transitions) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block == null) {
                        continue;
                    }
                    BlockKey existing_block = new BlockKey(block.getLocation(), block.getBlockData());

                    // Check if already shown
                    if (existing_shown != null && existing_shown.contains(existing_block)) {
                        continue;
                    }

                    BlockKey update = new BlockKey(block.getLocation(), corner_type);

                    new_existing.add(existing_block);
                    new_updates.add(update);
                }
            }

            // Update existing
            if (existing_shown == null) {
                existing.put(player_id, new HashSet<>(new_existing));
            } else {
                existing_shown.addAll(new_existing);
            }

            // Update the updates
            if (update_set == null) {
                updates.put(player_id, new HashSet<>(new_updates));
            } else {
                update_set.addAll(new_updates);
            }
        }    
    }

    private static List<Integer> getAirTransitions(World world, int x, int z, int player_height) {
        // Get chunk snapshot
        ChunkSnapshot snapshot = world.getChunkAt(x >> 4, z >> 4).getChunkSnapshot();

        // Get height bounds
        int min_height = world.getMinHeight();//Math.max(world.getMinHeight(), player_height - 10);
        int max_height = world.getMaxHeight();//Math.min(world.getMaxHeight(), player_height + 10);

        return getAirTransitions(snapshot, x & 15, z & 15, min_height, max_height);
    }

    private static List<Integer> getAirTransitions(ChunkSnapshot snapshot, int rx, int rz, int min_height, int max_height) {
        List<Integer> transitions = new ArrayList<Integer>();
        Material below = snapshot.getBlockType(rx, min_height, rz);

        for (int i = min_height; i < max_height; i++) {
            Material above = snapshot.getBlockType(rx, i, rz);
            if (below.isSolid() && !above.isSolid()) {
                transitions.add(i - 1);
            }
            below = above;
        }
        return transitions;
    }
}