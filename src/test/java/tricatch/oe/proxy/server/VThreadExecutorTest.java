package tricatch.oe.proxy.server;

import org.junit.jupiter.api.Test;
import tricatch.oe.proxy.pass.Stopable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class VThreadExecutorTest {

    @Test
    void stopAll_stopsEveryRegisteredStopable_evenWhenEachOneThrows() throws Exception {
        // Every one of these throws from stop(). Iteration order over the backing
        // ConcurrentHashMap isn't controllable, so this is the only way to make the assertion
        // order-independent: under the bug (one try wrapping the whole loop), whichever Stopable
        // happens to be visited first aborts the rest, so exactly 1 of 5 would get stopped instead
        // of all 5.
        Set<String> stoppedNames = ConcurrentHashMap.newKeySet();
        String prefix = "vt-test-" + UUID.randomUUID() + "-";
        List<Thread> threads = new ArrayList<>();
        List<String> names = new ArrayList<>();

        try {
            for (int i = 0; i < 5; i++) {
                String name = "stopable-" + i;
                names.add(name);
                Thread t = VThreadExecutor.run(fakeStopable(name, stoppedNames), prefix + i);
                threads.add(t);
            }

            VThreadExecutor.stopAll();

            assertThat(stoppedNames).containsExactlyInAnyOrderElementsOf(names);
        } finally {
            for (Thread t : threads) VThreadExecutor.removeVirtualThread(t);
        }
    }

    private static Stopable fakeStopable(String name, Set<String> stoppedNames) {
        return new Stopable() {
            @Override public void run() { }

            @Override public void stop() {
                stoppedNames.add(name);
                throw new RuntimeException("simulated stop() failure: " + name);
            }

            @Override public String getName() { return name; }
        };
    }
}
