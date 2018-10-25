package in4392.cloudcomputing.test.system;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.UriBuilder;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.amazonaws.services.ec2.model.Instance;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;

public class AppOrchestratorTest {
	private URI mainInstanceURI;
	private Client client;
	private URI shadowURI;
	private URI applicationOrchestratorURI;
	private URI loadBalancerURI;
	private Collection<Instance> applications;
	
	@BeforeEach
	public void initializeTestVariables() throws URISyntaxException {
		client = ClientBuilder.newClient().register(JacksonJsonProvider.class);
		mainInstanceURI = new URI("http", System.getProperty("instance.url"), null, null);
		
		URI mainInstanceDescribeShadowURI = UriBuilder.fromUri(mainInstanceURI).port(8080).path("main").path("instances").path("shadow").build();
		Instance shadow = client.target(mainInstanceDescribeShadowURI).request().get(Instance.class);
		Assertions.assertNotNull(shadow);
		
		shadowURI = new URI("http", shadow.getPublicDnsName(), null, null);
		
		URI mainInstanceDescribeApplicationOrchestratorURI = UriBuilder.fromUri(mainInstanceURI).port(8080).path("main").path("instances").path("application-orchestrator").build();
		Instance applicationOrchestrator = client.target(mainInstanceDescribeApplicationOrchestratorURI).request().get(Instance.class);
		Assertions.assertNotNull(applicationOrchestrator);
		
		applicationOrchestratorURI = new URI("http", applicationOrchestrator.getPublicDnsName(), null, null);
		
		URI appOrchestratorDescribeLoadBalancerURI = UriBuilder.fromUri(applicationOrchestratorURI).port(8080).path("application-orchestrator").path("instances").path("load-balancer").build();
		Instance loadBalancer = client.target(appOrchestratorDescribeLoadBalancerURI).request().get(Instance.class);
		Assertions.assertNotNull(loadBalancer);
		loadBalancerURI = new URI("http", loadBalancer.getPublicDnsName(), null, null);
		
		URI appOrchestratorDescribeApplicationsURI = UriBuilder.fromUri(applicationOrchestratorURI).port(8080).path("application-orchestrator").path("instances").path("applications").build();
		applications = client.target(appOrchestratorDescribeApplicationsURI).request().get(new GenericType<Collection<Instance>>(new TypeReference<Collection<Instance>>() {}.getType()));
	}
	
	@Test
	public void appOrchestratorReturnsLeastUtilizedAppInstance() {
		URI getAppInstanceUtilizationURI = UriBuilder.fromUri(applicationOrchestratorURI).port(8080).path("application-orchestrator").path("instanceutilization").build();
		Map<Integer, Instance> appInstanceUtilization = client.target(getAppInstanceUtilizationURI).request().get(new GenericType<Map<Integer, Instance>>(new TypeReference<Map<Integer, Instance>>() {}.getType()));
		Assertions.assertNotNull(appInstanceUtilization);
		Integer minUtilization = Collections.min(appInstanceUtilization.keySet());
		String expectedLeastUtilizedInstance = appInstanceUtilization.get(minUtilization).getPublicDnsName();
		
		URI minInstanceURI = UriBuilder.fromUri(applicationOrchestratorURI).port(8080).path("application-orchestrator").path("leastUtilizedInstance").build();
		String actualLeastUtilizedInstance = (String) client.target(minInstanceURI).request().get().getEntity();
		Assertions.assertNotNull(actualLeastUtilizedInstance);
		Assertions.assertEquals(actualLeastUtilizedInstance,expectedLeastUtilizedInstance);
	}
}
