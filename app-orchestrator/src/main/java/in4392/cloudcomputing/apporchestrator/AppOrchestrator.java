package in4392.cloudcomputing.apporchestrator;

import java.io.IOException;
import java.net.URI;
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
	private static final String AWS_KEYPAIR_NAME = "accessibleFromAppOrchestrator";
	private static boolean keepAlive;
	private static Instance loadBalancer;
	private static Map<String, Target> applicationEC2Instances = new HashMap<>();
	private static Instance appOrchestrator;
	
	
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
		addTarget(applicationInstance);
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
	public static void addTarget(Instance targetInstance) {
		if (applicationEC2Instances.containsKey(targetInstance.getInstanceId())){
			System.out.println("Already existing instanceID\n");
			return;
			}
		else {
			Target target = new Target(targetInstance, true, 0);
			applicationEC2Instances.put(targetInstance.getInstanceId(), target);
		}
	}
	
	/**
	 * remove targets from the appInstances
	 * @param toBeRemovedTargets
	 */
	public void removeTargets(Instance... toBeRemovedTargets) {
		if (applicationEC2Instances.isEmpty()) return;
		for (Instance targetInstance : toBeRemovedTargets) {
			if (!applicationEC2Instances.containsKey(targetInstance.getInstanceId())) {
				System.out.println("Not found instance ID");
				continue;
			}
			else applicationEC2Instances.remove(targetInstance.getInstanceId());
		}
	}
	
	
	public static void checkSufficientFreeInstances() throws NoSuchAlgorithmException, IOException {
		if (applicationEC2Instances.isEmpty()) {
			for (int i=0; i < MIN_FREE_INSTANCES; i++)
				deployApplication();
			return;
		}
		int count = 0;
		List<String> toBeRemovedIds = new ArrayList<>();
		/**
		 * this may seem that is done in the hard way, but I couldn't deploy dead apps 
		 * and mess with the loop. Thus the deadIds are kept and the removing is done out f
		 * of the loop.
		 */
		for (Target target : applicationEC2Instances.values()) {
			boolean isAlive = isAppInstanceAlive(target.getTargetInstance().getInstanceId());
			if (isAlive) {
				count = target.isFree() ? count + 1 : count;
			}
			else {
				toBeRemovedIds.add(target.getTargetInstance().getInstanceId());
			}
			
		}
		if (count < MIN_FREE_INSTANCES) {
			for (int i = 0; i < MIN_FREE_INSTANCES - count; i++) {
				deployApplication();
			}
		}
		for (String toBeRemovedId : toBeRemovedIds) {
			applicationEC2Instances.remove(toBeRemovedId);
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
		
	
	private static int healthCheckOnInstance(Instance instance) {
		URI targetInstanceHealth = UriBuilder.fromUri(instance.getPublicDnsName()).path("health").build();
		int httpStatus = ClientBuilder.newClient().target(targetInstanceHealth).request().get().getStatus();
		return httpStatus;
	}
	
	public static void isLoadBalancerAlive() throws NoSuchAlgorithmException, IOException {
		int httpStatus = healthCheckOnInstance(loadBalancer);
		if (httpStatus == 204) return;
		else {
			deployLoadBalancer();
		}
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
	
	public static boolean isAppInstanceAlive(String appInstanceId) throws NoSuchAlgorithmException, IOException {
		int httpStatus = healthCheckOnInstance(applicationEC2Instances.get(appInstanceId).getTargetInstance());
		if (httpStatus == 204) 
			return true;
		else return false;
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
				if (loadBalancer == null)
					deployLoadBalancer();
				else 
					isLoadBalancerAlive();
				checkSufficientFreeInstances();
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

	public static Instance getLoadBalancer() {
		return loadBalancer;
	}

	public static Map<String, Target> getApplicationEC2Instances() {
		return applicationEC2Instances;
	}
}
