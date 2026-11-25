package ink.ziip.ohmytransfer.listener;

import ink.ziip.ohmytransfer.OhMyTransferPlugin;
import ink.ziip.ohmytransfer.config.Settings;
import ink.ziip.ohmytransfer.ticket.NonceCache;
import ink.ziip.ohmytransfer.ticket.TicketCodec;
import ink.ziip.ohmytransfer.ticket.TicketRejectedException;
import ink.ziip.ohmytransfer.ticket.TransferTicket;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerLoginConnection;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Receiving side: reads the ticket cookie during the login phase and refuses
 * players without a valid ticket before they can finish logging in.
 *
 * <p>Paper fires {@link AsyncPlayerPreLoginEvent} on an async authenticator
 * thread and holds the login until the listener returns, while
 * {@link PlayerLoginConnection#retrieveCookie} is explicitly supported during
 * the login phase. Blocking on the cookie future here therefore gates the
 * connection at PreLogin, before the player ever enters configuration or the
 * world.</p>
 *
 * <p>The ticket cookie is cleared as soon as the connection reaches the
 * configuration phase (with a join-time fallback), so a consumed ticket cannot
 * be presented again on a later connection.</p>
 */
public final class TicketVerifyListener implements Listener {

    private static final long CLEAR_COOKIE_TTL_MILLIS = 60_000;

    private final OhMyTransferPlugin plugin;
    private final NonceCache nonces;
    private final FloodgateSupport floodgateSupport;
    /** Players whose validated ticket cookie should be cleared at the next writable phase. */
    private final Map<UUID, Long> pendingCookieClears = new ConcurrentHashMap<>();

    public TicketVerifyListener(OhMyTransferPlugin plugin, NonceCache nonces) {
        this.plugin = plugin;
        this.nonces = nonces;
        this.floodgateSupport = FloodgateSupport.detect();
        if (floodgateSupport != null) {
            plugin.getLogger().info("Floodgate detected: Bedrock players can bypass ticket checks when "
                    + "receiver.floodgate-bypass is enabled.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }

        UUID playerId = event.getUniqueId();
        String name = event.getName();
        Settings settings = plugin.settings();

        if (settings.bypassUuids().contains(playerId)) {
            plugin.getLogger().info("Allowed " + name + ": bypass UUID");
            return;
        }

        if (settings.floodgateBypass() && floodgateSupport != null
                && floodgateSupport.isFloodgatePlayer(playerId)) {
            plugin.getLogger().info("Allowed " + name + ": Floodgate player");
            return;
        }

        PlayerLoginConnection connection = event.getConnection();
        if (connection == null) {
            // Only possible for legacy internal callers that construct the event without a connection.
            plugin.getLogger().warning("Denied " + name + ": PreLogin event has no login connection");
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, settings.denyMessage());
            return;
        }
        byte[] cookie = fetchCookie(connection, settings, name);
        if (cookie == null || cookie.length == 0) {
            if (settings.requireTicket()) {
                plugin.getLogger().info("Denied " + name + ": no ticket (transferred="
                        + event.isTransferred() + ")");
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, settings.denyMessage());
            }
            return;
        }

        try {
            TransferTicket ticket = TicketCodec.decode(cookie, settings.secret());
            TicketCodec.validate(ticket, settings.serverId(), playerId, name, settings.identityMatch(),
                    System.currentTimeMillis(), settings.clockSkewMillis(), nonces);
            pendingCookieClears.put(playerId, System.currentTimeMillis());
            plugin.getLogger().info("Allowed " + name + ": valid ticket from " + ticket.issuerId());
        } catch (TicketRejectedException e) {
            plugin.getLogger().warning("Denied " + name + ": " + e.getMessage());
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, settings.denyMessage());
        }
    }

    /**
     * Clears the consumed ticket as soon as the connection reaches the
     * configuration phase, where the cookie API is writable again. This keeps
     * the replay window closed even if the client disconnects before joining.
     */
    @EventHandler
    public void onConfigure(AsyncPlayerConnectionConfigureEvent event) {
        PlayerConfigurationConnection connection = event.getConnection();
        UUID playerId = connection.getProfile().getId();
        if (playerId == null || pendingCookieClears.remove(playerId) == null) {
            return;
        }
        try {
            connection.storeCookie(plugin.settings().cookieKey(), new byte[0]);
        } catch (IllegalStateException e) {
            plugin.getLogger().fine("Could not clear ticket cookie for "
                    + connection.getProfile().getName() + ": " + e.getMessage());
        }
    }

    /** Fallback for any join path that skips the configuration phase. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (pendingCookieClears.remove(event.getPlayer().getUniqueId()) == null) {
            return;
        }
        try {
            event.getPlayer().storeCookie(plugin.settings().cookieKey(), new byte[0]);
        } catch (IllegalStateException e) {
            plugin.getLogger().fine("Could not clear ticket cookie for "
                    + event.getPlayer().getName() + ": " + e.getMessage());
        }
    }

    public void purgeStaleCookieClears(long nowMillis) {
        pendingCookieClears.values().removeIf(createdAt -> createdAt + CLEAR_COOKIE_TTL_MILLIS < nowMillis);
    }

    /** Returns the ticket cookie, or {@code null} if the client has none or did not answer in time. */
    private byte[] fetchCookie(PlayerLoginConnection connection, Settings settings, String name) {
        try {
            return connection.retrieveCookie(settings.cookieKey())
                    .get(settings.cookieTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            plugin.getLogger().warning("Timed out waiting for ticket cookie from " + name);
        } catch (ExecutionException e) {
            plugin.getLogger().warning("Failed to read ticket cookie from " + name + ": " + e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }
}
