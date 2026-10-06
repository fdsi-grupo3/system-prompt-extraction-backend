package co.edu.eci.fdsigp03.backend.service;

import co.edu.eci.fdsigp03.backend.dto.FilterResult;
import co.edu.eci.fdsigp03.backend.dto.LeakAssessment;
import co.edu.eci.fdsigp03.backend.exception.MlFilterUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Output Filter: decide si la respuesta generada por el LLM principal puede entregarse al
 * usuario o filtra información del system prompt protegido.
 * <p>
 * El detector se elige con {@code app.output-filter.engine}:
 * <ul>
 *     <li><b>ml</b> (default): clasificador de IA propio ({@link MlFilterClient}), entrenado para
 *     distinguir una respuesta que <i>aplica</i> una regla de una que la <i>revela</i>, incluso
 *     parafraseada, traducida, codificada o repartida en varios turnos. Si el servicio de IA no
 *     está disponible, se degrada al detector léxico en vez de dejar pasar la respuesta sin
 *     evaluar (lección del Vector 5: nada de fail-open).</li>
 *     <li><b>lexical</b>: el detector léxico original ({@link LexicalLeakDetector}).</li>
 *     <li><b>hybrid</b>: bloquea si cualquiera de los dos detecta una fuga.</li>
 * </ul>
 * <p>
 * Para el Vector 4 (reconstrucción incremental) este servicio mantiene, por sesión, las últimas
 * respuestas ya entregadas y se las pasa al detector, que evalúa también su concatenación con
 * la respuesta actual.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OutputFilterService {

    /** Cuántas respuestas previas por sesión se conservan para el chequeo acumulado (Vector 4). */
    private static final int ACCUMULATION_WINDOW = 5;

    private final SystemPromptService systemPromptService;
    private final AttackLogService attackLogService;
    private final LexicalLeakDetector lexicalLeakDetector;
    private final MlFilterClient mlFilterClient;

    /** Historial de respuestas ya entregadas por sesión, usado para el chequeo acumulado. */
    private final Map<String, Deque<String>> sessionHistory = new ConcurrentHashMap<>();

    @Value("${app.output-filter.enabled:true}")
    private boolean enabled;

    @Value("${app.output-filter.engine:ml}")
    private String engine;

    public FilterResult evaluate(String sessionId, String candidateResponse) {
        if (!enabled || candidateResponse == null || candidateResponse.isBlank()) {
            return new FilterResult(true, 0.0, null);
        }

        List<String> history = historySnapshot(sessionId);
        LeakAssessment assessment = switch (engine.toLowerCase(Locale.ROOT)) {
            case "lexical" -> lexicalLeakDetector.assess(candidateResponse, history);
            case "hybrid" -> hybrid(sessionId, candidateResponse, history);
            default -> mlOrLexicalFallback(sessionId, candidateResponse, history);
        };

        if (assessment.leak()) {
            String stage = stageFor(assessment);
            String reason = (assessment.accumulated()
                    ? "[%s] puntuación acumulada %.2f (umbral %.2f) al combinar con respuestas previas de la sesión"
                    : "[%s] puntuación %.2f (umbral %.2f) contra fragmento protegido del system prompt")
                    .formatted(assessment.engine(), assessment.score(), assessment.threshold());
            attackLogService.record(sessionId, stage, candidateResponse, reason);
            log.info("[Output Filter] respuesta bloqueada sessionId={} motor={} puntuacion={} acumulado={}",
                    sessionId, assessment.engine(), assessment.score(), assessment.accumulated());
        } else {
            rememberResponse(sessionId, candidateResponse);
        }

        return new FilterResult(!assessment.leak(), assessment.score(),
                assessment.leak() ? assessment.matchedFragment() : null);
    }

    private LeakAssessment mlOrLexicalFallback(String sessionId, String candidateResponse, List<String> history) {
        try {
            return mlFilterClient.assess(sessionId, candidateResponse, systemPromptService.protectedFragments(), history);
        } catch (MlFilterUnavailableException ex) {
            log.warn("[Output Filter] servicio de IA no disponible, se usa el detector léxico. sessionId={} motivo={}",
                    sessionId, ex.getMessage());
            LeakAssessment lexical = lexicalLeakDetector.assess(candidateResponse, history);
            return new LeakAssessment(lexical.leak(), lexical.score(), lexical.threshold(),
                    lexical.accumulated(), lexical.matchedFragment(), "LEXICAL_FALLBACK");
        }
    }

    private LeakAssessment hybrid(String sessionId, String candidateResponse, List<String> history) {
        LeakAssessment ml = mlOrLexicalFallback(sessionId, candidateResponse, history);
        if (ml.leak() || "LEXICAL_FALLBACK".equals(ml.engine())) {
            return ml;
        }
        LeakAssessment lexical = lexicalLeakDetector.assess(candidateResponse, history);
        return lexical.leak() ? lexical : ml;
    }

    /**
     * Etapa registrada en el log de ataques. Se conservan los nombres originales del detector
     * léxico (OUTPUT_FILTER / OUTPUT_FILTER_ACCUMULATED) para no romper la auditoría existente.
     */
    private String stageFor(LeakAssessment assessment) {
        String base = "ML".equals(assessment.engine()) ? "OUTPUT_FILTER_ML" : "OUTPUT_FILTER";
        return assessment.accumulated() ? base + "_ACCUMULATED" : base;
    }

    private List<String> historySnapshot(String sessionId) {
        Deque<String> history = sessionHistory.get(sessionId);
        if (history == null) {
            return List.of();
        }
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    private void rememberResponse(String sessionId, String response) {
        Deque<String> history = sessionHistory.computeIfAbsent(sessionId, key -> new ArrayDeque<>());
        synchronized (history) {
            history.addLast(response);
            while (history.size() > ACCUMULATION_WINDOW) {
                history.removeFirst();
            }
        }
    }
}
