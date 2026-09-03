package co.edu.eci.fdsigp03.backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Endpoint de verificación básica. El endpoint /chat (Unsecure/Secure)
 * se agrega en el Hito 2, junto con los controles de Input Guard y Output Filter.
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "fdsi-gp03-backend");
    }

}
