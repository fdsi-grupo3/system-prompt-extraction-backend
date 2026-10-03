package co.edu.eci.fdsigp03.backend.service;

import co.edu.eci.fdsigp03.backend.config.GeminiProperties;
import co.edu.eci.fdsigp03.backend.dto.ChatRequest;
import co.edu.eci.fdsigp03.backend.dto.ChatResponse;
import co.edu.eci.fdsigp03.backend.dto.FilterResult;
import co.edu.eci.fdsigp03.backend.dto.GuardVerdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cubre el Vector 8 (fijación/adivinación de sessionId): un identificador de sesión
 * arbitrario provisto por el cliente no debe usarse tal cual, ya que el Output Filter
 * acumula divulgación por sessionId (ver OutputFilterService) y aceptar cualquier cadena
 * permitiría a un atacante fijar o heredar el historial acumulado de otra sesión.
 */
class ChatOrchestrationServiceTest {

    private ChatOrchestrationService chatOrchestrationService;

    @BeforeEach
    void setUp() {
        InputGuardService inputGuardService = mock(InputGuardService.class);
        OutputFilterService outputFilterService = mock(OutputFilterService.class);
        GeminiClientService geminiClientService = mock(GeminiClientService.class);
        SystemPromptService systemPromptService = new SystemPromptService();
        GeminiProperties properties = new GeminiProperties(
                "https://generativelanguage.googleapis.com/v1beta",
                new GeminiProperties.ModelConfig("fake-security-key", "gemini-2.0-flash"),
                new GeminiProperties.ModelConfig("fake-main-key", "gemini-2.0-flash")
        );

        when(inputGuardService.evaluate(anyString(), anyString())).thenReturn(GuardVerdict.allow());
        when(geminiClientService.generateContent(anyString(), anyString(), any(), anyString(), anyDouble(), anyInt()))
                .thenReturn("Respuesta institucional de prueba.");
        when(outputFilterService.evaluate(anyString(), anyString())).thenReturn(new FilterResult(true, 0.0, null));

        chatOrchestrationService = new ChatOrchestrationService(
                inputGuardService, outputFilterService, geminiClientService, properties, systemPromptService
        );
    }

    @Test
    void conservaUnSessionIdConFormatoUuidValido() {
        String uuidValido = "550e8400-e29b-41d4-a716-446655440000";

        ChatResponse response = chatOrchestrationService.handle(new ChatRequest("hola", uuidValido));

        assertThat(response.sessionId()).isEqualTo(uuidValido);
    }

    @Test
    void descartaUnSessionIdArbitrarioYGeneraUnoNuevo() {
        String sessionIdSospechoso = "victima-123";

        ChatResponse response = chatOrchestrationService.handle(new ChatRequest("hola", sessionIdSospechoso));

        assertThat(response.sessionId()).isNotEqualTo(sessionIdSospechoso);
        assertThat(response.sessionId()).matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }
}
