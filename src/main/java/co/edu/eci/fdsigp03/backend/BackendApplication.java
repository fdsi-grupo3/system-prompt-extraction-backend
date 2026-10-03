package co.edu.eci.fdsigp03.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * FDSI-GP-03 · Extracción de instrucciones del sistema.
 * Punto de entrada del backend. Implementa la arquitectura Secure:
 * Prompt Hardening + Input Guard + Output Filter sobre dos API keys de Gemini
 * independientes (seguridad / LLM principal).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }

}
