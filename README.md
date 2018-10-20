# cloudcomputing
Orchestration for a cloud application

In order to deploy an EC2 instance with Gradle, the following need to be configured:
- Your credentials must be available from a default location (e.g. ~/.aws/credentials) with a profile named "default"
- The public key for your local SSH instance must be imported into AWS as KeyPair with the name "cloudcomputing"
- A security group with name "default" must be available and configured to allow inbound TCP traffic on port 22 (for SSH) and 8080 (for Tomcat)

Gradle will deploy a t2.micro instance with Ubuntu Server 18.04 to the eu-west-3 region (Paris)
You can deploy the EC2 instance with:
`./gradlew deployEC2Instance --info`

The instance can be terminated with Gradle as well, with:
`./gradlew terminateEC2Instance -PinstanceID=<instanceID of deployed EC2 instance>`

The instance ID can be found in the AWS console or the log output of the Gradle deployEC2Instance task (which is only shown if run with `--info` parameter).

After this, the application can be deployed with:
`./gradlew deployApplication -PinstanceURL=<Public DNS (IPv4) of your deployed AWS instance>`

The instance URL can be found in the AWS console.