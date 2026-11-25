package ink.ziip.ohmytransfer.map;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import ink.ziip.ohmytransfer.OhMyTransferPlugin;
import ink.ziip.ohmytransfer.config.Settings;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static ink.ziip.ohmytransfer.map.MapCompatProtocol.VOXELMAP_SETTINGS_CHANNEL;
import static ink.ziip.ohmytransfer.map.MapCompatProtocol.WORLD_INFO_CHANNEL;
import static ink.ziip.ohmytransfer.map.MapCompatProtocol.XAERO_MINIMAP_CHANNEL;
import static ink.ziip.ohmytransfer.map.MapCompatProtocol.XAEROWORLDMAP_CHANNEL;

/** Publishes map identities on Paper's main thread, independently of transfer tickets. */
public final class MapCompatListener implements Listener, PluginMessageListener {

    private static final long XAERO_RETRY_TICKS = 20L;

    private final OhMyTransferPlugin plugin;
    private final Set<String> outgoing = new HashSet<>();
    private final Map<UUID, BukkitRunnable> xaeroRetries = new HashMap<>();
    private final Map<UUID, MapCompatProtocol.WorldInfoRequest> worldInfoFormats = new HashMap<>();

    private int joinRepeat;
    private boolean nativeVoxelMap;
    private byte[] voxelMapIdentity;

    public MapCompatListener(OhMyTransferPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        stop();
        Settings.MapCompat config = plugin.settings().mapCompat();
        if (!config.enabled()) {
            worldInfoFormats.clear();
            return;
        }

        boolean mapModCompanion = Bukkit.getPluginManager().isPluginEnabled("MapModCompanion")
                || Bukkit.getPluginManager().isPluginEnabled("mapmodcompanion");
        boolean nativeJourneyMap = Bukkit.getPluginManager().isPluginEnabled("journeymap");
        nativeVoxelMap = Bukkit.getPluginManager().isPluginEnabled("VoxelMap");
        joinRepeat = config.joinRepeat();

        if (config.xaero() && !mapModCompanion) {
            // Paper advertises incoming channels at login. Diorama waits for its world ID only
            // if it saw such an advertisement, already received an ID, or has used one before.
            register(XAERO_MINIMAP_CHANNEL, true);
            register(XAEROWORLDMAP_CHANNEL, true);
        }
        if (config.worldInfo() && !mapModCompanion && !nativeJourneyMap) {
            register(WORLD_INFO_CHANNEL, true);
        }
        if (!outgoing.contains(WORLD_INFO_CHANNEL)) {
            worldInfoFormats.clear();
        }

        if (!config.voxelMapServerIdentity().isEmpty() && !nativeVoxelMap) {
            voxelMapIdentity = MapCompatProtocol.voxelMapIdentity(config.voxelMapServerIdentity());
            register(VOXELMAP_SETTINGS_CHANNEL, false);
        }

        if (mapModCompanion && (config.xaero() || config.worldInfo())) {
            plugin.getLogger().info("MapModCompanion detected; leaving Xaero and worldinfo IDs to it.");
        } else if (nativeJourneyMap && config.worldInfo()) {
            plugin.getLogger().info("JourneyMap server plugin detected; leaving worldinfo IDs to it.");
        }
        if (nativeVoxelMap && !config.voxelMapServerIdentity().isEmpty()) {
            plugin.getLogger().info("VoxelMap server plugin detected; leaving VoxelMap settings to it.");
        }
        if (!outgoing.isEmpty()) {
            plugin.getLogger().info("Map compatibility enabled on " + String.join(", ", outgoing.stream().sorted().toList()));
            Bukkit.getOnlinePlayers().forEach(this::refresh);
        }
    }

    private void register(String channel, boolean incoming) {
        Messenger messenger = Bukkit.getMessenger();
        try {
            messenger.registerOutgoingPluginChannel(plugin, channel);
            if (incoming) {
                messenger.registerIncomingPluginChannel(plugin, channel, this);
            }
            outgoing.add(channel);
        } catch (IllegalArgumentException e) {
            messenger.unregisterOutgoingPluginChannel(plugin, channel);
            plugin.getLogger().warning("Could not register map channel " + channel + ": " + e.getMessage());
        }
    }

    private void stop() {
        xaeroRetries.values().forEach(BukkitRunnable::cancel);
        xaeroRetries.clear();
        Messenger messenger = Bukkit.getMessenger();
        for (String channel : outgoing) {
            messenger.unregisterIncomingPluginChannel(plugin, channel, this);
            messenger.unregisterOutgoingPluginChannel(plugin, channel);
        }
        outgoing.clear();
        voxelMapIdentity = null;
    }

