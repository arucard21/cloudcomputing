package in4392.cloudcomputing.loadbalancer;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.HashMap;

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

public class SystemExperiments {
	private URI loadBalancerURI;
	private URI instanceUtilization;
	private Client client;
	private URI appOrchestratroURI;
	
	@BeforeEach
	public void initializeTestVariables() throws URISyntaxException {
		client = ClientBuilder.newClient().register(JacksonJsonProvider.class);
		loadBalancerURI = new URI("http", System.getProperty("instance.url"), null, null);
	}
	
	// Example of a test. Is that proper? 
	@Test
	public void reqestSentLeastUtilizedAppInstance() {
		//TODO create instance utilization endpoint in App Orchestrator
		URI getAppInstanceUtilization = UriBuilder.fromUri(appOrchestratroURI).port(8080).path("application-orchestrator").path("instanceutilization").build();
		HashMap<Instance, Integer> appInstanceUtilization = (HashMap) client.target(getAppInstanceUtilization).request().get().getEntity();
		Assertions.assertNotNull(appInstanceUtilization);
		URI minIstanceURI = UriBuilder.fromUri(appOrchestratroURI).port(8080).path("application-orchestrator").path("leastUtilizedInstance").build();
		String minInstance = (String) client.target(getAppInstanceUtilization).request().get().getEntity();
		Assertions.assertNotNull(minInstance);
		int minimum = Integer.MAX_VALUE;
		int value;
		String minInstance2;
		for(Instance instance: appInstanceUtilization.keySet()) {
			value = appInstanceUtilization.get(instance);
			if (value < minimum){
				minimum = value;
				minInstance2 = instance.getPublicDnsName();
			}
		}
		Assertions.assertEquals(minInstance,minInstance2);
	}


}
