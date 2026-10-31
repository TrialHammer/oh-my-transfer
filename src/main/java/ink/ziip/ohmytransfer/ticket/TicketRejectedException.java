package ink.ziip.ohmytransfer.ticket;

public final class TicketRejectedException extends Exception {

    public enum Reason {
        MALFORMED,
        UNSUPPORTED_VERSION,
        BAD_SIGNATURE,
        WRONG_TARGET,
        WRONG_PLAYER,
        NOT_YET_VALID,
        EXPIRED,
        REPLAYED
    }

    private final Reason reason;

    public TicketRejectedException(Reason reason, String detail) {
        super(reason + (detail == null ? "" : ": " + detail));
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
