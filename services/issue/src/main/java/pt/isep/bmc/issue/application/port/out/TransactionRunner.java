package pt.isep.bmc.issue.application.port.out;

import java.util.function.Supplier;

/**
 * Runs work in one database transaction. Any exception rolls the whole unit
 * back. Storage failures surface as
 * {@link pt.isep.bmc.issue.application.error.StorageUnavailableException}.
 */
public interface TransactionRunner {

    <T> T inTransaction(Supplier<T> work);

    <T> T readOnly(Supplier<T> work);
}
