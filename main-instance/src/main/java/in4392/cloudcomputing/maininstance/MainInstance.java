package in4392.cloudcomputing.maininstance;

import java.io.IOException;

import javax.inject.Named;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.DescribeInstanceStatusRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceStatus;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.ResourceType;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.SummaryStatus;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.TagSpecification;

@Named
public class MainInstance {
	private static final String AWS_KEYPAIR_NAME = "cloudcomputing";
	private static final String AMI_ID_EU_WEST_3_UBUNTU_SERVER_1804 = "ami-0a2ca21adb4a04084";
	private static final String ERROR_INCORRECTLY_DEPLOYED = "The newly deployed EC2 instance did not start correctly. You may need to manually verify and correct this";
	private static final String ERROR_STATUS_CHECKS_NOT_OK = "The newly deployed EC2 instance is running but some of the status checks may not have passed";
	private static final int ITERATION_WAIT_TIME = 60 * 1000;
	private static final int INSTANCE_PENDING = 0;
	private static final int INSTANCE_RUNNING = 16;
	private static boolean keepAlive;
	private static boolean shadowDeployed;
	private static boolean isShadow;
	private static AmazonEC2 client = AmazonEC2ClientBuilder.defaultClient();
	private static AWSCredentials credentials;

	/**
	 * Start the main loop. 
	 * 
	 * This will run continuously, executing an iteration every minute (defined by ITERATION_WAIT_TIME). 
	 * The loop can be stopped and restarted through the API, with a GET request to 
	 * "http:\<instanceURL\>:8080/main/start" or "http:\<instanceURL\>:8080/main/stop".
	 */
	protected static void startMainLoop() {
		keepAlive = true;
		while(keepAlive) {
			if (credentials == null) {
				// we can't do anything until we have credentials so this loop will do nothing
				continue;
			}
			if (!isShadow && !shadowDeployed) {
				deployShadow();
			}
			if (isShadow) {
				System.out.println("Do something useful here for the shadow instance, like checking the main instance");
			}
			else{
				System.out.println("Do something useful here for the main instance, like monitoring");
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
		return shadowDeployed;
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
	 * @throws IOException if the userdata script can not be read
	 */
	public static Instance deployDefaultEC2(String usageTag) {
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

	private static void deployShadow() {
		// TODO actually deploy the shadow
		shadowDeployed = true;
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
}
