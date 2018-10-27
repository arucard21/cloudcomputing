package in4392.cloudcomputing.apporchestrator;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

import javax.inject.Named;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.UriBuilder;

import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.util.EC2MetadataUtils;
import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;

@Named
public class AppOrchestrator {
	private static final int ITERATION_WAIT_TIME = 60 * 1000;
	private static final int MAX_REQUESTS_PER_INSTANCE = 5;
	private static final int MIN_REQUESTS_PER_INSTANCE = 3;
	private static final String INSTANCE_TYPE_LOAD_BALANCER = "loadBalancer";
	private static final String INSTANCE_TYPE_APPLICATIONS = "applications";
	/**
	 * Wait at least this many iterations before downscaling again
	 */
	private static final int DOWNSCALE_WAIT_ITERATIONS= 5;
	private static final String AWS_KEYPAIR_NAME = "accessibleFromAppOrchestrator";
	
	private static boolean keepAlive;
	private static boolean started;
	private static Instance loadBalancer;
	private static Map<String, Target> applicationTargets = new HashMap<>();
	private static List<String> toBeDownscaledInstances = new ArrayList<>();
	private static Instance appOrchestrator;
	private static int downscaleIterationWaitCounter;
	private static Map<String, List<String>> appOrchestratorRestoreState = new HashMap<>();
	private static Map<String, Integer> appOrchestratorRestoreApplicationCounters = new HashMap<>();
	private static String mainInstanceHostname;
	
	
	private static void deployLoadBalancer() throws IOException, NoSuchAlgorithmException, URISyntaxException {
		System.out.println("Starting Load Balancer deployment");
		loadBalancer = EC2.deployDefaultEC2("Load Balancer", AWS_KEYPAIR_NAME);
		backupLoadBalancer();
		System.out.println("Load Balancer deployed, waiting for instance to run");
		EC2.waitForInstanceToRun(loadBalancer.getInstanceId());
		System.out.println("Copying Load Balancer application");
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/load-balancer.jar").toFile(), loadBalancer);
		System.out.println("Starting Load Balancer application");
		EC2.startDeployedApplication(loadBalancer, "load-balancer");
		System.out.println("Load Balancer application started");
		URI loadBalancerURI = new URI("http", getLoadBalancerURI(), null, null);
		System.out.println(UriBuilder.fromUri(loadBalancerURI).port(8080).path("load-balancer").path("appOrchestratorURI").build());
		System.out.println(appOrchestrator.getPublicDnsName());
		waitForApplicationToStart();
		sendAppOrchestratorURIToLoadBalancer();
	}
	
