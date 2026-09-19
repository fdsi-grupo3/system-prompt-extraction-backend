package co.edu.eci.fdsigp03.backend.dto;

/**
 * Respuesta devuelta por POST /api/chat.
 *
 * @param reply     texto a mostrar al usuario (respuesta real o mensaje genérico si fue bloqueado).
 * @param blocked   true si el Input Guard o el Output Filter bloquearon la solicitud/respuesta.
 * @param blockedBy componente que bloqueó la interacción: "INPUT_GUARD", "OUTPUT_FILTER" o null.
 * @param latencyMs latencia total del endpoint, en milisegundos (usada para medir el sobrecosto
 *                  de los controles frente a la arquitectura Unsecure).
 * @param sessionId identificador de sesión (eco del request, o el generado por el backend).
 * @param error     true si ocurrió un error técnico (p. ej. fallo al contactar a Gemini),
 *                  distinto de un bloqueo de seguridad.
 */
public record ChatResponse(
        String reply,
        boolean blocked,
        String blockedBy,
        long latencyMs,
        String sessionId,
        boolean error
) {
}
