package com.reactor.netty.http2.client;

import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class LoadTest {

    private MeterRegistry registry;
    private AtomicInteger reqCounter;
    private AtomicInteger reqInitiatedCounter;
    private AtomicInteger resCounter;
    private AtomicInteger serverCounter;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        reqCounter = new AtomicInteger(1);
        reqInitiatedCounter = new AtomicInteger(1);
        resCounter = new AtomicInteger(1);
        serverCounter = new AtomicInteger(1);
    }

    @Test
    void testTimeForLoad() {
        long start = System.currentTimeMillis();
        LoadHandler.Load();
        long end =System.currentTimeMillis();
        System.out.println("Time taken: " + (end-start));
    }

    @Test
    void testConnectionProviderMetricsUnderLoad() throws Exception {

        System.setProperty("reactor.netty.ioWorkerCount", "4");

        DisposableServer disposableServer =
                HttpServer.create()
                        .handle((req, res) -> {
                            System.out.println("Server rec request " + req.uri());
                            return res.sendString(Mono.just("Hello " + req.uri()));
                        })
                        .bindNow();

        ConnectionProvider provider = ConnectionProvider.builder("test")
                .maxConnections(100)
                .metrics(true)
                .maxIdleTime(Duration.ofSeconds(60))
                .pendingAcquireMaxCount(-1)
                .pendingAcquireTimeout(Duration.ofMillis(50))
                .build();

        AtomicBoolean addLoad = new AtomicBoolean(true);


        final HttpClient client = createClient(provider, disposableServer.port())
                .doOnChannelInit((observer, channel, address) -> {
                    if (addLoad.get() && channel.pipeline().get("my-load") == null) {
                        channel.pipeline().addFirst("my-load", new LoadHandler());
                    }
                })
                .doOnRequest((req, conn) -> {
                    if(addLoad.get()) {
                        LoadHandler.Load();
                        printConnectionMetrics("Requests Sent " + reqCounter.getAndIncrement());
                    }
                })
                .doOnResponse((res,conn) -> printConnectionMetrics("Responses Rec " + resCounter.getAndIncrement()));

        final Runnable runnable = () -> callDownstream(client, 20, Duration.ofMillis(1000));
        Thread[] secondary = new Thread[6];

        for(int i=0;i<secondary.length;i++){
            secondary[i] = new Thread(runnable);
            secondary[i].start();
            Thread.sleep(Duration.ofSeconds(10).toMillis());
        }

        // Wait for sometime before sending midway requests
        Thread.sleep(Duration.ofMinutes(2).toMillis());

        Thread midway = new Thread(runnable);
        midway.start();

        Thread.sleep(Duration.ofMinutes(6).toMillis());

        System.out.println("\nSending final req");
        callDownstream(client, 1, Duration.ZERO);
        Thread.sleep(Duration.ofSeconds(30).toMillis());
    }

    private void callDownstream(HttpClient client, int numCalls, Duration delay) {
        Flux.range(1, numCalls)
                .map(integer -> {
                    System.out.println("\nInitiating request no. " + reqInitiatedCounter.getAndIncrement());
                    return reqInitiatedCounter.get();
                })
                .flatMap(request -> client
                        .get()
                        .uri("/"+ request)
                        .responseContent()
                        .aggregate()
                        .asString()
                        .onErrorResume(throwable -> true, throwable -> Mono.just(throwable.getMessage()))
                )
                .subscribe(System.out::println);
    }

    private void printConnectionMetrics(String prefix) {
        final Measurement totalConnections = Objects.requireNonNull(Metrics.globalRegistry.find("reactor.netty.connection.provider.total.connections")
                .meter())
                .measure().iterator().next();
        final Measurement activeConnections = Objects.requireNonNull(Metrics.globalRegistry.find("reactor.netty.connection.provider.active.connections")
                .meter())
                .measure().iterator().next();
        final Measurement idleConnections = Objects.requireNonNull(Metrics.globalRegistry.find("reactor.netty.connection.provider.idle.connections")
                .meter())
                .measure().iterator().next();
        final Measurement pendingConnections = Objects.requireNonNull(Metrics.globalRegistry.find("reactor.netty.connection.provider.pending.connections")
                .meter())
                .measure().iterator().next();

        System.out.println("\n"+ prefix+"\nTotal connections: " + totalConnections.getValue()
                + "\nActive connections: " + activeConnections.getValue()
                + "\nPending connections: " + pendingConnections.getValue()
                + "\nIdle connections: " + idleConnections.getValue());
    }

    public HttpClient createClient(ConnectionProvider provider, int port) {
        return HttpClient.create(provider)
                .port(port);
    }
}

class LoadHandler extends ChannelOutboundHandlerAdapter {

    @SuppressWarnings({"java:S2211", "java:S5612"})
    @Override
    public void connect(ChannelHandlerContext ctx,
                        SocketAddress remoteAddress,
                        SocketAddress localAddress,
                        ChannelPromise promise) throws Exception {
        promise.addListener(future ->
        {
            if(future.isSuccess()) {
                Load();
            }
        });

        //ctx.pipeline().remove("my-load");
        super.connect(ctx, remoteAddress, localAddress, promise);
    }

    public static void Load() {
        final Map<String, Integer> cache = new LinkedHashMap<String, Integer>() {
            // Preferring an LRU cache over a regular HashMap to prevent a scenario that may
            // cause the cache to grow unboundedly, just in case.  For instance, a service may not
            // be restarted for many years and many DNS updates happens over time.

            // I assume there is no service that calls more than 200 different downstream services.
            private static final int MAX_ENTRIES = 200;

            @Override
            protected boolean removeEldestEntry(Map.Entry eldest) {
                return size() > MAX_ENTRIES;
            }
        };

        String resp = "";
        for (int i = 500; i >= 1; i--) {
            for (int j = 99; j >= 1; j--) {
                resp += i+" "+j;
                if(cache.containsKey(resp)) {
                    cache.put(resp, cache.get(resp) + 1);
                } else {
                    cache.put(resp, 0);
                }
            }
        }
    }
}
