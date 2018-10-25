package in4392.cloudcomputing.test.system;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Map;

import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.UriBuilder;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.amazonaws.services.ec2.model.Instance;
import com.fasterxml.jackson.core.type.TypeReference;

import in4392.cloudcomputing.maininstance.EC2;

public class AppOrchestratorTest extends SystemTest {
	private static int RECOVERY_WAIT_TIME = 10000;
	
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
	
	@Test
	public void appOrchestratorRecoverLoadBalancer() throws URISyntaxException {
		EC2.terminateEC2(loadBalancerID);
		try {
			Thread.sleep(RECOVERY_WAIT_TIME);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		URI mainInstanceDescribeApplicationOrchestratorURI = UriBuilder.fromUri(mainInstanceURI).port(8080).path("main").path("instances").path("application-orchestrator").build();
		Instance applicationOrchestrator = client.target(mainInstanceDescribeApplicationOrchestratorURI).request().get(Instance.class);
		Assertions.assertNotNull(applicationOrchestrator);
		
		URI applicationOrchestratorURI = new URI("http", applicationOrchestrator.getPublicDnsName(), null, null);
		
		URI appOrchestratorDescribeLoadBalancerURI = UriBuilder.fromUri(applicationOrchestratorURI).port(8080).path("application-orchestrator").path("instances").path("load-balancer").build();
		Instance loadBalancer = client.target(appOrchestratorDescribeLoadBalancerURI).request().get(Instance.class);
		Assertions.assertNotNull(loadBalancer);
		URI loadBalancerURI = new URI("http", loadBalancer.getPublicDnsName(), null, null);
		
		URI loadBalancerHealth = UriBuilder.fromUri(loadBalancerURI).port(8080).path("load-balancer").path("health").build();
		int responseStatusCode = client.target(loadBalancerHealth).request().get().getStatus();
		Assertions.assertEquals(204, responseStatusCode);	
	
	}
}
