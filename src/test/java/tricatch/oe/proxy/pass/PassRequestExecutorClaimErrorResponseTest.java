package tricatch.oe.proxy.pass;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PassRequestExecutorClaimErrorResponseTest {

    @Test
    void onlyTheFirstCallerClaimsIt() {
        PassRequestExecutor executor = new PassRequestExecutor(null, 5000, 5000);

        assertThat(executor.claimErrorResponse()).isTrue();
        assertThat(executor.claimErrorResponse()).isFalse();
        assertThat(executor.claimErrorResponse()).isFalse();
    }

    @Test
    void underConcurrentRace_exactlyOneThreadWinsTheClaim() throws Exception {
        PassRequestExecutor executor = new PassRequestExecutor(null, 5000, 5000);

        int threadCount = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger(0);

        try {
            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    if (executor.claimErrorResponse()) {
                        winners.incrementAndGet();
                    }
                });
            }

            ready.await();
            go.countDown();
            pool.shutdown();
            pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(winners.get()).isEqualTo(1);
    }
}
