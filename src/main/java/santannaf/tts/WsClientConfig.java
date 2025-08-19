package santannaf.tts;

import io.netty.handler.logging.LogLevel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.logging.AdvancedByteBufFormat;

@Configuration
public class WsClientConfig {
    @Bean
    ReactorNettyWebSocketClient reactorWsClient() {
        var logCat = "reactor.netty.http.client.HttpClient";
        var http = HttpClient.create()
                .wiretap(logCat, LogLevel.DEBUG, AdvancedByteBufFormat.TEXTUAL)
                .compress(true)
                .keepAlive(true);
        return new ReactorNettyWebSocketClient(http);
    }
}