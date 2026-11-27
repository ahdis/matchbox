package ca.uhn.fhir.jpa.starter.mcp;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.RestfulServer;

import com.google.gson.Gson;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

public class RequestBuilder {

  private final String resourceType;
  private final Interaction interaction;
  private final Map<String, Object> config;
  private final String servletContextPath;
  private final FhirContext fhirContext;
  private final String operationName;

  /**
   * Constructs a RequestBuilder for a specific FHIR interaction.
   *
   * @param restfulServer the RestfulServer instance
   * @param contextMap    a map containing configuration parameters, including 'resourceType'
   * @param interaction   the type of interaction (e.g., SEARCH, READ, CREATE, etc.)
   */
  public RequestBuilder(RestfulServer restfulServer, Map<String, Object> contextMap, Interaction interaction) {
    this.config = contextMap;
    if (interaction == Interaction.TRANSACTION || interaction == Interaction.VALIDATE) this.resourceType = "";
    else if (contextMap.get("resourceType") instanceof String rt && !rt.isBlank()) this.resourceType = rt;
    else throw new IllegalArgumentException("Missing or invalid 'resourceType' in contextMap");

    if (contextMap.get("operationName") instanceof String op && !op.isBlank()) this.operationName = op;
    else this.operationName = null;

    this.interaction = interaction;
    this.servletContextPath = restfulServer.getServletContext().getContextPath();

    this.fhirContext = restfulServer.getFhirContext();
  }

  public MockHttpServletRequest buildRequest() {
    String basePath = servletContextPath + "/fhir/" + resourceType;
    String method;
    MockHttpServletRequest req;

    switch (interaction) {
      case SEARCH -> {
        method = "GET";
        req = new MockHttpServletRequest(method, basePath);
        req.setServletPath("/fhir");
        applyQuery(req, queryParameters());
      }
      case READ -> {
        method = "GET";
        String id = requireString();
        req = new MockHttpServletRequest(method, basePath + "/" + id);
        req.setServletPath("/fhir");
      }
      case CREATE, TRANSACTION -> {
        method = "POST";
        req = new MockHttpServletRequest(method, basePath);
        req.setServletPath("/fhir");
        applyResourceBody(req);
      }
      case UPDATE -> {
        method = "PUT";
        req = buildInstanceOrConditionalRequest(method, basePath);
        applyResourceBody(req);
      }
      case DELETE -> {
        method = "DELETE";
        String id = requireString();
        req = new MockHttpServletRequest(method, basePath + "/" + id);
        req.setServletPath("/fhir");
      }
      case PATCH -> {
        method = "PATCH";
        req = buildInstanceOrConditionalRequest(method, basePath);
        applyPatchBody(req);
      }
      case OPERATION -> {
        method = "POST";
        String operationPath = basePath + "/$" + operationName;
        req = new MockHttpServletRequest(method, operationPath);
        req.setServletPath("/fhir");
        applyResourceBody(req);
      }
      case VALIDATE -> {
        method = "POST";

        String uriValidate = servletContextPath + "/fhir/$validate";
        req = new MockHttpServletRequest(method, uriValidate);
        req.setServletPath("/fhir");

        String content = (String) config.get("resource");
        req.setContent(content.getBytes(StandardCharsets.UTF_8));
        String profile = (String) config.get("profile");
        if (profile != null && !profile.isBlank()) {
          req.addParameter("profile", profile);
        }
        Map<?, ?> sp = null;
        if (config.get("query") instanceof Map<?, ?> q) {
          sp = q;
        } else if (config.get("searchParams") instanceof Map<?, ?> s) {
          sp = s;
        }
        if (sp != null) {
          sp.forEach((k, v) -> req.addParameter(k.toString(), v.toString()));
        }
        req.addHeader("Content-Type", "text/plain; charset=UTF-8");
      }
      default -> throw new IllegalArgumentException("Unsupported interaction: " + interaction);
    }

    // MATCHBOX PATCH: the headers of the tools (e.g. If-None-Exist, If-Match) were ignored
    if (config.get("headers") instanceof Map<?, ?> headers) {
      headers.forEach((k, v) -> {
        if (k != null && v != null) {
          req.addHeader(k.toString(), v.toString());
        }
      });
    }

    if (req.getContentType() == null) {
      req.addHeader("Content-Type", "application/fhir+json");
    }

    req.addHeader("Accept", "application/fhir+json");
    return req;
  }

