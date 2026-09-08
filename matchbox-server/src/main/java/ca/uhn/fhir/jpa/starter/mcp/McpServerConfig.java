package ca.uhn.fhir.jpa.starter.mcp;

import ca.uhn.fhir.rest.server.McpFhirReadBridge;
import ca.uhn.fhir.rest.server.McpFhirWriteBridge;
import ca.uhn.fhir.rest.server.McpMatchboxBridge;
import ch.ahdis.matchbox.MatchboxRestfulServer;
import ch.ahdis.matchbox.config.property.MatchboxFhirMcpProperties;
import ch.ahdis.matchbox.providers.BundleResourceProvider;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// https://mcp-cn.ssshooter.com/sdk/java/mcp-server#sse-servlet
// https://www.baeldung.com/spring-ai-model-context-protocol-mcp
// https://github.com/spring-projects/spring-ai-examples/blob/main/model-context-protocol/weather/manual-webflux-server/src/main/java/org/springframework/ai/mcp/sample/server/McpServerConfig.java
// https://github.com/spring-projects/spring-ai-examples/tree/main/model-context-protocol/weather/starter-stdio-server/src/main/java/org/springframework/ai/mcp/sample/server
// https://github.com/spring-projects/spring-ai-examples/blob/main/model-context-protocol/sampling/mcp-weather-webmvc-server/src/main/java/org/springframework/ai/mcp/sample/server/WeatherService.java
// https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html

/**
 * Matchbox MCP Server configuration.
 * Most of the configuration work is done in the main auto-config class:
 * {@link org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration}.
 */
@Configuration
@ConditionalOnProperty(
  prefix = "spring.ai.mcp.server",
  name = {"enabled"},
  havingValue = "true")
public class McpServerConfig {

  private static final String SSE_ENDPOINT = "/sse";
  private static final String SSE_MESSAGE_ENDPOINT = "/mcp/message";

  /**
   * Read-only FHIR access tools (read + search), registered when
   * {@code matchbox.fhir.context.onlyOneEngine=true}.
   */
  @Bean
  @ConditionalOnExpression("${matchbox.fhir.context.onlyOneEngine:false}")
  public McpFhirReadBridge mcpFhirReadOnlyBridge(final MatchboxRestfulServer restfulServer) {
    return new McpFhirReadBridge(restfulServer);
  }

  /**
   * Read-write FHIR access tools (create/update/patch/delete/transaction), registered when
   * {@code matchbox.fhir.context.onlyOneEngine=false} and {@code matchbox.fhir.context.httpReadOnly=false}.
   */
  @Bean
  @ConditionalOnExpression("${matchbox.fhir.context.onlyOneEngine:false} && !${matchbox.fhir.context.httpReadOnly:false}")
  public McpFhirWriteBridge mcpFhirReadWriteBridge(final MatchboxRestfulServer restfulServer) {
    return new McpFhirWriteBridge(restfulServer);
  }

  @Bean
  public McpMatchboxBridge mcpMatchboxBridge(final MatchboxRestfulServer restfulServer,
                                             final MatchboxFhirMcpProperties matchboxFhirMcpProperties,
                                             final BundleResourceProvider bundleResourceProvider) {
    return new McpMatchboxBridge(restfulServer, matchboxFhirMcpProperties, bundleResourceProvider);
  }

  @Bean
  public HttpServletStreamableServerTransportProvider servletSseServerTransportProvider() {
    return HttpServletStreamableServerTransportProvider.builder()
      .disallowDelete(false)
      .mcpEndpoint(SSE_MESSAGE_ENDPOINT)
      // .contextExtractor((serverRequest, context) -> context)
      .build();
  }

  @Bean
  public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> customServletBean(final HttpServletStreamableServerTransportProvider transportProvider) {
    return new ServletRegistrationBean<>(transportProvider, SSE_MESSAGE_ENDPOINT, SSE_ENDPOINT);
  }
}
