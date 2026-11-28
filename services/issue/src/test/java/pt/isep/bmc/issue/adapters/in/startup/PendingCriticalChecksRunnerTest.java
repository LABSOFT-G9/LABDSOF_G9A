package pt.isep.bmc.issue.adapters.in.startup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.issue.application.port.in.CompletePendingCriticalChecksUseCase.Result;

/** US-14-02: the start-up pass never holds up the start, so reporting is unaffected by a long list. */
class PendingCriticalChecksRunnerTest {

    @Test
    void startReturnsAtOnceAndThePassFinishesInTheBackground() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        PendingCriticalChecksRunner runner = new PendingCriticalChecksRunner(() -> {
            await(release);
            return new Result(3, 1, 2);
        });

        runner.start();

        assertThat(runner.finished()).isNotDone();
        release.countDown();
        assertThat(runner.finished().get(5, TimeUnit.SECONDS)).contains(new Result(3, 1, 2));
        runner.destroy();
    }

    @Test
    void aFailedPassIsReportedAsEmptyAndRunsOnlyOncePerStart() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        PendingCriticalChecksRunner runner = new PendingCriticalChecksRunner(() -> {
            runs.incrementAndGet();
            throw new IllegalStateException("database unavailable");
        });

        runner.start();
        runner.start();

        assertThat(runner.finished().get(5, TimeUnit.SECONDS)).isEqualTo(Optional.empty());
        assertThat(runs).hasValue(1);
        runner.destroy();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
