package in4392.cloudcomputing.test.system;

import java.net.URI;
import java.util.Collections;
import java.util.Map;

import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.UriBuilder;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;

public class AppOrchestratorTest extends SystemTest {
	@Test
	public void appOrchestratorReturnsLeastUtilizedAppInstance() {
		URI getAppInstanceUtilizationURI = UriBuilder.fromUri(applicationOrchestratorURI).port(8080).path("application-orchestrator").path("instance-utilization").build();
		Map<Integer, String> appInstanceUtilization = client.target(getAppInstanceUtilizationURI).request().get(new GenericType<Map<Integer, String>>(new TypeReference<Map<Integer, String>>() {}.getType()));
		Assertions.assertNotNull(appInstanceUtilization);
		Integer minUtilization = Collections.min(appInstanceUtilization.keySet());
		String expectedLeastUtilizedInstance = appInstanceUtilization.get(minUtilization);
		
		URI minInstanceURI = UriBuilder.fromUri(applicationOrchestratorURI).port(8080).path("application-orchestrator").path("leastUtilizedInstance").build();
		String actualLeastUtilizedInstance = (String) client.target(minInstanceURI).request().get().getEntity();
		Assertions.assertNotNull(actualLeastUtilizedInstance);
		Assertions.assertEquals(actualLeastUtilizedInstance,expectedLeastUtilizedInstance);
	}
}
