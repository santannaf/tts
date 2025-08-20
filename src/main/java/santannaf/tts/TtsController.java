package santannaf.tts;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/tts")
public class TtsController {
    private final TtsWebSocketClient wsClient;
    private final DefaultDataBufferFactory buf = new DefaultDataBufferFactory();

    public TtsController(TtsWebSocketClient wsClient) {
        this.wsClient = wsClient;
    }

    @Value("${eleven.labs.apiKey:}")
    private String defaultApiKey;

    @Value("${eleven.labs.voiceId:}")
    private String defaultVoiceId;

    @Value("${eleven.labs.modelId:}")
    private String defaultModelId;

    @Value("${eleven.outputFormat:mp3_44100_128}")
    private String defaultOutputFormat;

    @PostMapping("/connect")
    public Mono<ResponseEntity<String>> connect(
            @RequestParam(value = "text", defaultValue = "Ola mundo do WebFlux!") String text
    ) {
        return wsClient.connect(defaultApiKey, defaultVoiceId, defaultModelId, text)
                .thenReturn(ResponseEntity.ok("Conectado e mensagens enviadas. Verifique os logs do servidor."));
    }

    @CrossOrigin
    @GetMapping(value = "/stream", produces = "audio/mpeg")
    public ResponseEntity<Flux<DataBuffer>> stream(
            @RequestParam("text") String text
    ) {
        String preview = text.length() > 64 ? text.substring(0, 64) + "…" : text;

        System.out.printf("HTTP /tts/stream textLen=%d preview=\"%s\" voiceId=%s modelId=%s outputFormat=%s%n",
                text.length(), preview, defaultVoiceId, defaultModelId, defaultOutputFormat);

        Flux<DataBuffer> body = wsClient
                .streamAudio(defaultApiKey, defaultVoiceId, defaultModelId, text, defaultOutputFormat)
                .map(buf::wrap);

        // mapeia content-type conforme formato (mantemos audio/mpeg p/ mp3)
        MediaType ct = defaultOutputFormat.startsWith("mp3") ? MediaType.valueOf("audio/mpeg") :
                defaultOutputFormat.startsWith("wav") || defaultOutputFormat.startsWith("pcm") ? MediaType.valueOf("audio/wav") :
                        MediaType.APPLICATION_OCTET_STREAM;

        return ResponseEntity.ok().contentType(ct).body(body);
    }
}
