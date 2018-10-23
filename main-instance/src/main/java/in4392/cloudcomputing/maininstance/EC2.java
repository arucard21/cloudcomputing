package in4392.cloudcomputing.maininstance;

import java.io.File;
import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;

import javax.inject.Named;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.DeleteKeyPairRequest;
import com.amazonaws.services.ec2.model.DescribeInstanceStatusRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.ImportKeyPairRequest;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceStatus;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.ResourceType;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.StartInstancesRequest;
import com.amazonaws.services.ec2.model.StopInstancesRequest;
import com.amazonaws.services.ec2.model.SummaryStatus;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.TagSpecification;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.direct.Session.Command;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.xfer.FileSystemFile;
import net.schmizz.sshj.xfer.scp.SCPFileTransfer;

@Named
public class EC2 {
	private static final String AWS_KEYPAIR_NAME = "accessibleFromMainInstance";
	private static final String AMI_ID_EU_WEST_3_UBUNTU_SERVER_1804 = "ami-0a2ca21adb4a04084";
	private static final String ERROR_INCORRECTLY_DEPLOYED = "The newly deployed EC2 instance did not start correctly. You may need to manually verify and correct this";
	private static final String ERROR_STATUS_CHECKS_NOT_OK = "The newly deployed EC2 instance is running but some of the status checks may not have passed";
	private static final int INSTANCE_PENDING = 0;
	private static final int INSTANCE_RUNNING = 16;
	private static final int INSTANCE_STOPPED = 80;
	private static AmazonEC2 client;
	private static AWSCredentials credentials;
	private static KeyPair javaKeyPair;
	
	public static void startEC2Instance(String instanceId) {
		client.startInstances(new StartInstancesRequest().withInstanceIds(instanceId));
	}

	public static boolean isStopped(String instanceId) {
		Instance stoppingInstance = retrieveEC2InstanceWithId(instanceId);
		if (stoppingInstance.getState().getCode() == INSTANCE_STOPPED) {
			return true;
		}
		return false;
	}

	public static void stopEC2Instance(String instanceId) {
		client.stopInstances(new StopInstancesRequest().withInstanceIds(instanceId));
	}

	public static void terminateEC2(String instanceId) {
		client.terminateInstances(new TerminateInstancesRequest().withInstanceIds(instanceId));
	}

	public static Instance retrieveEC2InstanceWithId(String instanceId) {
		return client.describeInstances(new DescribeInstancesRequest()
				.withInstanceIds(instanceId))
				.getReservations().get(0)
				.getInstances().get(0);
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

	public static void ensureJavaKeyPairExists() throws NoSuchAlgorithmException {
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

	public static void removeExistingKeyPair() {
		client.deleteKeyPair(new DeleteKeyPairRequest(AWS_KEYPAIR_NAME));
	}

	public static String getUserData() {
		String userData = "#!/bin/bash\n" + 
				"apt update\n" + 
				"apt install -y openjdk-8-jre\n";
		return Base64.getEncoder().encodeToString(userData.getBytes());
	}

	public static void waitForInstanceToRun(String deployedInstanceId) {
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

	public static int getInstanceState(String deployedInstanceId) {
		int state = client.describeInstances(new DescribeInstancesRequest().withInstanceIds(deployedInstanceId))
				.getReservations().get(0)
				.getInstances().get(0)
				.getState()
				.getCode();
		return state;
	}
	
	public static boolean verifyStatusChecksPassed(String deployedInstanceId) {
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

	public static void copyApplicationToDeployedInstance(File applicationJarFile, Instance instance) throws IOException, NoSuchAlgorithmException {
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

	public static void startDeployedApplication(Instance instance, String applicationName) throws IOException, NoSuchAlgorithmException {
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
	
	public static AmazonEC2 getClient() {
		return client;
	}

	public static AWSCredentials getCredentials() {
		return credentials;
	}

	public static void setCredentials(AWSCredentials credentials) {
		EC2.credentials = credentials;
		AWSStaticCredentialsProvider staticCredentialsProvider = new AWSStaticCredentialsProvider(credentials);
		client = AmazonEC2ClientBuilder.standard().withCredentials(staticCredentialsProvider).build();
	}
}
