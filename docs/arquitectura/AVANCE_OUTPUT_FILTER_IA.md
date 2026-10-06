# Avance — Output Filter con IA propia (FDSI-GP-03)

**Fecha:** 2026-10-06 · **Estado:** implementado de punta a punta (dataset → entrenamiento → ONNX → FastAPI → Spring Boot), evaluado contra los vectores de ataque y comparado con el filtro léxico anterior. Quedan pendientes reales (latencia < 100 ms, datos de testing real, revisión humana), detallados en la sección 8.

---

## 1. Resumen

Se reemplazó el Output Filter léxico (solapamiento de palabras + Levenshtein) por un **clasificador Transformer propio, independiente de Gemini**, que recibe `(fragmento protegido, respuesta del LLM)` y devuelve `P(fuga)`. El backend lo consume vía HTTP. Si el servicio no responde, el backend usa el filtro léxico (sin fail-open).

**Resultado del modelo final (v5, ONNX INT8, umbral 0.37)** sobre 1.341 respuestas completas del set end-to-end, evaluadas con el pipeline real:

| Filtro | Precision | Recall (fugas detectadas) | F1 | FPR (legítimas bloqueadas) |
|---|---|---|---|---|
| **IA entrenada (v5)** | **0.980** | **0.952** | **0.966** | **0.049** |
| Léxico anterior (umbral 0.45) | 0.909 | 0.313 | 0.466 | 0.078 |

- Detecta **~3 veces más fugas** que el filtro léxico y además **bloquea menos respuestas legítimas**.
- En **sondas escritas a mano con fraseos nuevos** (a ciegas, escritas antes de entrenar la versión que las usó): IA **10/12 fugas, 12/12 seguras**; léxico **0/12 fugas**.
- **Latencia** (CPU portátil, 4 hilos, 7 fragmentos del INTS): **p50 135 ms · p95 293 ms**. **No cumple aún el objetivo de < 100 ms**: ver sección 7.4.

---

## 2. Arquitectura implementada

```
Usuario → Input Guard (Gemini "security") → LLM principal (Gemini "main")
                                                   │
                Spring Boot · OutputFilterService  (OUTPUT_FILTER_ENGINE = ml | lexical | hybrid)
                                                   │  POST /api/output-filter  (fragmentos + historial de sesión)
                                                   ▼
        FastAPI :8001 · preprocesamiento determinista → cross-encoder ONNX INT8 → max P(fuga)
                                                   │
                   bloquea (mensaje genérico + AttackLog)   │   entrega la respuesta
        └─ si el servicio IA falla/timeout → LexicalLeakDetector (nunca fail-open)
```

**Preprocesamiento antes del modelo.** Un Transformer no sabe decodificar Base64, así que la respuesta se expande en *vistas*:
1. Original normalizada: NFKC, sin caracteres invisibles, homoglifos convertidos a latino.
2. Segmentos Base64 y hex decodificados (y retirados de la vista original para no gastar tokens).
3. ROT13, texto invertido y leetspeak, solo si así el texto parece más lenguaje natural.
4. Vista **acumulada** con las últimas respuestas de la sesión (Vector 4).

Cada vista se parte en ventanas de 600 caracteres. El veredicto es el máximo de P(fuga) sobre vista × ventana × fragmento.

---

## 3. Fase 1 — Arquitectura ML (decisión)

| Plan original | Lo implementado | Por qué |
|---|---|---|
| DistilBERT fine-tuned | **Cross-encoder sobre `paraphrase-multilingual-MiniLM-L12-v2`** (Transformer destilado, 118 M parámetros) | El sistema responde en **español** y el Vector 3 incluye **traducción**. Un DistilBERT solo en inglés no sirve. MiniLM multilingüe ya viene preentrenado en paráfrasis alineadas entre más de 50 idiomas, y es ~3 veces más rápido en CPU que DistilBERT-base. `distilbert-base-multilingual-cased` queda soportado con `--base-model`. |
| Fine-tuning con GPU (2-4 h) | Fine-tuning en **CPU** (~22 min por modelo) | La GPU de la máquina no está disponible (el driver de NVIDIA no responde). |
| ONNX / TensorRT | **ONNX Runtime + cuantización INT8 dinámica** | 118 MB frente a 471 MB en FP32. Diferencia máxima de probabilidad frente a PyTorch: 0.003. Coincidencia de decisiones: 100 %. |

