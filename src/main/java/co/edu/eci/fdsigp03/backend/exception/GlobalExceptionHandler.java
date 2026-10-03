package co.edu.eci.fdsigp03.backend.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manejador global de errores de la API.
 * <p>
 * Vector 9 (information disclosure vía errores no controlados): sin este handler, una
 * excepción inesperada (NPE, error de deserialización, etc.) cae en el manejo por defecto
 * de Spring, cuyo cuerpo de respuesta puede variar según configuración/perfil y, en algunos
 * casos, exponer el nombre de la clase de la excepción o detalles internos. Aquí se fija
 * explícitamente un cuerpo de error genérico y estable para cualquier excepción no prevista,
 * dejando el detalle real únicamente en el log del servidor.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("Solicitud inválida.");
        return ResponseEntity.badRequest().body(errorBody(HttpStatus.BAD_REQUEST, detail));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("Error no controlado procesando la solicitud", ex);
        return ResponseEntity.internalServerError().body(errorBody(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Ocurrió un problema técnico al procesar tu solicitud. Intenta de nuevo en unos momentos."
        ));
    }

    private Map<String, Object> errorBody(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return body;
    }
}
