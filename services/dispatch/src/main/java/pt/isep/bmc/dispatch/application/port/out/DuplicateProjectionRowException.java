package pt.isep.bmc.dispatch.application.port.out;

/**
 * Another transaction inserted the same projection row first. The event is
 * then applied again against that row, by version.
 */
public final class DuplicateProjectionRowException extends RuntimeException {

    public DuplicateProjectionRowException() {
        super("Projection row already inserted", null, false, false);
    }
}
