package ink.ziip.ohmytransfer.ticket;

import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers ticket nonces that were already accepted, until the ticket could no longer be valid anyway.
 */
public final class NonceCache {

    private final Map<String, Long> used = new ConcurrentHashMap<>();

    /**
     * @param forgetAtMillis time after which the nonce can be dropped (ticket expiry + skew)
     * @return {@code true} if the nonce was unused and is now marked as used
     */
    public boolean markUsed(byte[] nonce, long forgetAtMillis) {
        return used.putIfAbsent(HexFormat.of().formatHex(nonce), forgetAtMillis) == null;
    }

    public void purgeExpired(long nowMillis) {
        used.values().removeIf(forgetAt -> forgetAt < nowMillis);
    }

    public int size() {
        return used.size();
    }
}
