package co.edu.eci.fdsigp03.backend.config;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Cliente HTTP usado para invocar la API de Gemini, con timeouts explícitos
 * para no dejar colgada una request del usuario si el proveedor LLM no responde.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient geminiRestClient(RestClient.Builder builder) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(5))
                .withReadTimeout(Duration.ofSeconds(20));
        ClientHttpRequestFactory factory = ClientHttpRequestFactories.get(settings);
        return builder.requestFactory(factory).build();
    }
}
