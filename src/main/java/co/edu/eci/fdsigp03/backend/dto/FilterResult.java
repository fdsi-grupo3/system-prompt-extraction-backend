package co.edu.eci.fdsigp03.backend.dto;

/**
 * Resultado interno de la evaluación del Output Filter sobre la respuesta del LLM principal.
 *
 * @param safe           true si la respuesta puede entregarse al usuario tal cual.
 * @param maxSimilarity  puntuación máxima (0-1) del detector usado: P(fuga) del clasificador de IA,
 *                       o similitud léxica contra los fragmentos protegidos en modo lexical/fallback.
 * @param matchedFragment fragmento protegido que produjo la mayor similitud (null si safe = true).
 */
public record FilterResult(boolean safe, double maxSimilarity, String matchedFragment) {
}
