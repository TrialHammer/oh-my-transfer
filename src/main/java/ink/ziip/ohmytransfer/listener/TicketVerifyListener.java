package ink.ziip.ohmytransfer.listener;

import ink.ziip.ohmytransfer.OhMyTransferPlugin;
import ink.ziip.ohmytransfer.config.Settings;
import ink.ziip.ohmytransfer.ticket.NonceCache;
import ink.ziip.ohmytransfer.ticket.TicketCodec;
import ink.ziip.ohmytransfer.ticket.TicketRejectedException;
import ink.ziip.ohmytransfer.ticket.TransferTicket;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Receiving side: reads the ticket cookie while the player is in the configuration phase and refuses
 * players without a valid ticket before they enter the world.
 *
 * <p>The check runs in {@link AsyncPlayerConnectionConfigureEvent}, which is async and holds the connection
 * until it returns. A denied player is disconnected there, and the verdict is also enforced via
 * {@link PlayerConnectionValidateLoginEvent} when the player exits configuration, as Paper documents that
 * disconnecting during connection events may be unreliable.
 */
public final class TicketVerifyListener implements Listener {

    private static final long VERDICT_TTL_MILLIS = 60_000;

    private record Verdict(boolean allowed, long createdAtMillis) {
    }

    private final OhMyTransferPlugin plugin;
    private final NonceCache nonces;
    private final Map<UUID, Verdict> verdicts = new ConcurrentHashMap<>();

    public TicketVerifyListener(OhMyTransferPlugin plugin, NonceCache nonces) {
        this.plugin = plugin;
        this.nonces = nonces;
    }

    @EventHandler
    public void onConfigure(AsyncPlayerConnectionConfigureEvent event) {
        PlayerConfigurationConnection connection = event.getConnection();
        UUID playerId = connection.getProfile().getId();
        if (playerId == null || isReconfiguring(playerId)) {
            return;
        }

        Settings settings = plugin.settings();
        boolean allowed = check(connection, settings, playerId);
        verdicts.put(playerId, new Verdict(allowed, System.currentTimeMillis()));
        if (!allowed) {
            connection.disconnect(settings.denyMessage());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onValidateLogin(PlayerConnectionValidateLoginEvent event) {
        if (!(event.getConnection() instanceof PlayerConfigurationConnection connection)) {
            return;
        }
        UUID playerId = connection.getProfile().getId();
        if (playerId == null || isReconfiguring(playerId)) {
            return;
        }

        Settings settings = plugin.settings();
        Verdict verdict = verdicts.remove(playerId);
        boolean allowed = verdict != null
                ? verdict.allowed()
                : !settings.requireTicket() || settings.bypassUuids().contains(playerId);
        if (!allowed) {
            event.kickMessage(settings.denyMessage());
        }
    }

    public void purgeStaleVerdicts(long nowMillis) {
        verdicts.values().removeIf(v -> v.createdAtMillis() + VERDICT_TTL_MILLIS < nowMillis);
    }

    /** Players that are already in the world and re-enter configuration were checked when they joined. */
    private boolean isReconfiguring(UUID playerId) {
        return plugin.getServer().getPlayer(playerId) != null;
    }

    private boolean check(PlayerConfigurationConnection connection, Settings settings, UUID playerId) {
        String name = connection.getProfile().getName();
        if (settings.bypassUuids().contains(playerId)) {
            plugin.getLogger().info("Allowed " + name + ": bypass UUID");
            return true;
        }

        byte[] cookie = fetchCookie(connection, settings, name);
        if (cookie == null || cookie.length == 0) {
            if (settings.requireTicket()) {
                plugin.getLogger().info("Denied " + name + ": no ticket (transferred="
                        + connection.isTransferred() + ")");
                return false;
            }
            return true;
        }

        try {
            TransferTicket ticket = TicketCodec.decode(cookie, settings.secret());
            TicketCodec.validate(ticket, settings.serverId(), playerId, name, settings.identityMatch(),
                    System.currentTimeMillis(), settings.clockSkewMillis(), nonces);
            // Overwrite the consumed ticket so it is not presented again on a later transfer.
            connection.storeCookie(settings.cookieKey(), new byte[0]);
            plugin.getLogger().info("Allowed " + name + ": valid ticket from " + ticket.issuerId());
            return true;
        } catch (TicketRejectedException e) {
            plugin.getLogger().warning("Denied " + name + ": " + e.getMessage());
            return false;
        }
    }

    /** Returns the ticket cookie, or {@code null} if the client has none or did not answer in time. */
    private byte[] fetchCookie(PlayerConfigurationConnection connection, Settings settings, String name) {
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
