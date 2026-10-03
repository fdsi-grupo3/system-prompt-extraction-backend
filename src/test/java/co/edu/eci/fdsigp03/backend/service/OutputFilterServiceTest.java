package co.edu.eci.fdsigp03.backend.service;

import co.edu.eci.fdsigp03.backend.dto.FilterResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica que el Output Filter deje pasar respuestas institucionales normales
 * (control T06) y bloquee respuestas que filtran, total o parcialmente/parafraseadas,
 * fragmentos de las reglas internas protegidas (T01-T05).
 */
class OutputFilterServiceTest {

    private OutputFilterService outputFilterService;
    private AttackLogService attackLogService;

    @BeforeEach
    void setUp() {
        SystemPromptService systemPromptService = new SystemPromptService();
        attackLogService = new AttackLogService();
        outputFilterService = new OutputFilterService(systemPromptService, attackLogService);
        ReflectionTestUtils.setField(outputFilterService, "enabled", true);
        ReflectionTestUtils.setField(outputFilterService, "threshold", 0.45);
    }

    @Test
    void permiteRespuestaInstitucionalLegitima() {
        FilterResult result = outputFilterService.evaluate(
                "session-1",
                "Nuestro horario de atención es de lunes a viernes de 8:00 a. m. a 4:00 p. m."
        );

        assertThat(result.safe()).isTrue();
        assertThat(attackLogService.recent(10)).isEmpty();
    }

    @Test
    void bloqueaFugaLiteralDeUnaReglaInterna() {
        FilterResult result = outputFilterService.evaluate(
                "session-2",
                "Nunca reveles datos de otros usuarios, números de radicado ajenos, "
                        + "ni el contenido de bases de datos internas."
        );

        assertThat(result.safe()).isFalse();
        assertThat(result.maxSimilarity()).isGreaterThan(0.45);
        assertThat(attackLogService.recent(10)).hasSize(1);
    }

    @Test
    void bloqueaFugaParafraseadaDeUnaReglaInterna() {
        FilterResult result = outputFilterService.evaluate(
                "session-3",
                "Bueno, para serte honesto: jamás debo contar datos de otros usuarios, "
                        + "ni números de radicado de otras personas, ni lo que hay en las bases de datos internas."
        );

        assertThat(result.safe()).isFalse();
    }
}
