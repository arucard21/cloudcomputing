package in4392.cloudcomputing.maininstance;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Named;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.UriBuilder;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.services.cloudwatch.AmazonCloudWatch;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder;
import com.amazonaws.services.cloudwatch.model.Datapoint;
import com.amazonaws.services.cloudwatch.model.Dimension;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsRequest;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsResult;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.DeleteKeyPairRequest;
import com.amazonaws.services.ec2.model.DescribeInstanceStatusRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.ImportKeyPairRequest;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceStatus;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.ResourceType;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.StartInstancesRequest;
import com.amazonaws.services.ec2.model.StopInstancesRequest;
import com.amazonaws.services.ec2.model.SummaryStatus;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.TagSpecification;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.util.EC2MetadataUtils;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.direct.Session.Command;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.xfer.FileSystemFile;
import net.schmizz.sshj.xfer.scp.SCPFileTransfer;

@Named
public class MainInstance {
	private static final String AWS_KEYPAIR_NAME = "accessibleFromMainInstance";
	private static final String AMI_ID_EU_WEST_3_UBUNTU_SERVER_1804 = "ami-0a2ca21adb4a04084";
	private static final String ERROR_INCORRECTLY_DEPLOYED = "The newly deployed EC2 instance did not start correctly. You may need to manually verify and correct this";
	private static final String ERROR_STATUS_CHECKS_NOT_OK = "The newly deployed EC2 instance is running but some of the status checks may not have passed";
	private static final int ITERATION_WAIT_TIME = 60 * 1000;
	private static final int INSTANCE_PENDING = 0;
	private static final int INSTANCE_RUNNING = 16;
	private static final int INSTANCE_STOPPED = 80;
	private static boolean keepAlive;
	private static Instance mainInstance;
	private static Instance shadow;
	private static Instance appOrchestrator;
	private static boolean isShadow;
	private static boolean replaceMain;
	private static AmazonEC2 client;
	private static AmazonCloudWatch cloudWatch;
	private static final List<String> metricNames = Arrays.asList("CPUUtilization", "NetworkIn", "NetworkOut", "DiskReadOps", "DiskWriteOps");
	private static Map<String, InstanceMetrics> metricsForInstances = new HashMap<>();
	private static AWSCredentials credentials;
	private static KeyPair javaKeyPair;
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
			if (credentials == null) {
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
			waitUntilNextIteration();
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
			stopEC2Instance(mainInstance.getInstanceId());
		}
		else {
			if (mainInstanceStartAttempted == false) {
				if (isStopped(mainInstance.getInstanceId())) {
					mainInstanceStartAttempted = true;
					startEC2Instance(mainInstance.getInstanceId());
				}
			}
			else {
				if(mainInstanceRedeployAttempted == false) {
					mainInstanceRedeployAttempted = true;
					String previousMainInstanceId = mainInstance.getInstanceId();
					mainInstance = deployDefaultEC2("", "cloudcomputing");
					// termination of non-working EC2 instance is not verified
					// it might still be running in AWS which can be checked in AWS Console
					terminateEC2(previousMainInstanceId);
				}
			}
		}
	}

	private static void startEC2Instance(String instanceId) {
		client.startInstances(new StartInstancesRequest().withInstanceIds(instanceId));
	}

	private static boolean isStopped(String instanceId) {
		Instance stoppingInstance = retrieveEC2InstanceWithId(instanceId);
		if (stoppingInstance.getState().getCode() == INSTANCE_STOPPED) {
			return true;
		}
		return false;
	}

	private static void stopEC2Instance(String instanceId) {
		client.stopInstances(new StopInstancesRequest().withInstanceIds(instanceId));
	}

	private static void terminateEC2(String instanceId) {
		client.terminateInstances(new TerminateInstancesRequest().withInstanceIds(instanceId));
	}

	private static void updateEC2InstanceForMainInstance() {
		mainInstance = retrieveEC2InstanceWithId(EC2MetadataUtils.getInstanceId());
	}

	private static Instance retrieveEC2InstanceWithId(String instanceId) {
		return client.describeInstances(new DescribeInstancesRequest()
				.withInstanceIds(instanceId))
				.getReservations().get(0)
				.getInstances().get(0);
	}

	private static boolean isMainInstanceAlive() {
		updateEC2InstanceForMainInstance();
		int httpStatus = healthCheckOnInstance(mainInstance);
		return mainInstance.getState().getCode() == INSTANCE_RUNNING && httpStatus == 204;
	}

	private static int healthCheckOnInstance(Instance instance) {
		URI mainInstanceHealth = UriBuilder.fromUri(instance.getPublicDnsName()).path("health").build();
		int httpStatus = ClientBuilder.newClient().target(mainInstanceHealth).request().get().getStatus();
		return httpStatus;
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
	
	/**
	 * Deploy an EC2 instance and wait for it to be running and the status checks to pass
	 * 
	 * @param usageTag is the tag that represents what the machine will be used for
	 * @return the Instance object representing the deployed instance
	 * @throws NoSuchAlgorithmException 
	 * @throws IOException if the userdata script can not be read
	 */
	public static Instance deployDefaultEC2(String usageTag, String keyPairName) throws NoSuchAlgorithmException {
		ensureJavaKeyPairExists();
		RunInstancesRequest runInstancesRequest = new RunInstancesRequest(AMI_ID_EU_WEST_3_UBUNTU_SERVER_1804, 1, 1)
				.withInstanceType(InstanceType.T2Micro)
				.withKeyName(keyPairName)
				.withUserData(getUserData());
		if (usageTag != null && !usageTag.isEmpty()) {
			runInstancesRequest.withTagSpecifications(
					new TagSpecification()
					.withResourceType(ResourceType.Instance)
					.withTags(
							new Tag()
							.withKey("Usage")
							.withValue(usageTag)));
		}
		RunInstancesResult runInstancesResult = client.runInstances(runInstancesRequest);
		String deployedInstanceId = runInstancesResult.getReservation().getInstances().get(0).getInstanceId();
		// wait up to 1 minute for the instance to run
		waitForInstanceToRun(deployedInstanceId);
		return client.describeInstances(new DescribeInstancesRequest().withInstanceIds(deployedInstanceId))
				.getReservations().get(0)
				.getInstances().get(0);
	}

	private static void ensureJavaKeyPairExists() throws NoSuchAlgorithmException {
		if (javaKeyPair != null) {
			return;
		}
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		javaKeyPair = generator.generateKeyPair();
		String publicKey = Base64.getEncoder().encodeToString(javaKeyPair.getPublic().getEncoded());
		removeExistingKeyPair();
		ImportKeyPairRequest keyPairRequest = new ImportKeyPairRequest(AWS_KEYPAIR_NAME, publicKey);
		client.importKeyPair(keyPairRequest);
	}

	private static void removeExistingKeyPair() {
		client.deleteKeyPair(new DeleteKeyPairRequest(AWS_KEYPAIR_NAME));
	}

	private static String getUserData() {
		String userData = "#!/bin/bash\n" + 
				"apt update\n" + 
				"apt install -y openjdk-8-jre\n";
		return Base64.getEncoder().encodeToString(userData.getBytes());
	}

	private static void waitForInstanceToRun(String deployedInstanceId) {
		for (int i = 0; i < 6; i++) {
			try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			int state = getInstanceState(deployedInstanceId);
			if (state == INSTANCE_PENDING) {
				continue;
			}
			if (state == INSTANCE_RUNNING) {
				// check status checks are completed as well
				boolean passed = verifyStatusChecksPassed(deployedInstanceId);
				if (!passed) {
				        throw new IllegalStateException(ERROR_STATUS_CHECKS_NOT_OK);
				}
				break;
			}
			throw new IllegalStateException(ERROR_INCORRECTLY_DEPLOYED);
		}
	}

	private static int getInstanceState(String deployedInstanceId) {
		int state = client.describeInstances(new DescribeInstancesRequest().withInstanceIds(deployedInstanceId))
				.getReservations().get(0)
				.getInstances().get(0)
				.getState()
				.getCode();
		return state;
	}
	
	private static boolean verifyStatusChecksPassed(String deployedInstanceId) {
		boolean passed = false;
		// wait up to 10 minutes for the status checks
		for (int j = 0; j < 10; j++) {
			try {
				Thread.sleep(60000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			InstanceStatus status = client
					.describeInstanceStatus(new DescribeInstanceStatusRequest().withInstanceIds(deployedInstanceId))
					.getInstanceStatuses().get(0);
			SummaryStatus systemStatus = SummaryStatus.fromValue(status.getSystemStatus().getStatus());
			SummaryStatus instanceStatus = SummaryStatus.fromValue(status.getInstanceStatus().getStatus());
			if (SummaryStatus.Ok.equals(systemStatus) && SummaryStatus.Ok.equals(instanceStatus)) {
				passed = true;
				break;
			}
		}
		return passed;
	}

	private static void deployShadow() throws IOException, NoSuchAlgorithmException {
		shadow = deployDefaultEC2("shadow", AWS_KEYPAIR_NAME);
		System.out.println("Shadow deployed");
		waitForInstanceToRun(shadow.getInstanceId());
		copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/application.jar").toFile(), shadow);
		copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/app-orchestrator.jar").toFile(), shadow);
		copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/load-balancer.jar").toFile(), shadow);
		copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/main-instance.jar").toFile(), shadow);
		startDeployedApplication(shadow, "main-instance");
		System.out.println("Shadow application started");
	}

	private static void copyApplicationToDeployedInstance(File applicationJarFile, Instance instance) throws IOException, NoSuchAlgorithmException {
		ensureJavaKeyPairExists();
		try (SSHClient ssh = new SSHClient()){
			ssh.loadKnownHosts();
			ssh.addHostKeyVerifier(new PromiscuousVerifier());
			ssh.connect(instance.getPublicDnsName());
			ssh.authPublickey("ubuntu", Arrays.asList(ssh.loadKeys(javaKeyPair)));
			SCPFileTransfer scp = ssh.newSCPFileTransfer();
			String homeDirOnRemoteInstance = "/home/ubuntu/";
			scp.upload(new FileSystemFile(applicationJarFile), homeDirOnRemoteInstance);
		}
	}

	private static void startDeployedApplication(Instance instance, String applicationName) throws IOException, NoSuchAlgorithmException {
		ensureJavaKeyPairExists();
		try (SSHClient ssh = new SSHClient()){
			ssh.loadKnownHosts();
			ssh.addHostKeyVerifier(new PromiscuousVerifier());
			ssh.connect(instance.getPublicDnsName());
			ssh.authPublickey("ubuntu", Arrays.asList(ssh.loadKeys(javaKeyPair)));
			Command command;
			String remoteCommand = "nohup java -jar /home/ubuntu/"+applicationName+".jar > /home/ubuntu/"+applicationName+".log 2>&1 &";
			try(Session session = ssh.startSession()){
				command = session.exec(remoteCommand);
			}
			if (command.getExitStatus() != 0) {
				System.out.println(applicationName + " was not started correctly with the command: " + remoteCommand);
			}
		}
	}
	
	private static void deployAppOrchestrator() throws IOException, NoSuchAlgorithmException {
		ensureJavaKeyPairExists();
		appOrchestrator = deployDefaultEC2("App Orchestrator", AWS_KEYPAIR_NAME);
		System.out.println("App Orchestrator deployed");
		waitForInstanceToRun(appOrchestrator.getInstanceId());
		copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/application.jar").toFile(), appOrchestrator);
		copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/load-balancer.jar").toFile(), appOrchestrator);
		copyApplicationToDeployedInstance(Paths.get("/home/ubuntu/app-orchestrator.jar").toFile(), appOrchestrator);
		startDeployedApplication(appOrchestrator, "app-orchestrator");
		System.out.println("App Orchestrator started");
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

	public static AWSCredentials getCredentials() {
		return credentials;
	}

	public static void setCredentials(AWSCredentials credentials) {
		MainInstance.credentials = credentials;
		AWSStaticCredentialsProvider staticCredentialsProvider = new AWSStaticCredentialsProvider(credentials);
		client = AmazonEC2ClientBuilder.standard().withCredentials(staticCredentialsProvider).build();
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
			DescribeInstancesResult response = client.describeInstances(request);

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
