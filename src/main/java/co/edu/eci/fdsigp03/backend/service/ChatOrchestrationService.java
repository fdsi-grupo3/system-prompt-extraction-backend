package co.edu.eci.fdsigp03.backend.service;

import co.edu.eci.fdsigp03.backend.config.GeminiProperties;
import co.edu.eci.fdsigp03.backend.dto.ChatRequest;
import co.edu.eci.fdsigp03.backend.dto.ChatResponse;
import co.edu.eci.fdsigp03.backend.dto.FilterResult;
import co.edu.eci.fdsigp03.backend.dto.GuardVerdict;
import co.edu.eci.fdsigp03.backend.exception.GeminiClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Orquesta el flujo completo de la arquitectura Secure para una solicitud de chat:
 * <pre>
 *   mensaje ➔ Input Guard ➔ (si pasa) LLM principal (system prompt reforzado) ➔ Output Filter ➔ respuesta
 * </pre>
 * Cualquier bloqueo (Input Guard u Output Filter) devuelve el mismo mensaje genérico
 * al usuario, para no filtrar información sobre cuál control detectó el intento
 * (esa información solo queda en el log interno).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatOrchestrationService {

    private static final double MAIN_LLM_TEMPERATURE = 0.4;
    private static final int MAIN_LLM_MAX_TOKENS = 800;

    private final InputGuardService inputGuardService;
    private final OutputFilterService outputFilterService;
    private final GeminiClientService geminiClientService;
    private final GeminiProperties geminiProperties;
    private final SystemPromptService systemPromptService;

    public ChatResponse handle(ChatRequest request) {
        long start = System.currentTimeMillis();
        String sessionId = resolveSessionId(request.sessionId());

        GuardVerdict verdict = inputGuardService.evaluate(sessionId, request.message());
        if (!verdict.allowed()) {
            long latency = System.currentTimeMillis() - start;
            return new ChatResponse(
                    systemPromptService.genericRejectionMessage(),
                    true, "INPUT_GUARD", latency, sessionId, false
            );
        }

        String reply;
        try {
            reply = geminiClientService.generateContent(
                    geminiProperties.main().apiKey(),
                    geminiProperties.main().model(),
                    systemPromptService.buildSecureSystemPrompt(),
                    request.message(),
                    MAIN_LLM_TEMPERATURE,
                    MAIN_LLM_MAX_TOKENS
            );
        } catch (GeminiClientException ex) {
            long latency = System.currentTimeMillis() - start;
            log.error("[Chat] fallo al invocar el LLM principal sessionId={} motivo={}", sessionId, ex.getMessage());
            return new ChatResponse(
                    "Ocurrió un problema técnico al procesar tu solicitud. Intenta de nuevo en unos momentos.",
                    false, null, latency, sessionId, true
            );
        }

        FilterResult filterResult = outputFilterService.evaluate(sessionId, reply);
        long latency = System.currentTimeMillis() - start;

        if (!filterResult.safe()) {
            return new ChatResponse(
                    systemPromptService.genericRejectionMessage(),
                    true, "OUTPUT_FILTER", latency, sessionId, false
            );
        }

        return new ChatResponse(reply, false, null, latency, sessionId, false);
    }

    private String resolveSessionId(String requestSessionId) {
        return (requestSessionId == null || requestSessionId.isBlank())
                ? UUID.randomUUID().toString()
                : requestSessionId;
    }
}
