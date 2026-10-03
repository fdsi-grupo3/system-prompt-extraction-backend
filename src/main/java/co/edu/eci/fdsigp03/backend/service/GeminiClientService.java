package co.edu.eci.fdsigp03.backend.service;

import co.edu.eci.fdsigp03.backend.exception.GeminiClientException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cliente delgado para la API "generateContent" de Gemini.
 * <p>
 * Se usa tanto para el LLM principal (API Key "main") como para el clasificador
 * de intención del Input Guard (API Key "security"): la key y el modelo se reciben
 * como parámetros en cada llamada para no mezclar ambos canales de confianza.
 */
@Service
@Slf4j
public class GeminiClientService {

    private final RestClient restClient;
    private final String baseUrl;

    public GeminiClientService(RestClient geminiRestClient, @Value("${llm.base-url}") String baseUrl) {
        this.restClient = geminiRestClient;
        this.baseUrl = baseUrl;
    }

    /**
     * Invoca a Gemini con un system prompt opcional y el mensaje del usuario.
     *
     * @param apiKey            API key a usar (security o main, según el llamador).
     * @param model             nombre del modelo (ej. gemini-2.0-flash).
     * @param systemInstruction instrucciones de sistema; puede ser null/blank para
     *                          llamadas sin contexto institucional (p. ej. el clasificador).
     * @param userMessage       mensaje a enviar como turno del usuario.
     * @param temperature       temperatura de generación.
     * @param maxOutputTokens   límite de tokens de salida.
     * @return el texto generado por el modelo.
     */
    public String generateContent(String apiKey, String model, String systemInstruction,
                                   String userMessage, double temperature, int maxOutputTokens) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new GeminiClientException(
                    "API key de Gemini no configurada para el modelo '" + model
                            + "'. Define la variable de entorno correspondiente (GEMINI_SECURITY_API_KEY / GEMINI_MAIN_API_KEY).");
        }

        String url = baseUrl + "/models/" + model + ":generateContent?key=" + apiKey;

        Map<String, Object> body = new LinkedHashMap<>();
        if (systemInstruction != null && !systemInstruction.isBlank()) {
            body.put("system_instruction", Map.of("parts", List.of(Map.of("text", systemInstruction))));
        }
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userMessage)))));
        body.put("generationConfig", Map.of(
                "temperature", temperature,
                "maxOutputTokens", maxOutputTokens
        ));

        JsonNode response;
        try {
            response = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException ex) {
            log.error("Error llamando a la API de Gemini (modelo={})", model, ex);
            throw new GeminiClientException("No fue posible contactar al modelo Gemini (" + model + ").", ex);
        }

        return extractText(response, model);
    }

    private String extractText(JsonNode response, String model) {
        if (response == null) {
            throw new GeminiClientException("Respuesta vacía de Gemini (" + model + ").");
        }

        JsonNode candidates = response.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            JsonNode promptFeedback = response.path("promptFeedback");
            log.warn("Gemini no devolvió candidatos (modelo={}). promptFeedback={}", model, promptFeedback);
            throw new GeminiClientException(
                    "El modelo no devolvió una respuesta válida (posible bloqueo de seguridad del proveedor).");
        }

        JsonNode parts = candidates.get(0).path("content").path("parts");
        StringBuilder sb = new StringBuilder();
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                sb.append(part.path("text").asText(""));
            }
        }

        String text = sb.toString().trim();
        if (text.isEmpty()) {
            throw new GeminiClientException("El modelo devolvió una respuesta vacía (" + model + ").");
        }
        return text;
    }
}
