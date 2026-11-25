package ink.ziip.ohmytransfer.wormholes;

import ink.ziip.ohmytransfer.OhMyTransferPlugin;
import ink.ziip.ohmytransfer.config.Settings;
import ink.ziip.ohmytransfer.ticket.TransferTicketService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;

/** Adds a cookie to Wormholes' acknowledged native portal handoff without changing its transfer. */
public final class WormholesBridge implements Listener {

    private static final String PLUGIN_NAME = "Wormholes";
    private static final Set<String> COMMAND_LABELS = Set.of("wormholes", "wh", "wormhole");
    private static final Set<String> NON_TRANSFER_SUBCOMMANDS = Set.of("list", "export", "import", "remove", "help");

    private final OhMyTransferPlugin plugin;
    private final TransferTicketService tickets;
    private Listener portalListener;

    public WormholesBridge(OhMyTransferPlugin plugin, TransferTicketService tickets) {
        this.plugin = plugin;
        this.tickets = tickets;
    }

    public void reload() {
        unbind();
        if (!plugin.settings().wormholes().enabled()) {
            return;
        }

        Plugin wormholes = plugin.getServer().getPluginManager().getPlugin(PLUGIN_NAME);
        if (wormholes != null && wormholes.isEnabled()) {
            bind(wormholes);
        }
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (PLUGIN_NAME.equals(event.getPlugin().getName()) && plugin.settings().wormholes().enabled()) {
            unbind();
            bind(event.getPlugin());
        }
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (PLUGIN_NAME.equals(event.getPlugin().getName())) {
            unbind();
        }
    }

    public void shutdown() {
        unbind();
        HandlerList.unregisterAll(this);
    }

    private void unbind() {
        if (portalListener != null) {
            HandlerList.unregisterAll(portalListener);
            portalListener = null;
        }
    }

    private void bind(Plugin wormholes) {
        try {
            PortalApi api = PortalApi.load(wormholes);
            Listener listener = new Listener() {};
            plugin.getServer().getPluginManager().registerEvent(api.eventType(), listener, EventPriority.HIGHEST,
                    (ignored, event) -> onPortalTraverse(api, wormholes, event), plugin, true);
            portalListener = listener;
            plugin.getLogger().info("Wormholes portal ticket bridge registered (" + wormholes.getPluginMeta().getVersion() + ")");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            plugin.getLogger().log(Level.SEVERE, "Cannot hook Wormholes' cancellable portal API; no Wormholes tickets "
                    + "will be issued. Use the compatible Wormholes build or normal /omt.", error);
        }
    }

