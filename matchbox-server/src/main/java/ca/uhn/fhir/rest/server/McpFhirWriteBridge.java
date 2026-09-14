package ca.uhn.fhir.rest.server;

import ca.uhn.fhir.jpa.starter.mcp.Interaction;
import ca.uhn.fhir.jpa.starter.mcp.McpServerConfig;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP tools for write access to the FHIR server: create / update / patch / delete / transaction.
 * <p>
 * Registered when {@code matchbox.fhir.context.onlyOneEngine=true} and
 * {@code matchbox.fhir.context.httpReadOnly=false} (see {@link McpServerConfig}).
 */
public class McpFhirWriteBridge extends AbstractMcpBridge {
  
  public McpFhirWriteBridge(final RestfulServer restfulServer) {
    super(restfulServer);
  }
  
  @McpTool(name = "create-fhir-resource",
    description = "Create a new FHIR resource")
  private McpSchema.CallToolResult createFhirResource(
    @McpToolParam(description = "Type of the FHIR conformance resource to create")
    final String resourceType,
    @McpToolParam(description = "Resource content in JSON format")
    final Map<String, Object> resource,
    @McpToolParam(description = "Headers for create request.\nAvailable headers: If-None-Exist header for conditional create where the value is search param string.\nFor example: {\"If-None-Exist\": \"active=false\"}",
      required = false)
    final @Nullable Map<String, Object> headers
  ) {
    final HashMap<String, Object> arguments = HashMap.newHashMap(3);
    arguments.put("resourceType", resourceType);
    arguments.put("resource", resource);
    if (headers != null) {
      arguments.put("headers", headers);
    }
    return this.handle(arguments, Interaction.CREATE);
  }

  @McpTool(name = "update-fhir-resource",
    description = "Update an existing FHIR resource")
  private McpSchema.CallToolResult updateFhirResource(
    @McpToolParam(description = "Type of the FHIR conformance resource to update")
    final String resourceType,
    @McpToolParam(description = "ID of the resource to update")
    final String id,
    @McpToolParam(description = "Updated resource content in JSON format")
    final Map<String, Object> resource
  ) {
    final HashMap<String, Object> arguments = HashMap.newHashMap(3);
    arguments.put("resourceType", resourceType);
    arguments.put("id", id);
    arguments.put("resource", resource);
    return this.handle(arguments, Interaction.UPDATE);
  }

  @McpTool(name = "conditional-update-fhir-resource",
    description = "Conditional update an existing FHIR resource")
  private McpSchema.CallToolResult conditionalUpdateFhirResource(
    @McpToolParam(description = "Type of the FHIR conformance resource to update")
    final String resourceType,
    @McpToolParam(description = "Updated resource content in JSON format")
    final Map<String, Object> resource,
    @McpToolParam(description = "Query string with search params separate by \",\". For example: \"_id=pt-1,name=ivan\". Uses for conditional update.",
      required = false)
    final @Nullable String query,
    @McpToolParam(description = "Headers for create request.\nAvailable headers: If-None-Match header for conditional update where the value is ETag.\nFor example: {\"If-None-Match\": \"12345\"}",
      required = false)
    final @Nullable Map<String, Object> headers
  ) {
    final HashMap<String, Object> arguments = HashMap.newHashMap(4);
    arguments.put("resourceType", resourceType);
    arguments.put("resource", resource);
    if (query != null) {
      arguments.put("query", query);
    }
    if (headers != null) {
      arguments.put("headers", headers);
    }
    return this.handle(arguments, Interaction.UPDATE);
  }

  @McpTool(name = "patch-fhir-resource",
    description = "Patch an existing FHIR resource")
  private McpSchema.CallToolResult patchFhirResource(
    @McpToolParam(description = "Type of the FHIR conformance resource to patch")
    final String resourceType,
    @McpToolParam(description = "ID of the FHIR resource to patch")
    final String id,
    @McpToolParam(description = "Resource content to patch in JSON format")
    final Map<String, Object> resource
  ) {
    final HashMap<String, Object> arguments = HashMap.newHashMap(3);
    arguments.put("resourceType", resourceType);
    arguments.put("id", id);
    arguments.put("resource", resource);
    return this.handle(arguments, Interaction.PATCH);
  }

  @McpTool(name = "conditional-patch-fhir-resource",
    description = "Conditional patch an existing FHIR resource")
  private McpSchema.CallToolResult conditionalPatchFhirResource(
    @McpToolParam(description = "Type of the FHIR conformance resource to patch")
    final String resourceType,
    @McpToolParam(description = "Resource content to patch in JSON format")
    final Map<String, Object> resource,
    @McpToolParam(description = "Query string with search params separate by \",\". For example: \"_id=pt-1,name=ivan\". Uses for conditional patch.",
      required = false)
    final @Nullable String query,
    @McpToolParam(description = "Headers for create request.\nAvailable headers: If-None-Match header for conditional patch where the value is ETag.\nFor example: {\"If-None-Match\": \"12345\"}",
      required = false)
    final @Nullable Map<String, Object> headers
  ) {
    final HashMap<String, Object> arguments = HashMap.newHashMap(4);
    arguments.put("resourceType", resourceType);
    arguments.put("resource", resource);
    if (query != null) {
      arguments.put("query", query);
    }
    if (headers != null) {
      arguments.put("headers", headers);
    }
    return this.handle(arguments, Interaction.PATCH);
  }

  @McpTool(name = "delete-fhir-resource",
    description = "Delete an existing FHIR resource")
  private McpSchema.CallToolResult deleteFhirResource(
    @McpToolParam(description = "Type of the FHIR conformance resource to delete")
    final String resourceType,
    @McpToolParam(description = "ID of the resource to delete")
    final String id
  ) {
    final HashMap<String, Object> arguments = HashMap.newHashMap(2);
    arguments.put("resourceType", resourceType);
    arguments.put("id", id);
    return this.handle(arguments, Interaction.DELETE);
  }

  @McpTool(name = "create-fhir-transaction",
    description = "Create a FHIR transaction")
  private McpSchema.CallToolResult createFhirTransaction(
    @McpToolParam(description = "A Bundle resource type with type 'transaction' containing multiple FHIR conformance resources")
    final String resourceType,
    @McpToolParam(description = "A FHIR Bundle Resource content in JSON format")
    final Map<String, Object> resource
  ) {
    final HashMap<String, Object> arguments = HashMap.newHashMap(2);
    arguments.put("resourceType", resourceType);
    arguments.put("resource", resource);
    return this.handle(arguments, Interaction.TRANSACTION);
  }
}
