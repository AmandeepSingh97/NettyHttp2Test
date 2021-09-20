package com.reactor.netty.http2.client;

import io.netty.handler.logging.LogLevel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.DisposableServer;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.transport.logging.AdvancedByteBufFormat;
import reactor.test.StepVerifier;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class Http2ClientTests {

    private static final DisposableServer http2Server = mkHttp2Server();

    private static final String RESPONSE_STR = "Hello!!!";


    static DisposableServer mkHttp2Server() {
        try {
            final SelfSignedCertificate ssc = new SelfSignedCertificate();
            final Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());

            final DisposableServer server = HttpServer.create()
                    .route(routes ->
                            routes.get("/conn", (req, resp) -> {
                                final AtomicReference<String> id = new AtomicReference<>();
                                final CountDownLatch latch = new CountDownLatch(1);
                                resp.withConnection(conn -> {
                                    id.set(conn.channel().id().asShortText());
                                    System.out.println("Serving with conn id: " + id.get());
                                    latch.countDown();
                                });
                                try {
                                    latch.await();
                                    return resp
                                            .addHeader("Content-Type", "text/plain")
                                            .sendString(Mono.just(id.get()));
                                } catch (final Exception e) {
                                    return resp.sendString(Mono.error(e));
                                }
                            })
                    )
                    .protocol(HttpProtocol.H2, HttpProtocol.HTTP11)
                    .secure(sslContextSpec -> sslContextSpec.sslContext(serverCtx))
                    .port(18967)
                    .wiretap(true).bindNow();
            System.out.println("Reactor Netty started on " + server.port());
            return server;
        } catch (CertificateException e) {
            throw new RuntimeException(e);
        }
    }

    @Test(dataProvider = "numberOfCalls")
    public void testHttp2ClientParallelCalls(int n, int delay) throws FileNotFoundException, InterruptedException {
        emptyFile("log-ConnectionPool.txt");
        HttpClient client = getHttp2Client(1, Duration.ofMillis(1), false);
        performNParallelCalls(client, n);
        int connections = getNumEvents("log-ConnectionPool.txt", "CONNECT:");
        System.out.println("Connections observed in doing " + n +" parallel calls is: " + connections);
    }

    @Test(dataProvider = "numberOfCalls")
    public void testHttp2ClientParallelCallsWithLimitedMaxConnections(int n, int delay) throws FileNotFoundException, InterruptedException {
        emptyFile("log-ConnectionPool.txt");
        HttpClient client = getHttp2Client(5, Duration.ofMillis(1000), false);
        performNParallelCalls(client, n);
        int connections = getNumEvents("log-ConnectionPool.txt", "CONNECT:");
        System.out.println("Connections observed in doing " + n +" parallel calls is: " + connections);
    }

    @Test(dataProvider = "numberOfCalls")
    public void testHttp2ClientSequentialCalls(int n, int delay) throws FileNotFoundException {

        emptyFile("log-ConnectionPool.txt");
        HttpClient client = getHttp2Client(1, Duration.ofMillis(1), false);
        performNSequentialCalls(client, n, delay);
        int connections = getNumEvents("log-ConnectionPool.txt", "CONNECT:");
        System.out.println("Connections observed in doing " + n +" sequential calls with delay " + delay + " is: " + connections);
    }


    private HttpClient getHttp2Client(int maxConnections, Duration maxLifeTime, boolean useHTTP1) {
        ConnectionProvider provider = ConnectionProvider.builder("MyTestPool")
                .maxLifeTime(maxLifeTime)
                .maxConnections(maxConnections)
                .build();

        Http2SslContextSpec clientCtx =
                Http2SslContextSpec.forClient()
                        .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

        final HttpClient client =  HttpClient.create(provider)
                .secure(sslContextSpec -> sslContextSpec.sslContext(clientCtx))
                .protocol(useHTTP1 ? HttpProtocol.HTTP11 : HttpProtocol.H2)
                .wiretap("http2PoolTest", LogLevel.INFO, AdvancedByteBufFormat.HEX_DUMP);;
        return client;
    }

    private List<String> performNParallelCalls(HttpClient httpClient, int n) {
        return Flux.range(0, n)
                .parallel()
                .flatMap(item -> getServerHello(httpClient))
                .sequential()
                .collectList()
                .block();
    }

    private List<String> performNSequentialCalls(HttpClient httpClient, int n, long delay) {
        return Flux.range(0, n)
                .delayElements(Duration.ofMillis(delay))
                .flatMap(item -> getServerHello(httpClient))
                .collectList()
                .block();
    }

    private Mono<String> getServerHello(HttpClient httpClient) {
        return httpClient
                .get()
                .uri("https://localhost:" + http2Server.port()+"/conn")
                .responseContent()
                .aggregate()
                .asString();
    }

    private int getNumEvents(String path, String filter) throws FileNotFoundException {
        File logs = new File(path);
        Scanner st = new Scanner(logs);
        int connections = 0;
        while (st.hasNextLine()) {
            String line = st.nextLine();
            if (line.contains(filter)) {
                connections++;
            }
        }
        return connections;
    }

    private void emptyFile(String filePath) throws FileNotFoundException {
        File file = new File(filePath);
        PrintWriter writer = new PrintWriter(file);
        writer.print("");
        writer.close();
    }

    @DataProvider(name = "numberOfCalls")
    public Object[][] numberOfCallsWithDelay() {
        return new Object[][] {
                { 5 , 10},
                { 10, 5},
                { 20, 10},
                { 50, 10},
                { 5000, 10}
        };
    }
}
