package ink.ziip.ohmytransfer.ticket;

import ink.ziip.ohmytransfer.config.Settings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TransferTicketServiceTest {
    private static final UUID PLAYER_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private final TransferTicketService service = new TransferTicketService();

    @Test
    void writesVersionOneCookieAcceptedByExistingStrictVerifier() throws Exception {
        Settings settings = settings();
        AtomicReference<byte[]> stored = new AtomicReference<>();
        Settings.Target target = service.writeTicket(settings, "beta", PLAYER_ID, "Player", (key, value) -> {
            assertEquals(settings.cookieKey(), key);
            assertNull(stored.getAndSet(value), "one cookie write only");
        });

        assertEquals(new Settings.Target("127.0.0.1", 25566), target);
        assertEquals(1, stored.get()[0]);
        TransferTicket ticket = TicketCodec.decode(stored.get(), settings.secret());
        assertEquals("alpha", ticket.issuerId());
        assertEquals(settings.ticketTtlMillis(), ticket.expiresAtMillis() - ticket.issuedAtMillis());
        NonceCache nonces = new NonceCache();
        TicketCodec.validate(ticket, "beta", PLAYER_ID, "Player", IdentityMatch.UUID,
                ticket.issuedAtMillis(), 0, nonces);
        TicketRejectedException replay = assertThrows(TicketRejectedException.class, () ->
                TicketCodec.validate(ticket, "beta", PLAYER_ID, "Player", IdentityMatch.UUID,
                        ticket.issuedAtMillis(), 0, nonces));
        assertEquals(TicketRejectedException.Reason.REPLAYED, replay.reason());
        TicketRejectedException expired = assertThrows(TicketRejectedException.class, () ->
                TicketCodec.validate(ticket, "beta", PLAYER_ID, "Player", IdentityMatch.UUID,
                        ticket.expiresAtMillis() + 1, 0, new NonceCache()));
        assertEquals(TicketRejectedException.Reason.EXPIRED, expired.reason());
    }

    @Test
    void unknownAndLocalTargetsNeverWrite() throws Exception {
        Settings settings = settings();
        for (String target : new String[]{"missing", "alpha"}) {
            assertThrows(TransferTicketService.Failure.class, () ->
                    service.writeTicket(settings, target, PLAYER_ID, "Player", (key, value) -> fail("unexpected write")));
        }
    }

    @Test
    void propagatesCookieWriteFailureWithoutAUsableTarget() throws Exception {
        Settings settings = settings();
        var failure = new IllegalStateException("disconnected client");
        var rejected = assertThrows(TransferTicketService.Failure.class, () ->
                service.writeTicket(settings, "beta", PLAYER_ID, "Player", (key, value) -> {
                    throw failure;
                }));
        assertSame(failure, rejected.getCause());
    }

    private static Settings settings() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                server-id: alpha
                secret: "%s"
                servers:
                  alpha:
                    host: "127.0.0.1"
                  beta:
                    host: "127.0.0.1"
                    port: 25566
                """.formatted(Base64.getEncoder().encodeToString(new byte[32])));
        return Settings.load(yaml);
    }
}
