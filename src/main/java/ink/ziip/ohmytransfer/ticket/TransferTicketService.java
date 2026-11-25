package ink.ziip.ohmytransfer.ticket;

import ink.ziip.ohmytransfer.config.Settings;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Issues the same versioned cookie for every caller. The caller owns the actual transfer. */
public final class TransferTicketService {

    private final SecureRandom random = new SecureRandom();

    public Settings.Target writeTicket(Settings settings, Player player, String targetId) throws Failure {
        return writeTicket(settings, targetId, player.getUniqueId(), player.getName(), player::storeCookie);
    }

    Settings.Target writeTicket(Settings settings, String targetId, UUID playerId, String playerName,
                                BiConsumer<NamespacedKey, byte[]> storeCookie) throws Failure {
        Settings.Target target = settings.servers().get(targetId);
        if (target == null) {
            throw new Failure("未知服务器: " + targetId, null);
        }
        if (targetId.equals(settings.serverId())) {
            throw new Failure(targetId + " 就是当前服务器", null);
        }

        try {
            long now = System.currentTimeMillis();
            byte[] nonce = new byte[TransferTicket.NONCE_LENGTH];
            random.nextBytes(nonce);
            TransferTicket ticket = new TransferTicket(settings.serverId(), targetId, playerId,
                    playerName, now, Math.addExact(now, settings.ticketTtlMillis()), nonce);
            // Store the cookie before the caller sends Transfer on the same connection.
            storeCookie.accept(settings.cookieKey(), TicketCodec.encode(ticket, settings.secret()));
            return target;
        } catch (RuntimeException error) {
            throw new Failure("无法写入跨服票据，请稍后重试", error);
        }
    }

    public static final class Failure extends Exception {
        private Failure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
