package ch.ahdis.matchbox;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.method.IMethodBinding;
import ch.ahdis.matchbox.engine.cli.VersionUtil;
import ch.ahdis.matchbox.spring.boot.autoconfigure.MutableHttpServletRequest;
import ch.ahdis.matchbox.validation.ValidationProvider;

public class MatchboxRestfulServer extends RestfulServer {
  
  private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(MatchboxRestfulServer.class);


  private static final long serialVersionUID = 1L;

  public MatchboxRestfulServer(FhirContext fhirContext) {
    super(fhirContext);
  }

  @Override
  protected void handleRequest(RequestTypeEnum theRequestType, HttpServletRequest theRequest,
      HttpServletResponse theResponse) throws ServletException, IOException {

	  getServerConformanceMethod().setCacheMillis(0L);

    MutableHttpServletRequest mutableRequest = new MutableHttpServletRequest(theRequest);
    super.handleRequest(theRequestType, mutableRequest, theResponse);
  }
  
  /**
   * Routes the type-level $validate ([base]/{Type}/$validate) of any resource type to the system-level $validate of
   * the {@link ValidationProvider}, which reads the type from the request details.
   * <p>
   * HAPI can't bind it: an operation on all resource types is either on the system or on the instance level, and a
   * type-level request is rejected if no resource provider is registered for the type (HAPI-0302).
   */
  @Override
  public IMethodBinding determineResourceMethod(RequestDetails requestDetails, String requestPath) {
    final String resourceName = requestDetails.getResourceName();
    if (!ValidationProvider.OPERATION_VALIDATE.equals(requestDetails.getOperation())
        || requestDetails.getId() != null
        || resourceName == null
        || !getFhirContext().getResourceTypes().contains(resourceName)) {
      return super.determineResourceMethod(requestDetails, requestPath);
    }
    requestDetails.setResourceName(null);
    try {
      return super.determineResourceMethod(requestDetails, requestPath);
    } finally {
      requestDetails.setResourceName(resourceName);
    }
  }

  @Override
  protected void initialize() throws ServletException {
    super.initialize();
    ourLog.info(VersionUtil.getPoweredBy());
  }

}
