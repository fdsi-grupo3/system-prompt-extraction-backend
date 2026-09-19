package co.edu.eci.fdsigp03.backend.controller;

import co.edu.eci.fdsigp03.backend.dto.AttackLogEntry;
import co.edu.eci.fdsigp03.backend.service.AttackLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Expone los intentos de extracción bloqueados (Input Guard / Output Filter),
 * registrados en memoria por {@link AttackLogService}. Pensado para apoyar la
 * evaluación del experimento (tasa de éxito de extracción, cobertura de técnicas),
 * no como un endpoint de auditoría de producción.
 */
@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
public class LogController {

    private final AttackLogService attackLogService;

    @GetMapping
    public List<AttackLogEntry> logs(@RequestParam(defaultValue = "50") int limit) {
        return attackLogService.recent(Math.min(Math.max(limit, 1), 200));
    }
}
