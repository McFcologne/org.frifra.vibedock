package org.frifra.vibedock.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

@SpringBootApplication
public class Application {
    public static void main(String[] args) { SpringApplication.run(Application.class, args); }
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean HttpClient httpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name="vibedock.polling", havingValue="true", matchIfMissing=true)
    static class Scheduling {}
}
