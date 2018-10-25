package in4392.cloudcomputing.apporchestrator;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.inject.Named;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.UriBuilder;

import org.springframework.http.MediaType;

import com.amazonaws.services.ec2.model.Instance;

@Named
public class AppOrchestrator {
	private static final int ITERATION_WAIT_TIME = 60 * 1000;
	private static final int MIN_FREE_INSTANCES = 10;
	private static final int MAX_REQUESTS_PER_INSTANCE = 5;
	private static final int MIN_REQUESTS_PER_INSTANCE = 3;
	/**
	 * Wait at least this many iterations before downscaling again
	 */
	private static final int DOWNSCALE_WAIT_ITERATIONS= 5;
	private static final String AWS_KEYPAIR_NAME = "accessibleFromAppOrchestrator";
	
	private static boolean keepAlive;
	private static Instance loadBalancer;
	private static Map<String, Target> applicationTargets = new HashMap<>();
	private static List<String> toBeDownscaledInstances = new ArrayList<>();
	private static Instance appOrchestrator;
	private static int downscaleIterationWaitCounter;
	
	
	private static void deployLoadBalancer() throws IOException, NoSuchAlgorithmException {
		System.out.println("Starting Load Balancer deployment");
		loadBalancer = EC2.deployDefaultEC2("Load Balancer", AWS_KEYPAIR_NAME);
		System.out.println("Load Balancer deployed, waiting for instance to run");
		EC2.waitForInstanceToRun(loadBalancer.getInstanceId());
		System.out.println("Copying Load Balancer application");
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/load-balancer.jar").toFile(), loadBalancer);
		System.out.println("Starting Load Balancer application");
		EC2.startDeployedApplication(loadBalancer, "load-balancer");
		System.out.println("Load Balancer application started");
		ClientBuilder.newClient().target(getLoadBalancerURI()).request().post(Entity.entity(appOrchestrator.getPublicDnsName(),MediaType.TEXT_PLAIN_VALUE));
	}
	
	private static void deployApplication() throws IOException, NoSuchAlgorithmException {
		System.out.println("Starting User Application deployment");
		Instance applicationInstance = EC2.deployDefaultEC2("User Application", AWS_KEYPAIR_NAME, getApplicationUserData());
		applicationTargets.put(applicationInstance.getInstanceId(), new Target(applicationInstance, true, 0));
		System.out.println("User Application deployed, waiting for instance to run");
		EC2.waitForInstanceToRun(applicationInstance.getInstanceId());
		System.out.println("Copying User Application application");
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/application.jar").toFile(), applicationInstance);
		System.out.println("Starting User Application application");
		EC2.startDeployedApplication(applicationInstance, "application");
		System.out.println("User Application application started");
	}

	private static String getApplicationUserData() {
		String applicationInstallScript = EC2.getDefaultInstallScript();
		applicationInstallScript = applicationInstallScript + "apt install -y ffmpeg\n";
		return EC2.getUserData(applicationInstallScript);
	}

	public static void scaleUpOrDown() throws NoSuchAlgorithmException, IOException {
		// ensure that we wait at least the amount of iterations specified in DOWNSCALE_WAIT_ITERATIONS
		// before we check to downscale again. This should allow the average amount of requests to
		// stabilize to a new value before we check it again.
		if (toBeDownscaledInstances.isEmpty() && downscaleIterationWaitCounter < DOWNSCALE_WAIT_ITERATIONS) {
			downscaleIterationWaitCounter++;
		}
		int count = 0;
		int totalRequests = 0;
		for (Target target : applicationTargets.values()) {
			count = target.isFree() ? count + 1 : count;
			totalRequests = totalRequests + target.getCurrentAmountOfRequests();
		}	
		int meanRequests = Math.round(totalRequests/applicationTargets.size());
		
		if (count < MIN_FREE_INSTANCES || meanRequests > MAX_REQUESTS_PER_INSTANCE) {
			/*
			 * upscale by deploying 1 new application instance 
			 */
			deployApplication();
		}
		else {
			if (toBeDownscaledInstances.isEmpty() && 
					count > MIN_FREE_INSTANCES && 
					meanRequests < MIN_REQUESTS_PER_INSTANCE) {
				/*
				 * downscale by marking the least used application so it is no longer used
				 * (and can be removed once it's completely unused)
				 */
				String instanceIdOfLeastLoadedApplication = findLeastLoadedAppInstance();
				toBeDownscaledInstances.add(instanceIdOfLeastLoadedApplication);
				downscaleIterationWaitCounter = 0;
			}
		}		
	}
	
