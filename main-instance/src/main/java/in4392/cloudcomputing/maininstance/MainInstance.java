package in4392.cloudcomputing.maininstance;

import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

import javax.inject.Named;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
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
import com.amazonaws.services.ec2.model.SummaryStatus;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.TagSpecification;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsResult;
import com.amazonaws.services.cloudwatch.model.Datapoint;
import com.amazonaws.services.cloudwatch.model.Dimension;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsRequest;
import com.amazonaws.services.cloudwatch.AmazonCloudWatch;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;

@Named
public class MainInstance {
	private static final String AWS_KEYPAIR_NAME = "accessibleFromMainInstance";
	private static final String AMI_ID_EU_WEST_3_UBUNTU_SERVER_1804 = "ami-0a2ca21adb4a04084";
	private static final String ERROR_INCORRECTLY_DEPLOYED = "The newly deployed EC2 instance did not start correctly. You may need to manually verify and correct this";
	private static final String ERROR_STATUS_CHECKS_NOT_OK = "The newly deployed EC2 instance is running but some of the status checks may not have passed";
	private static final int ITERATION_WAIT_TIME = 60 * 1000;
	private static final int INSTANCE_PENDING = 0;
	private static final int INSTANCE_RUNNING = 16;
	private static boolean keepAlive;
	private static Instance shadow;
	private static boolean isShadow;
	private static AmazonEC2 client = AmazonEC2ClientBuilder.defaultClient();
	private static AmazonCloudWatch cloudWatch = AmazonCloudWatchClientBuilder.defaultClient();
	private static List<String> cloudWatchMetrics = new ArrayList<String>();
	private static AWSCredentials credentials;
	private static KeyPair javaKeyPair;

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
				continue;
			}
			if (!isShadow && !isShadowDeployed()) {
				deployShadow();
			}
			if (isShadow) {
				System.out.println("Do something useful here for the shadow instance, like checking the main instance");
			}
			else{
				System.out.println("Do something useful here for the main instance, like monitoring");
				monitor();
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

	public static boolean isShadowDeployed() {
		return shadow != null;
	}

	public static boolean isShadow() {
		return isShadow;
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
	public static Instance deployDefaultEC2(String usageTag) throws NoSuchAlgorithmException {
		ensureJavaKeyPairExists();
		RunInstancesRequest runInstancesRequest = new RunInstancesRequest(AMI_ID_EU_WEST_3_UBUNTU_SERVER_1804, 1, 1)
				.withInstanceType(InstanceType.T2Micro)
				.withKeyName(AWS_KEYPAIR_NAME)
				.withUserData(getUserData())
				.withTagSpecifications(
						new TagSpecification()
						.withResourceType(ResourceType.Instance)
						.withTags(
								new Tag()
								.withKey("Usage")
								.withValue(usageTag)));
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
		ImportKeyPairRequest keyPairRequest = new ImportKeyPairRequest(AWS_KEYPAIR_NAME, publicKey);
		client.importKeyPair(keyPairRequest);
	}

	private static String getUserData() {
		return "#!/bin/bash\n" + 
				"apt update\n" + 
				"apt install -y openjdk-8-jre\n";
	}

	private static void waitForInstanceToRun(String deployedInstanceId) {
		for (int i = 0; i < 6; i++) {
			try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			int state = client.describeInstances(new DescribeInstancesRequest().withInstanceIds(deployedInstanceId))
					.getReservations().get(0)
					.getInstances().get(0)
					.getState()
					.getCode();
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
					.describeInstanceStatus(
							new DescribeInstanceStatusRequest()
							.withInstanceIds(deployedInstanceId))
					.getInstanceStatuses()
					.get(0);
			SummaryStatus systemStatus = SummaryStatus.fromValue(status.getSystemStatus().getStatus());
			SummaryStatus instanceStatus = SummaryStatus.fromValue(status.getInstanceStatus().getStatus());
			if(SummaryStatus.Ok.equals(systemStatus) && SummaryStatus.Ok.equals(instanceStatus)) {
				passed = true;
				break;
			}
		}
		return passed;
	}

	private static void deployShadow() throws IOException, NoSuchAlgorithmException {
		ensureJavaKeyPairExists();
		shadow = deployDefaultEC2("shadow");
		System.out.println("Shadow deployed");
		try (SSHClient ssh = new SSHClient()){
			ssh.loadKnownHosts();
			ssh.addHostKeyVerifier(new PromiscuousVerifier());
			ssh.authPublickey("ubuntu", Arrays.asList(ssh.loadKeys(javaKeyPair)));
			ssh.connect(shadow.getPublicDnsName());
			try(Session session = ssh.startSession()){
				session.exec("nohup java -jar ~/main-instance.jar > ~/main-instance.log 2>&1 &");
			}
		}
		System.out.println("Shadow application started");
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
	}
	
	public static void monitor() {
		
		boolean done = false;
		DescribeInstancesRequest request = new DescribeInstancesRequest();
		cloudWatchMetrics.add("CPUUtilization");
		cloudWatchMetrics.add("NetworkIn");
		cloudWatchMetrics.add("NetworkOut");
		cloudWatchMetrics.add("DiskReadOps");
		cloudWatchMetrics.add("DiskWriteOps");
		// This part covers the Monitoring subsection of what resources are used in the system. 
		// TODO Usage of resources by the system can be the total of the aforementioned ones or the description of AWS resources used(basically everything present in the EC2 dashboard). 
		// TODO The number of users can be requested by the Load Balancer. 
		// TODO The performance part could be the time the system takes to fully handle a user request, measured by the Load Balancer again
		
		// Its one hour in milliseconds, in order to get past state of instance
		// Temporary offset that is the time difference between here and Paris(don't ask me why it is two hours). Will not be needed when running the system as instances will be in the same timezone
		long offsetInMilliseconds = 1000 * 60 * 60;
		long timeDifferenceParis = 1000 * 60 * 120;
		Dimension instanceDimension = new Dimension();
		List<Datapoint> Datapoints;

		while(!done) {
		    DescribeInstancesResult response = client.describeInstances(request);

		    for(Reservation reservation : response.getReservations()) {
		        for(Instance instance : reservation.getInstances()) {
		            System.out.printf(
		                "Found instance with id %s, " +
		                "AMI %s, " +
		                "type %s, " +
		                "state %s " +
		                "and monitoring state %s\n",
		                instance.getInstanceId(),
		                instance.getImageId(),
		                instance.getInstanceType(),
		                instance.getState().getName(),
		                instance.getMonitoring().getState());
			    	
				instanceDimension.setName("InstanceId");
				instanceDimension.setValue(instance.getInstanceId());
		//TODO Include other metrics besides cloudwatch
		//TODO Store/present values in useful way for the rest of the system
	            	for(String name: cloudWatchMetrics) {
				System.out.println(name);

				GetMetricStatisticsRequest request2 = new GetMetricStatisticsRequest()
					.withStartTime(new Date(new Date().getTime() - timeDifferenceParis - offsetInMilliseconds))
					.withNamespace("AWS/EC2")
		        		.withPeriod(300)
			        	.withMetricName(name)
					.withStatistics("Average")
					.withDimensions(Arrays.asList(instanceDimension))
				        .withEndTime(new Date(new Date().getTime() - timeDifferenceParis));
				GetMetricStatisticsResult getMetricStatisticsResult = cloudWatch.getMetricStatistics(request2);
			
				Datapoints =  getMetricStatisticsResult.getDatapoints();		       
				Collections.sort(Datapoints, new Comparator<Datapoint>() {
					public int compare(Datapoint dp1, Datapoint dp2) {
						return dp1.getTimestamp().compareTo(dp2.getTimestamp());
					}
				});


				for (Datapoint datapoint: Datapoints) {
					System.out.println(datapoint.getTimestamp());
					System.out.println(datapoint.getAverage());
				}

			   }
		        }
		    }
		   request.setNextToken(response.getNextToken());

    		   if(response.getNextToken() == null) {
		       done = true;
		   }
	      }
	}
}
