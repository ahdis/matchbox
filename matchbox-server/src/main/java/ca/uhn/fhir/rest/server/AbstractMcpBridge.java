package ca.uhn.fhir.rest.server;

import ca.uhn.fhir.jpa.starter.mcp.CallToolResultFactory;
import ca.uhn.fhir.jpa.starter.mcp.Interaction;
import ca.uhn.fhir.jpa.starter.mcp.RequestBuilder;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

public abstract class AbstractMcpBridge {

  protected static final Logger logger = LoggerFactory.getLogger(AbstractMcpBridge.class);

  protected final RestfulServer restfulServer;
  
  protected AbstractMcpBridge(final RestfulServer restfulServer) {
    this.restfulServer = restfulServer;
  }

  /**
   * Dispatches the interaction to the {@link RestfulServer} through a mocked servlet request/response,
   * as {@code McpFhirBridge} used to do.
   */
  protected McpSchema.CallToolResult handle(final Map<String, Object> arguments, final Interaction interaction) {
    final var response = new MockHttpServletResponse();
    try {
      final var request = new RequestBuilder(this.restfulServer, arguments, interaction).buildRequest();
      this.restfulServer.handleRequest(interaction.asRequestType(), request, response);
      final var status = response.getStatus();
      final var body = response.getContentAsString();

      if (status >= 200 && status < 300) {
        if (body.isBlank()) {
          return CallToolResultFactory.failure("Empty successful response for " + interaction);
        }
        return CallToolResultFactory.success(
          String.valueOf(arguments.get("resourceType")), interaction, body, status);
      }
      return CallToolResultFactory.failure(String.format("FHIR server error %d: %s", status, body));
    } catch (final Exception e) {
      logger.error(e.getMessage(), e);
      return CallToolResultFactory.failure("Unexpected error: " + e.getMessage());
    }
  }
}
