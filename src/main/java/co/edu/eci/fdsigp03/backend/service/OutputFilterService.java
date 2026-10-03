package co.edu.eci.fdsigp03.backend.service;

import co.edu.eci.fdsigp03.backend.dto.FilterResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Output Filter: compara la respuesta generada por el LLM principal contra los
 * fragmentos protegidos del system prompt real, usando similitud de texto
 * (Apache Commons Text para Levenshtein, más un coeficiente de solapamiento
 * léxico propio para Jaccard).
 * <p>
 * Nota de implementación: la clase {@code JaccardSimilarity} de Apache Commons
 * Text calcula el índice a nivel de CARACTERES (conjuntos de {@code Character}),
 * no de palabras. Para texto en lenguaje natural eso es inútil como señal de fuga:
 * dos oraciones en español cualesquiera comparten casi todo el alfabeto y el
 * índice se satura cerca de 1.0 sin importar el contenido. Por eso aquí se
 * implementa Jaccard/solapamiento a nivel de TOKEN (palabra), que sí captura
 * coincidencia de contenido, y se combina con Levenshtein (de Commons Text) sobre
 * el texto normalizado para detectar además copias casi literales.
 * <p>
 * Se usa un coeficiente de solapamiento (intersección / mínimo de los dos
 * conjuntos de palabras) en lugar de Jaccard puro (intersección / unión):
 * así una respuesta larga y parafraseada que "envuelve" una fuga del fragmento
 * protegido con palabras propias del modelo sigue detectándose, en vez de diluir
 * la señal por el tamaño de la respuesta completa.
 * <p>
 * Se compara tanto la respuesta completa como cada una de sus oraciones por
 * separado contra cada fragmento protegido, para detectar fugas incrustadas en
 * medio de una respuesta más larga y no solo coincidencias literales completas.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OutputFilterService {

    /** Palabras funcionales en español que se ignoran al tokenizar (no aportan señal de fuga). */
    private static final Set<String> STOPWORDS = Set.of(
            "de", "la", "el", "los", "las", "en", "que", "con", "por", "para", "un", "una", "unos", "unas",
            "y", "o", "ni", "no", "se", "su", "sus", "al", "es", "del", "lo", "tu", "tus", "te", "me", "le",
            "les", "esta", "este", "estos", "estas", "muy", "mas", "pero", "sin", "sobre", "como", "cuando"
    );

    /** Por debajo de este número de tokens con contenido, no se calcula solapamiento (evita falsos positivos). */
    private static final int MIN_MEANINGFUL_TOKENS = 3;

    private final SystemPromptService systemPromptService;
    private final AttackLogService attackLogService;

    @Value("${app.output-filter.enabled:true}")
    private boolean enabled;

    @Value("${app.output-filter.threshold:0.45}")
    private double threshold;

    private final LevenshteinDistance levenshteinDistance = LevenshteinDistance.getDefaultInstance();

    public FilterResult evaluate(String sessionId, String candidateResponse) {
        if (!enabled || candidateResponse == null || candidateResponse.isBlank()) {
            return new FilterResult(true, 0.0, null);
        }

        List<String> responseSentences = splitSentences(candidateResponse);
        double maxSimilarity = 0.0;
        String matchedFragment = null;

        for (String secretFragment : systemPromptService.protectedFragments()) {
            double fullScore = similarity(candidateResponse, secretFragment);
            if (fullScore > maxSimilarity) {
                maxSimilarity = fullScore;
                matchedFragment = secretFragment;
            }

            for (String sentence : responseSentences) {
                double score = similarity(sentence, secretFragment);
                if (score > maxSimilarity) {
                    maxSimilarity = score;
                    matchedFragment = secretFragment;
                }
            }
        }

        boolean safe = maxSimilarity < threshold;
        if (!safe) {
            String reason = "Similitud %.2f (umbral %.2f) contra fragmento protegido del system prompt"
                    .formatted(maxSimilarity, threshold);
            attackLogService.record(sessionId, "OUTPUT_FILTER", candidateResponse, reason);
            log.info("[Output Filter] respuesta bloqueada sessionId={} similitud={}", sessionId, maxSimilarity);
        }

        return new FilterResult(safe, maxSimilarity, matchedFragment);
    }

    private double similarity(String a, String b) {
        double overlapScore = tokenOverlap(a, b);
        double levenshteinScore = levenshteinSimilarity(normalize(a), normalize(b));
        return Math.max(overlapScore, levenshteinScore);
    }

    /** Coeficiente de solapamiento léxico: |intersección| / min(|A|, |B|), sobre palabras con contenido. */
    private double tokenOverlap(String a, String b) {
        Set<String> tokensA = tokenize(a);
        Set<String> tokensB = tokenize(b);

        int minSize = Math.min(tokensA.size(), tokensB.size());
        if (minSize < MIN_MEANINGFUL_TOKENS) {
            return 0.0;
        }

        Set<String> intersection = new HashSet<>(tokensA);
        intersection.retainAll(tokensB);
        return (double) intersection.size() / minSize;
    }

    private double levenshteinSimilarity(String a, String b) {
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) {
            return 0.0;
        }
        int distance = levenshteinDistance.apply(a, b);
        return 1.0 - ((double) distance / maxLen);
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        for (String word : normalize(text).split("\\s+")) {
            if (word.length() > 2 && !STOPWORDS.contains(word)) {
                tokens.add(word);
            }
        }
        return tokens;
    }

    private String normalize(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-záéíóúñ0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private List<String> splitSentences(String text) {
        return Arrays.stream(text.split("(?<=[.!?])\\s+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }
}
