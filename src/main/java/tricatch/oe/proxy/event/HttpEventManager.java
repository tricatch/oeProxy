package tricatch.oe.proxy.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tricatch.oe.proxy.event.consumer.HttpEventDropConsumer;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Manager for HTTP event pipeline
 * 
 * Flow: Request → HttpEvent → EventQueue → WorkerPool → IP Subscriber Map / DropConsumer
 * 
 * - EventQueue: Single shared queue for HttpEvents
 * - WorkerPool: 4~8 threads consuming from queue, started lazily on the first addEventConsumer()
 * - IP Subscriber Map: Registered consumers per client IP
 * - DropConsumer: Single instance for unregistered IPs
 *
 * This pipeline is an extension point for embedding applications (e.g. a live traffic monitor):
 * nothing in the proxy itself subscribes, and producers only build events while
 * hasSubscriber(clientId) is true. So no threads are created until an embedding application (or
 * the standalone --monitor option) registers its first consumer; a proxy that never subscribes
 * pays nothing for this class.
 */
public class HttpEventManager {

    private static final Logger logger = LoggerFactory.getLogger(HttpEventManager.class);

    private static final int DEFAULT_WORKER_COUNT = 6;
    private static final int MIN_WORKER_COUNT = 4;
    private static final int MAX_WORKER_COUNT = 8;

    private static HttpEventManager instance;

    private final EventQueue eventQueue;
    private final ClientConsumers clientConsumers;
    private final HttpEventConsumer defaultConsumer;
    private final int workerCount;
    private final Object workersLock = new Object();
    private volatile ExecutorService workerPool;   // null until the first addEventConsumer()
    private volatile boolean running = false;

    private HttpEventManager(int workerCount) {
        this.eventQueue = new EventQueue();
        this.clientConsumers = new ClientConsumers();
        this.defaultConsumer = new HttpEventDropConsumer();
        this.workerCount = Math.max(MIN_WORKER_COUNT, Math.min(MAX_WORKER_COUNT, workerCount));
    }

    /**
     * Start the worker pool if it isn't running yet (double-checked; safe to call concurrently).
     */
    private void ensureWorkersStarted() {
        if (workerPool != null) return;

        synchronized (workersLock) {
            if (workerPool != null) return;

            ExecutorService pool = Executors.newFixedThreadPool(workerCount, new ThreadFactory() {
                private int counter = 0;

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "HttpEventWorker-" + (++counter));
                    t.setDaemon(true);
                    return t;
                }
            });

