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
	private static final String TAG_REDEPLOYED_MAIN_INSTANCE = "Redeployed Main Instance";
	private static final String TAG_APP_ORCHESTRATOR = "App Orchestrator";
	private static final String TAG_SHADOW = "Shadow Instance";
	private static final String API_ROOT_MAIN = "main";
	private static final String AWS_KEYPAIR_NAME = "accessibleFromMainInstance";
	private static final int ITERATION_WAIT_TIME = 60 * 1000;
	private static boolean keepAlive;
	private static Instance mainInstance;
	private static Instance shadow;
	private static Instance appOrchestrator;
	private static boolean isShadow;
	private static boolean replaceMain;
	private static AmazonCloudWatch cloudWatch;
	private static final List<String> metricNames = Arrays.asList("CPUUtilization", "NetworkIn", "NetworkOut", "DiskReadOps", "DiskWriteOps");
	private static Map<String, InstanceMetrics> metricsForInstances = new HashMap<>();

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
			if (EC2.getCredentials() == null) {
				System.out.println("Waiting for AWS credentials, cannot start yet");
			}
			else {
				if (behaveAsShadow()) {
					System.out.println("Checking main instance liveness from shadow");
					checkMainInstanceLiveness();
				}
				else{
					updateEC2InstanceForMainInstance();
					if (!isShadowDeployed()) {
						System.out.println("Deploying shadow");
						deployShadow();
					}
					if(!isAppOrchestratorDeployed()) {
						System.out.println("Deploying app orchestrator");
						deployAppOrchestrator();
					}
					System.out.println("Checking shadow liveness from main instance");
					checkShadowInstanceLiveness();
					System.out.println("Checking app orchestrator liveness");
					checkAppOrchestratorLiveness();
					System.out.println("Start monitoring");
					monitor();
				}
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
		EC2.terminateEC2(previousInstanceId);
	}
	
	private static void redeployMainInstance() throws IOException, NoSuchAlgorithmException, URISyntaxException {
		mainInstance = EC2.deployDefaultEC2(TAG_REDEPLOYED_MAIN_INSTANCE, AWS_KEYPAIR_NAME);
		System.out.println("Main Instance redeployed");
		EC2.waitForInstanceToRun(mainInstance.getInstanceId());
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/application.jar").toFile(), mainInstance);
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/app-orchestrator.jar").toFile(), mainInstance);
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/load-balancer.jar").toFile(), mainInstance);
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/main-instance.jar").toFile(), mainInstance);
		EC2.startDeployedApplication(mainInstance, "main-instance");
		waitForApplicationToStart();
		uploadCredentials(mainInstance, API_ROOT_MAIN);
		System.out.println("Main Instance application started");
	}

	private static void deployShadow() throws IOException, NoSuchAlgorithmException, URISyntaxException {
		shadow = EC2.deployDefaultEC2(TAG_SHADOW, AWS_KEYPAIR_NAME);
		System.out.println("Shadow deployed");
		EC2.waitForInstanceToRun(shadow.getInstanceId());
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/application.jar").toFile(), shadow);
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/app-orchestrator.jar").toFile(), shadow);
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/load-balancer.jar").toFile(), shadow);
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/main-instance.jar").toFile(), shadow);
		EC2.startDeployedApplication(shadow, "main-instance");
		waitForApplicationToStart();
		uploadCredentials(shadow, API_ROOT_MAIN);
		configureProvidedInstanceAsShadow(shadow);
		System.out.println("Shadow application started");
	}

	private static void deployAppOrchestrator() throws IOException, NoSuchAlgorithmException, URISyntaxException {
		EC2.ensureJavaKeyPairExists();
		appOrchestrator = EC2.deployDefaultEC2(TAG_APP_ORCHESTRATOR, AWS_KEYPAIR_NAME);
		System.out.println("App Orchestrator deployed");
		EC2.waitForInstanceToRun(appOrchestrator.getInstanceId());
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/application.jar").toFile(), appOrchestrator);
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/load-balancer.jar").toFile(), appOrchestrator);
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/app-orchestrator.jar").toFile(), appOrchestrator);
		EC2.startDeployedApplication(appOrchestrator, "app-orchestrator");
		waitForApplicationToStart();
		uploadCredentials(appOrchestrator, "application-orchestrator");
		System.out.println("App Orchestrator started");
	}

	private static void updateEC2InstanceForMainInstance() {
		if (mainInstance == null) {
			mainInstance = EC2.retrieveEC2InstanceWithId(EC2MetadataUtils.getInstanceId());
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
		System.out.println(mainInstance);
		System.out.println(shadow);
		if (mainInstance == null) {
			return false;
		}
		URI mainInstanceURI = new URI("http", mainInstance.getPublicDnsName(), null, null);
		URI mainInstanceHealth = UriBuilder.fromUri(mainInstanceURI).port(8080).path(API_ROOT_MAIN).path("health").build();
		int httpStatus = ClientBuilder.newClient().target(mainInstanceHealth).request().get().getStatus();
		return mainInstance.getState().getCode() == EC2.INSTANCE_RUNNING && httpStatus == 204;
	}
	
	private static boolean isShadowInstanceAlive() throws URISyntaxException {
		updateEC2InstanceForShadow();
		if(shadow == null) {
			return false;
		}
		URI shadowInstanceURI = new URI("http", shadow.getPublicDnsName(), null, null);
		URI shadowInstanceHealth = UriBuilder.fromUri(shadowInstanceURI).port(8080).path(API_ROOT_MAIN).path("health").build();
		int httpStatus = ClientBuilder.newClient().target(shadowInstanceHealth).request().get().getStatus();
		return shadow.getState().getCode() == EC2.INSTANCE_RUNNING && httpStatus == 204;
	}
	
	private static boolean isAppOrchestratorAlive() throws URISyntaxException {
		updateEC2InstanceForAppOrchestrator();
		if (appOrchestrator == null) {
			return false;
		}
		URI appOrchestratorURI = new URI("http", appOrchestrator.getPublicDnsName(), null, null);
		URI appOrchestratorHealth = UriBuilder.fromUri(appOrchestratorURI).port(8080).path("application-orchestrator").path("health").build();
		int httpStatus = ClientBuilder.newClient().target(appOrchestratorHealth).request().get().getStatus();
		return appOrchestrator.getState().getCode() == EC2.INSTANCE_RUNNING && httpStatus == 204;
	}

	public static boolean isAlive() {
		return keepAlive;
	}
	
	public static void stopMainLoop() {
		keepAlive = false;
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
		System.out.println(mainInstanceId);
		mainInstance = EC2.retrieveEC2InstanceWithId(mainInstanceId);
		shadow = EC2.retrieveEC2InstanceWithId(EC2MetadataUtils.getInstanceId());
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
		URI instanceCredentials = UriBuilder.fromUri(instanceURI).port(8080).path(rootEndpointPath).path("credentials").build();
		SimpleAWSCredentials credentials = new SimpleAWSCredentials();
		credentials.setAccessKey(EC2.getCredentials().getAWSAccessKeyId());
		credentials.setSecretKey(EC2.getCredentials().getAWSSecretKey());
		ClientBuilder.newClient().register(JacksonJsonProvider.class).target(instanceCredentials).request().post(Entity.entity(credentials, MediaType.APPLICATION_JSON));
	}
	
	public static void configureProvidedInstanceAsShadow(Instance shadow) throws URISyntaxException {
		URI shadowURI = new URI("http", shadow.getPublicDnsName(), null, null);
		URI configureShadowURI = UriBuilder.fromUri(shadowURI).port(8080).path(API_ROOT_MAIN).path("shadow").queryParam("mainInstanceId", mainInstance.getInstanceId()).build();
		ClientBuilder.newClient().register(JacksonJsonProvider.class).target(configureShadowURI).request().get();
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
		
		// This part covers the Monitoring subsection of what resources are used in the
		// system.
		// TODO Usage of resources by the system can be the total of the aforementioned
		// ones or the description of AWS resources used(basically everything present in
		// the EC2 dashboard).
		// TODO The number of users can be requested by the Load Balancer.
		// TODO The performance part could be the time the system takes to fully handle
		// a user request, measured by the Load Balancer again

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

	private static List<String> getInstanceIDsFromAppOrchestrator() throws URISyntaxException {
		List<String> instanceIds = new ArrayList<>();
		URI appOrchestratorURI = new URI("http", appOrchestrator.getPublicDnsName(), null, null);
		UriBuilder appOrchestratorDescribeBase= UriBuilder.fromUri(appOrchestratorURI).port(8080).path("application-orchestrator").path("instances");
		URI appOrchestratorDescribeLoadBalancer = appOrchestratorDescribeBase.path("load-balancer").build();
		URI appOrchestratorDescribeApplications = appOrchestratorDescribeBase.path("applications").build();
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
		// TODO Include other metrics besides cloudwatch
		// TODO Store/present values in useful way for the rest of the system
		for (String metricName : metricNames) {
			additionalMetrics.put(metricName, getSingleMetric(instanceDimension, metricName));
		}
		return additionalMetrics;
	}

	private static List<Datapoint> getSingleMetric(Dimension instanceDimension, String metricName) {
		System.out.println(metricName);
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
		for (Datapoint datapoint : metricDatapoints) {
			System.out.println(datapoint.getTimestamp());
			System.out.println(datapoint.getAverage());
		}
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
}
