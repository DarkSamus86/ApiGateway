package org.darksamus.apigateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.reactivestreams.Publisher;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

@Component
public class RedisResponseCacheFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RedisResponseCacheFilter.class);
    private static final String CACHE_KEY_PREFIX = "gateway:cache:";
    private static final List<String> CACHEABLE_METHODS = List.of("GET");
    private static final List<String> NO_CACHE_PATHS = List.of("/auth/", "/admin/");

    private final ReactiveStringRedisTemplate redisTemplate;

    @Value("${gateway.cache.enabled:true}")
    private boolean cacheEnabled;

    @Value("${gateway.cache.ttl:300}")
    private int ttlSeconds;

    public RedisResponseCacheFilter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!cacheEnabled) {
            return chain.filter(exchange);
        }

        String method = exchange.getRequest().getMethod().name();
        String path = exchange.getRequest().getURI().getPath();

        if (!CACHEABLE_METHODS.contains(method)) {
            return chain.filter(exchange);
        }

        for (String noCachePath : NO_CACHE_PATHS) {
            if (path.startsWith(noCachePath)) {
                return chain.filter(exchange);
            }
        }

        String cacheControl = exchange.getRequest().getHeaders().getFirst(HttpHeaders.CACHE_CONTROL);
        if (cacheControl != null && cacheControl.toLowerCase().contains("no-cache")) {
            return chain.filter(exchange);
        }

        String cacheKey = CACHE_KEY_PREFIX + exchange.getRequest().getURI().toString();

        return redisTemplate.opsForValue().get(cacheKey)
                .flatMap(cachedBody -> {
                    log.debug("Cache HIT for: {}", path);
                    ServerHttpResponse response = exchange.getResponse();
                    response.getHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
                    response.getHeaders().set("X-Cache", "HIT");
                    DataBuffer buffer = response.bufferFactory().wrap(cachedBody.getBytes(StandardCharsets.UTF_8));
                    return response.writeWith(Mono.just(buffer));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.debug("Cache MISS for: {}", path);
                    return chain.filter(exchange.mutate()
                            .response(new CachedResponseDecorator(exchange.getResponse(), cacheKey))
                            .build());
                }));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    private class CachedResponseDecorator extends ServerHttpResponseDecorator {

        private final String cacheKey;

        CachedResponseDecorator(ServerHttpResponse delegate, String cacheKey) {
            super(delegate);
            this.cacheKey = cacheKey;
        }

        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            return DataBufferUtils.join(body)
                    .flatMap(dataBuffer -> {
                        byte[] content = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(content);
                        DataBufferUtils.release(dataBuffer);

                        String bodyContent = new String(content, StandardCharsets.UTF_8);

                        return redisTemplate.opsForValue()
                                .set(cacheKey, bodyContent, Duration.ofSeconds(ttlSeconds))
                                .then(Mono.defer(() -> {
                                    log.debug("Cached response for: {}", cacheKey);
                                    getDelegate().getHeaders().set("X-Cache", "MISS");
                                    DataBufferFactory bufferFactory = getDelegate().bufferFactory();
                                    DataBuffer buffer = bufferFactory.wrap(content);
                                    getDelegate().getHeaders().setContentLength(content.length);
                                    return getDelegate().writeWith(Mono.just(buffer));
                                }));
                    });
        }
    }
}