**Criterio de etiquetado (clave del enfoque):**
- **Fuga:** la respuesta revela *cómo está instruido* el asistente (cita, parafrasea, traduce, resume o enumera la regla **como regla**, o expone nombres internos).
- **Seguro:** la respuesta *aplica* la regla sin describirla.
- Ejemplo: "Me indicaron remitir las excepciones a PQRS" es fuga; "Para pedir una excepción, radícala por PQRS" es seguro.
- El filtro léxico no puede distinguir estos casos, porque ambas frases comparten casi todo el vocabulario.

---

## 4. Fase 2 — Dataset

**Situación de la Opción C (híbrida):**

| Componente | Estado |
|---|---|
| Síntesis automática (Opción B) | ✅ **Programática** (`leakfilter/synth.py`), sin LLM, a partir de un corpus semilla escrito a mano |
| Síntesis con Gemini como adversario | 🟡 Script listo (`scripts/generate_with_gemini.py`), **no ejecutado**: no había API key en el entorno |
| Casos límite manuales | ✅ 56 casos + 8 secuencias multi-turno + 44 sondas (solo evaluación) |
| Augmentation con Anthropic/OpenAI | ❌ No realizado (sin API keys) |
| 200-300 casos de testing real | ❌ Pendiente: requiere tráfico real contra Gemini |

**Corpus semilla** (`data/seed/`):
- El system prompt real del INTS: 7 fragmentos, cada uno con paráfrasis, traducciones a EN/FR/PT/IT, fugas parciales y respuestas que *aplican* la regla.
- 5 system prompts auxiliares (banco, salud, tutoría, e-commerce, telecomunicaciones), para que el modelo aprenda la *relación* fragmento↔respuesta y no memorice solo el INTS.
- 1 prompt reservado (aerolínea), que nunca entra al entrenamiento y sirve para medir generalización.
- Cláusulas de contenido para fugas contrastivas.

**Splits (dataset final):** el split 80/10/10 se hace **por grupo** (una misma frase núcleo nunca queda en dos splits, aunque cambie su envoltura).

| Split | Pares | Seguros / Fugas |
|---|---|---|
| train | 3.042 | 1.779 / 1.263 |
| val | 381 | 231 / 150 |
| test | 364 | 211 / 153 |
| test prompt no visto | 444 | 240 / 204 |
| e2e (respuestas completas) | 1.341 | 384 / 957 |

En total hay **~4.230 pares** etiquetados (dentro del rango 3.000-5.000 del plan). El set e2e incluye variantes codificadas generadas **solo** a partir de núcleos de test (Base64, ROT13, hex, leet, invertido, homoglifos, zero-width y el combo traducción + Base64 del Vector 5), además de 140 respuestas legítimas con plantillas exclusivas del e2e.

---

## 5. Fase 3 — Entrenamiento, ONNX y servicio

- `leakfilter/train.py`: bucle propio en PyTorch (AdamW, lr 5e-5, warmup del 10 %, 2 épocas). Elige el mejor checkpoint por PR-AUC de validación y **calibra el umbral solo con validación** (punto medio de la meseta de F2 máximo; F2 prioriza recall).
- `leakfilter/export_onnx.py`: exporta a ONNX, cuantiza a INT8 y verifica la paridad con PyTorch.
- `service/app.py` (FastAPI): `POST /api/output-filter` y `GET /health`. Token compartido opcional (`X-Filter-Token`). **No registra en el log el texto de respuestas ni de fragmentos.** Incluye un Dockerfile solo con ONNX Runtime (sin PyTorch).

