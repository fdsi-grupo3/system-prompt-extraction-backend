package co.edu.eci.fdsigp03.backend.service;

import co.edu.eci.fdsigp03.backend.dto.LeakAssessment;
import co.edu.eci.fdsigp03.backend.dto.MlFilterRequest;
import co.edu.eci.fdsigp03.backend.dto.MlFilterResponse;
import co.edu.eci.fdsigp03.backend.exception.MlFilterUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;

/**
 * Cliente del Output Filter basado en IA: un clasificador Transformer propio (cross-encoder
 * multilingüe fine-tuned, exportado a ONNX) servido por FastAPI en el proyecto hermano
 * {@code system-prompt-extraction-ml-filter}. Es independiente de Gemini: no consume
 * ninguna de las dos API keys y no puede ser manipulado por el mismo prompt que atacó al LLM.
 * <p>
 * Los fragmentos protegidos se envían en cada request desde {@link SystemPromptService},
 * que sigue siendo la única fuente de verdad del system prompt.
 */
@Service
@Slf4j
public class MlFilterClient {

    private final RestClient restClient;
    private final String apiToken;

    @Autowired
    public MlFilterClient(RestClient.Builder builder,
                          @Value("${app.output-filter.ml.base-url:http://localhost:8001}") String baseUrl,
                          @Value("${app.output-filter.ml.connect-timeout-ms:500}") long connectTimeoutMs,
                          @Value("${app.output-filter.ml.read-timeout-ms:2000}") long readTimeoutMs,
                          @Value("${app.output-filter.ml.api-token:}") String apiToken) {
        this(builder
                        .baseUrl(baseUrl)
                        .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                                .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                                .withReadTimeout(Duration.ofMillis(readTimeoutMs))))
                        .build(),
                apiToken);
    }

    /** Constructor para pruebas: recibe un RestClient ya configurado (p. ej. con MockRestServiceServer). */
    MlFilterClient(RestClient restClient, String apiToken) {
        this.restClient = restClient;
        this.apiToken = apiToken;
    }

    /**
     * Pide al servicio de IA el veredicto sobre una respuesta candidata.
     *
     * @throws MlFilterUnavailableException si el servicio no responde, falla o devuelve un cuerpo inválido.
     */
    public LeakAssessment assess(String sessionId, String candidateResponse,
                                 List<String> protectedFragments, List<String> history) {
        MlFilterRequest body = new MlFilterRequest(candidateResponse, protectedFragments, history, sessionId);
        MlFilterResponse response;
        try {
            response = restClient.post()
                    .uri("/api/output-filter")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        if (apiToken != null && !apiToken.isBlank()) {
                            headers.set("X-Filter-Token", apiToken);
                        }
                    })
                    .body(body)
                    .retrieve()
                    .body(MlFilterResponse.class);
        } catch (RestClientException ex) {
            throw new MlFilterUnavailableException("No fue posible contactar al Output Filter de IA", ex);
        }

        if (response == null) {
            throw new MlFilterUnavailableException("El Output Filter de IA devolvió una respuesta vacía");
        }
        log.debug("[Output Filter ML] sessionId={} score={} vista={} latenciaServicio={}ms modelo={}",
                sessionId, response.score(), response.matchedView(), response.latencyMs(), response.modelVersion());
        return new LeakAssessment(response.isLeak(), response.score(), response.threshold(),
                response.accumulated(), response.matchedFragment(), "ML");
    }
}