  /**
   * MATCHBOX PATCH: the request on an instance ([type]/[id]), or the conditional request on the type ([type]?[query])
   * when there is no id, for the conditional update and patch.
   */
  private MockHttpServletRequest buildInstanceOrConditionalRequest(final String method, final String basePath) {
    if (config.get("id") instanceof String id && !id.isBlank()) {
      final var req = new MockHttpServletRequest(method, basePath + "/" + id);
      req.setServletPath("/fhir");
      return req;
    }
    final List<Map.Entry<String, String>> query = queryParameters();
    if (query.isEmpty()) {
      throw new IllegalArgumentException("Missing 'id' or 'query' for the " + interaction + " interaction");
    }
    final var req = new MockHttpServletRequest(method, basePath);
    req.setServletPath("/fhir");
    applyQuery(req, query);
    return req;
  }

  /**
   * MATCHBOX PATCH: the search parameters of the 'query' (or 'searchParams') argument, either a map or a string like
   * "_id=pt-1,name=ivan" (separated by ',' or '&'; a ',' not followed by a parameter name stays in the value, as in
   * "name=a,b").
   */
  private List<Map.Entry<String, String>> queryParameters() {
    Object query = config.get("query");
    if (query == null) {
      query = config.get("searchParams");
    }
    final List<Map.Entry<String, String>> parameters = new ArrayList<>();
    if (query instanceof Map<?, ?> map) {
      map.forEach((k, v) -> parameters.add(Map.entry(k.toString(), String.valueOf(v))));
    } else if (query instanceof String string && !string.isBlank()) {
      String queryString = string.strip();
      if (queryString.startsWith("?")) {
        queryString = queryString.substring(1);
      }
      for (final String parameter : queryString.split("[,&](?=[^,&=]+=)")) {
        final int equals = parameter.indexOf('=');
        if (equals > 0) {
          parameters.add(Map.entry(parameter.substring(0, equals).strip(), parameter.substring(equals + 1).strip()));
        }
      }
    }
    return parameters;
  }

  /**
   * MATCHBOX PATCH (#684): the RestfulServer parses the parameters of a GET from the query string (it ignores the
   * parameters parsed by the servlet container), and the conditional URL of an update or a patch comes from the query
   * string too, so the parameters are set both as servlet parameters and in the query string.
   */
  private static void applyQuery(final MockHttpServletRequest req, final List<Map.Entry<String, String>> parameters) {
    if (parameters.isEmpty()) {
      return;
    }
    final var queryString = new StringJoiner("&");
    for (final var parameter : parameters) {
      req.addParameter(parameter.getKey(), parameter.getValue());
      queryString.add(URLEncoder.encode(parameter.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(parameter.getValue(), StandardCharsets.UTF_8));
    }
    req.setQueryString(queryString.toString());
  }

  private void applyResourceBody(MockHttpServletRequest req) {
    Object resourceObj = config.get("resource");
    String json;
    if (resourceObj instanceof Map<?, ?>) json = new Gson().toJson(resourceObj, Map.class);
    else if (resourceObj instanceof String) json = resourceObj.toString();
    else throw new IllegalArgumentException("Unsupported resource body type: " + resourceObj.getClass());
    req.setContent(json.getBytes(StandardCharsets.UTF_8));
  }

  private void applyPatchBody(MockHttpServletRequest req) {
    Object patchBody = config.get("resource");
    if (patchBody == null) {
      throw new IllegalArgumentException("Missing 'resource' for patch interaction");
    }
    String content;
    if (patchBody instanceof String s) {
      content = s;
    } else if (patchBody instanceof IBaseResource r) {
      content = fhirContext.newJsonParser().encodeResourceToString(r);
    } else if (patchBody instanceof Map<?, ?> map) {
      // MATCHBOX PATCH: the MCP tools give the patch as a map, a FHIRPath Patch (Parameters resource)
      content = new Gson().toJson(map, Map.class);
    } else if (patchBody instanceof List<?> list) {
      // MATCHBOX PATCH: a JSON Patch (array of operations)
      content = new Gson().toJson(list, List.class);
      req.addHeader("Content-Type", "application/json-patch+json");
    } else {
      throw new IllegalArgumentException("Unsupported patch body type: " + patchBody.getClass());
    }
    req.setContent(content.getBytes(StandardCharsets.UTF_8));
  }

  private String requireString() {
    Object val = config.get("id");
    if (!(val instanceof String s) || s.isBlank()) {
      throw new IllegalArgumentException("Missing or invalid '" + "id" + "'");
    }
    return s;
  }
}