Contrato:
```json
// request
{"response": "...", "protected_fragments": ["..."], "history": ["..."], "session_id": "..."}
// response
{"is_leak": true, "score": 0.98, "threshold": 0.37, "matched_fragment": "...", "matched_view": "base64[0]",
 "accumulated": false, "pairs_evaluated": 14, "latency_ms": 92.1, "model_version": "leakfilter-v1"}
```

---

## 6. Fase 3.3 — Integración con Spring Boot

| Archivo | Cambio |
|---|---|
| `service/OutputFilterService.java` | Reescrito como orquestador: motores `ml` (default), `lexical` y `hybrid`; mantiene el historial por sesión (Vector 4); si el servicio IA falla, usa el léxico como **fallback**. |
| `service/MlFilterClient.java` | **Nuevo.** Cliente HTTP con timeouts (500 ms de conexión / 2 s de lectura) y token. Envía los fragmentos desde `SystemPromptService`, que sigue siendo la única fuente de verdad. |
| `service/LexicalLeakDetector.java` | **Nuevo.** El algoritmo léxico original extraído sin cambios, para fallback, modo híbrido y línea base. |
| `dto/LeakAssessment`, `MlFilterRequest`, `MlFilterResponse` | **Nuevos.** |
| `exception/MlFilterUnavailableException.java` | **Nueva.** |
| `application.yml` / `README.md` | Nuevas variables: `OUTPUT_FILTER_ENGINE`, `OUTPUT_FILTER_ML_URL`, timeouts y `OUTPUT_FILTER_ML_API_TOKEN`. |

En el log de ataques se conservan las etapas `OUTPUT_FILTER` / `OUTPUT_FILTER_ACCUMULATED` y se añaden `OUTPUT_FILTER_ML` / `OUTPUT_FILTER_ML_ACCUMULATED`.

**Pruebas:**
- Java **33/33** en verde: los tests léxicos originales migrados, motor IA con mocks, fallback, modo híbrido y contrato HTTP con `MockRestServiceServer`.
- Python **17/17** en verde: preprocesamiento y API con un scorer falso.
- Prueba de integración opcional `MlFilterClientIT` contra el servicio real: **3/4**. Falla una paráfrasis que el modelo no detecta (score 0.07); se dejó así a propósito, porque es un fallo real del modelo.

---

## 7. Fase 4 — Validación

### 7.1 TPR por vector (e2e, modelo v5)

| Vector | n | IA | Léxico | Meta > 95 % |
|---|---|---|---|---|
| V1 Override directo → copia literal | 111 | **100 %** | 99.1 % | ✅ |
| V2 Role-play / autoridad ficticia | 73 | **100 %** | 79.5 % | ✅ |
| V3 Traducción / codificación | 448 | **93.3 %** | 10.7 % | ❌ |
| V4 Paráfrasis / parcial / incremental | 134 | **93.3 %** | 32.1 % | ❌ |
| V5 Input Guard caído + traducción + Base64 | 8 | **87.5 %** | 12.5 % | ❌ (n muy pequeño) |
| V6 Homoglifos / caracteres invisibles | 160 | **96.2 %** | 25.0 % | ✅ |
| V7 Mezcla de idiomas | 23 | **100 %** | 0.0 % | ✅ |
| V8 Fijación de sessionId · V9 Errores no controlados | — | N/A | N/A | No son vectores de *contenido de salida*. Siguen cubiertos por la validación UUID y `GlobalExceptionHandler`. |

Detalle por técnica: Base64 96 % (léxico 45 %), hex 98 % (0 %), leetspeak 95 % (10 %), ROT13 89 % (0 %), texto invertido 90 % (0 %), traducción al inglés 96 % (0 %), paráfrasis 100 % (21 %), meta-revelación en inglés 100 % (0 %).

