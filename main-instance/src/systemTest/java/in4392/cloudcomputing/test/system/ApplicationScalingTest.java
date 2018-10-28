package in4392.cloudcomputing.test.system;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
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
											// actually read the inputstream so the connection doesn't get broken prematurely
											UUID convertedFileName = UUID.randomUUID();
											Path convertedFile = Paths.get(convertedFileName+".mkv");
											Files.copy(response, convertedFile);
											convertedFile.toFile().delete();
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
		URI applicationOrchestratorAPIURI = UriBuilder.fromUri(applicationOrchestratorURI)
				.port(8080)
				.path("application-orchestrator")
				.path("instances")
				.path("applications")
				.build();
		Collection<Instance> applications = client.target(applicationOrchestratorAPIURI)
				.request()
				.get(new GenericType<Collection<Instance>>(new TypeReference<Collection<Instance>>() {}.getType()));
		return applications.size();
	}

	/**
	 * This test assumes that the system is fully idle and is configured to allow:
	 * - minumum 3 requests per instance
	 * - maximum 5 requests per instance
	 * 
	 * This means that an idle system should have 2 instances and sending more than 11 requests would
	 * trigger it to scale up to 3 instances. After that, it should scale down again to 2 instances.
	 * 
	 * @throws IOException
	 * @throws InterruptedException
	 */
	@Test
	public void scaleUpAndDownByOneInstance() throws IOException, InterruptedException {
		int initialAmountOfApplications = getAmountOfDeployedApplicationsFromAppOrchestrator();
		Assertions.assertEquals(2, initialAmountOfApplications);
		sendRequestsToApplicationWithDelay(11, 300);

		/*
		 * wait for scaling up (includes waiting for the next iteration as well as for all status 
		 * checks to pass on the deployed instance)
		 */
		Thread.sleep(300 * 1000);
		int scaledUpAmountOfApplications = getAmountOfDeployedApplicationsFromAppOrchestrator();
		Assertions.assertEquals(3, scaledUpAmountOfApplications);
		
		/*
		 * wait for scaling down (includes waiting for the next iteration as well as for all requests
		 * on the instance to be completed and the instance to be terminated)
		 */
		Thread.sleep(120 * 1000);
		int scaledDownAmountOfApplications = getAmountOfDeployedApplicationsFromAppOrchestrator();
		Assertions.assertEquals(2, scaledDownAmountOfApplications);
	}
}
