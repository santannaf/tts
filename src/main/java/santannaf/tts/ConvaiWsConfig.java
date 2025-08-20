package santannaf.tts;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import java.util.Map;

@Configuration
public class ConvaiWsConfig {
    @Bean
    public SimpleUrlHandlerMapping wsMapping(ConvaiWsProxyHandler proxy) {
        // ordem alta (menor número) para priorizar WS
        var mapping = new SimpleUrlHandlerMapping();
        mapping.setOrder(1);
        mapping.setUrlMap(Map.of(
                "/convai/ws", (WebSocketHandler) proxy
        ));
        return mapping;
    }

    @Bean
    public WebSocketHandlerAdapter handlerAdapter() {
        return new WebSocketHandlerAdapter();
    }
}
