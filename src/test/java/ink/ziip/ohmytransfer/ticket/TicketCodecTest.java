package ink.ziip.ohmytransfer.ticket;

import ink.ziip.ohmytransfer.ticket.TicketRejectedException.Reason;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketCodecTest {

    private static final byte[] SECRET = filled(32, (byte) 7);
    private static final UUID PLAYER = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final long NOW = 1_800_000_000_000L;
    private static final long SKEW = 5_000;

    private static TransferTicket ticket(String target, long issuedAt, long expiresAt, byte nonceSeed) {
        return new TransferTicket("lobby", target, PLAYER, "Notch", issuedAt, expiresAt,
                filled(TransferTicket.NONCE_LENGTH, nonceSeed));
    }

    private static TransferTicket valid() {
        return ticket("survival", NOW, NOW + 30_000, (byte) 1);
    }

    private static byte[] filled(int length, byte value) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, value);
        return bytes;
    }

    private static Reason rejection(org.junit.jupiter.api.function.Executable executable) {
        return assertThrows(TicketRejectedException.class, executable).reason();
    }

    @Test
    void roundTripPreservesFields() throws Exception {
        TransferTicket original = valid();
        byte[] encoded = TicketCodec.encode(original, SECRET);
        assertTrue(encoded.length < TicketCodec.MAX_COOKIE_BYTES);

        TransferTicket decoded = TicketCodec.decode(encoded, SECRET);
        assertEquals(original.issuerId(), decoded.issuerId());
        assertEquals(original.targetId(), decoded.targetId());
        assertEquals(original.playerId(), decoded.playerId());
        assertEquals(original.playerName(), decoded.playerName());
        assertEquals(original.issuedAtMillis(), decoded.issuedAtMillis());
        assertEquals(original.expiresAtMillis(), decoded.expiresAtMillis());
        assertArrayEquals(original.nonce(), decoded.nonce());

        TicketCodec.validate(decoded, "survival", PLAYER, "Notch", IdentityMatch.UUID, NOW + 1_000, SKEW, new NonceCache());
    }

    @Test
    void anyTamperedByteFailsSignature() {
        byte[] encoded = TicketCodec.encode(valid(), SECRET);
        for (int i = 0; i < encoded.length; i++) {
            byte[] tampered = encoded.clone();
            tampered[i] ^= 0x01;
            assertEquals(Reason.BAD_SIGNATURE, rejection(() -> TicketCodec.decode(tampered, SECRET)), "byte " + i);
        }
    }

    @Test
    void wrongSecretFailsSignature() {
        byte[] encoded = TicketCodec.encode(valid(), SECRET);
        assertEquals(Reason.BAD_SIGNATURE, rejection(() -> TicketCodec.decode(encoded, filled(32, (byte) 8))));
    }

    @Test
    void truncatedOrEmptyIsMalformed() {
        assertEquals(Reason.MALFORMED, rejection(() -> TicketCodec.decode(new byte[0], SECRET)));
        assertEquals(Reason.MALFORMED, rejection(() -> TicketCodec.decode(null, SECRET)));
        assertEquals(Reason.MALFORMED, rejection(() -> TicketCodec.decode(new byte[10], SECRET)));
    }

    @Test
    void wrongTargetIsRejected() {
        TransferTicket t = valid();
        assertEquals(Reason.WRONG_TARGET,
                rejection(() -> TicketCodec.validate(t, "creative", PLAYER, "Notch", IdentityMatch.UUID, NOW, SKEW, new NonceCache())));
    }

    @Test
    void wrongPlayerIsRejected() {
        TransferTicket t = valid();
        assertEquals(Reason.WRONG_PLAYER,
                rejection(() -> TicketCodec.validate(t, "survival", UUID.randomUUID(), "Notch", IdentityMatch.UUID, NOW, SKEW, new NonceCache())));
    }

    @Test
    void identityMatchModes() throws Exception {
        UUID otherUuid = UUID.randomUUID();
        // Same player, different UUID on the receiving server (e.g. online-mode source, offline-mode target).
        assertEquals(Reason.WRONG_PLAYER, rejection(() -> TicketCodec.validate(valid(), "survival", otherUuid,
                "Notch", IdentityMatch.UUID, NOW, SKEW, new NonceCache())));
        TicketCodec.validate(valid(), "survival", otherUuid, "Notch", IdentityMatch.NAME, NOW, SKEW, new NonceCache());
        TicketCodec.validate(valid(), "survival", otherUuid, "Notch", IdentityMatch.UUID_OR_NAME, NOW, SKEW,
                new NonceCache());
        TicketCodec.validate(valid(), "survival", PLAYER, "Renamed", IdentityMatch.UUID_OR_NAME, NOW, SKEW,
                new NonceCache());

        assertEquals(Reason.WRONG_PLAYER, rejection(() -> TicketCodec.validate(valid(), "survival", PLAYER,
                "Herobrine", IdentityMatch.NAME, NOW, SKEW, new NonceCache())));
        assertEquals(Reason.WRONG_PLAYER, rejection(() -> TicketCodec.validate(valid(), "survival", PLAYER,
                "notch", IdentityMatch.NAME, NOW, SKEW, new NonceCache())));
        assertEquals(Reason.WRONG_PLAYER, rejection(() -> TicketCodec.validate(valid(), "survival", otherUuid,
                "Herobrine", IdentityMatch.UUID_OR_NAME, NOW, SKEW, new NonceCache())));
    }

    @Test
    void expiryAndFutureIssueRespectSkew() throws Exception {
        TransferTicket t = valid();
        TicketCodec.validate(t, "survival", PLAYER, "Notch", IdentityMatch.UUID, t.expiresAtMillis() + SKEW, SKEW, new NonceCache());
        assertEquals(Reason.EXPIRED, rejection(() ->
                TicketCodec.validate(t, "survival", PLAYER, "Notch", IdentityMatch.UUID, t.expiresAtMillis() + SKEW + 1, SKEW, new NonceCache())));

        TicketCodec.validate(t, "survival", PLAYER, "Notch", IdentityMatch.UUID, NOW - SKEW, SKEW, new NonceCache());
        assertEquals(Reason.NOT_YET_VALID, rejection(() ->
                TicketCodec.validate(t, "survival", PLAYER, "Notch", IdentityMatch.UUID, NOW - SKEW - 1, SKEW, new NonceCache())));
    }

    @Test
    void nonceCanOnlyBeUsedOnce() throws Exception {
        NonceCache nonces = new NonceCache();
        TransferTicket t = valid();
        TicketCodec.validate(t, "survival", PLAYER, "Notch", IdentityMatch.UUID, NOW, SKEW, nonces);
        assertEquals(Reason.REPLAYED, rejection(() -> TicketCodec.validate(t, "survival", PLAYER, "Notch", IdentityMatch.UUID, NOW, SKEW, nonces)));

        TransferTicket other = ticket("survival", NOW, NOW + 30_000, (byte) 2);
        TicketCodec.validate(other, "survival", PLAYER, "Notch", IdentityMatch.UUID, NOW, SKEW, nonces);
    }

    @Test
    void failedChecksDoNotConsumeNonce() throws Exception {
        NonceCache nonces = new NonceCache();
        TransferTicket t = valid();
        rejection(() -> TicketCodec.validate(t, "creative", PLAYER, "Notch", IdentityMatch.UUID, NOW, SKEW, nonces));
        TicketCodec.validate(t, "survival", PLAYER, "Notch", IdentityMatch.UUID, NOW, SKEW, nonces);
    }

    @Test
    void purgeDropsOnlyExpiredNonces() {
        NonceCache nonces = new NonceCache();
        nonces.markUsed(filled(16, (byte) 1), NOW);
        nonces.markUsed(filled(16, (byte) 2), NOW + 10_000);
        nonces.purgeExpired(NOW + 1);
        assertEquals(1, nonces.size());
        assertTrue(nonces.markUsed(filled(16, (byte) 1), NOW + 20_000));
    }
}
