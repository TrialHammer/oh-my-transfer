package ink.ziip.ohmytransfer.ticket;

/**
 * How the receiving server decides that the joining player is the one the ticket was issued for.
 */
public enum IdentityMatch {
    /** Ticket UUID must equal the UUID this server sees. Use when all servers share one UUID scheme. */
    UUID,
    /** Ticket player name must equal the name this server sees (case-sensitive). */
    NAME,
    /** Either the UUID or the name matches. */
    UUID_OR_NAME
}
