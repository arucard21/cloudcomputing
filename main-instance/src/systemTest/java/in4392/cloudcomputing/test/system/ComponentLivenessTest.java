package in4392.cloudcomputing.test.system;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.stream.Collectors;

import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.UriBuilder;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.amazonaws.services.ec2.model.Instance;
import com.fasterxml.jackson.core.type.TypeReference;

public class ComponentLivenessTest extends SystemTest{
	@Test
	public void mainInstanceisAlive() {
		URI mainInstanceHealth = UriBuilder.fromUri(mainInstanceURI).port(8080).path("main").path("health").build();
		int responseStatusCode = client.target(mainInstanceHealth).request().get().getStatus();
		Assertions.assertEquals(204, responseStatusCode);
	}

	@Test
	public void shadowInstanceisAlive() throws URISyntaxException {
		URI mainInstanceDescribeShadowURI = UriBuilder.fromUri(mainInstanceURI).port(8080).path("main").path("instances").path("shadow").build();
		Instance shadow = client.target(mainInstanceDescribeShadowURI).request().get(Instance.class);
		Assertions.assertNotNull(shadow);
		
		String applicationHostname = shadow.getPublicDnsName();
		if (applicationHostname == null || applicationHostname.isEmpty()) {
			Assertions.fail("The application did not have a public DNS name and is likely not working correctly");
		}
		URI shadowURI = new URI("http", applicationHostname, null, null);
		
		URI shadowInstanceHealth = UriBuilder.fromUri(shadowURI).port(8080).path("main").path("health").build();
		int responseStatusCode = client.target(shadowInstanceHealth).request().get().getStatus();
		Assertions.assertEquals(204, responseStatusCode);
	}
	
	@Test
	public void applicationOrchestratorisAlive() throws URISyntaxException {
		URI shadowInstanceHealth = UriBuilder.fromUri(applicationOrchestratorURI).port(8080).path("application-orchestrator").path("health").build();
		int responseStatusCode = client.target(shadowInstanceHealth).request().get().getStatus();
		Assertions.assertEquals(204, responseStatusCode);
	}
	
	@Test
	public void loadBalancerisAlive() throws URISyntaxException {
		URI appOrchestratorDescribeLoadBalancerURI = UriBuilder.fromUri(applicationOrchestratorURI).port(8080).path("application-orchestrator").path("instances").path("load-balancer").build();
		Instance loadBalancer = client.target(appOrchestratorDescribeLoadBalancerURI).request().get(Instance.class);
		Assertions.assertNotNull(loadBalancer);
		URI loadBalancerURI = new URI("http", loadBalancer.getPublicDnsName(), null, null);
		
		URI loadBalancerHealth = UriBuilder.fromUri(loadBalancerURI).port(8080).path("load-balancer").path("health").build();
		int responseStatusCode = client.target(loadBalancerHealth).request().get().getStatus();
		Assertions.assertEquals(204, responseStatusCode);
	}
	
	@Test
	public void applicationsAreAlive() throws URISyntaxException, InterruptedException {
		URI appOrchestratorDescribeApplicationsURI = UriBuilder.fromUri(applicationOrchestratorURI).
				port(8080)
				.path("application-orchestrator")
				.path("instances")
				.path("applications").build();
		Collection<Instance> applications = null;
		for (int i = 0; i < 10; i++) {
			applications = client
					.target(appOrchestratorDescribeApplicationsURI)
					.request()
					.get(
							new GenericType<Collection<Instance>>(
									new TypeReference<Collection<Instance>>() {}.getType()));
			int amountOfOutdatedApplications = applications
					.stream()
					.filter(instance -> instance.getPublicDnsName() == null || instance.getPublicDnsName().isEmpty())
					.collect(Collectors.toList())
					.size();
			if (amountOfOutdatedApplications == 0) {
				break;
			}
			Thread.sleep(60 * 1000);
		}
		Assertions.assertNotNull(applications);
		Assertions.assertFalse(applications.isEmpty());
		for (Instance application : applications) {
			URI applicationURI = new URI("http", application.getPublicDnsName(), null, null);
			URI applicationHealth = UriBuilder.fromUri(applicationURI).port(8080).path("application").path("health").build();
			int responseStatusCode = client.target(applicationHealth).request().get().getStatus();
			Assertions.assertEquals(204, responseStatusCode);
		}
	}
}
