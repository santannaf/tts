package santannaf.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ConvaiWsProxyHandler implements WebSocketHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConvaiWsProxyHandler.class);

    private final WebClient http;
    private final ReactorNettyWebSocketClient wsClient;
    private final ObjectMapper om = new ObjectMapper();
    private final String xiApiKey;

    public ConvaiWsProxyHandler(WebClient.Builder builder,
                                ReactorNettyWebSocketClient wsClient,
                                org.springframework.core.env.Environment env) {
        this.http = builder.baseUrl("https://api.elevenlabs.io").build();
        this.wsClient = wsClient;
        this.xiApiKey = Optional.ofNullable(env.getProperty("eleven.labs.apiKey")).orElse("");
    }

    @Override
    @Nonnull
    public List<String> getSubProtocols() {
        return List.of();
    }

    @Override
    @Nonnull
    public Mono<Void> handle(WebSocketSession clientSession) {
        var qp = clientSession.getHandshakeInfo().getUri().getQuery();
        Map<String, String> params = QueryParams.of(qp);
        String agentId = params.get("agentId");
        if (agentId == null || agentId.isBlank()) {
            LOGGER.warn("agentId ausente na query string");
            return clientSession.send(Mono.just(clientSession.textMessage("{\"error\":\"agentId is required\"}"))).then();
        }

        return getSignedUrl(agentId)
                .publishOn(Schedulers.boundedElastic())
                .flatMap(signedUrl -> bridge(clientSession, signedUrl))
                .onErrorResume(e -> {
                    LOGGER.error("Falha ao obter signed_url ou ao iniciar bridge", e);
                    return clientSession.send(Mono.just(clientSession.textMessage("{\"error\":\"proxy_init_failed\"}"))).then();
                });
    }

    private Mono<String> getSignedUrl(String agentId) {
        LOGGER.info("Solicitando signed_url para agentId={}", agentId);
        return http.get()
                .uri(uri -> uri.path("/v1/convai/conversation/get-signed-url")
                        .queryParam("agent_id", agentId).build())
                .header("xi-api-key", xiApiKey)
                .retrieve()
                .bodyToMono(String.class)
                .handle((json, sink) -> {
                    try {
                        JsonNode n = om.readTree(json);
                        String signed = n.path("signed_url").asText();
                        if (signed == null || signed.isBlank()) {
                            sink.error(new IllegalStateException("signed_url vazio"));
                            return;
                        }
                        LOGGER.info("signed_url OK (prefix)={}", signed.substring(0, Math.min(60, signed.length())));
                        sink.next(signed);
                    } catch (Exception ex) {
                        sink.error(new RuntimeException("Erro parseando signed_url: " + ex.getMessage(), ex));
                    }
                });
    }

    private Mono<Void> bridge(WebSocketSession clientSession, String elevenSignedUrl) {
        LOGGER.info("Iniciando bridge WS ↔ WS. ClientId={}, Eleven={}", id(clientSession), elevenSignedUrl);

        return wsClient.execute(URI.create(elevenSignedUrl), elevenSession -> {
            // FLUX: cliente → eleven (repasse de tudo que o cliente enviar)
            Flux<WebSocketMessage> clientToElevenMsg =
                    clientSession
                            .receive()
                            .onBackpressureBuffer(2048, BufferOverflowStrategy.DROP_OLDEST)
                            .map(msg -> msg.getPayloadAsText(StandardCharsets.UTF_8))
                            .publishOn(Schedulers.boundedElastic())
                            .map(txt -> {
                                LOGGER.debug("[C→E] {}", slice(txt));
                                return elevenSession.textMessage(txt);
                            });

            // FLUX: eleven → cliente (repasse de tudo; e auto-pong)
            Flux<String> elevenInboundText =
                    elevenSession.receive()
                            .map(WebSocketMessage::getPayloadAsText)
                            .doOnSubscribe(_ -> LOGGER.info("eleven.receive subscribed"))
                            .doOnComplete(() -> LOGGER.info("eleven.receive completed (server fechou)"))
                            .doOnError(err -> LOGGER.error("eleven.receive error", err))
                            .publishOn(Schedulers.boundedElastic())
                            .publish()
                            .autoConnect(2);

            // Auto-pong: se chegar 'ping', respondemos à Eleven de imediato
            Flux<WebSocketMessage> autoPongs =
                    elevenInboundText
                            .flatMap(txt -> {
                                try {
                                    JsonNode n = om.readTree(txt);
                                    if (n.has("type") && "ping".equals(n.get("type").asText())) {
                                        String eventId = n.path("ping_event").path("event_id").asText(null);
                                        if (eventId != null) {
                                            var pong = om.createObjectNode();
                                            pong.put("type", "pong");
                                            pong.put("event_id", eventId);
                                            String out = pong.toString();
                                            LOGGER.debug("[AUTO-PONG] {}", out);
                                            return Mono.just(elevenSession.textMessage(out));
                                        }
                                    }
                                } catch (Exception ignore) {
                                }
                                return Mono.empty();
                            }).subscribeOn(Schedulers.boundedElastic());

            // Upstream (para Eleven): tudo que o cliente mandar + eventuais pongs automáticos
            Mono<Void> upstream =
                    elevenSession
                            .send(Flux.merge(clientToElevenMsg, autoPongs))
                            .doOnSubscribe(_ -> LOGGER.info("Upstream iniciado (cliente→eleven)"))
                            .doOnError(err -> LOGGER.error("Erro upstream", err));

            // Downstream (para cliente): tudo que vier da Eleven vai para o cliente
            Mono<Void> downstream =
                    clientSession
                            .send(elevenInboundText.map(clientSession::textMessage))
                            .doOnSubscribe(_ -> LOGGER.info("Downstream iniciado (eleven→cliente)"))
                            .doOnError(err -> LOGGER.error("Erro downstream", err));

            // Fecha ambos quando qualquer ponta termina

            return Mono.whenDelayError(upstream, downstream)
                    .timeout(Duration.ofMinutes(15))
                    .onErrorResume(_ -> Mono.empty())
                    .doFinally(sig -> LOGGER.info("Bridge encerrada. Motivo={}", sig))
                    .then(
                            Mono.whenDelayError(
                                    clientSession.close(CloseStatus.NORMAL).onErrorResume(_ -> Mono.empty()),
                                    elevenSession.close(CloseStatus.NORMAL).onErrorResume(_ -> Mono.empty())
                            )
                    );
        });
    }

    private static String id(WebSocketSession s) {
        return s.getId() + "@" + s.getHandshakeInfo().getRemoteAddress();
    }

    private static String slice(String s) {
        if (s == null) return "null";
        return s.length() > 300 ? s.substring(0, 300) + " …" : s;
    }

    static class QueryParams {
        static Map<String, String> of(String raw) {
            java.util.HashMap<String, String> m = new java.util.HashMap<>();
            if (raw == null || raw.isBlank()) return m;
            for (String p : raw.split("&")) {
                int i = p.indexOf('=');
                if (i > 0) m.put(urlDecode(p.substring(0, i)), urlDecode(p.substring(i + 1)));
            }
            return m;
        }

        static String urlDecode(String s) {
            try {
                return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                return s;
            }
        }
    }
}
