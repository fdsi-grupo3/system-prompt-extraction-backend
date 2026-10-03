package co.edu.eci.fdsigp03.backend.controller;

import co.edu.eci.fdsigp03.backend.dto.ChatRequest;
import co.edu.eci.fdsigp03.backend.dto.ChatResponse;
import co.edu.eci.fdsigp03.backend.service.ChatOrchestrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint principal de la arquitectura Secure.
 * Orquesta Prompt Hardening + Input Guard + Output Filter sobre la API de Gemini.
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatOrchestrationService chatOrchestrationService;

    @PostMapping
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return ResponseEntity.ok(chatOrchestrationService.handle(request));
    }
}
