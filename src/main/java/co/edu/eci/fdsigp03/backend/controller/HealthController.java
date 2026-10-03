package co.edu.eci.fdsigp03.backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Endpoint de verificación básica. La lógica de negocio vive en POST /api/chat,
 * con los controles de Prompt Hardening, Input Guard y Output Filter (arquitectura Secure).
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "fdsi-gp03-backend");
    }

}
