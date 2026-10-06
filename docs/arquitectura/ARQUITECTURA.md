# Arquitectura actual — FDSI-GP-03

Asistente institucional del INTS protegido contra la **extracción de instrucciones del sistema**.
La arquitectura *Secure* encadena tres controles alrededor del LLM. El tercero (Output Filter) es ahora un
**clasificador de IA propio, entrenado e independiente de Gemini**.

![Diagrama de arquitectura](arquitectura.png)

> Versión vectorial para diapositivas: [`arquitectura.svg`](arquitectura.svg). Los diagramas Mermaid de abajo
> se renderizan en GitHub y en VS Code (con la extensión *Markdown Preview Mermaid Support*).

---

## 1. Componentes

| Componente | Tecnología | Puerto | Responsabilidad |
|---|---|---|---|
| **Frontend** | React 19 + Vite + Tailwind | 5173 | Chat, selector Secure/Unsecure, insignias de bloqueo, pestaña de logs |
| **Backend** | Spring Boot 3.3 · Java 21 | 8080 | Orquesta las capas de seguridad, llama a Gemini y al servicio de IA |
| **Servicio de IA** (nuevo) | FastAPI + ONNX Runtime (Python) | 8001 | Decide si una respuesta filtra el system prompt |
| **Google Gemini API** | externo | — | Key `security`: clasificador del Input Guard · Key `main`: respuesta institucional |

Endpoints del backend: `POST /api/chat` (Secure), `POST /api/chat/unsecure` (sin controles, para comparar A/B),
`GET /api/logs`, `GET /health`. Endpoints del servicio de IA: `POST /api/output-filter`, `GET /health`.

---

## 2. Flujo de una solicitud (modo Secure)

```mermaid
sequenceDiagram
    autonumber
    actor U as Usuario
    participant F as Frontend :5173
    participant B as Backend :8080<br/>ChatOrchestrationService
    participant IG as Input Guard
    participant G as Gemini API
    participant OF as Output Filter<br/>(OutputFilterService)
    participant IA as Servicio IA :8001
    participant L as AttackLogService

    U->>F: escribe mensaje
    F->>B: POST /api/chat {message, sessionId}
    B->>B: valida sessionId (UUID) o genera uno nuevo
    B->>IG: evaluate(mensaje)
    IG->>IG: normaliza · regex · combinación de palabras clave
    alt patrón de ataque detectado
        IG->>L: registra INPUT_GUARD_*
        B-->>F: mensaje genérico (blockedBy = INPUT_GUARD)
    else sin patrón
        IG->>G: clasificar ALLOW/BLOCK (key «security»)
        G-->>IG: veredicto (o fallo → respaldo heurístico)
    end
    B->>G: system prompt reforzado + mensaje (key «main»)
    G-->>B: respuesta candidata
    B->>OF: evaluate(sessionId, respuesta)
    OF->>IA: POST /api/output-filter {response, protected_fragments, history}
    IA-->>OF: {is_leak, score, matched_view, accumulated}
    alt fuga
        OF->>L: registra OUTPUT_FILTER_ML(_ACCUMULATED)
        B-->>F: mensaje genérico (blockedBy = OUTPUT_FILTER)
    else segura
        OF->>OF: guarda la respuesta en el historial de la sesión
        B-->>F: respuesta del LLM
    end
    F-->>U: muestra respuesta o insignia de bloqueo
```

Cualquier bloqueo devuelve **el mismo texto genérico**, para no revelar al atacante qué capa lo detectó.
El motivo real solo queda en `GET /api/logs`.

---

## 3. Decisión del Output Filter (motores y fallback)

