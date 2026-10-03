package co.edu.eci.fdsigp03.backend.exception;

/**
 * Error al invocar la API de Gemini (configuración faltante, timeout, respuesta
 * inválida o bloqueada por los filtros de seguridad propios del proveedor).
 */
public class GeminiClientException extends RuntimeException {

    public GeminiClientException(String message) {
        super(message);
    }

    public GeminiClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
