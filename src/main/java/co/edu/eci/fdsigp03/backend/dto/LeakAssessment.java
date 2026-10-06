package co.edu.eci.fdsigp03.backend.dto;

/**
 * Veredicto de un detector de fugas (léxico o IA) sobre una respuesta del LLM principal.
 *
 * @param leak            true si la respuesta filtra (total o parcialmente) un fragmento protegido.
 * @param score           puntuación del detector (similitud 0-1 en el léxico, P(fuga) 0-1 en el de IA).
 * @param threshold       umbral con el que el detector tomó la decisión.
 * @param accumulated     true si la fuga se detectó al combinar con respuestas previas de la sesión (Vector 4).
 * @param matchedFragment fragmento protegido que produjo la mayor puntuación (puede ser null).
 * @param engine          detector que produjo el veredicto: "ML", "LEXICAL" o "LEXICAL_FALLBACK".
 */
public record LeakAssessment(
        boolean leak,
        double score,
        double threshold,
        boolean accumulated,
        String matchedFragment,
        String engine
) {
}