            running = true;
            for (int i = 0; i < workerCount; i++) {
                pool.execute(this::workerLoop);
            }
            workerPool = pool;
            logger.info("HttpEventManager started: EventQueue > WorkerPool({} threads) > IP Subscriber / DropConsumer", workerCount);
        }
    }

    /** For tests: whether the worker pool has been started. */
    boolean isWorkersStarted() {
        return workerPool != null;
    }

    /**
     * Worker loop: take from EventQueue → dispatch to IP Subscriber or DropConsumer
     */
    private void workerLoop() {
        while (running) {
            try {
                HttpEvent event = eventQueue.take();
                dispatch(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Worker error: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Dispatch event: registered IP → Subscriber, else → DropConsumer.
     */
    private void dispatch(HttpEvent event) throws IOException {
        if (event == null || event.getClientId() == null) {
            logger.warn("Invalid HttpEvent: {}", event);
            return;
        }

        String clientId = event.getClientId();

        ChannelConsumers channelConsumers = clientConsumers.get(clientId);

        if (channelConsumers == null || channelConsumers.isEmpty()) {
            defaultConsumer.process(event);
            return;
        }

        for (var entry : channelConsumers.entrySet()) {
            String channelId = entry.getKey();
            HttpEventConsumer consumer = entry.getValue();
            try {
                consumer.process(event);
            } catch (Exception e) {
                // Only the failing channel is deregistered; other channels/tabs for the same
                // clientId keep receiving events. ConcurrentHashMap's iterator tolerates this
                // removal mid-loop.
                logger.warn("Consumer failed, removing: clientId={}, channelId={}, error={}",
                        clientId, channelId, e.getMessage(), e);
                // computeIfPresent makes "remove this channel, then drop the client entry if that
                // was the last one" atomic with respect to addEventConsumer()'s computeIfAbsent on
                // the same key: ConcurrentHashMap serializes compute-family calls per key, so a
                // concurrent registration for this clientId can no longer land in a ChannelConsumers
                // instance that this cleanup is about to (or just did) drop from the outer map.
                clientConsumers.computeIfPresent(clientId, (id, cc) -> {
                    cc.remove(channelId);
                    return cc.isEmpty() ? null : cc;
                });
            }
        }
    }

    /**
     * Initialize HttpEventManager with custom worker count (4~8). Only records the count; the
     * worker threads themselves start on the first addEventConsumer().
     */
    public static synchronized void initialize(int workerCount) {
        if (instance != null) {
            logger.warn("HttpEventManager already initialized");
            return;
        }
        instance = new HttpEventManager(workerCount);
    }

    /**
     * Get singleton instance (lazy init with default)
     */
    public static HttpEventManager getInstance() {
        if (instance == null) {
            synchronized (HttpEventManager.class) {
                if (instance == null) {
                    instance = new HttpEventManager(DEFAULT_WORKER_COUNT);
                    logger.info("HttpEventManager initialized with default worker count");
                }
            }
        }
        return instance;
    }

    /**
     * Cheap upfront check so callers on the request/response hot path (body relay, header
     * capture) can skip building a monitor payload entirely when nobody is watching this
     * clientId, instead of paying the copy/allocation cost and then having dispatch() hand the
     * event straight to DropConsumer.
     */
    public boolean hasSubscriber(String clientId) {
        if (clientId == null) return false;
        ChannelConsumers channelConsumers = clientConsumers.get(clientId);
        return channelConsumers != null && !channelConsumers.isEmpty();
    }

    /**
     * Enqueue HttpEvent to pipeline
     * Request → HttpEvent 생성 → EventQueue → WorkerPool → IP Subscriber / DropConsumer
     */
    public void enqueue(HttpEvent event) {
        if (event == null || event.getClientId() == null) {
            logger.warn("Invalid HttpEvent: {}", event);
            return;
        }

        // No worker pool means no consumer was ever registered; nothing would drain the queue, so
        // drop instead of letting events pile up.
        if (workerPool == null) {
            if( logger.isDebugEnabled() ) logger.debug("Workers not started, dropping event: cid={}, rid={}, type={}", event.getClientId(), event.getRid(), event.getType());
            return;
        }

        if( logger.isDebugEnabled() ) logger.debug( "EventQueue, cid={}, rid={}, type={}", event.getClientId(), event.getRid(), event.getType());

        if (!eventQueue.offer(event)) {
            logger.warn("EventQueue full, dropping event: clientId={}, rid={}", event.getClientId(), event.getRid());
        }
    }

    /**
     * Add subscriber for IP (clientId). The first call also starts the worker pool.
     */
    public void addEventConsumer(HttpEventConsumer consumer) {

        if (consumer == null) {
            throw new IllegalArgumentException("clientId and consumer cannot be null");
        }

        ensureWorkersStarted();

        String clientId = consumer.getClientId();
        String channelId = consumer.getChannelId();

        // computeIfAbsent() makes the get-or-create atomic: two threads registering the first two
        // channels for the same clientId at once would otherwise each create their own
        // ChannelConsumers and the later put() would silently drop the other's registration.
        ChannelConsumers channelConsumers = clientConsumers.computeIfAbsent(clientId, id -> new ChannelConsumers());

        channelConsumers.put(channelId, consumer);

        if( logger.isDebugEnabled() ) {
            logger.debug("Added subscriber for clientId={} / channelId={}", clientId, channelId);
        }
    }

    public void removeEventConsumer(HttpEventConsumer consumer){

        if (consumer == null ) return;

        String clientId = consumer.getClientId();
        String channelId = consumer.getChannelId();

        // Same check-then-act hazard as dispatch()'s cleanup: a concurrent addEventConsumer() for
        // this clientId could otherwise land its new channel in a ChannelConsumers instance this
        // unsubscribe is dropping. computeIfPresent keeps it atomic per key.
        clientConsumers.computeIfPresent(clientId, (id, cc) -> {
            cc.remove(channelId);
            return cc.isEmpty() ? null : cc;
        });

        if( logger.isDebugEnabled() ) {
            logger.debug("Removed subscriber for clientId={} / channelId={}", clientId, channelId);
        }
    }

    /**
     * Shutdown pipeline. Safe when workers were never started; a later addEventConsumer() starts
     * a fresh pool.
     */
    public void shutdown() {
        logger.info("Shutting down HttpEventManager");

        ExecutorService pool;
        synchronized (workersLock) {
            running = false;
            pool = workerPool;
            workerPool = null;
        }

        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        clientConsumers.clear();
    }
}
