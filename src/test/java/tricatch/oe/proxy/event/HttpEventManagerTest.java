package tricatch.oe.proxy.event;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class HttpEventManagerTest {
    @Test
    void workers_startOnlyWhenFirstConsumerIsAdded() {
        HttpEventManager manager = HttpEventManager.getInstance();
        // The singleton may already have been started by another test; shutdown() resets it.
        manager.shutdown();
        assertThat(manager.isWorkersStarted()).isFalse();

        // hasSubscriber() and enqueue() must not start threads; enqueue drops the event.
        assertThat(manager.hasSubscriber("lazy-client")).isFalse();
        manager.enqueue(new HttpEvent("lazy-client", "rid", HttpEventType.REQ_HEADER));
        assertThat(manager.isWorkersStarted()).isFalse();

        HttpEventConsumer consumer = new HttpEventConsumer() {
            public String getClientId() { return "lazy-client"; }
            public String getChannelId() { return "c"; }
            public void process(HttpEvent event) { }
        };
        manager.addEventConsumer(consumer);
        try {
            assertThat(manager.isWorkersStarted()).isTrue();
        } finally {
            manager.removeEventConsumer(consumer);
        }
    }


    @Test
    void oneFailingChannel_doesNotDeregisterOtherChannelsForTheSameClient() throws Exception {
        HttpEventManager manager = HttpEventManager.getInstance();
        String clientId = "test-client-" + UUID.randomUUID();

        List<HttpEvent> receivedByGood = new CopyOnWriteArrayList<>();
        AtomicInteger badCallCount = new AtomicInteger(0);

        HttpEventConsumer bad = new HttpEventConsumer() {
            public String getClientId() { return clientId; }
            public String getChannelId() { return "bad"; }
            public void process(HttpEvent event) throws IOException {
                badCallCount.incrementAndGet();
                throw new IOException("simulated channel failure");
            }
        };
        HttpEventConsumer good = new HttpEventConsumer() {
            public String getClientId() { return clientId; }
            public String getChannelId() { return "good"; }
            public void process(HttpEvent event) {
                receivedByGood.add(event);
            }
        };

        manager.addEventConsumer(bad);
        manager.addEventConsumer(good);

        try {
            for (int i = 0; i < 3; i++) {
                manager.enqueue(new HttpEvent(clientId, "rid" + i, HttpEventType.REQ_HEADER));
            }

            waitUntil(() -> receivedByGood.size() >= 3, 5000);

            // "good" must receive every event even though "bad" (registered under the same
            // clientId) throws on every one of its own — a failing channel must not take down
            // its sibling channels.
            assertThat(receivedByGood).hasSize(3);
            assertThat(badCallCount.get()).isGreaterThanOrEqualTo(1);
        } finally {
            manager.removeEventConsumer(bad);
            manager.removeEventConsumer(good);
        }
    }

    @Test
    void concurrentFirstRegistrationsForTheSameClient_neverLoseAChannel() throws Exception {
        // All of these channels are the first registrations for this brand-new clientId, so every
        // one of them races through the same get-or-create-ChannelConsumers window in
        // addEventConsumer().
        HttpEventManager manager = HttpEventManager.getInstance();
        String clientId = "test-client-" + UUID.randomUUID();

        int channelCount = 50;
        Map<String, List<HttpEvent>> received = new ConcurrentHashMap<>();
        List<HttpEventConsumer> consumers = new ArrayList<>();
        for (int i = 0; i < channelCount; i++) {
            String channelId = "ch" + i;
            received.put(channelId, new CopyOnWriteArrayList<>());
            consumers.add(new HttpEventConsumer() {
                public String getClientId() { return clientId; }
                public String getChannelId() { return channelId; }
                public void process(HttpEvent event) { received.get(channelId).add(event); }
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(channelCount);
        CountDownLatch ready = new CountDownLatch(channelCount);
        CountDownLatch go = new CountDownLatch(1);

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (HttpEventConsumer c : consumers) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    manager.addEventConsumer(c);
                    return null;
                }));
            }
            ready.await();
            go.countDown();
            for (Future<?> f : futures) f.get(5, TimeUnit.SECONDS);

            manager.enqueue(new HttpEvent(clientId, "rid", HttpEventType.REQ_HEADER));

            waitUntil(() -> received.values().stream().allMatch(l -> l.size() >= 1), 5000);

            // Every channel registered above must have received the event; a lost registration
            // would show up here as an empty list for that channel.
            for (var entry : received.entrySet()) {
                assertThat(entry.getValue()).as("channel %s", entry.getKey()).hasSize(1);
            }
        } finally {
            pool.shutdownNow();
            for (HttpEventConsumer c : consumers) manager.removeEventConsumer(c);
        }
    }

    @Test
    void registrationRacingConcurrentDispatchCleanup_neverLosesTheNewChannel() throws Exception {
        // Races addEventConsumer() (new channel) against dispatch()'s failure-cleanup path (a
        // different, always-failing channel on the same clientId being removed) many times, each
        // round on a fresh clientId so no round can leave residue for the next.
        HttpEventManager manager = HttpEventManager.getInstance();
        int rounds = 100;
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            for (int i = 0; i < rounds; i++) {
                final int round = i;
                String clientId = "race-client-" + UUID.randomUUID();
                List<HttpEvent> received = new CopyOnWriteArrayList<>();

                HttpEventConsumer failing = new HttpEventConsumer() {
                    public String getClientId() { return clientId; }
                    public String getChannelId() { return "failing"; }
                    public void process(HttpEvent event) throws IOException { throw new IOException("boom"); }
                };
                manager.addEventConsumer(failing);

                HttpEventConsumer good = new HttpEventConsumer() {
                    public String getClientId() { return clientId; }
                    public String getChannelId() { return "good"; }
                    public void process(HttpEvent event) { received.add(event); }
                };

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch go = new CountDownLatch(1);
                Future<?> f1 = pool.submit(() -> {
                    ready.countDown();
                    await(go);
                    // Triggers dispatch()'s failure-cleanup path for "failing".
                    manager.enqueue(new HttpEvent(clientId, "rid-fail-" + round, HttpEventType.REQ_HEADER));
                });
                Future<?> f2 = pool.submit(() -> {
                    ready.countDown();
                    await(go);
                    manager.addEventConsumer(good);
                });
                ready.await();
                go.countDown();
                f1.get(2, TimeUnit.SECONDS);
                f2.get(2, TimeUnit.SECONDS);

                // A later event must still reach "good" — proving its registration above wasn't
                // lost to the concurrent cleanup of "failing".
                manager.enqueue(new HttpEvent(clientId, "rid-followup-" + round, HttpEventType.REQ_HEADER));
                waitUntil(() -> !received.isEmpty(), 2000);

                assertThat(received).as("round %d", i).isNotEmpty();

                manager.removeEventConsumer(good);
                manager.removeEventConsumer(failing);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }
}
