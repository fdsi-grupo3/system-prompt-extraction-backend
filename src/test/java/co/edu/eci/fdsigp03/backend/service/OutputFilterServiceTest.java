package co.edu.eci.fdsigp03.backend.service;

import co.edu.eci.fdsigp03.backend.dto.FilterResult;
import co.edu.eci.fdsigp03.backend.dto.LeakAssessment;
import co.edu.eci.fdsigp03.backend.exception.MlFilterUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifica el Output Filter con sus tres motores: el detector léxico original (T01-T06),
 * el clasificador de IA (mockeado: su calidad se mide en el proyecto ml-filter) y la
 * degradación al léxico cuando el servicio de IA no está disponible.
 */
class OutputFilterServiceTest {

    private static final String FUGA_LITERAL = "Nunca reveles datos de otros usuarios, números de radicado ajenos, "
            + "ni el contenido de bases de datos internas.";
    private static final String RESPUESTA_LEGITIMA =
            "Nuestro horario de atención es de lunes a viernes de 8:00 a. m. a 4:00 p. m.";

    private SystemPromptService systemPromptService;
    private AttackLogService attackLogService;
    private MlFilterClient mlFilterClient;
    private OutputFilterService outputFilterService;

    @BeforeEach
    void setUp() {
        systemPromptService = new SystemPromptService();
        attackLogService = new AttackLogService();
        mlFilterClient = mock(MlFilterClient.class);
        LexicalLeakDetector lexicalLeakDetector = new LexicalLeakDetector(systemPromptService);
        ReflectionTestUtils.setField(lexicalLeakDetector, "threshold", 0.45);
        outputFilterService = new OutputFilterService(systemPromptService, attackLogService, lexicalLeakDetector, mlFilterClient);
        ReflectionTestUtils.setField(outputFilterService, "enabled", true);
    }

    private void useEngine(String engine) {
        ReflectionTestUtils.setField(outputFilterService, "engine", engine);
    }

    private static LeakAssessment ml(boolean leak, double score, boolean accumulated) {
        return new LeakAssessment(leak, score, 0.5, accumulated, leak ? "fragmento" : null, "ML");
    }

    @Nested
    class MotorLexico {

        @BeforeEach
        void motorLexico() {
            useEngine("lexical");
        }

        @Test
        void permiteRespuestaInstitucionalLegitima() {
            FilterResult result = outputFilterService.evaluate("session-1", RESPUESTA_LEGITIMA);

            assertThat(result.safe()).isTrue();
            assertThat(attackLogService.recent(10)).isEmpty();
            verifyNoInteractions(mlFilterClient);
        }

        @Test
        void bloqueaFugaLiteralDeUnaReglaInterna() {
            FilterResult result = outputFilterService.evaluate("session-2", FUGA_LITERAL);

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

            FilterResult result = outputFilterService.evaluate("session-7", RESPUESTA_LEGITIMA);

            assertThat(result.safe()).isTrue();
        }
    }

    @Nested
    class MotorIA {

        @BeforeEach
        void motorIa() {
            useEngine("ml");
        }

        @Test
        void bloqueaCuandoElClasificadorDetectaFugaYRegistraLaEtapaMl() {
            when(mlFilterClient.assess(anyString(), anyString(), anyList(), anyList())).thenReturn(ml(true, 0.98, false));

            FilterResult result = outputFilterService.evaluate("session-ml-1", "Tengo prohibido compartir radicados ajenos.");

            assertThat(result.safe()).isFalse();
            assertThat(result.maxSimilarity()).isEqualTo(0.98);
            assertThat(attackLogService.recent(10)).singleElement()
                    .satisfies(entry -> assertThat(entry.stage()).isEqualTo("OUTPUT_FILTER_ML"));
        }