	/**
	 * restore from the downscale list. First consider the list and if is empty then redeploy
	 */
//	public static void restoreFromDownscale() {
//		for (Target toBeRestored : toBeDownscaledInstances.values()) {
//			String instanceId = toBeRestored.getTargetInstance().getInstanceId();
//			toBeDownscaledInstances.remove(instanceId);
////			toBeAddedTargets.add(toBeRestored);
//		}
//	}

	/**
	 * LoadBalancing policy. find the target appInstance with the minimum number of current requests
	 * @return
	 * @throws IOException 
	 * @throws NoSuchAlgorithmException 
	 */
	public static String findLeastLoadedAppInstance() throws NoSuchAlgorithmException, IOException {		
		Map<Integer, String> instanceUtilizations = new HashMap<>();
		for(Entry<String, Target> targetEntry : applicationTargets.entrySet()) {
			if(!toBeDownscaledInstances.contains(targetEntry.getKey())) {
				Target target = targetEntry.getValue();
				instanceUtilizations.put(target.getCurrentAmountOfRequests(), target.getTargetInstance().getInstanceId());
			}
		}
		Integer leastLoadedApplicationUtilizationValue = Collections.min(instanceUtilizations.keySet());
		String leastLoadedApplicationInstanceId = instanceUtilizations.get(leastLoadedApplicationUtilizationValue);
		return leastLoadedApplicationInstanceId;
	}

	private static void updateEC2InstanceForLoadBalancer() {
		loadBalancer = EC2.retrieveEC2InstanceWithId(loadBalancer.getInstanceId());
	}
	
	private static void updateEC2InstanceForApplicationTargets() {
		for (Entry<String, Target> applicationTarget : applicationTargets.entrySet()) {
			Instance applicationInstance = EC2.retrieveEC2InstanceWithId(applicationTarget.getKey());
			applicationTarget.getValue().setTargetInstance(applicationInstance);
		}
	}
	
	private static void checkLoadBalancerLiveness() throws NoSuchAlgorithmException, URISyntaxException, IOException {
		if(!isLoadBalancerAlive()) {
			recoverLoadBalancer();
		}
	}
	
	private static void checkAppInstanceLiveness(Instance appInstance) throws NoSuchAlgorithmException, IOException, URISyntaxException {
		if (!isAppInstanceAlive(appInstance)) {
			recoverAppInstance(appInstance);
		}
	}
	
	private static void checkAppInstancesLiveness() throws NoSuchAlgorithmException, IOException, URISyntaxException {
		updateEC2InstanceForApplicationTargets();
		for (Target target : applicationTargets.values()) {
			checkAppInstanceLiveness(target.getTargetInstance());
		}
	}
	
	private static boolean isLoadBalancerAlive() throws URISyntaxException {
		updateEC2InstanceForLoadBalancer();
		if (loadBalancer == null) {
			return false;
		}
		URI loadBalancerURI = new URI("http", loadBalancer.getPublicDnsName(), null, null);
		URI loadBalancerHealth = UriBuilder.fromUri(loadBalancerURI).port(8080).path("load-balancer").path("health").build();
		int httpStatus = ClientBuilder.newClient().target(loadBalancerHealth).request().get().getStatus();
		return loadBalancer.getState().getCode() == EC2.INSTANCE_RUNNING && httpStatus == 204;
	}
	
	public static boolean isAppInstanceAlive(Instance appInstance) throws NoSuchAlgorithmException, IOException, URISyntaxException {
		if (appInstance == null) {
			return false;
		}
		URI appInstanceURI = new URI("http", appInstance.getPublicDnsName(), null, null);
		URI appInstanceHealth = UriBuilder.fromUri(appInstanceURI).port(8080).path("application").path("health").build();
		int httpStatus = ClientBuilder.newClient().target(appInstanceHealth).request().get().getStatus();
		return loadBalancer.getState().getCode() == EC2.INSTANCE_RUNNING && httpStatus == 204;
	}
	
	private static void recoverLoadBalancer() throws NoSuchAlgorithmException, IOException, URISyntaxException {
		recoverInstance(loadBalancer, "loadBalancer");
	}
	