	private static void waitForApplicationToStart() {
		try {
			Thread.sleep(30 * 1000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
	
	private static void deployApplication() throws IOException, NoSuchAlgorithmException, URISyntaxException {
		System.out.println("Starting User Application deployment");
		Instance applicationInstance = EC2.deployDefaultEC2("User Application", AWS_KEYPAIR_NAME, getApplicationUserData());
		applicationTargets.put(applicationInstance.getInstanceId(), new Target(applicationInstance, 0));
		backupApplicationIds();
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

	public static void scaleUpOrDown() throws NoSuchAlgorithmException, IOException, URISyntaxException {
		// ensure that we wait at least the amount of iterations specified in DOWNSCALE_WAIT_ITERATIONS
		// before we check to downscale again. This should allow the average amount of requests to
		// stabilize to a new value before we check it again.
		if (toBeDownscaledInstances.isEmpty() && downscaleIterationWaitCounter < DOWNSCALE_WAIT_ITERATIONS) {
			downscaleIterationWaitCounter++;
		}
		int count = 0;
		int totalRequests = 0;
		//Needed to deploy one application instance
		int meanRequests = MAX_REQUESTS_PER_INSTANCE+1;
		for (Target target : applicationTargets.values()) {
			count = target.getCurrentAmountOfRequests() < MAX_REQUESTS_PER_INSTANCE ? count + 1 : count;
			totalRequests = totalRequests + target.getCurrentAmountOfRequests();
		}	
		if (applicationTargets.size() > 0){
			meanRequests = Math.round(totalRequests/applicationTargets.size());
		}
		
		if (meanRequests > MAX_REQUESTS_PER_INSTANCE) {
			/*
			 * upscale by deploying 1 new application instance 
			 */
			deployApplication();
		}
		else {
			if (toBeDownscaledInstances.isEmpty() &&
					applicationTargets.size() > 1 && 
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
	
	private static void checkAppInstancesLiveness() throws NoSuchAlgorithmException, IOException, URISyntaxException {
		updateEC2InstanceForApplicationTargets();
		List<Instance> brokenApplications = new ArrayList<>();
		for (Target target : applicationTargets.values()) {
			Instance appInstance = target.getTargetInstance();
			if (!isAppInstanceAlive(appInstance)) {
				brokenApplications.add(appInstance);
			}
		}
		for(Instance brokenApplication: brokenApplications) {
			recoverAppInstance(brokenApplication);
		}
	}
	
	private static boolean isLoadBalancerAlive() throws URISyntaxException {
		updateEC2InstanceForLoadBalancer();
		if (loadBalancer == null) {
			return false;
		}
		try {
			URI loadBalancerURI = new URI("http", loadBalancer.getPublicDnsName(), null, null);
			URI loadBalancerHealth = UriBuilder.fromUri(loadBalancerURI).port(8080).path("load-balancer").path("health").build();
			int httpStatus = ClientBuilder.newClient().target(loadBalancerHealth).request().get().getStatus();
			return loadBalancer.getState().getCode() == EC2.INSTANCE_RUNNING && httpStatus == 204;
		} catch(Exception e) {
			System.out.println("Load Balancer not alive");
			return false;
		}
	}
	
	public static boolean isAppInstanceAlive(Instance appInstance) throws NoSuchAlgorithmException, IOException, URISyntaxException {
		if (appInstance == null) {
			return false;
		}
		try {
			URI appInstanceURI = new URI("http", appInstance.getPublicDnsName(), null, null);
			URI appInstanceHealth = UriBuilder.fromUri(appInstanceURI).port(8080).path("application").path("health").build();
			int httpStatus = ClientBuilder.newClient().target(appInstanceHealth).request().get().getStatus();
			return appInstance.getState().getCode() == EC2.INSTANCE_RUNNING && httpStatus == 204;
		}catch(Exception e) {
			System.out.println("AppInstance not Alive");
			return false;
		}
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
	
	public static int incrementRequests(String minId) throws URISyntaxException {
		applicationTargets.get(minId).incrementCurrentAmountOfRequests();
		backupApplicationCounter(minId, applicationTargets.get(minId).getCurrentAmountOfRequests());
		return applicationTargets.get(minId).getCurrentAmountOfRequests();
	}
	
	public static void decrementRequests(URI applicationURI) throws URISyntaxException {
		Optional<String> minIdOptional = applicationTargets.entrySet()
				.stream()
				.map(entry -> entry.getValue().getTargetInstance())
				.filter(target -> applicationURI.getHost().equals(target.getPublicDnsName()))
				.map(target -> target.getInstanceId())
				.findFirst();
		if (!minIdOptional.isPresent()) {
			System.out.println("No application instance available at URI: " + applicationURI);
			return;
		}
		String minId = minIdOptional.get();
		applicationTargets.get(minId).decrementCurrentAmountofRequests();
		backupApplicationCounter(minId, applicationTargets.get(minId).getCurrentAmountOfRequests());
	}

	protected static void startMainLoop() throws IOException, NoSuchAlgorithmException, URISyntaxException {
		keepAlive = true;
		while(keepAlive) {
			waitUntilNextIteration();
			if (EC2.getCredentials() == null) {
				System.out.println("Waiting for AWS credentials, cannot start yet");
				continue;
			}
			if (!started) {
				System.out.println("Main loop has not been started yet. This can be started through the API");
				continue;
			}
			if (appOrchestrator == null){
				appOrchestrator = EC2.retrieveEC2InstanceWithId(EC2MetadataUtils.getInstanceId());
			}
			if (loadBalancer == null) {
				if (appOrchestratorRestoreState.containsKey(INSTANCE_TYPE_LOAD_BALANCER)) {
					List<String> restoreLoadBalancer = appOrchestratorRestoreState.get(INSTANCE_TYPE_LOAD_BALANCER);
					System.out.println("Restoring load balancer from backup id");
					loadBalancer = EC2.retrieveEC2InstanceWithId(restoreLoadBalancer.get(0));
					sendAppOrchestratorURIToLoadBalancer();
				}
				else{
					deployLoadBalancer();
				}
			}
			if (applicationTargets == null || applicationTargets.isEmpty()) {
				if (appOrchestratorRestoreState.containsKey(INSTANCE_TYPE_APPLICATIONS)) {
					List<String> applicationInstanceIds = appOrchestratorRestoreState.get(INSTANCE_TYPE_APPLICATIONS);
					System.out.println("Restoring applications from backup ids");
					for (String applicationIds : applicationInstanceIds) {
						// Here you get a String of all instanceIds. Can't find why that happens
						String[] sanitizedApplicationIds = applicationIds.replace("[", "").replace("]", "").split(",");
						System.out.println(sanitizedApplicationIds);
						for(String applicationId: sanitizedApplicationIds) {
							System.out.println(applicationId);
							String fixedAppId = applicationId.replace(" ","");
							Instance application = EC2.retrieveEC2InstanceWithId(fixedAppId);
							Integer amountOfRequests = appOrchestratorRestoreApplicationCounters.getOrDefault(applicationId, 0);
							Target applicationTarget = new Target(application, amountOfRequests);
							applicationTargets.put(applicationId, applicationTarget);
						}
					}
				}
			}
			checkLoadBalancerLiveness();
			checkAppInstancesLiveness();
			
			scaleUpOrDown();
			processDownscaledApplicationInstances();
		}
	}

	private static void processDownscaledApplicationInstances() {
		List<String> downscaledInstances = new ArrayList<>();
		for (String instanceId: toBeDownscaledInstances) {
			if (applicationTargets.get(instanceId).getCurrentAmountOfRequests() == 0) {
				EC2.terminateEC2(instanceId);
				downscaledInstances.add(instanceId);
				applicationTargets.remove(instanceId);
			}
		}
		// Avoid ConcurrentModificiation Exception
		toBeDownscaledInstances.removeAll(downscaledInstances);
		downscaledInstances = new ArrayList<>();
	}
	
	
	public static boolean isAlive() {
		return keepAlive;
	}
	
	public static boolean isStarted() {
		return started;
	}
	
	public static void kill() {
		keepAlive = false;
	}

	public static void start() {
		started = true;
	}
	
	public static void stop() {
		started = false;
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

	public static Map<String, Target> getApplicationTargets() {
		return applicationTargets;
	}
	
	public static void setRestoreIdForLoadBalancer(String loadBalancerId) {
		appOrchestratorRestoreState.put(INSTANCE_TYPE_LOAD_BALANCER, Arrays.asList(loadBalancerId));		
	}

	public static void setRestoreIdsForApplications(List<String> applicationIds) {
		appOrchestratorRestoreState.put(INSTANCE_TYPE_APPLICATIONS, applicationIds);
	}

	public static void setBackupApplicationCounter(String applicationId, int counter) {
		appOrchestratorRestoreApplicationCounters.put(applicationId, counter);
	}
	
	public static void backupLoadBalancer() throws URISyntaxException{
		URI mainInstanceURI = new URI("http", mainInstanceHostname, null, null);
		URI backupURI = UriBuilder.fromUri(mainInstanceURI).port(8080)
				.path("main")
				.path("backup")
				.path("load-balancer")
				.queryParam("loadBalancerId", loadBalancer.getInstanceId())
				.build();
		ClientBuilder.newClient().register(JacksonJsonProvider.class).target(backupURI).request().get();
	}
	
	public static void backupApplicationIds() throws URISyntaxException{
		URI mainInstanceURI = new URI("http", mainInstanceHostname, null, null);
		URI backupURI = UriBuilder.fromUri(mainInstanceURI).port(8080)
				.path("main")
				.path("backup")
				.path("applications")
				.queryParam("applicationIds", applicationTargets.keySet().toArray())
				.build();
		ClientBuilder.newClient().register(JacksonJsonProvider.class).target(backupURI).request().get();
	}
	
	public static void backupApplicationCounter(String applicationId, int counter) throws URISyntaxException{
		URI mainInstanceURI = new URI("http", mainInstanceHostname, null, null);
		URI backupURI = UriBuilder.fromUri(mainInstanceURI).port(8080)
				.path("main")
				.path("backup")
				.path("application-counter")
				.queryParam("applicationId", applicationId)
				.queryParam("counter", counter)
				.build();
		ClientBuilder.newClient().register(JacksonJsonProvider.class).target(backupURI).request().get();
	}
	
	public static void sendAppOrchestratorURIToLoadBalancer() throws URISyntaxException{
		URI loadBalancerURI = new URI("http", loadBalancer.getPublicDnsName(), null, null);
		URI backupURI = UriBuilder.fromUri(loadBalancerURI).port(8080)
				.path("load-balancer")
				.path("appOrchestratorURI")
				.queryParam("appOrchestratorURI", appOrchestrator.getPublicDnsName())
				.build();
		ClientBuilder.newClient().register(JacksonJsonProvider.class).target(backupURI).request().get();
	}

	public static void setMainInstance(String mainInstanceId) {
		AppOrchestrator.mainInstanceHostname = EC2.retrieveEC2InstanceWithId(mainInstanceId).getPublicDnsName();
	}
}
