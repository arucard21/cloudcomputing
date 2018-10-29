package in4392.cloudcomputing.maininstance;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.inject.Named;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriBuilder;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.services.cloudwatch.AmazonCloudWatch;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder;
import com.amazonaws.services.cloudwatch.model.Datapoint;
import com.amazonaws.services.cloudwatch.model.Dimension;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsRequest;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsResult;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.util.EC2MetadataUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;

import in4392.cloudcomputing.maininstance.api.SimpleAWSCredentials;

@Named
public class MainInstance {
	private static final String INSTANCE_TYPE_APP_ORCHESTRATOR = "appOrchestrator";
	private static final String INSTANCE_TYPE_SHADOW = "shadow";
	private static final String INSTANCE_TYPE_MAIN = "main";
	private static final String INSTANCE_TYPE_LOAD_BALANCER = "loadBalancer";
	private static final String INSTANCE_TYPE_APPLICATIONS = "applications";
	private static final String TAG_REDEPLOYED_MAIN_INSTANCE = "Redeployed Main Instance";
	private static final String TAG_APP_ORCHESTRATOR = "App Orchestrator";
	private static final String TAG_SHADOW = "Shadow Instance";
	private static final String API_ROOT_MAIN = "main";
	private static final String API_ROOT_APPLICATION_ORCHESTRATOR = "application-orchestrator";
	private static final String AWS_KEYPAIR_NAME = "accessibleFromMainInstance";
	private static final List<String> metricNames = Arrays.asList("CPUUtilization", "NetworkIn", "NetworkOut", "DiskReadOps", "DiskWriteOps");
	private static final int ITERATION_WAIT_TIME = 60 * 1000;
	private static boolean keepAlive;
	private static boolean started;
	private static Instance mainInstance;
	private static Instance shadow;
	private static Instance appOrchestrator;
	private static boolean isShadow;
	private static boolean replaceMain;
	private static AmazonCloudWatch cloudWatch;
	private static Map<String, InstanceMetrics> metricsForInstances = new HashMap<>();
	private static Map<String, String> mainInstanceRestoreState = new HashMap<>();
	private static Map<String, List<String>> appOrchestratorRestoreState = new HashMap<>();
	private static final Map<String, Integer > appOrchestratorRestoreApplicationCounters = new HashMap<>();

	/**
	 * Start the main loop. 
	 * 
	 * This will run continuously, executing an iteration every minute (defined by ITERATION_WAIT_TIME). 
	 * The loop can be stopped and restarted through the API, with a GET request to 
	 * "http:\<instanceURL\>:8080/main/start" or "http:\<instanceURL\>:8080/main/stop".
	 * 
	 * Note that you must configure the instance as shadow before providing the credentials.
	 * 
	 * @throws IOException 
	 * @throws NoSuchAlgorithmException 
	 * @throws URISyntaxException 
	 */
	protected static void startMainLoop() throws IOException, NoSuchAlgorithmException, URISyntaxException {
		keepAlive = true;
		while(keepAlive) {
			waitUntilNextIteration();
			if (EC2.getCredentials() == null) {
				System.out.println("Waiting for AWS credentials, cannot start yet");
				continue;
			}
			if(!started) {
				System.out.println("Main loop has not been started yet. This can be started through the API");
				continue;
			}
			if (behaveAsShadow()) {
				System.out.println("Checking main instance liveness from shadow");
				checkMainInstanceLiveness();
			}
			else{
				updateEC2InstanceForMainInstance();
				if (!isShadowDeployed()) {
					String restoreShadowId = mainInstanceRestoreState.get(INSTANCE_TYPE_SHADOW);
					if (restoreShadowId != null && !restoreShadowId.isEmpty()) {
						System.out.println("Restoring shadow from backup id");
						shadow = EC2.retrieveEC2InstanceWithId(restoreShadowId);
					}
					else{
						System.out.println("Deploying shadow");
						deployShadow();
					}
				}
				if(!isAppOrchestratorDeployed()) {
					String appOrchestratorId = mainInstanceRestoreState.get(INSTANCE_TYPE_APP_ORCHESTRATOR);
					if (appOrchestratorId != null && !appOrchestratorId.isEmpty()) {
						System.out.println("Restoring app orchestrator from backup id");
						appOrchestrator = EC2.retrieveEC2InstanceWithId(appOrchestratorId);
					}
					else{
						System.out.println("Deploying app orchestrator");
						deployAppOrchestrator();
					}
				}
				System.out.println("Checking shadow liveness from main instance");
				checkShadowInstanceLiveness();
				System.out.println("Checking app orchestrator liveness");
				checkAppOrchestratorLiveness();
				System.out.println("Start monitoring");
				monitor();
			}
			waitUntilNextIteration();
		}
	}

