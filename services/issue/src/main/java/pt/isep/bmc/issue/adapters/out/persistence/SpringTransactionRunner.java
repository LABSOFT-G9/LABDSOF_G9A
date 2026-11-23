package pt.isep.bmc.issue.adapters.out.persistence;

import jakarta.persistence.PersistenceException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import pt.isep.bmc.issue.application.error.StorageUnavailableException;
import pt.isep.bmc.issue.application.port.out.TransactionRunner;

/**
 * {@link TransactionRunner} over Spring's transaction manager. Database
 * failures become {@link StorageUnavailableException}; everything else (domain
 * and application errors) passes through unchanged after the rollback.
 *
 * <p>Logs only exception class names: driver messages can contain values.
 */
@Component
class SpringTransactionRunner implements TransactionRunner {

    private static final Logger log = LoggerFactory.getLogger(SpringTransactionRunner.class);

    private final TransactionTemplate writes;
    private final TransactionTemplate reads;

    SpringTransactionRunner(PlatformTransactionManager transactionManager) {
        this.writes = new TransactionTemplate(transactionManager);
        this.reads = new TransactionTemplate(transactionManager);
        this.reads.setReadOnly(true);
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return run(writes, work);
    }

    @Override
    public <T> T readOnly(Supplier<T> work) {
        return run(reads, work);
    }

    private static <T> T run(TransactionTemplate template, Supplier<T> work) {
        try {
            return template.execute(status -> work.get());
        } catch (DataAccessException | TransactionException | PersistenceException e) {
            log.warn("Database operation failed: {} (root cause {})",
                    e.getClass().getName(), rootCause(e).getClass().getName());
            throw new StorageUnavailableException(e);
        }
    }

    private static Throwable rootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
