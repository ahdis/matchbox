package ca.uhn.fhir.jpa.starter.mcp;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.RestfulServer;
import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests that the MCP tools' query and headers reach the request handled by the RestfulServer.
 */
class RequestBuilderTest {

	private RestfulServer restfulServer;

	@BeforeEach
	void setUp() {
		final ServletContext servletContext = mock(ServletContext.class);
		when(servletContext.getContextPath()).thenReturn("/matchboxv3");
		this.restfulServer = mock(RestfulServer.class);
		when(this.restfulServer.getServletContext()).thenReturn(servletContext);
		when(this.restfulServer.getFhirContext()).thenReturn(FhirContext.forR4Cached());
	}

	private MockHttpServletRequest build(final Map<String, Object> arguments, final Interaction interaction) {
		return new RequestBuilder(this.restfulServer, arguments, interaction).buildRequest();
	}

	private static Map<String, Object> arguments(final Object... keyValues) {
		final Map<String, Object> arguments = new HashMap<>();
		arguments.put("resourceType", "Patient");
		for (int i = 0; i < keyValues.length; i += 2) {
			arguments.put((String) keyValues[i], keyValues[i + 1]);
		}
		return arguments;
	}

	@Test
	void searchWithMapQuery() {
		final var req = build(arguments("query", Map.of("_tag", "http://matchbox.health/fhir/CodeSystem/tag|current")),
									 Interaction.SEARCH);
		assertEquals("GET", req.getMethod());
		assertEquals("/matchboxv3/fhir/Patient", req.getRequestURI());
		assertEquals("_tag=http%3A%2F%2Fmatchbox.health%2Ffhir%2FCodeSystem%2Ftag%7Ccurrent", req.getQueryString());
		assertEquals("http://matchbox.health/fhir/CodeSystem/tag|current", req.getParameter("_tag"));
	}

	@Test
	void searchWithStringQuery() {
		final var req = build(arguments("query", "_id=pt-1,name=ivan,peter&birthdate=ge2000"), Interaction.SEARCH);
		assertEquals("_id=pt-1&name=ivan%2Cpeter&birthdate=ge2000", req.getQueryString());
		assertEquals("pt-1", req.getParameter("_id"));
		assertEquals("ivan,peter", req.getParameter("name"));
		assertEquals("ge2000", req.getParameter("birthdate"));
	}

	@Test
	void updateWithId() {
		final var req = build(arguments("id", "pt-1", "resource", Map.of("resourceType", "Patient", "id", "pt-1")),
									 Interaction.UPDATE);
		assertEquals("PUT", req.getMethod());
		assertEquals("/matchboxv3/fhir/Patient/pt-1", req.getRequestURI());
		assertNull(req.getQueryString());
	}

	@Test
	void conditionalUpdate() {
		final var req = build(arguments("query", "identifier=urn:oid:1.2.3|42",
												  "resource", Map.of("resourceType", "Patient"),
												  "headers", Map.of("If-Match", "W/\"1\"")),
									 Interaction.UPDATE);
		assertEquals("PUT", req.getMethod());
		assertEquals("/matchboxv3/fhir/Patient", req.getRequestURI());
		assertEquals("identifier=urn%3Aoid%3A1.2.3%7C42", req.getQueryString());
		assertEquals("W/\"1\"", req.getHeader("If-Match"));
		assertEquals("application/fhir+json", req.getContentType());
	}

	@Test
	void conditionalPatch() throws Exception {
		final var req = build(arguments("query", "_id=pt-1",
												  "resource", Map.of("resourceType", "Parameters")),
									 Interaction.PATCH);
		assertEquals("PATCH", req.getMethod());
		assertEquals("/matchboxv3/fhir/Patient", req.getRequestURI());
		assertEquals("_id=pt-1", req.getQueryString());
		assertEquals("application/fhir+json", req.getContentType());
		assertEquals("{\"resourceType\":\"Parameters\"}", new String(req.getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8));
	}

	@Test
	void jsonPatch() throws Exception {
		final var req = build(arguments("id", "pt-1",
												  "resource", java.util.List.of(Map.of("op", "remove", "path", "/active"))),
									 Interaction.PATCH);
		assertEquals("/matchboxv3/fhir/Patient/pt-1", req.getRequestURI());
		assertEquals("application/json-patch+json", req.getContentType());
		assertTrue(new String(req.getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8).contains("\"op\":\"remove\""));
	}

	@Test
	void updateWithoutIdOrQueryFails() {
		assertThrows(IllegalArgumentException.class,
						 () -> build(arguments("resource", Map.of("resourceType", "Patient")), Interaction.UPDATE));
	}

	@Test
	void conditionalCreateHeader() {
		final var req = build(arguments("resource", Map.of("resourceType", "Patient"),
												  "headers", Map.of("If-None-Exist", "identifier=urn:oid:1.2.3|42")),
									 Interaction.CREATE);
		assertEquals("POST", req.getMethod());
		assertEquals("identifier=urn:oid:1.2.3|42", req.getHeader("If-None-Exist"));
	}
}
