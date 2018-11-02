package in4392.cloudcomputing.test.system;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.ZonedDateTime;
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
	
	private List<Future<InputStream>> ongoingRequests;

	private void sendRequestsToApplicationWithDelay(int amountOfRequests, int delayInSeconds) throws IOException, InterruptedException {
		URI getLoadBalancerEntry = UriBuilder.fromUri(loadBalancerURI)
				.port(8080)
				.path("load-balancer")
				.path("entry")
				.queryParam("delayApplication", delayInSeconds)
				.build();
		ongoingRequests = new ArrayList<>();
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
			Thread.sleep(100);
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
	 * @throws IOException
	 * @throws InterruptedException
	 */
	@Test
	public void scaleUpAndDownByOneInstance() throws IOException, InterruptedException {
		int initialAmountOfApplications = getAmountOfDeployedApplicationsFromAppOrchestrator();
		sendRequestsToApplicationWithDelay((initialAmountOfApplications*6), 600);
		long startTime = System.currentTimeMillis();
		System.err.println("Start time: "+ZonedDateTime.now());
		/*
		 * wait for scaling up, check every minute
		 */
		boolean scaledUp = false;
		int scaledUpAmountOfApplications = 0;
		for(int i = 0; i < 20; i++) {
			scaledUpAmountOfApplications = getAmountOfDeployedApplicationsFromAppOrchestrator();
			if (scaledUpAmountOfApplications > initialAmountOfApplications) {
				scaledUp = true;
				break;
			}
			Thread.sleep(60 * 1000);
		}
		if (!scaledUp) {
			Assertions.fail("The system did not scale up the applications");
			return;
		}
		Assertions.assertEquals(initialAmountOfApplications+1, scaledUpAmountOfApplications);
		System.err.println("Upscale time: "+ZonedDateTime.now());
		long upscaleTime = System.currentTimeMillis() - startTime;
		System.err.println("Upscale duration: "+Duration.ofMillis(upscaleTime));
		/*
		 * wait for scaling down, check every minute
		 */
		boolean scaledDown = false;
		int scaledDownAmountOfApplications = 0;
		for(int i = 0; i < 20; i++) {
			scaledDownAmountOfApplications = getAmountOfDeployedApplicationsFromAppOrchestrator();
			if (scaledDownAmountOfApplications < scaledUpAmountOfApplications) {
				scaledDown = true;
				break;
			}
			Thread.sleep(60 * 1000);
		}
		if (!scaledDown) {
			Assertions.fail("The system did not scale down the applications");
			return;
		}
		Assertions.assertEquals(initialAmountOfApplications, scaledDownAmountOfApplications);
		System.err.println("Downscale time: "+ZonedDateTime.now());
		long downscaleTime = System.currentTimeMillis() - upscaleTime;
		System.err.println("Downscale duration: "+Duration.ofMillis(downscaleTime));
		
	}
}
