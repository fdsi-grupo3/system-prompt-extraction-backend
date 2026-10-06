package co.edu.eci.fdsigp03.backend.exception;

/**
 * El servicio del Output Filter basado en IA no respondió a tiempo, devolvió un error
 * o una respuesta inválida. OutputFilterService la captura para degradar al filtro
 * léxico en vez de dejar pasar la respuesta sin evaluar (lección del Vector 5).
 */
public class MlFilterUnavailableException extends RuntimeException {

    public MlFilterUnavailableException(String message) {
        super(message);
    }

    public MlFilterUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
