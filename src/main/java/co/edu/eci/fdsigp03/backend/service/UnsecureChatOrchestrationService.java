package co.edu.eci.fdsigp03.backend.service;

import co.edu.eci.fdsigp03.backend.config.GeminiProperties;
import co.edu.eci.fdsigp03.backend.dto.ChatRequest;
import co.edu.eci.fdsigp03.backend.dto.ChatResponse;
import co.edu.eci.fdsigp03.backend.exception.GeminiClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class UnsecureChatOrchestrationService {

    private static final double TEMPERATURE = 0.4;
    private static final int MAX_TOKENS = 800;

    private final GeminiClientService geminiClientService;
    private final GeminiProperties geminiProperties;
    private final SystemPromptService systemPromptService;

    public ChatResponse handle(ChatRequest request) {
        long start = System.currentTimeMillis();
        String sessionId = (request.sessionId() == null || request.sessionId().isBlank())
                ? UUID.randomUUID().toString()
                : request.sessionId();

        String reply;
        try {
            reply = geminiClientService.generateContent(
                    geminiProperties.main().apiKey(),
                    geminiProperties.main().model(),
                    systemPromptService.buildUnsecureSystemPrompt(),
                    request.message(),
                    TEMPERATURE,
                    MAX_TOKENS
            );
        } catch (GeminiClientException ex) {
            long latency = System.currentTimeMillis() - start;
            log.error("[Chat/Unsecure] fallo al invocar el LLM sessionId={} motivo={}", sessionId, ex.getMessage());
            return new ChatResponse(
                    "Ocurrió un problema técnico al procesar tu solicitud. Intenta de nuevo en unos momentos.",
                    false, null, latency, sessionId, true
            );
        }

        long latency = System.currentTimeMillis() - start;
        return new ChatResponse(reply, false, null, latency, sessionId, false);
    }
}