	private static void recoverAppInstance(Instance appInstance) throws NoSuchAlgorithmException, IOException, URISyntaxException {
		recoverInstance(appInstance, "application");
	}
	
	/**
	 * Recover an instance, after it has been detected to no longer be alive.
	 * 
	 * @throws NoSuchAlgorithmException
	 * @throws IOException
	 * @throws URISyntaxException
	 */
	private static void recoverInstance(Instance brokenInstance, String brokenInstanceType) throws NoSuchAlgorithmException, IOException, URISyntaxException {
		if (brokenInstance == null) {
			redeployInstance(brokenInstance, brokenInstanceType);
			return;
		}
		int brokenInstanceState = brokenInstance.getState().getCode();
		if (brokenInstanceState == EC2.INSTANCE_RUNNING || brokenInstanceState == EC2.INSTANCE_STOPPED) {
			EC2.terminateEC2(brokenInstance.getInstanceId());
			return;
		}
		if (brokenInstanceState == EC2.INSTANCE_TERMINATED) {
			redeployInstance(brokenInstance, brokenInstanceType);
			return;
		}
		/*
		 * all other instance states are temporary and will eventually transition 
		 * to either running, stopped or terminated state, so we do nothing and
		 * wait for the instance to reach one of those states
		 */
		return;
	}
	
	private static void redeployInstance(Instance brokenInstance, String brokenInstanceType) throws NoSuchAlgorithmException, IOException, URISyntaxException {
		String previousInstanceId = brokenInstance.getInstanceId();
		switch(brokenInstanceType) {
			case "loadBalancer":
				deployLoadBalancer();
				break;
			case "application":
				if (!toBeDownscaledInstances.isEmpty()) {					
					toBeDownscaledInstances.remove(0);
				}
				else {
					deployApplication();
				}
			default:
				System.err.println("Unknown type of instance provided, can not be redeployed");
		}
		// termination of non-working EC2 instance is not verified
		// it might still be running in AWS which can be checked in AWS Console
		EC2.terminateEC2(previousInstanceId);
		applicationTargets.remove(previousInstanceId);
	}
	
	public static String getLoadBalancerURI() throws NoSuchAlgorithmException, IOException {
		//isLoadBalancerAlive();
		return loadBalancer.getPublicDnsName();
	}
	
	public static int incrementRequests(String minId) {
		applicationTargets.get(minId).incrementCurrentAmountOfRequests();
		return applicationTargets.get(minId).getCurrentAmountOfRequests();
	}
	
	public static void decrementRequests(String minId) {
		applicationTargets.get(minId).decrementCurrentAmountofRequests();
	}
	
	public static void setInstanceFreeStatus(String minId, boolean isFree) {
		applicationTargets.get(minId).setFree(isFree);
	}

	protected static void startMainLoop() throws IOException, NoSuchAlgorithmException, URISyntaxException {
		keepAlive = true;
		while(keepAlive) {
			if (EC2.getCredentials() == null) {
				System.out.println("Waiting for AWS credentials, cannot start yet");
			}
			else {
				if (loadBalancer == null) {
					deployLoadBalancer();
				}
				checkLoadBalancerLiveness();
				checkAppInstancesLiveness();
				
				scaleUpOrDown();
				processDownscaledApplicationInstances();
			}
			waitUntilNextIteration();
		}
	}

	private static void processDownscaledApplicationInstances() {
		for (String instanceId: toBeDownscaledInstances) {
			if (applicationTargets.get(instanceId).getCurrentAmountOfRequests() == 0) {
				EC2.terminateEC2(instanceId);
				toBeDownscaledInstances.remove(instanceId);
				applicationTargets.remove(instanceId);
			}
		}
	}
	
	
	public static boolean isAlive() {
		return keepAlive;
	}

	public static void restartMainLoop() {
		keepAlive = true;
	}
	
	public static void stopMainLoop() {
		keepAlive = false;
	}

	/**
	 * Wait a specific amount of time before
	 */
	private static void waitUntilNextIteration() {
		try {
			Thread.sleep(ITERATION_WAIT_TIME);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	public static Instance getLoadBalancer() {
		return loadBalancer;
	}

	public static Map<String, Target> getApplicationEC2Instances() {
		return applicationTargets;
	}
}
