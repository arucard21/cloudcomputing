# cloudcomputing
Orchestration for a cloud application

In order to deploy an EC2 instance with Gradle, the following need to be configured:
- Your credentials must be available from a default location (e.g. ~/.aws/credentials) with a profile named "default"
- Your credentials must be configured in your gradle.properties file (as aws_access_key_id and aws_secret_access)
- You must have `ssh` installed on your local machine
- The public key for your local SSH instance must be imported into AWS as KeyPair with the name "cloudcomputing"
- You must have `curl` installed on your local machine
- A security group with name "default" must be available and configured to allow inbound TCP traffic on port 22 (for SSH) and 8080 (for Tomcat)

Since we use system tools with Gradle, this will work best on a Linux system with these tools installed.

Gradle will deploy a t2.micro instance with Ubuntu Server 18.04 to the eu-west-3 region (Paris)
You can deploy the EC2 instance with:
`./gradlew deployEC2Instance --info`

The instance ID of this deployed EC2 instance can be found in the AWS console or the log output of the Gradle deployEC2Instance task (which is only shown if run with `--info` parameter). This instance ID is needed for the other Gradle tasks.

The instance can be terminated through Gradle with:
`./gradlew terminateEC2Instance -PinstanceID=<instanceID of deployed EC2 instance>`

In order to deploy and start the main instance application, use the following Gradle task:
`./gradlew deployApplication -PinstanceID=<instanceID of deployed EC2 instance>`
This will build the project, create the executable jar files, upload them to the EC2 instance and launch the main instance application.

The experiments can be run with:
`./gradlew systemTest -PmainInstanceHostname=<Public DNS (IPv4) of your deployed AWS instance running the Main Instance component>`
All experiments are located in the main-instance project in the `systemTest` source set.