    private void onPortalTraverse(PortalApi api, Plugin wormholes, Event event) {
        Player player = null;
        try {
            Object context = api.getContext().invoke(event);
            if (!"CROSS_SERVER".equals(((Enum<?>) api.kind().invoke(context)).name())) {
                return;
            }
            player = (Player) api.traveler().invoke(context);
            if (event.isAsynchronous() || !plugin.getServer().isPrimaryThread() || !player.isOnline()) {
                deny(api, event, player, "玩家连接尚未就绪，无法签发跨服票据");
                return;
            }

            var destination = (Optional<?>) api.destination().invoke(context);
            String peer = destination.isPresent() ? (String) api.serverName().invoke(destination.get()) : "";
            if (peer == null || peer.isBlank()) {
                deny(api, event, player, "跨服传送门未配置目标服务器");
                return;
            }

            Settings settings = plugin.settings();
            if (!settings.wormholes().mapValid()) {
                deny(api, event, player, "Wormholes 目标映射配置无效，请联系管理员");
                return;
            }
            String targetId = settings.wormholes().targetId(peer);
            if (targetId.isBlank() || !settings.servers().containsKey(targetId) || targetId.equals(settings.serverId())) {
                deny(api, event, player, "Wormholes 目标 " + peer + " 未对应有效的 OMT 目标服");
                return;
            }

            if (!api.isDirect(wormholes, peer)) {
                deny(api, event, player, "此传送门不使用原生 Transfer，请联系管理员检查 Wormholes 转服模式");
                return;
            }
            if (!authenticate(api, event, player)) {
                return;
            }

            tickets.writeTicket(settings, player, targetId);
            plugin.getLogger().fine("Wormholes ticket written for " + player.getName() + " to " + targetId);
        } catch (TransferTicketService.Failure error) {
            deny(api, event, player, error.getMessage());
            if (error.getCause() != null) {
                plugin.getLogger().log(Level.WARNING, "Could not write Wormholes transfer ticket", error.getCause());
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            deny(api, event, player, "跨服桥接不可用，请联系管理员");
            plugin.getLogger().log(Level.WARNING, "Wormholes portal ticket check failed", error);
        }
    }

    private boolean authenticate(PortalApi api, Event event, Player player) {
        PluginManager plugins = plugin.getServer().getPluginManager();
        try {
            if (AuthMeAccess.authenticated(plugins.getPlugin("AuthMe"), player)) {
                return true;
            }
            deny(api, event, player, "请先登录 AuthMe，再使用跨服传送门");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            deny(api, event, player, "无法确认 AuthMe 登录状态，已取消跨服穿越");
            plugin.getLogger().log(Level.WARNING, "AuthMe login check failed", error);
        }
        return false;
    }

    private void deny(PortalApi api, Event event, Player player, String reason) {
        ((Cancellable) event).setCancelled(true);
        try {
            api.setCancelReason().invoke(event, reason);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Could not set Wormholes' cancellation reason", error);
        }
        if (player != null) {
            player.sendMessage(Component.text(reason, NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.settings().wormholes().enabled()
                || !plugin.getServer().getPluginManager().isPluginEnabled(PLUGIN_NAME)
                || !isUnsignedConnectCommand(event.getMessage())) {
            return;
        }
        String label = event.getMessage().strip().substring(1).split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        var command = plugin.getServer().getCommandMap().getCommand(label);
        if (command instanceof PluginIdentifiableCommand identified
                && !PLUGIN_NAME.equals(identified.getPlugin().getName())) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(Component.text("Wormholes 的 server connect 不签发 OMT 票据。请走跨服门或使用 /omt。", NamedTextColor.RED));
    }

    static boolean isUnsignedConnectCommand(String message) {
        String command = message.strip();
        if (!command.startsWith("/")) {
            return false;
        }
        String[] parts = command.substring(1).toLowerCase(Locale.ROOT).split("\\s+");
        if (parts.length < 3 || !parts[1].equals("server")) {
            return false;
        }
        String label = parts[0];
        if (label.startsWith("wormholes:")) {
            label = label.substring("wormholes:".length());
        }
        return COMMAND_LABELS.contains(label) && (parts[2].equals("connect")
                || parts.length == 3 && !parts[2].contains("=") && !NON_TRANSFER_SUBCOMMANDS.contains(parts[2]));
    }

    /** Avoids linking Wormholes classes when absent, including its nested runtime JAR on Paper. */
    private record PortalApi(Class<? extends Event> eventType, Method getContext, Method kind, Method traveler,
                             Method destination, Method serverName, Method setCancelReason, Method getSettings,
                             Method getNetworkConfig, Field transferMode, Method effectiveMode, Method getNetworkManager,
                             Method getPeer, Method resolveMethod) {

        static PortalApi load(Plugin wormholes) throws ReflectiveOperationException {
            ClassLoader loader = wormholes.getClass().getClassLoader();
            Class<? extends Event> event = Class.forName("art.arcane.wormholes.api.traversal.WormholesPortalTraverseEvent",
                    false, loader).asSubclass(Event.class);
            if (!Cancellable.class.isAssignableFrom(event)) {
                throw new IllegalStateException("Wormholes traversal event is not cancellable");
            }
            Method getContext = event.getMethod("getContext");
            Class<?> context = getContext.getReturnType();
            Class<?> destination = Class.forName("art.arcane.wormholes.api.traversal.TraversalDestination", false, loader);
            Method settings = wormholes.getClass().getMethod("getSettings");
            Method networkConfig = settings.getReturnType().getMethod("getNetwork");
            Method networkManager = wormholes.getClass().getMethod("getNetworkManager");
            Method peer = networkManager.getReturnType().getMethod("getPeer", String.class);
            Class<?> method = Class.forName("art.arcane.wormholes.network.PlayerTransferMethod", false, loader);
            return new PortalApi(event, getContext, context.getMethod("kind"), context.getMethod("traveler"),
                    context.getMethod("destination"), destination.getMethod("serverName"),
                    event.getMethod("setCancelReason", String.class), settings, networkConfig,
                    networkConfig.getReturnType().getField("transferMode"),
                    networkConfig.getReturnType().getMethod("effectiveTransferMode", String.class, String.class),
                    networkManager, peer, method.getMethod("resolve", peer.getReturnType(), String.class));
        }

        boolean isDirect(Plugin wormholes, String peerName) throws ReflectiveOperationException {
            Object network = getNetworkManager.invoke(wormholes);
            if (network == null) {
                return false;
            }
            Object peer = getPeer.invoke(network, peerName);
            if (peer == null) {
                return false;
            }
            Object config = getNetworkConfig.invoke(getSettings.invoke(wormholes));
            String mode = (String) effectiveMode.invoke(config, peerName, transferMode.get(config));
            return "DIRECT".equals(((Enum<?>) resolveMethod.invoke(null, peer, mode)).name());
        }
    }
}
