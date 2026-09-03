package co.edu.eci.fdsigp03.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * FDSI-GP-03 · Extracción de instrucciones del sistema.
 * Punto de entrada del backend. La lógica de negocio (Unsecure/Secure,
 * Input Guard, Output Filter) se implementa a partir del Hito 2.
 */
@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }

}
