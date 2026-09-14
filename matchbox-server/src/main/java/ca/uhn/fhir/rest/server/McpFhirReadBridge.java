package ca.uhn.fhir.rest.server;

import ca.uhn.fhir.jpa.starter.mcp.Interaction;
import ca.uhn.fhir.jpa.starter.mcp.McpServerConfig;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import java.util.HashMap;

/**
 * MCP tools for read-only access to the FHIR server (read + search).
 * <p>
 * Registered when {@code matchbox.fhir.context.onlyOneEngine=true} (see {@link McpServerConfig}).
 */
public class McpFhirReadBridge extends AbstractMcpBridge {

  public McpFhirReadBridge(final RestfulServer restfulServer) {
    super(restfulServer);
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
}
