package in4392.cloudcomputing.apporchestrator;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;

import javax.ws.rs.core.UriBuilder;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;

import in4392.cloudcomputing.apporchestrator.Target;

@Named
public class AppOrchestrator {
	private static final int ITERATION_WAIT_TIME = 1000;
	private static final int MIN_FREE_INSTANCES = 10;
	private static final int MAX_REQUESTS_PER_INSTANCE = 5;
	private static final String AMI_ID_EU_WEST_3_UBUNTU_SERVER_1804 = "ami-0a2ca21adb4a04084";
	
	private static boolean keepAlive;
	private static final String KEY_NAME = "cloudcomputing";
	private static Queue<HttpServletRequest> requestsQueue = new LinkedList<>();
	
	private AmazonEC2 amazonEC2Client = AmazonEC2ClientBuilder.standard().build();
	private static Target loadBalancerInstance;
	private static List<Target> appInstances = new ArrayList<Target>();
	
	
	public void deployEC2Instance(boolean isAppInstance, int count) {
		RunInstancesRequest runInstancesRequest = new RunInstancesRequest();

		runInstancesRequest.withImageId(AMI_ID_EU_WEST_3_UBUNTU_SERVER_1804)
				           .withInstanceType(InstanceType.T2Micro)
				           .withMinCount(1)
				           .withMaxCount(1)
				           .withKeyName(KEY_NAME)
				           // haven't define this
				           .withSecurityGroups("my-security-group");
		
		RunInstancesResult result = amazonEC2Client.runInstances(runInstancesRequest);
		
		if (isAppInstance) {
			// add check that this does not already exists
			
			List<Instance> toBeAddedTargets = result.getReservation().getInstances();
			addTargets(toBeAddedTargets);
		}else {
			String uri = result.getReservation().getInstances().get(0).getPublicDnsName();
			loadBalancerInstance = new Target(uri, true, 0);
		}
	}
	
	
	
	/**
	 * add new targets to the appInstances
	 * @param toBeAddedTargets
	 */
	private void addTargets(List<Instance> toBeAddedTargets) {
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
	public void removeTargets(List<Instance> toBeRemovedTargets) {
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
	
	
	public void checkSufficientFreeInstances() {
		int count = 0;
		for(Target target : appInstances) {
			count = target.isFree() ? count + 1 : count;
		}
		if (count < MIN_FREE_INSTANCES) {
			deployEC2Instance(true, MIN_FREE_INSTANCES - count);	
		}
	}
	
	/**
	 * LoadBalancing policy. find the target appInstance with the minimum number of current requests
	 * @return
	 */
	public int findLeastLoadedAppInstance() {
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
	 */
	public void sendRequest() {
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
	
	public void isLoadBalancerAlive() {
		int httpStatus = healthCheckOnInstance(loadBalancerInstance);
		if (httpStatus == 204) return;
		else {
			deployEC2Instance(false, 1);
		}
	}
	
	/**
	 * run until stopped through API
	 */
	public static void run() {
		// TODO do one-time things here
		keepAlive = true;
		while(keepAlive) {
			System.out.println("Do periodic things here");
			waitUntilNextIteration();
		}
	}

	/**
	 * Stop the main loop on the running master instance
	 */
	public static void destroy() {
		System.out.println("Destroying the Main Instance application");
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
