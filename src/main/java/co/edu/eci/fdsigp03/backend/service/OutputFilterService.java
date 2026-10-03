package co.edu.eci.fdsigp03.backend.service;

import co.edu.eci.fdsigp03.backend.dto.FilterResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p>
 * Dos mitigaciones adicionales (ver {@code docs/seguridad/vectores-ataque.md}):
 * <ul>
 *     <li><b>Vector 3 (exfiltración codificada)</b>: antes de comparar, se buscan
 *     substrings con forma de Base64 en la respuesta y, si decodifican a texto
 *     imprimible, ese texto decodificado también se compara contra los fragmentos
 *     protegidos.</li>
 *     <li><b>Vector 4 (fuga parafraseada incremental)</b>: además de evaluar la
 *     respuesta actual de forma aislada, se mantiene un acumulado en memoria de las
 *     últimas respuestas entregadas en la misma sesión y se evalúa también esa
 *     concatenación, para detectar reconstrucciones del prompt repartidas en varios
 *     turnos que individualmente no superarían el umbral.</li>
 * </ul>
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

    /** Cuántas respuestas previas por sesión se conservan para el chequeo acumulado (Vector 4). */
    private static final int ACCUMULATION_WINDOW = 5;

    /** Substrings candidatos a ser Base64 (alfabeto válido, longitud mínima para evitar ruido). */
    private static final Pattern BASE64_CANDIDATE = Pattern.compile("[A-Za-z0-9+/]{16,}={0,2}");

    private final SystemPromptService systemPromptService;
    private final AttackLogService attackLogService;

    /** Historial de respuestas ya entregadas por sesión, usado para el chequeo acumulado. */
    private final Map<String, Deque<String>> sessionHistory = new ConcurrentHashMap<>();

    @Value("${app.output-filter.enabled:true}")
    private boolean enabled;

    @Value("${app.output-filter.threshold:0.45}")
    private double threshold;

    private final LevenshteinDistance levenshteinDistance = LevenshteinDistance.getDefaultInstance();

    public FilterResult evaluate(String sessionId, String candidateResponse) {
        if (!enabled || candidateResponse == null || candidateResponse.isBlank()) {
            return new FilterResult(true, 0.0, null);
        }

        String decoded = decodeEmbeddedBase64(candidateResponse);
        String textToScan = decoded.isEmpty() ? candidateResponse : candidateResponse + " " + decoded;

        List<String> responseSentences = splitSentences(textToScan);
        double maxSimilarity = 0.0;
        String matchedFragment = null;

        for (String secretFragment : systemPromptService.protectedFragments()) {
            double fullScore = similarity(textToScan, secretFragment);
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

        boolean individualSafe = maxSimilarity < threshold;

        double accumulatedSimilarity = 0.0;
        if (individualSafe) {
            accumulatedSimilarity = accumulatedSimilarityFor(sessionId, textToScan);
            if (accumulatedSimilarity > maxSimilarity) {
                maxSimilarity = accumulatedSimilarity;
            }
        }

        boolean safe = maxSimilarity < threshold;
        if (!safe) {
            boolean dueToAccumulation = individualSafe && accumulatedSimilarity >= threshold;
            String reason = (dueToAccumulation
                    ? "Similitud acumulada %.2f (umbral %.2f) al combinar con respuestas previas de la sesión"
                    : "Similitud %.2f (umbral %.2f) contra fragmento protegido del system prompt")
                    .formatted(maxSimilarity, threshold);
            attackLogService.record(sessionId, dueToAccumulation ? "OUTPUT_FILTER_ACCUMULATED" : "OUTPUT_FILTER",
                    candidateResponse, reason);
            log.info("[Output Filter] respuesta bloqueada sessionId={} similitud={} acumulado={}",
                    sessionId, maxSimilarity, dueToAccumulation);
        } else {
            rememberResponse(sessionId, candidateResponse);
        }

        return new FilterResult(safe, maxSimilarity, matchedFragment);
    }

    /** Compara el acumulado de respuestas previas + la actual contra cada fragmento protegido. */
    private double accumulatedSimilarityFor(String sessionId, String currentResponse) {
        Deque<String> history = sessionHistory.get(sessionId);
        if (history == null || history.isEmpty()) {
            return 0.0;
        }

        String accumulated = String.join(" ", history) + " " + currentResponse;
        double max = 0.0;
        for (String secretFragment : systemPromptService.protectedFragments()) {
            double score = similarity(accumulated, secretFragment);
            if (score > max) {
                max = score;
            }
        }
        return max;
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

    /**
     * Busca substrings con forma de Base64 en el texto y devuelve la concatenación de los que
     * decodifican a texto imprimible (UTF-8), para que también se comparen contra los fragmentos
     * protegidos. Devuelve cadena vacía si no hay ninguno decodificable.
     */
    private String decodeEmbeddedBase64(String text) {
        StringBuilder decodedText = new StringBuilder();
        Matcher matcher = BASE64_CANDIDATE.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group();
            try {
                byte[] raw = Base64.getDecoder().decode(candidate);
                String asText = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
                if (isMostlyPrintable(asText)) {
                    decodedText.append(' ').append(asText);
                }
            } catch (IllegalArgumentException ignored) {
                // No es Base64 válido, se ignora el substring.
            }
        }
        return decodedText.toString().trim();
    }

    private boolean isMostlyPrintable(String text) {
        if (text.isBlank()) {
            return false;
        }
        long printable = text.chars().filter(c -> c >= 32 && c < 127 || Character.isLetter(c)).count();
        return printable >= text.length() * 0.85;
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