```mermaid
flowchart TD
    A[Respuesta candidata del LLM] --> E{OUTPUT_FILTER_ENGINE}
    E -- lexical --> LX[LexicalLeakDetector<br/>solapamiento + Levenshtein + Base64]
    E -- ml (default) --> ML[MlFilterClient → Servicio IA]
    E -- hybrid --> ML2[MlFilterClient → Servicio IA]
    ML -- responde --> D1{is_leak?}
    ML -- timeout / error --> FB[LexicalLeakDetector<br/>LEXICAL_FALLBACK]
    ML2 -- fuga --> BLK
    ML2 -- segura --> LX2[LexicalLeakDetector] --> D2{fuga léxica?}
    ML2 -- timeout / error --> FB
    D1 -- sí --> BLK[Bloquear: mensaje genérico + AttackLog]
    D1 -- no --> OK[Entregar y guardar en historial de sesión]
    D2 -- sí --> BLK
    D2 -- no --> OK
    LX --> D3{fuga?}
    FB --> D3
    D3 -- sí --> BLK
    D3 -- no --> OK
```

- **Nunca hay fail-open:** si el servicio de IA cae, la respuesta igual se evalúa con el detector léxico.
- El **historial por sesión** (últimas 5 respuestas entregadas) se envía al servicio para detectar fugas repartidas en varios turnos (Vector 4).
- Los **fragmentos protegidos** viajan en cada request desde `SystemPromptService`, que es la única fuente de verdad. El servicio de IA no los almacena.

---

## 4. Dentro del servicio de IA

```mermaid
flowchart LR
    R[response + history] --> P[Preprocesamiento<br/>NFKC · invisibles · homoglifos]
    P --> V[Vistas<br/>original · Base64 · hex · ROT13<br/>invertido · leet · acumulada]
    V --> W[Ventanas ≤ 600 car.]
    W --> X[Pares<br/>fragmento × ventana]
    X --> M[Cross-encoder<br/>MiniLM-L12 multilingüe<br/>ONNX INT8]
    M --> S[P fuga por par]
    S --> D{máx ≥ 0.37}
    D -- sí --> Y[is_leak = true<br/>+ fragmento y vista]
    D -- no --> N[is_leak = false]
```

Detalle en [`INFORME_IA.md`](INFORME_IA.md).

---

## 5. Qué cambió respecto a la arquitectura anterior

| Antes (Hito 2) | Ahora |
|---|---|
| Output Filter **léxico** dentro del backend (solapamiento de palabras + Levenshtein, umbral 0.45) | Output Filter con **clasificador Transformer propio** en un servicio aparte. El léxico queda como fallback y segunda opinión |
| Detectaba sobre todo copias literales (recall e2e 31 %) | Detecta paráfrasis, traducciones, codificaciones y homoglifos (recall e2e 95 %) |
| Bloqueaba el propio mensaje genérico de rechazo del sistema | Distingue "aplicar una regla" de "revelar una regla" (FPR 4.9 % frente a 7.8 %) |
| `OutputFilterService` hacía todo | `OutputFilterService` orquesta · `MlFilterClient` · `LexicalLeakDetector` |
| Log: `OUTPUT_FILTER`, `OUTPUT_FILTER_ACCUMULATED` | Se añaden `OUTPUT_FILTER_ML` y `OUTPUT_FILTER_ML_ACCUMULATED` (también visibles en la pestaña Logs del frontend) |
| Sin servicio Python | Proyecto `system-prompt-extraction-ml-filter`: dataset, entrenamiento, ONNX, FastAPI, evaluación |

---

## 6. Despliegue y dependencias entre procesos

```
[Frontend :5173] ──HTTP──▶ [Backend :8080] ──HTTPS──▶ [Gemini API]
                                  │
                                  └──HTTP interno──▶ [Servicio IA :8001]  (solo red interna; token X-Filter-Token opcional)
```

- Orden de arranque recomendado: servicio IA → backend → frontend. El backend funciona aunque el servicio IA no esté (usa el fallback léxico).
- El servicio IA solo necesita CPU: modelo de ~120 MB y ~1.3 GB de RAM medidos en ejecución. Existe un `Dockerfile` sin PyTorch (escrito, no probado).
- Configuración relevante del backend: `OUTPUT_FILTER_ENGINE`, `OUTPUT_FILTER_ML_URL`, `OUTPUT_FILTER_ML_*_TIMEOUT_MS` y `OUTPUT_FILTER_ML_API_TOKEN` (ver [`COMANDOS.txt`](COMANDOS.txt)).