    public void shutdown() {
        stop();
        worldInfoFormats.clear();
        HandlerList.unregisterAll(this);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (refresh(player)) {
            scheduleXaeroRetries(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChannelRegistered(PlayerRegisterChannelEvent event) {
        Player player = event.getPlayer();
        String channel = event.getChannel();
        if (!outgoing.contains(channel) || !player.isOnline()) {
            return;
        }
        switch (channel) {
            case XAERO_MINIMAP_CHANNEL, XAEROWORLDMAP_CHANNEL -> {
                player.sendPluginMessage(plugin, channel, xaeroPayload(player));
                scheduleXaeroRetries(player);
            }
            case WORLD_INFO_CHANNEL -> resendWorldInfo(player);
            case VOXELMAP_SETTINGS_CHANNEL -> player.sendPluginMessage(plugin, channel, voxelMapIdentity);
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        BukkitRunnable retry = xaeroRetries.remove(playerId);
        if (retry != null) {
            retry.cancel();
        }
        worldInfoFormats.remove(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        sendXaero(player);
        resendWorldInfo(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPostRespawn(PlayerPostRespawnEvent event) {
        // The respawn packet has been sent and the player is now in the destination world.
        sendXaero(event.getPlayer());
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!WORLD_INFO_CHANNEL.equals(channel) || !handlesWorldInfo(player)) {
            return;
        }
        MapCompatProtocol.WorldInfoRequest request =
                MapCompatProtocol.worldInfoRequest(message, player.getProtocolVersion());
        if (request != null) {
            worldInfoFormats.put(player.getUniqueId(), request);
            sendWorldInfo(player, request);
        }
    }

    private boolean refresh(Player player) {
        if (outgoing.isEmpty()) {
            return false;
        }
        if (canSend(player, VOXELMAP_SETTINGS_CHANNEL)) {
            player.sendPluginMessage(plugin, VOXELMAP_SETTINGS_CHANNEL, voxelMapIdentity);
        }
        resendWorldInfo(player);
        return sendXaero(player);
    }

    private void scheduleXaeroRetries(Player player) {
        if (joinRepeat <= 1) {
            return;
        }
        UUID playerId = player.getUniqueId();
        xaeroRetries.computeIfAbsent(playerId, id -> {
            BukkitRunnable retry = new BukkitRunnable() {
                private int remaining = joinRepeat - 1;

                @Override
                public void run() {
                    // Do not send on a replacement connection, and always use the current world.
                    if (Bukkit.getPlayer(playerId) != player || !sendXaero(player) || --remaining == 0) {
                        cancel();
                        xaeroRetries.remove(playerId, this);
                    }
                }
            };
            retry.runTaskTimer(plugin, XAERO_RETRY_TICKS, XAERO_RETRY_TICKS);
            return retry;
        });
    }

    private boolean sendXaero(Player player) {
        boolean minimap = canSend(player, XAERO_MINIMAP_CHANNEL);
        boolean worldMap = canSend(player, XAEROWORLDMAP_CHANNEL);
        if (!minimap && !worldMap) {
            return false;
        }
        byte[] payload = xaeroPayload(player);
        if (minimap) {
            player.sendPluginMessage(plugin, XAERO_MINIMAP_CHANNEL, payload);
        }
        if (worldMap) {
            player.sendPluginMessage(plugin, XAEROWORLDMAP_CHANNEL, payload);
        }
        return true;
    }

    private byte[] xaeroPayload(Player player) {
        return MapCompatProtocol.xaeroLevelMapProperties(mapWorldId(player.getWorld()));
    }

    private void resendWorldInfo(Player player) {
        MapCompatProtocol.WorldInfoRequest format = worldInfoFormats.get(player.getUniqueId());
        if (format != null && handlesWorldInfo(player)) {
            sendWorldInfo(player, format);
        }
    }

    private boolean handlesWorldInfo(Player player) {
        return outgoing.contains(WORLD_INFO_CHANNEL)
                && !(nativeVoxelMap && player.getListeningPluginChannels().contains(VOXELMAP_SETTINGS_CHANNEL));
    }

    private void sendWorldInfo(Player player, MapCompatProtocol.WorldInfoRequest format) {
        // A request can precede the client's channel advertisement. Remember its format and
        // answer on registration in that case instead of sending an unsolicited guessed format.
        if (canSend(player, WORLD_INFO_CHANNEL)) {
            String worldId = Integer.toString(mapWorldId(player.getWorld()));
            player.sendPluginMessage(plugin, WORLD_INFO_CHANNEL, MapCompatProtocol.worldInfoResponse(format, worldId));
        }
    }

    private boolean canSend(Player player, String channel) {
        return outgoing.contains(channel) && player.getListeningPluginChannels().contains(channel);
    }

    private int mapWorldId(World world) {
        return MapWorldIds.forServer(plugin.settings().serverId(), groupWorld(world).getUID());
    }

    private World groupWorld(World world) {
        if (MapWorldIds.isVanillaSibling(world.getKey(), world.getEnvironment())) {
            World overworld = Bukkit.getWorld(MapWorldIds.OVERWORLD);
            if (overworld != null) {
                return overworld;
            }
        }
        String base = MapWorldIds.legacyOverworldName(world.getName(), world.getEnvironment());
        World sibling = base == null ? null : Bukkit.getWorld(base);
        return sibling != null && sibling.getEnvironment() == World.Environment.NORMAL ? sibling : world;
    }
}
