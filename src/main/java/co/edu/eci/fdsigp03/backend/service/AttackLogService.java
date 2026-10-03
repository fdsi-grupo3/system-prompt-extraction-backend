package co.edu.eci.fdsigp03.backend.service;

import co.edu.eci.fdsigp03.backend.dto.AttackLogEntry;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Registro en memoria de los intentos de extracción bloqueados por el Input Guard
 * o el Output Filter. Se usa para el requisito de logging descrito en la propuesta
 * y para poder auditar el experimento (T01-T06) vía GET /api/logs.
 * <p>
 * No es un mecanismo de persistencia de producción: se reinicia con cada despliegue
 * y mantiene únicamente los últimos {@value #MAX_ENTRIES} registros.
 */
@Service
public class AttackLogService {

    private static final int MAX_ENTRIES = 500;

    private final Deque<AttackLogEntry> entries = new ArrayDeque<>();
    private final Object lock = new Object();

    public void record(String sessionId, String stage, String content, String reason) {
        AttackLogEntry entry = new AttackLogEntry(
                Instant.now(),
                sessionId,
                stage,
                truncate(content, 300),
                reason
        );
        synchronized (lock) {
            entries.addFirst(entry);
            while (entries.size() > MAX_ENTRIES) {
                entries.removeLast();
            }
        }
    }

    public List<AttackLogEntry> recent(int limit) {
        synchronized (lock) {
            return entries.stream().limit(limit).toList();
        }
    }

    public List<AttackLogEntry> all() {
        synchronized (lock) {
            return Collections.unmodifiableList(List.copyOf(entries));
        }
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