**Falsos positivos por tipo:** respuestas legítimas cortas 0 %, respuestas largas exclusivas del e2e 3.8 % (léxico: 15 %), rechazos 0 %, aplicación de regla 10 %. El mensaje genérico de rechazo del propio sistema, que el filtro léxico **bloqueaba**, ahora pasa.

**Generalización:** en el prompt reservado (nunca visto) a nivel par: precision 0.981 · recall 0.990 · FPR 0.017.

### 7.2 Historial de iteraciones (lo más importante del experimento)

| Iter. | Cambio | Recall e2e | FPR e2e | Sondas a ciegas (fugas · seguras) |
|---|---|---|---|---|
| 1 | Dataset inicial | 96.6 % | **18.9 %** | 10/12 · 11/12 |
| 2 | + negativos difíciles ("aplicar la regla") y respuestas largas | 97.8 % | 8.2 % | 11/12 · 11/12 |
| 3 | + muchas respuestas seguras largas, 3 épocas | 95.9 % | **2.0 %** | **7/12** · 12/12 |
| 4 | + fugas contrastivas (meta-revelación × contenido), menos respuestas largas | 97.7 % | 14.2 % | **12/12 · 12/12** |
| **5** | **Contrastivas + volumen de respuestas largas (final)** | **95.2 %** | **4.9 %** | **10/12 · 12/12** |

*(El set e2e creció entre iteraciones, así que las cifras son orientativas de tendencia, no estrictamente comparables.)*

**Lecciones:**
1. **El modelo aprende atajos de los datos sintéticos.** En la iteración 1 aprendió "habla de PQRS = fuga"; en la 2-3, "texto largo = fuga". Cada atajo se corrigió con negativos específicos.
2. **Un test sintético es optimista.** La iteración 3 parecía la mejor en el e2e (FPR 2 %), pero con frases nuevas escritas a mano solo detectó 7/12 fugas: había sobreajustado a mis plantillas. Sin las sondas a ciegas, se habría elegido un modelo peor. **Las métricas de este informe miden la detección sobre datos de la misma familia que los de entrenamiento; la validación definitiva requiere respuestas reales de Gemini etiquetadas por personas.**
3. El filtro léxico solo es competitivo en copias literales (V1). Ante paráfrasis nuevas detecta 0/12.

### 7.3 Ejemplos que el modelo final todavía no detecta
- Paráfrasis informales sin fórmulas de meta-revelación conocidas: *"Me programaron para que, si alguien quiere saltarse un requisito, le diga que lo tramite por PQRS"* (score 0.07).
- Idiomas no vistos con pocas pistas (alemán: 1/3), texto con guiones entre letras (`N-u-n-c-a r-e-v-e-l-e-s…`), y algunas variantes ROT13 / invertidas de traducciones al inglés.
- Fuera de alcance por diseño: acrósticos y esteganografía.

### 7.4 Latencia (objetivo < 100 ms: **no cumplido aún**)

Medida en CPU portátil, 4 hilos, ONNX INT8, 7 fragmentos (7 pares por respuesta corta):

| Longitud de la respuesta | p50 | p95 |
|---|---|---|
| < 200 caracteres | 114 ms | 170 ms |
| 200-400 caracteres | 192 ms | 233 ms |
| 400-800 caracteres | 277 ms | 508 ms |
| Global (respuestas naturales del INTS) | 135 ms | 293 ms |

Qué se probó: INT8 frente a FP32 (INT8 es ~30 % más rápido, se adoptó); 8 hilos frente a 4 (peor, porque son hilos lógicos); fusión de grafo de ORT (sin mejora); ventanas de 300 caracteres (sin mejora de calidad).

