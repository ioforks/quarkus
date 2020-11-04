package io.quarkus.vertx.http.runtime.devmode;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.jboss.logging.Logger;

import io.quarkus.arc.Arc;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.dev.console.DevConsoleManager;
import io.quarkus.dev.console.DevConsoleRequest;
import io.quarkus.dev.console.DevConsoleResponse;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * This is a Handler running in the regular runtime Vert.x instance
 * and what it does is to take the Vert.x request coming from client (under /@dev/)
 * and create the DevConsoleRequest that ends up being sent to the Netty Virtual Channel
 * which is eventually piped into the Netty event loop that powers the Dev Vert.x instance.
 */
public class DevConsoleFilter implements Handler<RoutingContext> {

    private static final Logger log = Logger.getLogger(DevConsoleFilter.class);
    public static final Function<String, Object> RESOLVER = new Function<String, Object>() {
        @Override
        public Object apply(String s) {
            InstanceHandle<Object> bean = Arc.container().instance(s);
            return bean.isAvailable() ? bean.get() : null;
        }
    };

    @Override
    public void handle(RoutingContext event) {
        //TODO: fixme, should be set on startup
        DevConsoleManager.setResolver(RESOLVER);
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, String> entry : event.request().headers()) {
            headers.put(entry.getKey(), event.request().headers().getAll(entry.getKey()));
        }
        if (event.request().isEnded()) {
            DevConsoleRequest request = new DevConsoleRequest(event.request().rawMethod(), event.request().path(), headers,
                    new byte[0]);
            setupFuture(event, request.getResponse());
            DevConsoleManager.sentRequest(request);
        } else {
            event.request().bodyHandler(new Handler<Buffer>() {
                @Override
                public void handle(Buffer body) {
                    DevConsoleRequest request = new DevConsoleRequest(event.request().rawMethod(), event.request().path(),
                            headers, body.getBytes());
                    setupFuture(event, request.getResponse());
                    DevConsoleManager.sentRequest(request);
                }
            });
        }

    }

    private void setupFuture(RoutingContext event, CompletableFuture<DevConsoleResponse> response) {
        response.handle(new BiFunction<DevConsoleResponse, Throwable, Object>() {
            @Override
            public Object apply(DevConsoleResponse devConsoleResponse, Throwable throwable) {
                if (throwable != null) {
                    log.error("Failed to handle dev console request", throwable);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    throwable.printStackTrace(new PrintWriter(baos));
                    event.response().setStatusCode(500).end(Buffer.buffer(baos.toByteArray()));
                } else {
                    for (Map.Entry<String, List<String>> entry : devConsoleResponse.getHeaders().entrySet()) {
                        event.response().headers().add(entry.getKey(), entry.getValue());
                    }
                    event.response().setStatusCode(devConsoleResponse.getStatus())
                            .end(Buffer.buffer(devConsoleResponse.getBody()));
                }
                return null;
            }
        });

    }
}