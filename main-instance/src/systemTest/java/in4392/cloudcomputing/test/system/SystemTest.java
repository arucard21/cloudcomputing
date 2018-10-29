package in4392.cloudcomputing.test.system;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.UriBuilder;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.ec2.model.Instance;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;

import in4392.cloudcomputing.maininstance.EC2;

/**
 * Initialize the variables needed for all system tests
 */
public abstract class SystemTest {
	Client client;
	Instance mainInstance;
	URI mainInstanceURI;
	Instance shadow;
	URI shadowURI;
	Instance applicationOrchestrator;
	URI applicationOrchestratorURI;
	Instance loadBalancer;
	URI loadBalancerURI;
	Collection<Instance> applications;
	
	Path testVideoSmall;
	
	@BeforeEach
	public void initializeTestVariables() throws URISyntaxException {
		createClient();
		setEC2Credentials();
		getMainInstanceURIFromSystemProperty();
		getShadowURIFromMainInstance();
		getApplicationOrchestratorURIFromMainInstance();
		getLoadBalancerURIFromAppOrchestrator();
		getApplicationURIsFromAppOrchestrator();
		testVideoSmall = Paths.get(ClassLoader.getSystemResource("sintel_trailer-480p.mp4").toURI());
		ensureIdleSystem();
	}

	private void ensureIdleSystem() {
		URI appOrchestratorDescribeApplicationsURI = UriBuilder.fromUri(applicationOrchestratorURI).port(8080).path("application-orchestrator").path("instances").path("applications").build();
		for (int i = 0; i < 30; i++) {
			applications = client.target(appOrchestratorDescribeApplicationsURI).request().get(new GenericType<Collection<Instance>>(new TypeReference<Collection<Instance>>() {}.getType()));
			if (applications.size() == 2) {
				break;
			}
		}
	}

	private void setEC2Credentials() {
		String accessKey = System.getProperty("aws_access_key_id");
		String secretKey = System.getProperty("aws_secret_access_key");
		EC2.setCredentials(new BasicAWSCredentials(accessKey, secretKey));
	}

	private void getApplicationURIsFromAppOrchestrator() {
		URI appOrchestratorDescribeApplicationsURI = UriBuilder.fromUri(applicationOrchestratorURI).port(8080).path("application-orchestrator").path("instances").path("applications").build();
		applications = client.target(appOrchestratorDescribeApplicationsURI).request().get(new GenericType<Collection<Instance>>(new TypeReference<Collection<Instance>>() {}.getType()));
	}

	private void getLoadBalancerURIFromAppOrchestrator() throws URISyntaxException {
		URI appOrchestratorDescribeLoadBalancerURI = UriBuilder.fromUri(applicationOrchestratorURI).port(8080).path("application-orchestrator").path("instances").path("load-balancer").build();
		loadBalancer = client.target(appOrchestratorDescribeLoadBalancerURI).request().get(Instance.class);
		Assertions.assertNotNull(loadBalancer);
		loadBalancerURI = new URI("http", loadBalancer.getPublicDnsName(), null, null);
	}

	private void getApplicationOrchestratorURIFromMainInstance() throws URISyntaxException {
		URI mainInstanceDescribeApplicationOrchestratorURI = UriBuilder.fromUri(mainInstanceURI).port(8080).path("main").path("instances").path("application-orchestrator").build();
		applicationOrchestrator = client.target(mainInstanceDescribeApplicationOrchestratorURI).request().get(Instance.class);
		Assertions.assertNotNull(applicationOrchestrator);
		
		applicationOrchestratorURI = new URI("http", applicationOrchestrator.getPublicDnsName(), null, null);
	}

	private void getShadowURIFromMainInstance() throws URISyntaxException {
		URI mainInstanceDescribeShadowURI = UriBuilder.fromUri(mainInstanceURI).port(8080).path("main").path("instances").path("shadow").build();
		shadow = client.target(mainInstanceDescribeShadowURI).request().get(Instance.class);
		Assertions.assertNotNull(shadow);
		
		shadowURI = new URI("http", shadow.getPublicDnsName(), null, null);
	}

	private void getMainInstanceURIFromSystemProperty() throws URISyntaxException {
		mainInstanceURI = new URI("http", System.getProperty("instance.url"), null, null);
		URI mainInstanceDescribeMainInstanceURI = UriBuilder.fromUri(mainInstanceURI).port(8080).path("main").path("instances").path("main").build();
		mainInstance = client.target(mainInstanceDescribeMainInstanceURI).request().get(Instance.class);
	}

	private void createClient() {
		client = ClientBuilder
				.newBuilder()
				.connectTimeout(1, TimeUnit.DAYS)
				.readTimeout(1, TimeUnit.DAYS)
				.build()
				.register(JacksonJsonProvider.class);
	}
}
