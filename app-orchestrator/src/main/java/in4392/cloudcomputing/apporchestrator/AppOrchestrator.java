package in4392.cloudcomputing.apporchestrator;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Named;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.UriBuilder;

import org.springframework.http.MediaType;

import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.util.EC2MetadataUtils;

@Named
public class AppOrchestrator {
	private static final int ITERATION_WAIT_TIME = 60 * 1000;
	private static final int MIN_FREE_INSTANCES = 10;
	private static final int MAX_REQUESTS_PER_INSTANCE = 5;
	private static final int MIN_REQUESTS_PER_INSTANCE = 3;
	private static final String AWS_KEYPAIR_NAME = "accessibleFromAppOrchestrator";
	
	private static boolean keepAlive;
	private static Instance loadBalancer;
	private static Map<String, Target> applicationEC2Instances = new HashMap<>();
	private static Instance appOrchestrator;
	private static List<Instance> toBeRemovedTargetInstances = new ArrayList<>();
	private static List<Target> toBeAddedTargets = new ArrayList<>();
	
	
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
		Instance applicationInstance = EC2.deployDefaultEC2("User Application", AWS_KEYPAIR_NAME);
		Target toBeAdded = new Target(applicationInstance, true, 0);
		toBeAddedTargets.add(toBeAdded);
		System.out.println("User Application deployed, waiting for instance to run");
		EC2.waitForInstanceToRun(applicationInstance.getInstanceId());
		System.out.println("Copying User Application application");
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/application.jar").toFile(), applicationInstance);
		System.out.println("Starting User Application application");
		EC2.startDeployedApplication(applicationInstance, "application");
		System.out.println("User Application application started");
	}
	
	
	/**
	 * add new targets to the appInstances
	 * @param toBeAddedTargets
	 */
	public static void addTargets() {
		for (Target toBeAdded : toBeAddedTargets) {
			String instanceId = toBeAdded.getTargetInstance().getInstanceId();
			if (applicationEC2Instances.containsKey(instanceId)){
				System.out.println("Already existing instanceID");
				continue;
			}
			else {
				applicationEC2Instances.put(instanceId, toBeAdded);
			}
		}
	}
	
	/**
	 * remove targets from the appInstances
	 * @param toBeRemovedTargets
	 */
	public static void removeTargets() {
		for (Instance toBeRemovedInstance : toBeRemovedTargetInstances) {
			String instanceId = toBeRemovedInstance.getInstanceId();
			if (!applicationEC2Instances.containsKey(instanceId)){
				System.out.println("Not found instanceID");
				continue;
			}
			else {
				applicationEC2Instances.remove(instanceId);
			}
		}
	}
	
	
	public static void checkSufficientFreeInstances() throws NoSuchAlgorithmException, IOException {
		int count = 0;
		for (Target target : applicationEC2Instances.values()) {
			count = target.isFree() ? count + 1 : count;
		}	
		if (count < MIN_FREE_INSTANCES) {
			for (int i = 0; i < MIN_FREE_INSTANCES - count; i++) {
				deployApplication();
			}
		}
	}
	
	public void downscaleAppInstances() throws NoSuchAlgorithmException, IOException {
		int meanRequests = 0;
		for (Target target: applicationEC2Instances.values()) {
			meanRequests = meanRequests + target.getCurrentAmountOfRequests();
		}
		meanRequests = Math.round(meanRequests/applicationEC2Instances.size());
		if (meanRequests < MIN_REQUESTS_PER_INSTANCE) {
			String minId = findLeastLoadedAppInstance();
		}
	}

	/**
	 * LoadBalancing policy. find the target appInstance with the minimum number of current requests
	 * @return
	 * @throws IOException 
	 * @throws NoSuchAlgorithmException 
	 */
	public static String findLeastLoadedAppInstance() throws NoSuchAlgorithmException, IOException {
		int min = MAX_REQUESTS_PER_INSTANCE;
		String minId = null;
		checkSufficientFreeInstances();
		for (Target target : applicationEC2Instances.values()) {
			if (target.getCurrentAmountOfRequests() < min) {
				min = target.getCurrentAmountOfRequests();
				minId = target.getTargetInstance().getInstanceId();
				if (min == 0) break;
			}
		}
		return minId;
	}
		
	
	
	private static void updateEC2InstanceForLoadBalancer() {
		loadBalancer = EC2.retrieveEC2InstanceWithId(loadBalancer.getInstanceId());
	}
	
	
	/**
	 * fix this
	 * @param appIns
	 */
	
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
		for (Target target : applicationEC2Instances.values()) {
			checkAppInstanceLiveness(target.getTargetInstance());
		}
	}
	
	private static boolean isLoadBalancerAlive() throws URISyntaxException {
		updateEC2InstanceForLoadBalancer();
		URI loadBalancerURI = new URI("http", loadBalancer.getPublicDnsName(), null, null);
		URI loadBalancerHealth = UriBuilder.fromUri(loadBalancerURI).port(8080).path("load-balancer").path("health").build();
		int httpStatus = ClientBuilder.newClient().target(loadBalancerHealth).request().get().getStatus();
		return loadBalancer.getState().getCode() == EC2.INSTANCE_RUNNING && httpStatus == 204;
	}
	
	public static boolean isAppInstanceAlive(Instance appInstance) throws NoSuchAlgorithmException, IOException, URISyntaxException {
		//updateEC2InstanceForAppInstance(appInstance);
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
		if (brokenInstanceState == EC2.INSTANCE_RUNNING) {
			EC2.stopEC2Instance(brokenInstance.getInstanceId());
			return;
		}
		if (brokenInstanceState == EC2.INSTANCE_STOPPED) {
			EC2.startEC2Instance(brokenInstance.getInstanceId());
			return;
		}
		if (brokenInstanceState == EC2.INSTANCE_TERMINATED) {
			redeployInstance(brokenInstance, brokenInstanceType);
			if (brokenInstanceType == "application")
				toBeRemovedTargetInstances.add(brokenInstance);
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
				deployApplication();
				copyTargetElements(previousInstanceId);
			default:
				System.err.println("Unknown type of instance provided, can not be redeployed");
		}
		// termination of non-working EC2 instance is not verified
		// it might still be running in AWS which can be checked in AWS Console
		EC2.terminateEC2(previousInstanceId);
	}

	private static void copyTargetElements(String toBeReplacedInstanceId) {
		if (toBeAddedTargets.isEmpty()) {
			System.err.println("Empty list of to be added Targets");
			return;
		}
		int indexOfLast = toBeAddedTargets.size();
		if (!applicationEC2Instances.containsKey(toBeReplacedInstanceId)) {
			System.err.println("Not found instance ID");
			return;
		}
		boolean freeState = applicationEC2Instances.get(toBeReplacedInstanceId).isFree();
		int currentRequests = applicationEC2Instances.get(toBeReplacedInstanceId).getCurrentAmountOfRequests();
		toBeAddedTargets.get(indexOfLast).setFree(freeState);
		toBeAddedTargets.get(indexOfLast).setCurrentAmountOfRequests(currentRequests);
	}
	
	public static String getLoadBalancerURI() throws NoSuchAlgorithmException, IOException {
		//isLoadBalancerAlive();
		return loadBalancer.getPublicDnsName();
	}
	
	public static int incrementRequests(String minId) {
		applicationEC2Instances.get(minId).incrementCurrentAmountOfRequests();
		return applicationEC2Instances.get(minId).getCurrentAmountOfRequests();
	}
	
	public static void decrementRequests(String minId) {
		applicationEC2Instances.get(minId).decrementCurrentAmountofRequests();
	}
	
	public static void setInstanceFreeStatus(String minId, boolean isFree) {
		applicationEC2Instances.get(minId).setFree(isFree);
	}
	
	
	public static void resetToBeRemovedTargets() {
		toBeRemovedTargetInstances.clear();
	}
	
	public static void resetToBeAddedTargets() {
		toBeAddedTargets.clear();
	}
	
	protected static void startMainLoop() throws IOException, NoSuchAlgorithmException, URISyntaxException {
		keepAlive = true;
		while(keepAlive) {
			if (EC2.getCredentials() == null) {
				System.out.println("Waiting for AWS credentials, cannot start yet");
			}
			else {
				if (loadBalancer == null)
					deployLoadBalancer();
				updateEC2InstanceForLoadBalancer();
				checkLoadBalancerLiveness();
				
				// clear the lists for the current iteration
				resetToBeRemovedTargets();
				resetToBeAddedTargets();
				
				if (applicationEC2Instances.isEmpty()) {
					System.out.printf("No Application Instances found. Deploying now the defined minimum of %d", MIN_FREE_INSTANCES);
					for (int i=0; i<MIN_FREE_INSTANCES; i++) {
						deployApplication();
					}
					// adding new Targets
					addTargets();
					resetToBeAddedTargets();
				}
				
				checkAppInstancesLiveness();
				/**
				 * update applicationEC2Instances based on the lists 
				 * toBeAddedTargets and ToBeRemovedTargetInstances
				 */
				// removing broken instances from target list and reset
				if (!toBeRemovedTargetInstances.isEmpty()) {
					removeTargets();
					resetToBeAddedTargets();
				}
				// replacing broken instances in the target list, while copying their elements and reset
				if (!toBeAddedTargets.isEmpty()) {
					addTargets();
					resetToBeAddedTargets();
				}
				// This add replaces a broken target-instance, thus also copying its elements;
				
				
				checkSufficientFreeInstances();
				if (!toBeAddedTargets.isEmpty()) {
					addTargets();
					resetToBeAddedTargets();
				}
			}
			waitUntilNextIteration();
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
		return applicationEC2Instances;
	}
}
