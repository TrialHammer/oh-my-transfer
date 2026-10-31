package ink.ziip.ohmytransfer.ticket;

import ink.ziip.ohmytransfer.ticket.TicketRejectedException.Reason;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;

/**
 * Binary ticket format:
 * <pre>
 * byte     version
 * UTF      issuerId
 * UTF      targetId
 * long     uuidMost, uuidLeast
 * UTF      playerName
 * long     issuedAtMillis
 * long     expiresAtMillis
 * byte[16] nonce
 * byte[32] HMAC-SHA256(secret, all preceding bytes)
 * </pre>
 */
public final class TicketCodec {

    public static final byte VERSION = 1;
    /** Vanilla clients/servers cap cookie payloads at 5 KiB. */
    public static final int MAX_COOKIE_BYTES = 5120;

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MAC_LENGTH = 32;

    private TicketCodec() {
    }

    public static byte[] encode(TransferTicket ticket, byte[] secret) {
        if (ticket.nonce().length != TransferTicket.NONCE_LENGTH) {
            throw new IllegalArgumentException("nonce must be " + TransferTicket.NONCE_LENGTH + " bytes");
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeByte(VERSION);
            out.writeUTF(ticket.issuerId());
            out.writeUTF(ticket.targetId());
            out.writeLong(ticket.playerId().getMostSignificantBits());
            out.writeLong(ticket.playerId().getLeastSignificantBits());
            out.writeUTF(ticket.playerName());
            out.writeLong(ticket.issuedAtMillis());
            out.writeLong(ticket.expiresAtMillis());
            out.write(ticket.nonce());
            out.write(hmac(secret, buffer.toByteArray()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byte[] bytes = buffer.toByteArray();
        if (bytes.length > MAX_COOKIE_BYTES) {
            throw new IllegalArgumentException("ticket exceeds cookie size limit: " + bytes.length);
        }
        return bytes;
    }

    /**
     * Parses a ticket and checks its signature. Does not check target, player, time or replay.
     */
    public static TransferTicket decode(byte[] bytes, byte[] secret) throws TicketRejectedException {
        if (bytes == null || bytes.length <= MAC_LENGTH || bytes.length > MAX_COOKIE_BYTES) {
            throw new TicketRejectedException(Reason.MALFORMED, "length " + (bytes == null ? -1 : bytes.length));
        }
        byte[] body = Arrays.copyOf(bytes, bytes.length - MAC_LENGTH);
        byte[] mac = Arrays.copyOfRange(bytes, bytes.length - MAC_LENGTH, bytes.length);
        if (!MessageDigest.isEqual(mac, hmac(secret, body))) {
            throw new TicketRejectedException(Reason.BAD_SIGNATURE, null);
        }

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
            byte version = in.readByte();
            if (version != VERSION) {
                throw new TicketRejectedException(Reason.UNSUPPORTED_VERSION, "version " + version);
            }
            String issuerId = in.readUTF();
            String targetId = in.readUTF();
            UUID playerId = new UUID(in.readLong(), in.readLong());
            String playerName = in.readUTF();
            long issuedAt = in.readLong();
            long expiresAt = in.readLong();
            byte[] nonce = in.readNBytes(TransferTicket.NONCE_LENGTH);
            if (nonce.length != TransferTicket.NONCE_LENGTH || in.available() != 0) {
                throw new TicketRejectedException(Reason.MALFORMED, "bad trailing data");
            }
            return new TransferTicket(issuerId, targetId, playerId, playerName, issuedAt, expiresAt, nonce);
        } catch (IOException e) {
            throw new TicketRejectedException(Reason.MALFORMED, e.getMessage());
        }
    }

    /**
     * Checks that a signature-verified ticket is meant for this server and player, is within its validity
     * window and has not been used before. Marks the nonce as used only if every other check passes.
     */
    public static void validate(TransferTicket ticket, String expectedTarget, UUID expectedPlayer, String expectedName,
                                IdentityMatch identityMatch, long nowMillis, long skewMillis, NonceCache nonces)
            throws TicketRejectedException {
        if (!ticket.targetId().equals(expectedTarget)) {
            throw new TicketRejectedException(Reason.WRONG_TARGET, ticket.targetId());
        }
        boolean uuidMatches = ticket.playerId().equals(expectedPlayer);
        boolean nameMatches = ticket.playerName().equals(expectedName);
        boolean identityMatches = switch (identityMatch) {
            case UUID -> uuidMatches;
            case NAME -> nameMatches;
            case UUID_OR_NAME -> uuidMatches || nameMatches;
        };
        if (!identityMatches) {
            throw new TicketRejectedException(Reason.WRONG_PLAYER, "ticket " + ticket.playerName() + "/"
                    + ticket.playerId() + ", joined as " + expectedName + "/" + expectedPlayer);
        }
        if (ticket.issuedAtMillis() - skewMillis > nowMillis) {
            throw new TicketRejectedException(Reason.NOT_YET_VALID, null);
        }
        if (ticket.expiresAtMillis() + skewMillis < nowMillis) {
            throw new TicketRejectedException(Reason.EXPIRED, null);
        }
        if (!nonces.markUsed(ticket.nonce(), ticket.expiresAtMillis() + skewMillis)) {
            throw new TicketRejectedException(Reason.REPLAYED, null);
        }
    }

    private static byte[] hmac(byte[] secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
