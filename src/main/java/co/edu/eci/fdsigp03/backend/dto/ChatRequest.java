package co.edu.eci.fdsigp03.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload recibido en POST /api/chat.
 *
 * @param message   mensaje del usuario, tal cual, sin sanitizar (el Input Guard
 *                  se encarga de evaluarlo antes de reenviarlo al LLM).
 * @param sessionId identificador de sesión de conversación, generado por el frontend.
 *                  Es opcional: si no llega, el backend genera uno.
 */
public record ChatRequest(

        @NotBlank(message = "El mensaje no puede estar vacío")
        @Size(max = 4000, message = "El mensaje no puede superar los 4000 caracteres")
        String message,

        @Size(max = 100, message = "sessionId inválido")
        String sessionId
) {
}
