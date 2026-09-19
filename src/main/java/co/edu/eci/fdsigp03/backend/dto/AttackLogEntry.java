package co.edu.eci.fdsigp03.backend.dto;

import java.time.Instant;

/**
 * Registro de un intento de extracción detectado (por el Input Guard o el Output Filter).
 * Se mantiene en memoria y se expone vía GET /api/logs para fines de demostración/evaluación
 * del experimento (no sustituye un sistema de logging de producción).
 */
public record AttackLogEntry(
        Instant timestamp,
        String sessionId,
        String stage,
        String contentSnippet,
        String reason
) {
}
