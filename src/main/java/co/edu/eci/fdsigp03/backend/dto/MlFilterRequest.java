package co.edu.eci.fdsigp03.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Cuerpo enviado a POST /api/output-filter del servicio de IA (FastAPI).
 *
 * @param response            respuesta candidata del LLM principal.
 * @param protectedFragments  fragmentos protegidos del system prompt real (SystemPromptService
 *                            sigue siendo la única fuente de verdad; el servicio no los guarda).
 * @param history             respuestas ya entregadas en la sesión, para el chequeo acumulado (Vector 4).
 * @param sessionId           identificador de sesión, solo para trazabilidad en el log del servicio.
 */
public record MlFilterRequest(
        String response,
        @JsonProperty("protected_fragments") List<String> protectedFragments,
        List<String> history,
        @JsonProperty("session_id") String sessionId
) {
}
