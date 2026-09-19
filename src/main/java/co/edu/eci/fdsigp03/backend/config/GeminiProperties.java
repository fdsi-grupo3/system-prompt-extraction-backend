package co.edu.eci.fdsigp03.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propiedades de configuración para el proveedor LLM (Gemini).
 * <p>
 * Se exponen DOS configuraciones independientes, con sus propias API keys,
 * tal como exige la arquitectura Secure descrita en la propuesta:
 * <ul>
 *     <li>{@code security}: usada por el Input Guard (clasificador de intención)
 *     y, opcionalmente, por el Output Filter.</li>
 *     <li>{@code main}: usada exclusivamente para generar la respuesta institucional
 *     final con el system prompt protegido.</li>
 * </ul>
 * Mapea las propiedades {@code llm.*} de {@code application.yml}.
 */
@ConfigurationProperties(prefix = "llm")
public record GeminiProperties(String baseUrl, ModelConfig security, ModelConfig main) {

    public record ModelConfig(String apiKey, String model) {
    }
}
