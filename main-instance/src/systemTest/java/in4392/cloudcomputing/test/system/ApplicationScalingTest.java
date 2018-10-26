package in4392.cloudcomputing.test.system;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Future;

import javax.ws.rs.client.Entity;
import javax.ws.rs.client.InvocationCallback;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.UriBuilder;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.amazonaws.services.ec2.model.Instance;
import com.fasterxml.jackson.core.type.TypeReference;

public class ApplicationScalingTest extends SystemTest{
	
	private void sendRequestsToApplicationWithDelay(int amountOfRequests, int delayInSeconds) throws IOException {
		URI getLoadBalancerEntry = UriBuilder.fromUri(loadBalancerURI)
				.port(8080)
				.path("load-balancer")
				.path("entry")
				.queryParam("delayApplication", delayInSeconds)
				.build();
		List<Future<InputStream>> ongoingRequests = new ArrayList<>();
		for(int i = 0; i < amountOfRequests; i++) {			
			try(InputStream inputVideo = Files.newInputStream(testVideoSmall)){
				ongoingRequests.add(client
						.target(getLoadBalancerEntry)
						.request()
						.async()
						.post(
								Entity.entity(inputVideo, MediaType.APPLICATION_OCTET_STREAM_VALUE), 
								new InvocationCallback<InputStream>() {
									@Override
									public void completed(InputStream response) {
										Assertions.assertNotNull(response);
										try {
											Assertions.assertTrue(response.available() > 0);
										} catch (IOException e) {
											e.printStackTrace();
										}
									}

									@Override
									public void failed(Throwable throwable) {
										throwable.printStackTrace();
									}
								}));				
			}
		}
		while(true) {
			if (ongoingRequests.stream().allMatch((request) -> request.isDone())) {
				break;
			}
			try {
				Thread.sleep(1 * 1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
	
	private int getAmountOfDeployedApplicationsFromAppOrchestrator() {
		Collection<Instance> applications = client
				.target(applicationOrchestratorURI)
				.path("application-orchestrator")
				.path("instances")
				.path("applications")
				.request()
				.get(new GenericType<Collection<Instance>>(new TypeReference<Collection<Instance>>() {}.getType()));
		return applications.size();
	}

	/**
	 * This test assumes that the system is fully idle and is configured to allow:
	 * - minumum 3 requests per instance
	 * - maximum 5 requests per instance
	 * - 2 free instances per request
	 * 
	 * This means that an idle system should have 3 instances and sending more than 5 requests would
	 * trigger it to scale up to 4 instances. After that, it should scale down again to 3 instances.
	 * 
	 * @throws IOException
	 * @throws InterruptedException
	 */
	@Test
	public void scaleUpAndDownByOneInstance() throws IOException, InterruptedException {
		int initialAmountOfApplications = getAmountOfDeployedApplicationsFromAppOrchestrator();
		Assertions.assertEquals(3, initialAmountOfApplications);
		sendRequestsToApplicationWithDelay(6, 120);

		// wait for scaling up (at least 1 iteration of the app orchestrator and less than it takes to complete the request)
		Thread.sleep(100 * 1000);
		int scaledUpAmountOfApplications = getAmountOfDeployedApplicationsFromAppOrchestrator();
		Assertions.assertEquals(4, scaledUpAmountOfApplications);
		
		// wait for scaling down (at least 1 iteration of the app orchestrator and more than the remaining time to complete the request)
		Thread.sleep(80 * 1000);
		int scaledDownAmountOfApplications = getAmountOfDeployedApplicationsFromAppOrchestrator();
		Assertions.assertEquals(3, scaledDownAmountOfApplications);
	}
}
