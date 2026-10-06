package co.edu.eci.fdsigp03.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Respuesta de POST /api/output-filter del servicio de IA (FastAPI).
 *
 * @param isLeak          true si el clasificador considera que la respuesta filtra un fragmento protegido.
 * @param score           P(fuga) máxima sobre todas las vistas/ventanas/fragmentos evaluados.
 * @param threshold       umbral calibrado con el que el servicio tomó la decisión.
 * @param matchedFragment fragmento protegido con mayor P(fuga) (null si no hay fuga).
 * @param matchedView     vista que produjo el máximo: original, base64[i], rot13, accumulated, etc.
 * @param accumulated     true si la fuga solo aparece al combinar con el historial de la sesión.
 * @param latencyMs       latencia de la inferencia en el servicio.
 * @param modelVersion    versión del modelo cargado.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlFilterResponse(
        @JsonProperty("is_leak") boolean isLeak,
        double score,
        double threshold,
        @JsonProperty("matched_fragment") String matchedFragment,
        @JsonProperty("matched_view") String matchedView,
        boolean accumulated,
        @JsonProperty("latency_ms") double latencyMs,
        @JsonProperty("model_version") String modelVersion
) {
}
