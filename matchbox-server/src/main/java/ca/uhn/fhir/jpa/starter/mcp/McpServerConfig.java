package ca.uhn.fhir.jpa.starter.mcp;

import ca.uhn.fhir.rest.server.McpBridge;
import ca.uhn.fhir.rest.server.McpFhirBridge;
import ca.uhn.fhir.rest.server.McpMatchboxBridge;
import ch.ahdis.matchbox.CliContext;
import ch.ahdis.matchbox.MatchboxRestfulServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

// https://mcp-cn.ssshooter.com/sdk/java/mcp-server#sse-servlet
// https://www.baeldung.com/spring-ai-model-context-protocol-mcp
// https://github.com/spring-projects/spring-ai-examples/blob/main/model-context-protocol/weather/manual-webflux-server/src/main/java/org/springframework/ai/mcp/sample/server/McpServerConfig.java
// https://github.com/spring-projects/spring-ai-examples/tree/main/model-context-protocol/weather/starter-stdio-server/src/main/java/org/springframework/ai/mcp/sample/server
// https://github.com/spring-projects/spring-ai-examples/blob/main/model-context-protocol/sampling/mcp-weather-webmvc-server/src/main/java/org/springframework/ai/mcp/sample/server/WeatherService.java
// https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html
@Configuration
@ConditionalOnProperty(
  prefix = "spring.ai.mcp.server",
  name = {"enabled"},
  havingValue = "true")
public class McpServerConfig {

  private static final String SSE_ENDPOINT = "/sse";
  private static final String SSE_MESSAGE_ENDPOINT = "/mcp/message";

  @Bean
  public McpSyncServer syncServer(
    final List<McpBridge> mcpBridges,
    final McpServerTransportProvider transportProvider
  ) {
    return McpServer.sync(transportProvider)
      .requestTimeout(Duration.ofSeconds(60))
      .tools(mcpBridges.stream()
               .flatMap(bridge -> bridge.generateTools().stream())
               .toList())
      .build();
  }

  @Bean
  public McpFhirBridge mcpFhirBridge(final MatchboxRestfulServer restfulServer, CliContext cliContext) {
    return new McpFhirBridge(restfulServer, cliContext);
  }

  @Bean
  public McpMatchboxBridge mcpMatchboxBridge(final MatchboxRestfulServer restfulServer) {
    return new McpMatchboxBridge(restfulServer);
  }

  @Bean
  public HttpServletSseServerTransportProvider servletSseServerTransportProvider() {
    return HttpServletSseServerTransportProvider.builder()
      .messageEndpoint(SSE_MESSAGE_ENDPOINT)
      .sseEndpoint(SSE_ENDPOINT)
      //.objectMapper(new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false))
      .build();
  }

  @Bean
  public ServletRegistrationBean<?> customServletBean(
    final HttpServletSseServerTransportProvider transportProvider
  ) {
    return new ServletRegistrationBean<>(transportProvider, SSE_MESSAGE_ENDPOINT, SSE_ENDPOINT);
  }
}
