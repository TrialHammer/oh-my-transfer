package ink.ziip.ohmytransfer.ticket;

import java.util.UUID;

/**
 * A one-shot authorization for a single player to join a single target server.
 */
public record TransferTicket(
        String issuerId,
        String targetId,
        UUID playerId,
        String playerName,
        long issuedAtMillis,
        long expiresAtMillis,
        byte[] nonce
) {
    public static final int NONCE_LENGTH = 16;
}
