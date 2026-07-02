package org.darksamus.apigateway.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class BackendHealthIndicator implements ReactiveHealthIndicator {

    private final WebClient webClient;

    public BackendHealthIndicator(
            @Value("${spring.cloud.gateway.server.webflux.routes[0].uri:http://localhost:8081}") String backendUri) {
        this.webClient = WebClient.create(backendUri);
    }

    @Override
    public Mono<Health> health() {
        return webClient.get()
                .uri("/actuator/health")
                .retrieve()
                .bodyToMono(String.class)
                .map(response -> Health.up().withDetail("backend", "available").build())
                .onErrorResume(e -> Mono.just(
                        Health.down()
                                .withDetail("backend", "unavailable")
                                .withDetail("error", e.getMessage())
                                .build()
                ));
    }
}