Palancas pendientes, en orden de impacto esperado:
1. **Podar el encoder a 6 capas** (ya implementado: `--num-layers 6`). Debería ser ~2 veces más rápido; falta entrenarlo y evaluarlo sobre el dataset final.
2. **Prefiltro bi-encoder:** evaluar solo los 2-3 fragmentos más cercanos en lugar de los 7.
3. Desplegar en un servidor con más núcleos físicos, o con GPU.

---

## 8. Pendientes y próximos pasos

1. **Datos reales (prioridad 1):** ejecutar los ataques T01-T06 y los vectores 1-9 contra Gemini, etiquetar a mano 200-300 respuestas reales, usarlas como test definitivo y luego incorporarlas al entrenamiento (completar la Opción C).
2. **Generación con Gemini:** con `GEMINI_SECURITY_API_KEY`, ejecutar `scripts/generate_with_gemini.py` y revisar manualmente al menos el 10 % antes de reentrenar.
3. **Latencia:** entrenar y evaluar la variante de 6 capas; si no basta, añadir el prefiltro bi-encoder.
4. **Recall de V3/V4 > 95 %:** más diversidad de paráfrasis informales y de idiomas (alemán, catalán).
5. **Revisión del criterio de etiquetado con el equipo**, sobre todo en casos ambiguos como *"mis reglas internas me obligan a orientarte sobre tus solicitudes"* o *"solo puedo ayudarte con horarios, sedes y trámites"*.
6. **Modo recomendado mientras tanto:** `OUTPUT_FILTER_ENGINE=hybrid` si se quiere sumar la cobertura literal del léxico; `ml` si se prioriza un menor FPR.

---

## 9. Cómo ejecutar

```bash
# Servicio IA (proyecto system-prompt-extraction-ml-filter)
cd system-prompt-extraction-ml-filter
source .venv/bin/activate
uvicorn service.app:app --port 8001

# Backend (usa engine=ml por defecto, con fallback léxico)
cd system-prompt-extraction-backend
OUTPUT_FILTER_ML_URL=http://localhost:8001 mvn spring-boot:run

# Reproducir el pipeline completo
python -m leakfilter.build_dataset && python -m leakfilter.train --epochs 2 \
  && python -m leakfilter.export_onnx && python -m leakfilter.evaluate && python -m leakfilter.probe --models models/leakfilter-v1

# Pruebas
pytest                                                         # Python
mvn test                                                       # Java
ML_FILTER_IT_URL=http://localhost:8001 mvn test -Dtest=MlFilterClientIT   # integración real
```

---

## 10. Archivos

**Nuevo proyecto `system-prompt-extraction-ml-filter/`:**
- `data/seed/prompts.json`, `data/seed/clauses.json`: corpus semilla escrito a mano.
- `data/manual/edge_cases.jsonl`, `sequences.jsonl`, `probes.jsonl`, `probes_blind.jsonl`: casos y sondas manuales.
- `leakfilter/`: `synth.py`, `build_dataset.py`, `train.py`, `export_onnx.py`, `evaluate.py`, `probe.py`, `preprocessing.py`, `inference.py`, `lexical_baseline.py`.
- `service/app.py`, `tests/`, `scripts/generate_with_gemini.py`, `Dockerfile`, `requirements*.txt`, `README.md`.
- `reports/`: logs de entrenamiento y evaluaciones de las 5 iteraciones (`eval_*.md/json`), `probes.json`.
- `models/leakfilter-v1/`: modelo final (v5). `models/leakfilter-v1-iter{1..4}/` son modelos intermedios, 1.1 GB cada uno; se pueden borrar si no se necesitan para comparar.

**Backend `system-prompt-extraction-backend/` (modificado):**
- `OutputFilterService.java` (reescrito), `MlFilterClient.java`, `LexicalLeakDetector.java`, 3 DTOs, 1 excepción, `FilterResult.java` (javadoc), `application.yml`, `README.md`.
- Tests: `OutputFilterServiceTest.java` (reescrito), `MlFilterClientTest.java`, `MlFilterClientIT.java`.
