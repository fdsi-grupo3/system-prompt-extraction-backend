package co.edu.eci.fdsigp03.backend.controller;

import co.edu.eci.fdsigp03.backend.dto.ChatRequest;
import co.edu.eci.fdsigp03.backend.dto.ChatResponse;
import co.edu.eci.fdsigp03.backend.service.UnsecureChatOrchestrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat/unsecure")
@RequiredArgsConstructor
public class UnsecureChatController {

    private final UnsecureChatOrchestrationService unsecureChatOrchestrationService;

    @PostMapping
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return ResponseEntity.ok(unsecureChatOrchestrationService.handle(request));
    }
}
