package in4392.cloudcomputing.maininstance;

import java.io.IOException;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Named;

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

@Named
public class MainInstance {
	private static final String AWS_KEYPAIR_NAME = "accessibleFromMainInstance";
	private static final int INSTANCE_RUNNING = 16;
	private static boolean keepAlive;
	private static Instance mainInstance;
	private static Instance shadow;
	private static Instance appOrchestrator;
	private static boolean isShadow;
	private static boolean replaceMain;
	private static AmazonCloudWatch cloudWatch;
	private static final List<String> metricNames = Arrays.asList("CPUUtilization", "NetworkIn", "NetworkOut", "DiskReadOps", "DiskWriteOps");
	private static Map<String, InstanceMetrics> metricsForInstances = new HashMap<>();
	private static boolean mainInstanceStopAttempted = false;
	private static boolean mainInstanceStartAttempted = false;
	private static boolean mainInstanceRedeployAttempted = false;

	/**
	 * Start the main loop. 
	 * 
	 * This will run continuously, executing an iteration every minute (defined by ITERATION_WAIT_TIME). 
	 * The loop can be stopped and restarted through the API, with a GET request to 
	 * "http:\<instanceURL\>:8080/main/start" or "http:\<instanceURL\>:8080/main/stop".
	 * @throws IOException 
	 * @throws NoSuchAlgorithmException 
	 */
	protected static void startMainLoop() throws IOException, NoSuchAlgorithmException {
		keepAlive = true;
		while(keepAlive) {
			if (EC2.getCredentials() == null) {
				System.out.println("Waiting for AWS credentials, cannot start yet");
			}
			else {
				if (mainInstance == null) {
					updateEC2InstanceForMainInstance();
				}
				if (!behaveAsShadow() && !isShadowDeployed()) {
					deployShadow();
				}
				if(!isAppOrchestratorDeployed()) {
					deployAppOrchestrator();
				}
				if (behaveAsShadow()) {
					if(!isMainInstanceAlive()) {
						recoverMainInstance();
					}
					else {
						resetRecoveryFlags();
					}
				}
				else{
					monitor();
				}
			}
			EC2.waitUntilNextIteration();
		}
	}

	private static void resetRecoveryFlags() {
		if(replaceMain == true) {
			// reset all flags since the main instance is working correctly
			mainInstanceStopAttempted = false;
			mainInstanceRedeployAttempted = false;
			replaceMain = false;
		}
	}

	private static void recoverMainInstance() throws NoSuchAlgorithmException {
		replaceMain = true;
		if(mainInstanceStopAttempted == false) {
			mainInstanceStopAttempted = true;
			EC2.stopEC2Instance(mainInstance.getInstanceId());
		}
		else {
			if (mainInstanceStartAttempted == false) {
				if (EC2.isStopped(mainInstance.getInstanceId())) {
					mainInstanceStartAttempted = true;
					EC2.startEC2Instance(mainInstance.getInstanceId());
				}
			}
			else {
				if(mainInstanceRedeployAttempted == false) {
					mainInstanceRedeployAttempted = true;
					String previousMainInstanceId = mainInstance.getInstanceId();
					mainInstance = EC2.deployDefaultEC2("", "cloudcomputing");
					// termination of non-working EC2 instance is not verified
					// it might still be running in AWS which can be checked in AWS Console
					EC2.terminateEC2(previousMainInstanceId);
				}
			}
		}
	}

	private static void updateEC2InstanceForMainInstance() {
		mainInstance = EC2.retrieveEC2InstanceWithId(EC2MetadataUtils.getInstanceId());
	}

	private static boolean isMainInstanceAlive() {
		updateEC2InstanceForMainInstance();
		int httpStatus = EC2.healthCheckOnInstance(mainInstance);
		return mainInstance.getState().getCode() == INSTANCE_RUNNING && httpStatus == 204;
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

	public static boolean isShadowDeployed() {
		return shadow != null;
	}
	
	public static boolean isAppOrchestratorDeployed() {
		return appOrchestrator != null;
	}

	public static boolean behaveAsShadow() {
		return isShadow && !replaceMain;
	}

	public static void setShadow(boolean isShadow) {
		MainInstance.isShadow = isShadow;
	}
	
	private static void deployShadow() throws IOException, NoSuchAlgorithmException {
		shadow = EC2.deployDefaultEC2("shadow", AWS_KEYPAIR_NAME);
		System.out.println("Shadow deployed");
		EC2.waitForInstanceToRun(shadow.getInstanceId());
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/application.jar").toFile(), shadow);
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/app-orchestrator.jar").toFile(), shadow);
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/load-balancer.jar").toFile(), shadow);
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/main-instance.jar").toFile(), shadow);
		EC2.startDeployedApplication(shadow, "main-instance");
		System.out.println("Shadow application started");
	}

	private static void deployAppOrchestrator() throws IOException, NoSuchAlgorithmException {
		EC2.ensureJavaKeyPairExists();
		appOrchestrator = EC2.deployDefaultEC2("App Orchestrator", AWS_KEYPAIR_NAME);
		System.out.println("App Orchestrator deployed");
		EC2.waitForInstanceToRun(appOrchestrator.getInstanceId());
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/application.jar").toFile(), appOrchestrator);
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/load-balancer.jar").toFile(), appOrchestrator);
		EC2.copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/app-orchestrator.jar").toFile(), appOrchestrator);
		EC2.startDeployedApplication(appOrchestrator, "app-orchestrator");
		System.out.println("App Orchestrator started");
	}

	public static AWSCredentials getCredentials() {
		return EC2.getCredentials();
	}

	public static void setCredentials(AWSCredentials credentials) {
		EC2.setCredentials(credentials);
		AWSStaticCredentialsProvider staticCredentialsProvider = new AWSStaticCredentialsProvider(credentials);
		cloudWatch = AmazonCloudWatchClientBuilder.standard().withCredentials(staticCredentialsProvider).build();
	}
	
	public static void monitor() {
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

	private static List<String> getInstanceIDsFromAppOrchestrator() {
		// TODO retrieve the instance IDs of the load balancer and application 
		// instances through the API of the app orchestrator.
		return Collections.emptyList();
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
}
