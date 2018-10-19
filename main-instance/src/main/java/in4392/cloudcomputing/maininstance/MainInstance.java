package in4392.cloudcomputing.maininstance;

import java.util.List;

import javax.inject.Named;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.DescribeImagesRequest;
import com.amazonaws.services.ec2.model.DescribeImagesResult;
import com.amazonaws.services.ec2.model.DescribeInstanceStatusRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.Image;
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
	private static final String ERROR_INCORRECTLY_DEPLOYED = "The newly deployed EC2 instance did not start correctly. You may need to manually verify and correct this";
	private static final String ERROR_STATUS_CHECKS_NOT_OK = "The newly deployed EC2 instance is running but some of the status checks may not have passed";
	private static final int ITERATION_WAIT_TIME = 1000;
	private static final int INSTANCE_PENDING = 0;
	private static final int INSTANCE_RUNNING = 16;
	private static boolean keepAlive;
	private static boolean shadowDeployed;
	private static boolean isShadow;

	/**
	 * run main instance until stopped through API
	 */
	public static void run() {
		if (!isShadow && !shadowDeployed) {
			deployShadow();
		}
		keepAlive = true;
		while(keepAlive) {
			if (isShadow) {
				System.out.println("Do something useful here for the shadow instance, like checking the main instance");
			}
			else{
				System.out.println("Do something useful here for the main instance, like monitoring");
			}
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
	 */
	public static Instance deployDefaultEC2(String usageTag) {
		AmazonEC2 client = AmazonEC2ClientBuilder.defaultClient();
		DescribeImagesResult imageDescriptions = client.describeImages(new DescribeImagesRequest()
				.withOwners("099720109477")
				.withFilters(
						new Filter()
						.withName("is-public")
						.withValues("true"),
						new Filter()
						.withName("name")
						.withValues("ubuntu/images/hvm-ssd/ubuntu-bionic-18.04-amd64-server-????????")));
		List<Image> images = imageDescriptions.getImages();
		images.sort((a,b) -> a.getCreationDate().compareTo(b.getCreationDate()));
		String imageID = images.get(0).getImageId();
		RunInstancesRequest runInstancesRequest = new RunInstancesRequest(imageID, 1, 1)
				.withInstanceType(InstanceType.T2Micro)
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
				if (!passed) {
					throw new IllegalStateException(ERROR_STATUS_CHECKS_NOT_OK);
				}
				break;
			}
			throw new IllegalStateException(ERROR_INCORRECTLY_DEPLOYED);
		}
		return client.describeInstances(new DescribeInstancesRequest().withInstanceIds(deployedInstanceId))
				.getReservations().get(0)
				.getInstances().get(0);
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
}
