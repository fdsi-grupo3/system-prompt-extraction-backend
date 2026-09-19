package co.edu.eci.fdsigp03.backend.dto;

/**
 * Resultado interno de la evaluación del Output Filter sobre la respuesta del LLM principal.
 *
 * @param safe           true si la respuesta puede entregarse al usuario tal cual.
 * @param maxSimilarity  máxima similitud (0-1) encontrada entre la respuesta y los fragmentos
 *                       protegidos del system prompt real.
 * @param matchedFragment fragmento protegido que produjo la mayor similitud (null si safe = true).
 */
public record FilterResult(boolean safe, double maxSimilarity, String matchedFragment) {
}
