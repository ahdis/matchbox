package ch.ahdis.matchbox.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MatchboxMcpConfig {

	@Bean
	public HttpServletSseServerTransportProvider servletSseServerTransportProvider() {
		return new HttpServletSseServerTransportProvider(new ObjectMapper(), "/mcp/message");
	}

	@Bean
	public ServletRegistrationBean<HttpServletSseServerTransportProvider> customServletBean(final HttpServletSseServerTransportProvider transportProvider) {
		final var servletRegistrationBean = new ServletRegistrationBean<>(transportProvider);
		servletRegistrationBean.setLoadOnStartup(2);
		servletRegistrationBean.setName("MCP");
		servletRegistrationBean.addUrlMappings("/mcp/*");
		return servletRegistrationBean;
	}
}
