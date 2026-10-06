package co.edu.eci.fdsigp03.backend.service;

import co.edu.eci.fdsigp03.backend.dto.LeakAssessment;
import lombok.RequiredArgsConstructor;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detector léxico de fugas (el Output Filter original del Hito 2): compara la respuesta
 * del LLM principal contra los fragmentos protegidos del system prompt usando un
 * coeficiente de solapamiento por palabras más Levenshtein normalizado.
 * <p>
 * Ya no es el detector principal (ver {@link MlFilterClient}), pero se conserva como:
 * <ul>
 *     <li><b>fallback</b> cuando el servicio de IA no está disponible, para no caer en un
 *     fail-open (lección del Vector 5);</li>
 *     <li>segunda opinión en el modo {@code hybrid};</li>
 *     <li>línea base para la comparación A/B del experimento.</li>
 * </ul>
 * <p>
 * Nota de implementación: la clase {@code JaccardSimilarity} de Apache Commons
 * Text calcula el índice a nivel de CARACTERES, lo que para texto en lenguaje natural
 * se satura cerca de 1.0 sin importar el contenido. Por eso aquí se implementa el
 * solapamiento a nivel de TOKEN (intersección / mínimo de los dos conjuntos), que no se
 * diluye cuando una fuga va "envuelta" en una respuesta larga, y se combina con
 * Levenshtein para detectar copias casi literales. Se compara la respuesta completa y
 * cada oración, se decodifican substrings Base64 (Vector 3) y se evalúa el acumulado de
 * la sesión (Vector 4).
 */
@Component
@RequiredArgsConstructor
public class LexicalLeakDetector {

    /** Palabras funcionales en español que se ignoran al tokenizar (no aportan señal de fuga). */
    private static final Set<String> STOPWORDS = Set.of(
            "de", "la", "el", "los", "las", "en", "que", "con", "por", "para", "un", "una", "unos", "unas",
            "y", "o", "ni", "no", "se", "su", "sus", "al", "es", "del", "lo", "tu", "tus", "te", "me", "le",
            "les", "esta", "este", "estos", "estas", "muy", "mas", "pero", "sin", "sobre", "como", "cuando"
    );

    /** Por debajo de este número de tokens con contenido, no se calcula solapamiento (evita falsos positivos). */
    private static final int MIN_MEANINGFUL_TOKENS = 3;

    /** Substrings candidatos a ser Base64 (alfabeto válido, longitud mínima para evitar ruido). */
    private static final Pattern BASE64_CANDIDATE = Pattern.compile("[A-Za-z0-9+/]{16,}={0,2}");

    private final SystemPromptService systemPromptService;

    @Value("${app.output-filter.threshold:0.45}")
    private double threshold;

    private final LevenshteinDistance levenshteinDistance = LevenshteinDistance.getDefaultInstance();

    /**
     * Evalúa la respuesta de forma aislada y, si no supera el umbral, también concatenada con
     * las respuestas previas de la sesión.
     */
    public LeakAssessment assess(String candidateResponse, List<String> history) {
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

        if (maxSimilarity >= threshold) {
            return new LeakAssessment(true, maxSimilarity, threshold, false, matchedFragment, "LEXICAL");
        }

        if (history != null && !history.isEmpty()) {
            String accumulated = String.join(" ", history) + " " + textToScan;
            for (String secretFragment : systemPromptService.protectedFragments()) {
                double score = similarity(accumulated, secretFragment);
                if (score >= threshold) {
                    return new LeakAssessment(true, score, threshold, true, secretFragment, "LEXICAL");
                }
            }
        }

        return new LeakAssessment(false, maxSimilarity, threshold, false, matchedFragment, "LEXICAL");
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