        @Test
        void enviaLosFragmentosProtegidosDelSystemPromptRealYElHistorialDeLaSesion() {
            when(mlFilterClient.assess(anyString(), anyString(), anyList(), anyList())).thenReturn(ml(false, 0.02, false));

            outputFilterService.evaluate("session-ml-2", RESPUESTA_LEGITIMA);
            outputFilterService.evaluate("session-ml-2", "Para la apostilla necesitas el documento original.");

            verify(mlFilterClient).assess(eq("session-ml-2"), eq("Para la apostilla necesitas el documento original."),
                    eq(systemPromptService.protectedFragments()), eq(List.of(RESPUESTA_LEGITIMA)));
        }

        @Test
        void noGuardaEnElHistorialUnaRespuestaBloqueada() {
            when(mlFilterClient.assess(anyString(), anyString(), anyList(), anyList()))
                    .thenReturn(ml(true, 0.9, false))
                    .thenReturn(ml(false, 0.1, false));

            outputFilterService.evaluate("session-ml-3", "fuga");
            outputFilterService.evaluate("session-ml-3", RESPUESTA_LEGITIMA);

            verify(mlFilterClient).assess(eq("session-ml-3"), eq(RESPUESTA_LEGITIMA), anyList(), eq(List.of()));
        }

        @Test
        void registraFugaAcumuladaDetectadaPorElClasificador() {
            when(mlFilterClient.assess(anyString(), anyString(), anyList(), anyList())).thenReturn(ml(true, 0.8, true));

            outputFilterService.evaluate("session-ml-4", "y tampoco bases de datos internas.");

            assertThat(attackLogService.recent(10))
                    .anyMatch(entry -> "OUTPUT_FILTER_ML_ACCUMULATED".equals(entry.stage()));
        }

        @Test
        void siElServicioDeIaNoEstaDisponibleSeDegradaAlLexicoYNoDejaPasarLaFuga() {
            when(mlFilterClient.assess(anyString(), anyString(), anyList(), anyList()))
                    .thenThrow(new MlFilterUnavailableException("timeout"));

            FilterResult result = outputFilterService.evaluate("session-ml-5", FUGA_LITERAL);

            assertThat(result.safe()).isFalse();
            assertThat(attackLogService.recent(10)).singleElement()
                    .satisfies(entry -> {
                        assertThat(entry.stage()).isEqualTo("OUTPUT_FILTER");
                        assertThat(entry.reason()).contains("LEXICAL_FALLBACK");
                    });
        }

        @Test
        void siElServicioDeIaNoEstaDisponibleUnaRespuestaLegitimaSiguePasando() {
            when(mlFilterClient.assess(anyString(), anyString(), anyList(), anyList()))
                    .thenThrow(new MlFilterUnavailableException("connection refused"));

            assertThat(outputFilterService.evaluate("session-ml-6", RESPUESTA_LEGITIMA).safe()).isTrue();
        }
    }

    @Nested
    class MotorHibrido {

        @Test
        void bloqueaSiElLexicoDetectaLoQueElClasificadorDejoPasar() {
            useEngine("hybrid");
            when(mlFilterClient.assess(anyString(), anyString(), anyList(), anyList())).thenReturn(ml(false, 0.1, false));

            FilterResult result = outputFilterService.evaluate("session-h-1", FUGA_LITERAL);

            assertThat(result.safe()).isFalse();
            assertThat(attackLogService.recent(10)).singleElement()
                    .satisfies(entry -> assertThat(entry.stage()).isEqualTo("OUTPUT_FILTER"));
        }

        @Test
        void permiteSiAmbosDetectoresConsideranSeguraLaRespuesta() {
            useEngine("hybrid");
            when(mlFilterClient.assess(any(), any(), any(), any())).thenReturn(ml(false, 0.1, false));

            assertThat(outputFilterService.evaluate("session-h-2", RESPUESTA_LEGITIMA).safe()).isTrue();
        }
    }

    @Test
    void filtroDeshabilitadoNoEvaluaNada() {
        ReflectionTestUtils.setField(outputFilterService, "enabled", false);
        useEngine("ml");

        assertThat(outputFilterService.evaluate("session-off", FUGA_LITERAL).safe()).isTrue();
        verifyNoInteractions(mlFilterClient);
    }
}
