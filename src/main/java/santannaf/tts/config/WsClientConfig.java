package santannaf.tts.config;

import io.netty.handler.logging.LogLevel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.WebsocketClientSpec;
import reactor.netty.transport.logging.AdvancedByteBufFormat;

@Configuration
public class WsClientConfig {
    @Bean
    ReactorNettyWebSocketClient reactorWsClient() {
        HttpClient http = HttpClient.create()
                .wiretap("reactor.netty.http.client.HttpClient",
                        LogLevel.DEBUG, AdvancedByteBufFormat.TEXTUAL)
                .compress(true)
                .keepAlive(true);

        return new ReactorNettyWebSocketClient(http,
                () -> WebsocketClientSpec.builder().maxFramePayloadLength(2 * 1024 * 1024).handlePing(true));
    }
}