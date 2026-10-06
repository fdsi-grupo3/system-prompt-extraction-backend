package co.edu.eci.fdsigp03.backend.service;

import co.edu.eci.fdsigp03.backend.dto.LeakAssessment;
import co.edu.eci.fdsigp03.backend.exception.MlFilterUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Contrato HTTP con el servicio FastAPI del Output Filter de IA. */
class MlFilterClientTest {

    private MockRestServiceServer server;
    private MlFilterClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ml-filter");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MlFilterClient(builder.build(), "token-123");
    }

    @Test
    void enviaElContratoEnSnakeCaseConTokenYMapeaElVeredicto() {
        server.expect(requestTo("http://ml-filter/api/output-filter"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Filter-Token", "token-123"))
                .andExpect(jsonPath("$.response").value("respuesta"))
                .andExpect(jsonPath("$.protected_fragments[0]").value("fragmento A"))
                .andExpect(jsonPath("$.history[0]").value("turno previo"))
                .andExpect(jsonPath("$.session_id").value("s-1"))
                .andRespond(withSuccess("""
                        {"is_leak": true, "score": 0.97, "threshold": 0.6, "matched_fragment": "fragmento A",
                         "matched_view": "accumulated", "accumulated": true, "pairs_evaluated": 14,
                         "latency_ms": 23.5, "model_version": "leakfilter-v1"}
                        """, MediaType.APPLICATION_JSON));

        LeakAssessment result = client.assess("s-1", "respuesta", List.of("fragmento A"), List.of("turno previo"));

        assertThat(result).isEqualTo(new LeakAssessment(true, 0.97, 0.6, true, "fragmento A", "ML"));
        server.verify();
    }

    @Test
    void unErrorDelServicioSeTraduceEnMlFilterUnavailableException() {
        server.expect(requestTo("http://ml-filter/api/output-filter")).andRespond(withServerError());

        assertThatThrownBy(() -> client.assess("s-2", "respuesta", List.of("f"), List.of()))
                .isInstanceOf(MlFilterUnavailableException.class);
    }
}
