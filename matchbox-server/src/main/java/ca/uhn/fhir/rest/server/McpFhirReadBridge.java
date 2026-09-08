package ca.uhn.fhir.rest.server;

import ca.uhn.fhir.jpa.starter.mcp.CallToolResultFactory;
import ca.uhn.fhir.jpa.starter.mcp.Interaction;
import ca.uhn.fhir.jpa.starter.mcp.McpServerConfig;
import ca.uhn.fhir.jpa.starter.mcp.RequestBuilder;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP tools for read-only access to the FHIR server (read + search).
 * <p>
 * Registered when {@code matchbox.fhir.context.onlyOneEngine=true} (see {@link McpServerConfig}).
 */
public class McpFhirReadBridge {

  private static final Logger logger = LoggerFactory.getLogger(McpFhirReadBridge.class);

  private final RestfulServer restfulServer;

  public McpFhirReadBridge(final RestfulServer restfulServer) {
    this.restfulServer = restfulServer;
  }

  @McpTool(name = "read-fhir-resource",
    description = "Read an individual FHIR resource",
    annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
  private McpSchema.CallToolResult readFhirResource(
    @McpToolParam(description = "type of the FHIR conformance resource to read", required = false)
    final @Nullable String resourceType,
    @McpToolParam(description = "id of the resource to read", required = false)
    final @Nullable String id
  ) {
    final HashMap<String, Object> arguments = HashMap.newHashMap(2);
    if (resourceType != null) {
      arguments.put("resourceType", resourceType);
    }
    if (id != null) {
      arguments.put("id", id);
    }
    return this.handle(arguments, Interaction.READ);
  }

  @McpTool(name = "search-fhir-resources",
    description = "Search an existing FHIR resources",
    annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
  private McpSchema.CallToolResult searchFhirResources(
    @McpToolParam(description = "Type of the FHIR conformance resource to search")
    final String resourceType,
    @McpToolParam(description = "Query string with search params separate by \",\". For example: \"_id=pt-1,name=ivan\"")
    final String query
  ) {
    final HashMap<String, Object> arguments = HashMap.newHashMap(2);
    arguments.put("resourceType", resourceType);
    arguments.put("query", query);
    return this.handle(arguments, Interaction.SEARCH);
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
