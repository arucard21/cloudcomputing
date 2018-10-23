package in4392.cloudcomputing.apporchestrator;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriBuilder;

import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.util.EC2MetadataUtils;

@Named
public class AppOrchestrator {
	private static final int ITERATION_WAIT_TIME = 60 * 1000;
	private static final int MIN_FREE_INSTANCES = 10;
	private static final int MAX_REQUESTS_PER_INSTANCE = 5;
	private static final String AWS_KEYPAIR_NAME = "accessibleFromAppOrchestrator";
	private static boolean keepAlive;
	private static Queue<HttpServletRequest> requestsQueue = new LinkedList<>();
	private static Instance loadBalancer;
	private static Map<String, Instance> applicationEC2Instances = new HashMap<>();
	private static Instance appOrchestrator;
	
	private static Target loadBalancerInstance;
	private static List<Target> appInstances = new ArrayList<Target>();
	
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
	}
	
	private static void deployApplication() throws IOException, NoSuchAlgorithmException {
		System.out.println("Starting User Application deployment");
		Instance applicationInstance = EC2.deployDefaultEC2("User Application", AWS_KEYPAIR_NAME);
		applicationEC2Instances.put(applicationInstance.getInstanceId(), applicationInstance);
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
	public void addTargets(Instance... toBeAddedTargets) {
		for (Instance targetInstance : toBeAddedTargets) {
			boolean alreadyExists = false;
			for (Target appInstance : appInstances) {
				if (appInstance.getTargetURI() == targetInstance.getPublicDnsName()) {
					alreadyExists = true;
					break;
				}
			}
			if(!alreadyExists){
				// create a new target with its URI, being available, with 0 pending requests
				Target target = new Target(targetInstance.getPublicDnsName(), true, 0);
				appInstances.add(target);
			} 
			else System.out.print("Already exiting URI\n");
		}
	}
	
	/**
	 * remove targets from the appInstances
	 * @param toBeRemovedTargets
	 */
	public void removeTargets(Instance... toBeRemovedTargets) {
		for (Instance targetInstance : toBeRemovedTargets) {
			if (appInstances.size()==0) return;
			boolean foundMatch = false;
			for (Target appInstance : appInstances) {
				if (appInstance.getTargetURI() == targetInstance.getPublicDnsName()) {
					appInstances.remove(appInstance);
					foundMatch = true;
					break;
				}
			}
			if (foundMatch == false) System.out.println("Not found\n");
		}
	}
	
	
	public void checkSufficientFreeInstances() throws NoSuchAlgorithmException, IOException {
		int count = 0;
		for(Target target : appInstances) {
			count = target.isFree() ? count + 1 : count;
		}
		if (count < MIN_FREE_INSTANCES) {
			for(int i = 0; i < MIN_FREE_INSTANCES - count; i++) {
				deployApplication();
			}
		}
	}

	/**
	 * LoadBalancing policy. find the target appInstance with the minimum number of current requests
	 * @return
	 * @throws IOException 
	 * @throws NoSuchAlgorithmException 
	 */
	public int findLeastLoadedAppInstance() throws NoSuchAlgorithmException, IOException {
		int min = MAX_REQUESTS_PER_INSTANCE, minIndex = 0;
		
		//first check that there are free instances
		checkSufficientFreeInstances();
		
		for (Target target : appInstances) {
			if (target.getCurrentAmountOfRequests() < min) {
				min = target.getCurrentAmountOfRequests();
				minIndex = appInstances.indexOf(target);
			}
		}
		return minIndex;
	}
	
	/** 
	 * These two probably should go to endpoint
	 * @throws IOException 
	 * @throws NoSuchAlgorithmException 
	 */
	public void sendRequest() throws NoSuchAlgorithmException, IOException {
		if (requestsQueue.isEmpty()) {
			System.out.println("There are no pending requests\n");
			return;
		}
		int index = findLeastLoadedAppInstance();
		HttpServletRequest request = requestsQueue.poll();
		ClientBuilder.newClient().target(appInstances.get(index).getTargetURI()).request().post(Entity.entity(request, MediaType.APPLICATION_JSON));
		appInstances.get(index).incrementCurrentAmountOfRequests();
		if (appInstances.get(index).getCurrentAmountOfRequests() == MAX_REQUESTS_PER_INSTANCE) 
			appInstances.get(index).setFree(false);
	}
	
	public void receiveRequest(HttpServletRequest request) {
		requestsQueue.add(request);
	}
	
	
	private static int healthCheckOnInstance(Target target) {
		URI targetInstanceHealth = UriBuilder.fromUri(target.getTargetURI()).path("health").build();
		int httpStatus = ClientBuilder.newClient().target(targetInstanceHealth).request().get().getStatus();
		return httpStatus;
	}
	
	public void isLoadBalancerAlive() throws NoSuchAlgorithmException, IOException {
		int httpStatus = healthCheckOnInstance(loadBalancerInstance);
		if (httpStatus == 204) return;
		else {
			deployLoadBalancer();
		}
	}
	
	protected static void startMainLoop() throws IOException, NoSuchAlgorithmException {
		keepAlive = true;
		while(keepAlive) {
			if (EC2.getCredentials() == null) {
				System.out.println("Waiting for AWS credentials, cannot start yet");
			}
			else {
				if (appOrchestrator == null) {
					updateEC2InstanceForMainInstance();
				}
				// do app-orchestrator things here
			}
			waitUntilNextIteration();
		}
	}
	
	private static void updateEC2InstanceForMainInstance() {
		appOrchestrator = EC2.retrieveEC2InstanceWithId(EC2MetadataUtils.getInstanceId());
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
}
