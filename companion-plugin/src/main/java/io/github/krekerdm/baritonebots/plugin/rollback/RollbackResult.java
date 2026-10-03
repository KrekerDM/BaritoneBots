package io.github.krekerdm.baritonebots.plugin.rollback;

/**
 * Outcome of one rollback request.
 *
 * @param via      {@code journal} or {@code command}
 * @param error    machine-readable reason when {@code ok} is false (constants below), else null
 * @param total    journal records considered
 * @param restored blocks set back to their {@code before} state; -1 when unknown ({@code command} mode)
 * @param skipped  positions changed by someone else since the bot touched them
 * @param failed   records that could not be applied (unknown world, invalid block data, chunk failed to load)
 * @param command  the console command that was run ({@code command} mode)
 */
public record RollbackResult(boolean ok, String via, String error, int total, int restored, int skipped,
                             int failed, String command) {
    public static final String VIA_JOURNAL = "journal";
    public static final String VIA_COMMAND = "command";

    /** Another rollback is still running. */
    public static final String BUSY = "busy";
    /** Journal mode while {@code journal.enabled} is false, or command mode without a command. */
    public static final String UNAVAILABLE = "unavailable";
    /** The window holds more records than {@code rollback.max-blocks}. */
    public static final String TOO_LARGE = "too_large";
    /** Target is not in the {@code bots} list. */
    public static final String NOT_A_BOT = "not_a_bot";
    /** Minutes outside 1..max. */
    public static final String BAD_MINUTES = "bad_minutes";
    /** Request came over the channel from a player that did not pass the handshake. */
    public static final String NOT_VERIFIED = "not_verified";
    /** The plugin was disabled while the rollback ran. */
    public static final String CANCELLED = "cancelled";
    /** The console command returned false or threw. */
    public static final String COMMAND_FAILED = "command_failed";
    /** Journal files could not be read. */
    public static final String ERROR = "error";

    public static RollbackResult failure(String via, String error) {
        return new RollbackResult(false, via, error, 0, 0, 0, 0, null);
    }

    public static RollbackResult journal(int total, int restored, int skipped, int failed) {
        return new RollbackResult(true, VIA_JOURNAL, null, total, restored, skipped, failed, null);
    }

    public static RollbackResult command(String command, boolean dispatched) {
        return new RollbackResult(dispatched, VIA_COMMAND, dispatched ? null : COMMAND_FAILED, 0, -1, 0, 0, command);
    }

    public RollbackResult cancelled() {
        return new RollbackResult(false, via, CANCELLED, total, restored, skipped, failed, command);
    }
}
