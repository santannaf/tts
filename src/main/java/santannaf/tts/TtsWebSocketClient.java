package santannaf.tts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class TtsWebSocketClient {
    private static final Logger log = LoggerFactory.getLogger(TtsWebSocketClient.class);

    private final ReactorNettyWebSocketClient client;
    private final ObjectMapper om = new ObjectMapper();

    public TtsWebSocketClient(ReactorNettyWebSocketClient client) {
        this.client = client;
    }

    public Mono<Void> connect(String apiKey, String voiceId, String modelId, String textToSpeak) {
        Assert.hasText(apiKey, "apiKey required");
        Assert.hasText(voiceId, "voiceId required");

        String url = "wss://api.elevenlabs.io/v1/text-to-speech/" + voiceId + "/stream-input";
        if (modelId != null && !modelId.isBlank()) {
            url += "?model_id=" + modelId;
        }

        URI uri = URI.create(url);

        return client.execute(uri, session -> {
            // Header 'xi-api-key' é exigido no handshake
            session.getHandshakeInfo().getHeaders()
                    .addAll(new HttpHeaders() {{
                        add("xi-api-key", apiKey);
                    }});

            // Mensagem 1: initializeConnection (conforme docs)
            ObjectNode init = om.createObjectNode();
            init.put("text", " "); // espaço em branco para iniciar
            ObjectNode voiceSettings = init.putObject("voice_settings");
            voiceSettings.put("speed", 1.0);
            voiceSettings.put("stability", 0.5);
            voiceSettings.put("similarity_boost", 0.8);
            init.put("xi_api_key", apiKey); // também aceito no payload

            // Mensagem 2: enviar o texto (sendText)
            ObjectNode sendText = om.createObjectNode();
            sendText.put("text", textToSpeak);
            sendText.put("try_trigger_generation", true);

            // Mensagem 3: flush (closeConnection / texto vazio)
            ObjectNode flush = om.createObjectNode();
            flush.put("text", "");

            Flux<WebSocketMessage> toSend = Flux.just(init, sendText, flush)
                    .map(node -> session.textMessage(node.toString()));

            // Recepção: imprimir audio/base64 e metadados (audioOutput / finalOutput)
            Mono<Void> receive = session.receive()
                    .map(WebSocketMessage::getPayloadAsText)
                    .doOnNext(msg -> {
                        log.info("WS <= {}", msg);
                        try {
                            ObjectNode n = (ObjectNode) om.readTree(msg);
                            if (n.has("audio")) {
                                String b64 = n.get("audio").asText();
                                byte[] bytes = Base64.getDecoder().decode(b64);
                                log.info("Audio chunk size: {} bytes", bytes.length);
                            }
                            if (n.has("isFinal") && n.get("isFinal").asBoolean()) {
                                log.info("Final chunk received.");
                            }
                        } catch (Exception e) {
                            log.warn("Failed parsing message", e);
                        }
                    })
                    .then();

            return session.send(toSend).then(receive)
                    .timeout(Duration.ofSeconds(30))
                    .onErrorResume(e -> {
                        log.error("WS error", e);
                        return Mono.empty();
                    });
        });
    }

    public Flux<byte[]> streamAudio(String apiKey, String voiceId, String modelId, String textToSpeak, String outputFormat) {

        String k = Objects.requireNonNull(apiKey, "apiKey").trim();
        String v = Objects.requireNonNull(voiceId, "voiceId").trim();
        if (k.isEmpty() || v.isEmpty()) {
            return Flux.error(new IllegalArgumentException("apiKey/voiceId vazios"));
        }

        var uri = UriComponentsBuilder
                .fromUriString("wss://api.elevenlabs.io/v1/text-to-speech/{voiceId}/stream-input")
                .queryParamIfPresent("model_id", (modelId == null || modelId.isBlank()) ? java.util.Optional.empty() : java.util.Optional.of(modelId.trim()))
                .queryParamIfPresent("output_format", (outputFormat == null || outputFormat.isBlank()) ? java.util.Optional.empty() : java.util.Optional.of(outputFormat.trim()))
                .buildAndExpand(v)
                .toUri();

        var headers = new org.springframework.http.HttpHeaders();
        headers.set("xi-api-key", k);

        log.info("WS URL  : {}", uri);
        log.info("voiceId : {}", v);
        log.info("modelId : {}", modelId);
        log.info("outFmt  : {}", outputFormat);
        log.info("apiKey  : ...{}", k.length() >= 6 ? k.substring(k.length() - 6) : "(short)");

        return Flux.create((FluxSink<byte[]> sink) -> {
            final long start = System.currentTimeMillis();
            final AtomicBoolean first = new AtomicBoolean(true);
            final AtomicInteger chunks = new AtomicInteger();
            final AtomicLong total = new AtomicLong();

            client.execute(uri, headers, session -> {
                        // Mensagem 1: init
                        ObjectNode init = om.createObjectNode();
                        init.put("text", " ");
                        ObjectNode vs = init.putObject("voice_settings");
                        vs.put("speed", 1.0);
                        vs.put("stability", 0.5);
                        vs.put("similarity_boost", 0.8);
                        init.put("xi_api_key", apiKey.trim());

                        // Mensagem 2: texto
                        ObjectNode sendText = om.createObjectNode();
                        sendText.put("text", textToSpeak);
                        sendText.put("try_trigger_generation", true);

                        // Mensagem 3: flush
                        ObjectNode flush = om.createObjectNode();
                        flush.put("text", "");

                        Flux<WebSocketMessage> toSend = Flux.just(init, sendText, flush)
                                .map(n -> session.textMessage(n.toString()));

                        Mono<Void> receive = session.receive()
                                .map(WebSocketMessage::getPayloadAsText)
                                .doOnNext(msg -> {
                                    try {
                                        ObjectNode n = (ObjectNode) om.readTree(msg);

                                        // Se vier erro no payload, logue e sinalize
                                        if (n.has("error") || n.has("message") && !n.has("audio")) {
                                            log.warn("WS error payload: {}", msg);
                                        }

                                        if (n.has("audio")) {
                                            byte[] bytes = Base64.getDecoder().decode(n.get("audio").asText());
                                            int idx = chunks.incrementAndGet();
                                            long tot = total.addAndGet(bytes.length);

                                            if (first.compareAndSet(true, false)) {
                                                long ttfb = System.currentTimeMillis() - start;
                                                log.info("First audio chunk: {} bytes (TTFB={} ms) head={}", bytes.length, ttfb, headHex(bytes));
                                            } else if (idx % 10 == 0) {
                                                log.info("Chunks={} Total={} KiB (≈{} KB)", idx, (tot / 1024), (tot / 1000));
                                            }

                                            sink.next(bytes); // envia pro HTTP streaming
                                        }

                                        if (n.has("isFinal") && n.get("isFinal").asBoolean()) {
                                            log.info("WS isFinal=true (último chunk sinalizado)");
                                        }
                                    } catch (Exception e) {
                                        log.warn("WS parse fail: {}", e.toString());
                                    }
                                })
                                .doOnError(err -> {
                                    log.error("WS receive error", err);
                                    sink.error(err);
                                })
                                .doOnComplete(() -> {
                                    log.info("WS completed. chunks={}, total={} bytes", chunks.get(), total.get());
                                    sink.complete();
                                })
                                .then();

                        return session.send(toSend).then(receive)
                                .timeout(Duration.ofMinutes(2))
                                .onErrorResume(e -> {
                                    log.error("WS error", e);
                                    sink.error(e);
                                    return Mono.empty();
                                });
                    })
                    .subscribe(
                            null,
                            sink::error,
                            () -> {
                                log.info("WS session terminated (publisher complete).");
                                sink.complete();
                            }
                    );
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    private static String headHex(byte[] b) {
        int n = Math.min(8, b.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(String.format("%02X", b[i])).append(i + 1 < n ? " " : "");
        return sb.toString();
    }
}
