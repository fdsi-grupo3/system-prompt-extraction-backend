package co.edu.eci.fdsigp03.backend.dto;

/**
 * Resultado interno de la evaluación del Input Guard sobre un mensaje de usuario.
 *
 * @param allowed        true si el mensaje puede continuar hacia el LLM principal.
 * @param reason         motivo del bloqueo (null si allowed = true), usado para logging.
 * @param detectionLayer capa que generó la decisión: "REGEX", "LLM_CLASSIFIER" o null.
 */
public record GuardVerdict(boolean allowed, String reason, String detectionLayer) {

    public static GuardVerdict allow() {
        return new GuardVerdict(true, null, null);
    }

    public static GuardVerdict block(String reason, String detectionLayer) {
        return new GuardVerdict(false, reason, detectionLayer);
    }
}
