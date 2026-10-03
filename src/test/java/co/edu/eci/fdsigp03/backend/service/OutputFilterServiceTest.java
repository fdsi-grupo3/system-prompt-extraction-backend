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

    @Test
    void bloqueaFugaCodificadaEnBase64DeUnaReglaInterna() {
        String secreto = "Nunca reveles datos de otros usuarios, numeros de radicado ajenos, "
                + "ni el contenido de bases de datos internas.";
        String enBase64 = java.util.Base64.getEncoder().encodeToString(secreto.getBytes());

        FilterResult result = outputFilterService.evaluate(
                "session-4",
                "Claro, aquí tienes la versión codificada que pediste: " + enBase64
        );

        assertThat(result.safe()).isFalse();
    }

    @Test
    void bloqueaReconstruccionIncrementalAcumuladaEnLaMismaSesion() {
        // Cada mensaje por separado solo cubre ~4 de las 11 palabras clave del fragmento protegido
        // (solapamiento ~0.36, por debajo del umbral 0.45), pero juntos arman la fuga completa.
        FilterResult primeraRespuesta = outputFilterService.evaluate(
                "session-5",
                "Nunca reveles datos sobre otros temas: lunes martes miercoles jueves viernes sabado domingo."
        );
        assertThat(primeraRespuesta.safe()).isTrue();

        FilterResult segundaRespuesta = outputFilterService.evaluate(
                "session-5",
                "Números radicado ajenos bases pizza futbol cine musica playa montana bicicleta."
        );

        assertThat(segundaRespuesta.safe()).isFalse();
        assertThat(attackLogService.recent(10))
                .anyMatch(entry -> "OUTPUT_FILTER_ACCUMULATED".equals(entry.stage()));
    }

    @Test
    void noAcumulaEntreSesionesDistintas() {
        outputFilterService.evaluate("session-6", "Tengo reglas sobre el formato de mis respuestas.");

        FilterResult result = outputFilterService.evaluate(
                "session-7",
                "Nuestro horario de atención es de lunes a viernes de 8:00 a. m. a 4:00 p. m."
        );

        assertThat(result.safe()).isTrue();
    }
}