	private static void checkMainInstanceLiveness() throws NoSuchAlgorithmException, URISyntaxException, IOException {
		if(!isMainInstanceAlive()) {
			recoverMainInstance();
		}
	}
	
	private static void checkShadowInstanceLiveness() throws NoSuchAlgorithmException, URISyntaxException, IOException {
		if(!isShadowInstanceAlive()) {
			recoverShadowInstance();
		}
	}
	
	private static void checkAppOrchestratorLiveness() throws NoSuchAlgorithmException, URISyntaxException, IOException {
		if(!isAppOrchestratorAlive()) {
			recoverAppOrchestrator();
		}
	}

	private static void recoverMainInstance() throws NoSuchAlgorithmException, IOException, URISyntaxException {
		recoverInstance(mainInstance, INSTANCE_TYPE_MAIN);
	}
	
	private static void recoverShadowInstance() throws NoSuchAlgorithmException, IOException, URISyntaxException {
		recoverInstance(shadow, INSTANCE_TYPE_SHADOW);
	}
	
	private static void recoverAppOrchestrator() throws NoSuchAlgorithmException, IOException, URISyntaxException {
		recoverInstance(appOrchestrator, INSTANCE_TYPE_APP_ORCHESTRATOR);
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
			EC2.terminateEC2(shadow.getInstanceId());
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
			case INSTANCE_TYPE_MAIN:
				redeployMainInstance();
				break;
			case INSTANCE_TYPE_SHADOW:
				deployShadow();
				break;
			case INSTANCE_TYPE_APP_ORCHESTRATOR:
				deployAppOrchestrator();
				break;
			default:
				System.err.println("Unknown type of instance provided, can not be redeployed");
		}
		// termination of non-working EC2 instance is not verified
		// it might still be running in AWS which can be checked in AWS Console
		if (previousInstanceId != null) {
			EC2.terminateEC2(previousInstanceId);
		}
	}
	
	private static void redeployMainInstance() throws IOException, NoSuchAlgorithmException, URISyntaxException {
		Instance deployedInstance = EC2.deployDefaultEC2(TAG_REDEPLOYED_MAIN_INSTANCE, AWS_KEYPAIR_NAME);
		System.out.println("Main Instance redeployed");
		EC2.waitForInstanceToRun(deployedInstance.getInstanceId());
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/application.jar").toFile(), deployedInstance);
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/app-orchestrator.jar").toFile(), deployedInstance);
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/load-balancer.jar").toFile(), deployedInstance);
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/main-instance.jar").toFile(), deployedInstance);
		EC2.startDeployedApplication(deployedInstance, "main-instance");
		waitForApplicationToStart();
		uploadCredentials(deployedInstance, API_ROOT_MAIN);
		mainInstanceRestoreState.put(INSTANCE_TYPE_MAIN, deployedInstance.getInstanceId());
		sendShadowIdFromRestoreStateToMainInstance(deployedInstance);
		sendApplicationOrchestratorIdFromRestoreStateToMainInstance(deployedInstance);
		sendMainInstanceIdToApplicationOrchestratorFromShadow();
		sendAppOrchestratorRestoreStateToMainInstance(deployedInstance);
		sendAppOrchestratorApplicationCountersToMainInstance(deployedInstance);
		startInstance(deployedInstance, API_ROOT_MAIN);
		mainInstance = deployedInstance;
		System.out.println("Main Instance application started");
	}

	private static void deployShadow() throws IOException, NoSuchAlgorithmException, URISyntaxException {
		Instance deployedInstance = EC2.deployDefaultEC2(TAG_SHADOW, AWS_KEYPAIR_NAME);
		System.out.println("Shadow deployed");
		EC2.waitForInstanceToRun(deployedInstance.getInstanceId());
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/application.jar").toFile(), deployedInstance);
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/app-orchestrator.jar").toFile(), deployedInstance);
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/load-balancer.jar").toFile(), deployedInstance);
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/main-instance.jar").toFile(), deployedInstance);
		EC2.startDeployedApplication(deployedInstance, "main-instance");
		waitForApplicationToStart();
		uploadCredentials(deployedInstance, API_ROOT_MAIN);
		configureProvidedInstanceAsShadow(deployedInstance);
		shadow = deployedInstance;
		//Such if conditions were changed because we thought there was an issue here with the previous ones, but it may not be the case
		if (mainInstanceRestoreState.containsKey(INSTANCE_TYPE_APP_ORCHESTRATOR)) {
			sendApplicationOrchestratorIdFromRestoreStateToShadow();
		}
		startInstance(shadow, API_ROOT_MAIN);
		System.out.println("Shadow application started");
	}

	private static void deployAppOrchestrator() throws IOException, NoSuchAlgorithmException, URISyntaxException {
		EC2.ensureJavaKeyPairExists();
		Instance deployedInstance = EC2.deployDefaultEC2(TAG_APP_ORCHESTRATOR, AWS_KEYPAIR_NAME);
		System.out.println("App Orchestrator deployed");
		EC2.waitForInstanceToRun(deployedInstance.getInstanceId());
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/application.jar").toFile(), deployedInstance);
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/load-balancer.jar").toFile(), deployedInstance);
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/app-orchestrator.jar").toFile(), deployedInstance);
		EC2.startDeployedApplication(deployedInstance, "app-orchestrator");
		waitForApplicationToStart();
		uploadCredentials(deployedInstance, "application-orchestrator");
		appOrchestrator = deployedInstance;
		if (appOrchestratorRestoreState.containsKey(INSTANCE_TYPE_LOAD_BALANCER)) {
			sendLoadBalancerIdFromRestoreStateToApplicationOrchestrator();
		}
		if (appOrchestratorRestoreState.containsKey(INSTANCE_TYPE_APPLICATIONS)) {
			sendApplicationIdsFromRestoreStateToApplicationOrchestrator();
		}
		if (appOrchestratorRestoreApplicationCounters != null && !appOrchestratorRestoreApplicationCounters.isEmpty()) {
			sendApplicationCountersFromRestoreStateToApplicationOrchestrator();
		}
		mainInstanceRestoreState.put(INSTANCE_TYPE_APP_ORCHESTRATOR, appOrchestrator.getInstanceId());
		sendMainInstanceIdToApplicationOrchestrator();
		setRestoreIdForAppOrchestrator(appOrchestrator.getInstanceId());
		sendApplicationOrchestratorIdFromRestoreStateToShadow();
		startInstance(appOrchestrator, API_ROOT_APPLICATION_ORCHESTRATOR);
		System.out.println("App Orchestrator started");
	}

	private static void updateEC2InstanceForMainInstance() {
		if (mainInstance == null) {
			mainInstance = EC2.retrieveEC2InstanceWithId(EC2MetadataUtils.getInstanceId());
			mainInstanceRestoreState.put(INSTANCE_TYPE_MAIN, mainInstance.getInstanceId());
			return;
		}
		mainInstance = EC2.retrieveEC2InstanceWithId(mainInstance.getInstanceId());
	}
	
	private static void updateEC2InstanceForShadow() {
		shadow = EC2.retrieveEC2InstanceWithId(shadow.getInstanceId());
	}
	
	private static void updateEC2InstanceForAppOrchestrator() {
		appOrchestrator = EC2.retrieveEC2InstanceWithId(appOrchestrator.getInstanceId());
	}

	private static boolean isMainInstanceAlive() throws URISyntaxException {
		updateEC2InstanceForMainInstance();
		if (mainInstance == null) {
			return false;
		}

		try {
			URI mainInstanceURI = new URI("http", mainInstance.getPublicDnsName(), null, null);
			URI mainInstanceHealth = UriBuilder.fromUri(mainInstanceURI).port(8080).path(API_ROOT_MAIN).path("health").build();
			int httpStatus = ClientBuilder.newClient().target(mainInstanceHealth).request().get().getStatus();
			return mainInstance.getState().getCode() == EC2.INSTANCE_RUNNING && httpStatus == 204;
		}catch(Exception e) {
			System.out.println("Main Instance not alive");
			return false;
		}
		
	}
	
	private static boolean isShadowInstanceAlive() throws URISyntaxException {
		updateEC2InstanceForShadow();
		if(shadow == null) {
			return false;
		}
		try  {
			URI shadowInstanceURI = new URI("http", shadow.getPublicDnsName(), null, null);
			URI shadowInstanceHealth = UriBuilder.fromUri(shadowInstanceURI).port(8080).path(API_ROOT_MAIN).path("health").build();
			int httpStatus = ClientBuilder.newClient().target(shadowInstanceHealth).request().get().getStatus();
			return shadow.getState().getCode() == EC2.INSTANCE_RUNNING && httpStatus == 204;
		} catch(Exception e) {
			System.out.println("Shadow Instance not alive");
			return false;
		}
	}
	
	private static boolean isAppOrchestratorAlive() throws URISyntaxException {
		updateEC2InstanceForAppOrchestrator();
		if (appOrchestrator == null) {
			return false;
		}
		try {
			URI appOrchestratorURI = new URI("http", appOrchestrator.getPublicDnsName(), null, null);
			URI appOrchestratorHealth = UriBuilder.fromUri(appOrchestratorURI).port(8080).path("application-orchestrator").path("health").build();
			int httpStatus = ClientBuilder.newClient().target(appOrchestratorHealth).request().get().getStatus();
			return appOrchestrator.getState().getCode() == EC2.INSTANCE_RUNNING && httpStatus == 204;
		} catch(Exception e) {
			System.out.println("App Orchestrator not alive");
			return false;
		}
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

	public static boolean isShadowDeployed() {
		return shadow != null;
	}
	
	public static Instance getAppOrchestrator() {
		return appOrchestrator;
	}

	public static boolean isAppOrchestratorDeployed() {
		return appOrchestrator != null;
	}

	public static boolean behaveAsShadow() {
		return isShadow && !replaceMain;
	}

	public static Instance getShadow() {
		return shadow;
	}

	public static void configureThisInstanceAsShadowWithProvidedInstanceAsMain(String mainInstanceId) {
		MainInstance.isShadow = true;
		mainInstance = EC2.retrieveEC2InstanceWithId(mainInstanceId);
		shadow = EC2.retrieveEC2InstanceWithId(EC2MetadataUtils.getInstanceId());
		mainInstanceRestoreState.put(INSTANCE_TYPE_MAIN, mainInstanceId);
		mainInstanceRestoreState.put(INSTANCE_TYPE_SHADOW, shadow.getInstanceId());
	}
	
	public static void setRestoreIdForAppOrchestrator(String appOrchestratorId) {
		mainInstanceRestoreState.put(INSTANCE_TYPE_APP_ORCHESTRATOR, appOrchestratorId);
	}
	
	private static void waitForApplicationToStart() {
		try {
			Thread.sleep(30 * 1000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	public static void uploadCredentials(Instance instance, String rootEndpointPath) throws URISyntaxException {
		URI instanceURI = new URI("http", instance.getPublicDnsName(), null, null);
		URI instanceCredentials = UriBuilder.fromUri(instanceURI).port(8080)
				.path(rootEndpointPath)
				.path("credentials")
				.build();
		SimpleAWSCredentials credentials = new SimpleAWSCredentials();
		credentials.setAccessKey(EC2.getCredentials().getAWSAccessKeyId());
		credentials.setSecretKey(EC2.getCredentials().getAWSSecretKey());
		ClientBuilder.newClient().register(JacksonJsonProvider.class).target(instanceCredentials).request().post(Entity.entity(credentials, MediaType.APPLICATION_JSON));
	}
	
	private static void sendShadowIdFromRestoreStateToMainInstance(Instance redeployedMainInstance) throws URISyntaxException {
		URI mainInstanceURI = new URI("http", redeployedMainInstance.getPublicDnsName(), null, null);
		URI backupURI = UriBuilder.fromUri(mainInstanceURI).port(8080)
				.path(API_ROOT_MAIN)
				.path("backup")
				.path("shadow")
				.queryParam("shadowInstanceId", mainInstanceRestoreState.get(INSTANCE_TYPE_SHADOW))
				.build();
		ClientBuilder.newClient().register(JacksonJsonProvider.class).target(backupURI).request().get();
	}
	
	private static void sendApplicationOrchestratorIdFromRestoreStateToShadow() throws URISyntaxException {
		URI shadowURI = new URI("http", shadow.getPublicDnsName(), null, null);
		URI backupURI = UriBuilder.fromUri(shadowURI).port(8080)
				.path(API_ROOT_MAIN)
				.path("backup")
				.path("application-orchestrator")
				.queryParam("appOrchestratorId", mainInstanceRestoreState.get(INSTANCE_TYPE_APP_ORCHESTRATOR))
				.build();
		ClientBuilder.newClient().register(JacksonJsonProvider.class).target(backupURI).request().get();
	}
	
	private static void sendApplicationOrchestratorIdFromRestoreStateToMainInstance(Instance redeployedMainInstance) throws URISyntaxException {
		URI mainInstanceURI = new URI("http", redeployedMainInstance.getPublicDnsName(), null, null);
		URI backupURI = UriBuilder.fromUri(mainInstanceURI).port(8080)
				.path(API_ROOT_MAIN)
				.path("backup")
				.path("application-orchestrator")
				.queryParam("appOrchestratorId", mainInstanceRestoreState.get(INSTANCE_TYPE_APP_ORCHESTRATOR))
				.build();
		ClientBuilder.newClient().register(JacksonJsonProvider.class).target(backupURI).request().get();
	} 
	
	private static void sendMainInstanceIdToApplicationOrchestratorFromShadow() throws URISyntaxException {
        URI appOrchestratorURI = new URI("http", EC2.retrieveEC2InstanceWithId(mainInstanceRestoreState.get(INSTANCE_TYPE_APP_ORCHESTRATOR)).getPublicDnsName(), null, null);
        sendMainInstanceIdToApplicationOrchestrator(appOrchestratorURI);
	}
	
	private static void sendMainInstanceIdToApplicationOrchestrator() throws URISyntaxException {
		URI appOrchestratorURI = new URI("http", appOrchestrator.getPublicDnsName(), null, null);
		sendMainInstanceIdToApplicationOrchestrator(appOrchestratorURI);
	}

	private static void sendMainInstanceIdToApplicationOrchestrator(URI appOrchestratorURI) throws URISyntaxException {
		URI backupURI = UriBuilder.fromUri(appOrchestratorURI).port(8080)
				.path(API_ROOT_APPLICATION_ORCHESTRATOR)
				.path("backup")
				.path("main-instance")
				.queryParam("mainInstanceId", mainInstanceRestoreState.get(INSTANCE_TYPE_MAIN))
				.build();
		ClientBuilder.newClient().register(JacksonJsonProvider.class).target(backupURI).request().get();
	}
	
	private static void sendLoadBalancerIdFromRestoreStateToApplicationOrchestrator() throws URISyntaxException {
		URI appOrchestratorURI = new URI("http", appOrchestrator.getPublicDnsName(), null, null);
		URI backupURI = UriBuilder.fromUri(appOrchestratorURI).port(8080)
				.path(API_ROOT_APPLICATION_ORCHESTRATOR)
				.path("backup")
				.path("load-balancer")
				.queryParam("loadBalancerId", appOrchestratorRestoreState.get(INSTANCE_TYPE_LOAD_BALANCER).get(0))
				.build();
		ClientBuilder.newClient().register(JacksonJsonProvider.class).target(backupURI).request().get();
	}
	
	private static void sendApplicationIdsFromRestoreStateToApplicationOrchestrator() throws URISyntaxException {
		URI appOrchestratorURI = new URI("http", appOrchestrator.getPublicDnsName(), null, null);
		URI backupURI = UriBuilder.fromUri(appOrchestratorURI).port(8080)
				.path(API_ROOT_APPLICATION_ORCHESTRATOR)
				.path("backup")
				.path("applications")
				.queryParam("applicationIds", appOrchestratorRestoreState.get(INSTANCE_TYPE_APPLICATIONS))
				.build();
		ClientBuilder.newClient().register(JacksonJsonProvider.class).target(backupURI).request().get();
	}
	
	private static void sendApplicationCountersFromRestoreStateToApplicationOrchestrator() throws URISyntaxException {
		URI appOrchestratorURI = new URI("http", appOrchestrator.getPublicDnsName(), null, null);
		for (Entry<String, Integer> entry : appOrchestratorRestoreApplicationCounters.entrySet()) {			
			URI backupURI = UriBuilder.fromUri(appOrchestratorURI).port(8080)
					.path(API_ROOT_APPLICATION_ORCHESTRATOR)
					.path("backup")
					.path("application-counter")
					.queryParam("applicationId", entry.getKey())
					.queryParam("counter", entry.getValue())
					.build();
			ClientBuilder.newClient().register(JacksonJsonProvider.class).target(backupURI).request().get();
		}
	}

	public static void configureProvidedInstanceAsShadow(Instance shadow) throws URISyntaxException {
		URI shadowURI = new URI("http", shadow.getPublicDnsName(), null, null);
		URI configureShadowURI = UriBuilder.fromUri(shadowURI).port(8080).path(API_ROOT_MAIN).path("shadow").queryParam("mainInstanceId", mainInstance.getInstanceId()).build();
		ClientBuilder.newClient().register(JacksonJsonProvider.class).target(configureShadowURI).request().get();
	}
	
	private static void startInstance(Instance instance, String rootEndpointPath) throws URISyntaxException {
		URI mainInstanceURI = new URI("http", instance.getPublicDnsName(), null, null);
		URI backupURI = UriBuilder.fromUri(mainInstanceURI).port(8080)
				.path(rootEndpointPath)
				.path("start")
				.build();
		ClientBuilder.newClient().register(JacksonJsonProvider.class).target(backupURI).request().get();
	}

	public static AWSCredentials getCredentials() {
		return EC2.getCredentials();
	}

	public static void setCredentials(AWSCredentials credentials) {
		EC2.setCredentials(credentials);
		AWSStaticCredentialsProvider staticCredentialsProvider = new AWSStaticCredentialsProvider(credentials);
		cloudWatch = AmazonCloudWatchClientBuilder.standard().withCredentials(staticCredentialsProvider).build();
	}
	
	public static void monitor() throws URISyntaxException {
		DescribeInstancesRequest request = new DescribeInstancesRequest()
				.withInstanceIds(getInstanceIDsFromAppOrchestrator())
				.withInstanceIds(shadow.getInstanceId(), appOrchestrator.getInstanceId());
		
		// This part covers the Monitoring subsection of what resources are used in the system.

		while (true) {
			DescribeInstancesResult response = EC2.getClient().describeInstances(request);

			for (Reservation reservation : response.getReservations()) {
				for (Instance instance : reservation.getInstances()) {
					InstanceMetrics instanceMetrics = new InstanceMetrics();
					instanceMetrics.setInstance(instance);
					instanceMetrics.setCloudWatchMetrics(getAdditionalMetrics(instance));
					metricsForInstances.put(instance.getInstanceId(), instanceMetrics);
				}
			}
			request.setNextToken(response.getNextToken());
			if (response.getNextToken() == null) {
				break;
			}
		}
	}
	
	public static Map<String, InstanceMetrics> getMetricsForInstances() {
		return metricsForInstances;
	}

	private static List<String> getInstanceIDsFromAppOrchestrator() throws URISyntaxException {
		List<String> instanceIds = new ArrayList<>();
		URI appOrchestratorURI = new URI("http", appOrchestrator.getPublicDnsName(), null, null);
		URI appOrchestratorDescribeLoadBalancer= UriBuilder.fromUri(appOrchestratorURI).port(8080).path("application-orchestrator").path("instances").path("load-balancer").build();
		URI appOrchestratorDescribeApplications = UriBuilder.fromUri(appOrchestratorURI).port(8080).path("application-orchestrator").path("instances").path("applications").build();
		Client client = ClientBuilder.newClient().register(JacksonJsonProvider.class);
		Instance loadBalancer = client.target(appOrchestratorDescribeLoadBalancer).request().get(Instance.class);
		if(loadBalancer != null) {			
			instanceIds.add(loadBalancer.getInstanceId());
		}		
		Collection<Instance> applications = client.target(appOrchestratorDescribeApplications).request().get(new GenericType<Collection<Instance>>(new TypeReference<Collection<Instance>>() {}.getType()));
		if(applications != null && !applications.isEmpty()) {
			applications.forEach((instance) -> instanceIds.add(instance.getInstanceId()));
		}
		return instanceIds;
	}

	private static Map<String, List<Datapoint>> getAdditionalMetrics(Instance instance) {
		Map<String, List<Datapoint>> additionalMetrics = new HashMap<>();
		
		Dimension instanceDimension = new Dimension();
		instanceDimension.setName("InstanceId");
		instanceDimension.setValue(instance.getInstanceId());
		for (String metricName : metricNames) {
			additionalMetrics.put(metricName, getSingleMetric(instanceDimension, metricName));
		}
		return additionalMetrics;
	}

	private static List<Datapoint> getSingleMetric(Dimension instanceDimension, String metricName) {
		Date oneHourAgoInUTCTimezone = Date.from(ZonedDateTime.now(ZoneOffset.UTC).minusHours(1).toInstant());
		Date currentTimeInUTCTimezone = Date.from(ZonedDateTime.now(ZoneOffset.UTC).toInstant());
		GetMetricStatisticsRequest metricStatisticsRequest = new GetMetricStatisticsRequest()
				.withStartTime(oneHourAgoInUTCTimezone).withNamespace("AWS/EC2").withPeriod(300)
				.withMetricName(metricName).withStatistics("Average")
				.withDimensions(Arrays.asList(instanceDimension)).withEndTime(currentTimeInUTCTimezone);
		GetMetricStatisticsResult getMetricStatisticsResult = cloudWatch.getMetricStatistics(metricStatisticsRequest);

		List<Datapoint> metricDatapoints = getMetricStatisticsResult.getDatapoints();
		Collections.sort(metricDatapoints, new Comparator<Datapoint>() {
			public int compare(Datapoint dp1, Datapoint dp2) {
				return dp1.getTimestamp().compareTo(dp2.getTimestamp());
			}
		});
		return metricDatapoints;
	}

	public static Instance getMainInstance() {
		return mainInstance;
	}

	public static void setMainInstance(Instance mainInstance) {
		MainInstance.mainInstance = mainInstance;
	}
	
	/**
	 * Wait a specific amount of time before
	 */
	public static void waitUntilNextIteration() {
		try {
			Thread.sleep(ITERATION_WAIT_TIME);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	public static void setRestoreIdForLoadBalancer(String loadBalancerId) {
		appOrchestratorRestoreState.put(INSTANCE_TYPE_LOAD_BALANCER, Arrays.asList(loadBalancerId));
	}

	public static void setRestoreIdsForApplications(List<String> applicationIds) {
		appOrchestratorRestoreState.put(INSTANCE_TYPE_APPLICATIONS, applicationIds);
		if(!isShadow) {
			backupApplicationsRestoreState();
		}
	}

	public static void setRestoreIdForShadow(String shadowId) {
		mainInstanceRestoreState.put(INSTANCE_TYPE_SHADOW, shadowId);
	}

	public static void setBackupApplicationCounter(String applicationId, int counter) {
		appOrchestratorRestoreApplicationCounters.put(applicationId, counter);
		if(!isShadow) {
			backupAppOrchestratorApplicationCounter(applicationId, counter);
		}
	}

	private static void backupApplicationsRestoreState() {
		URI backupURI = UriBuilder.fromPath("")
				.scheme("http")
				.host(shadow.getPublicDnsName())
				.port(8080)
				.path(API_ROOT_MAIN)
				.path("backup")
				.path("applications")
				.build();
		ClientBuilder.newClient()
		.target(backupURI)
		.queryParam("applicationIds", appOrchestratorRestoreState.get(INSTANCE_TYPE_APPLICATIONS).toArray())
		.request()
		.get();
	}

	private static void backupAppOrchestratorApplicationCounter(String applicationId, int counter) {
		URI backupURI = UriBuilder.fromPath("")
				.scheme("http")
				.host(shadow.getPublicDnsName())
				.port(8080)
				.path(API_ROOT_MAIN)
				.path("backup")
				.path("applications")
				.build();
		ClientBuilder.newClient()
		.target(backupURI)
		.queryParam("applicationId", applicationId)
		.queryParam("counter", counter)
		.request()
		.get();
	}
	
	private static void sendAppOrchestratorRestoreStateToMainInstance(Instance redeployedMainInstance) {
		URI backupURI = UriBuilder.fromPath("")
				.scheme("http")
				.host(redeployedMainInstance.getPublicDnsName())
				.port(8080)
				.path(API_ROOT_MAIN)
				.path("backup")
				.path("applications")
				.build();
		ClientBuilder.newClient()
		.target(backupURI)
		.queryParam("applicationIds", appOrchestratorRestoreState.get(INSTANCE_TYPE_APPLICATIONS).toArray())
		.request()
		.get();
	}
	
	private static void sendAppOrchestratorApplicationCountersToMainInstance(Instance redeployedMainInstance) {
		URI backupURI = UriBuilder.fromPath("")
				.scheme("http")
				.host(redeployedMainInstance.getPublicDnsName())
				.port(8080)
				.path(API_ROOT_MAIN)
				.path("backup")
				.path("applications")
				.build();
		Client client = ClientBuilder.newClient();
		appOrchestratorRestoreApplicationCounters.entrySet()
		.stream()
		.forEach(entry -> {
			client
			.target(backupURI)
			.queryParam("applicationId", entry.getKey())
			.queryParam("counter", entry.getValue())
			.request()
			.get();			
		});
	}
}
