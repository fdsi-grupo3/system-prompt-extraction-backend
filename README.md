# FDSI-GP-03 · Backend

API que orquesta el asistente institucional del seminario FDSI/SPTI 2026-2 —
tema **Extracción de instrucciones del sistema**.

Este repo es solo el backend. El frontend vive en un repo hermano dentro de la
misma organización: `fdsi-gp03-frontend`.

## Stack

- Java 21
- Spring Boot 3.3 (Web, Validation)
- Maven

## Requisitos

- JDK 21
- Maven 3.9+ (o usar el wrapper cuando se agregue)

## Configuración

Variables de entorno (ver `src/main/resources/application.yml`):

| Variable         | Descripción                              | Default                  |
|------------------|-------------------------------------------|---------------------------|
| `GEMINI_API_KEY` | API key del modelo LLM (Gemini)           | *(vacío)*                 |
| `FRONTEND_URL`   | Origen permitido para CORS                | `http://localhost:5173`   |

## Desarrollo local

```bash
export GEMINI_API_KEY=tu_api_key
mvn spring-boot:run
```

Verificar que levantó:

```bash
curl http://localhost:8080/health
```

## Estructura

```
controller/   endpoints REST (health, /chat en el Hito 2)
service/      orquestación de la llamada al LLM, Input Guard, Output Filter
config/       configuración de CORS, beans del cliente LLM
dto/          contratos de request/response
```

## Estado

Hito 1 (05/09): solo esqueleto del proyecto y endpoint `/health`, sin lógica de negocio.
El endpoint `/chat` (arquitecturas Unsecure y Secure) se implementa en el Hito 2.
