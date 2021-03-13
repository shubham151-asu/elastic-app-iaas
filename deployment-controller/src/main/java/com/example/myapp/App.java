package com.example.myapp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.CreateBucketConfiguration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListBucketsRequest;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.CORSRule;
import software.amazon.awssdk.services.s3.S3Client;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;


public class App {
    // Setting all the required variables that will be used throughout
    final static Region REGION = Region.US_EAST_2;
    final static String REQUEST_QUEUE_NAME = "autoscaler-request-queue";
    final static String RESPONSE_QUEUE_NAME = "autoscaler-response-queue";
    final static String S3_BUCKET_NAME_INPUT = "autoscaler-image-bucket-input";
    final static String S3_BUCKET_NAME_OUTPUT = "autoscaler-image-bucket-output";
    final static String S3_BUCKET_KEY = "key";
    final static String SECURITY_GROUP_NAME = "autoscaler-us-east-2";
    final static String SSH_KEY_PAIR_NAME = "test-key-pair";
    final static String WEB_TIER_AMI_ID = "ami-0109c3bcd44757ed3";
    final static String APP_TIER_AMI_ID = "ami-002afa9bfad06d387";
    final static String WEB_TIER_USER_DATA_FILE_PATH = "web-tier.sh";
    final static String S3_IAM_ROLE = "s3_sqs_ec2_access_role";

    public static void main(String[] args) throws IOException {
        String option = args[0];
        S3Client s3Client = S3Client.builder().region(REGION).build();
        SqsClient sqsClient = SqsClient.builder().region(REGION).build();
        Ec2Client ec2Client = Ec2Client.builder().region(REGION).build();

        // Creating an instance of the velocity template engine
        VelocityEngine ve = new VelocityEngine();
        ve.setProperty(RuntimeConstants.RESOURCE_LOADER, "classpath"); 
        ve.setProperty("classpath.resource.loader.class", ClasspathResourceLoader.class.getName());
        ve.init();

        switch(option) {
            case "launch":
                            ProjectInstances projectInstances = launchProject(s3Client, sqsClient, ec2Client, ve);
                            System.out.println(projectInstances);
                            break;
            case "clean":
                            cleanUp(s3Client, sqsClient, ec2Client);
                            break;
        }
        
        System.out.println("Exiting...");
    }

    // Reads the web-tier.sh template, replaces all the required values, base64 encodes the script
    public static String readUserDataFile(VelocityEngine ve, String filePath, String inputBucketName, String outputBucketName,
    String requestQueueUrl, String responseQueueUrl) throws IOException {
        return Base64.getEncoder().encodeToString(replaceValuesInTemplate(ve, filePath,
            inputBucketName, outputBucketName, requestQueueUrl, responseQueueUrl).getBytes());
    }

    // Sets the environment variables in the web-tier.sh template
    public static String replaceValuesInTemplate(VelocityEngine ve, String filePath, String inputBucketName, String outputBucketName,
    String requestQueueUrl, String responseQueueUrl) throws IOException {
        Template t = ve.getTemplate(filePath);

        VelocityContext context = new VelocityContext();
        context.put("SQS_REQUEST_QUEUE_URL", requestQueueUrl);
        context.put("SQS_RESPONSE_QUEUE_URL", responseQueueUrl);
        context.put("S3_INPUT_BUCKET_NAME", inputBucketName);
        context.put("S3_OUTPUT_BUCKET_NAME", outputBucketName);
        StringWriter writer = new StringWriter();
        t.merge(context, writer);
        return writer.toString();
    }

    // Launches all the required cloud resources for the project
    public static ProjectInstances launchProject(S3Client s3Client, SqsClient sqsClient, Ec2Client ec2Client, VelocityEngine ve)
            throws IOException {
        String requestQueueUrl = createSQSQueue(sqsClient, REQUEST_QUEUE_NAME);
        String responseQueueUrl = createSQSQueue(sqsClient, RESPONSE_QUEUE_NAME);
        String inputBucketName = createS3Bucket(s3Client, S3_BUCKET_NAME_INPUT);
        String outputBucketName = createS3Bucket(s3Client, S3_BUCKET_NAME_OUTPUT);
        String webInstanceId = createEc2Instance(ec2Client, WEB_TIER_AMI_ID, "web-tier", 
        readUserDataFile(ve, WEB_TIER_USER_DATA_FILE_PATH, inputBucketName, outputBucketName, requestQueueUrl, responseQueueUrl), S3_IAM_ROLE);
        
        ProjectInstances projectInstances = new ProjectInstances.ProjectInstancesBuilder()
            .inputBucketName(inputBucketName)
            .outputBucketName(outputBucketName)
            .requestQueueUrl(requestQueueUrl)
            .responseQueueUrl(responseQueueUrl)
            .webInstanceId(webInstanceId)
            .build();
        
        return projectInstances;
    }

    // Created S3 bucket with specified name
    public static String createS3Bucket(S3Client s3Client, String bucketName) {
        s3Client.createBucket(CreateBucketRequest
                .builder()
                .bucket(bucketName)
                .createBucketConfiguration(
                        CreateBucketConfiguration.builder()
                                .locationConstraint(REGION.id())
                                .build())
                .build());
        System.out.println("Creating bucket: " + bucketName);
        s3Client.waiter().waitUntilBucketExists(HeadBucketRequest.builder()
                .bucket(bucketName)
                .build());
        System.out.println(bucketName +" is ready.");
        System.out.printf("%n");
        return bucketName;
    }

    // Created SQS queue with specified name
    public static String createSQSQueue(SqsClient sqsClient, String queueName) {
        System.out.println("\nCreating Queue");
        CreateQueueRequest createQueueRequest = CreateQueueRequest.builder()
            .queueName(queueName)
            .build();
        sqsClient.createQueue(createQueueRequest);
        GetQueueUrlResponse getQueueUrlResponse =
            sqsClient.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build());
        String queueUrl = getQueueUrlResponse.queueUrl();
        System.out.println("Created queue : " + queueUrl);
        return queueUrl;
    }

    // Created EC2 instance with specified AMI id, name, userdata and IAM role
    public static String createEc2Instance(Ec2Client ec2Client, String amiId, String name, String userData, String iamRole) {
        RunInstancesRequest runRequest = RunInstancesRequest.builder()
            .imageId(amiId)
            .instanceType(InstanceType.T2_MICRO)
            .securityGroups(Arrays.asList(SECURITY_GROUP_NAME))
            .keyName(SSH_KEY_PAIR_NAME)
            .userData(userData)
            .iamInstanceProfile(IamInstanceProfileSpecification.builder().name(iamRole).build())
            .maxCount(1)
            .minCount(1)
            .build();
        RunInstancesResponse response = ec2Client.runInstances(runRequest);

        String instanceId = response.instances().get(0).instanceId();
        Tag tag = Tag.builder()
                .key("Name")
                .value(name)
                .build();

        CreateTagsRequest tagRequest = CreateTagsRequest.builder()
                .resources(instanceId)
                .tags(tag)
                .build();
        try {
            ec2Client.createTags(tagRequest);
            System.out.printf(
                    "Successfully started EC2 Instance %s based on AMI %s",
                    instanceId, amiId);
            return instanceId;
        } catch (Ec2Exception e) {
            System.err.println(e.awsErrorDetails().errorMessage());
            System.exit(1);
        }
        return "";
    }

    // Cleans up created S3 buckets, SQS queues and EC2 instances
    public static void cleanUp(S3Client s3Client, SqsClient sqsClient, Ec2Client ec2Client) {
        System.out.println("Cleaning up...");
        deleteS3Buckets(s3Client);
        deleteSQSQueues(sqsClient);
        deleteEc2Instances(ec2Client);
        System.out.println("Closing the connection to Amazon S3");
        s3Client.close();
        System.out.println("Closing the connection to Amazon SQS");
        sqsClient.close();
        System.out.println("Closing the connection to Amazon EC2");
        ec2Client.close();
        System.out.println("Connection closed");
        System.out.println("Cleanup complete");
        System.out.printf("%n");
    }

    // Deletes all the S3 buckets
    public static void deleteS3Buckets(S3Client s3Client) {
        ListBucketsRequest listBucketsRequest = ListBucketsRequest.builder().build();
        ListBucketsResponse listBucketsResponse = s3Client.listBuckets(listBucketsRequest);

        for (String bucketName : listBucketsResponse.buckets().stream().map(x->x.name()).collect(Collectors.toList())) {
            try {
                deleteObjectsInBucket(s3Client, bucketName);
                System.out.println("Deleting bucket: " + bucketName);
                DeleteBucketRequest deleteBucketRequest = DeleteBucketRequest.builder().bucket(bucketName).build();
                s3Client.deleteBucket(deleteBucketRequest);
                System.out.println(bucketName +" has been deleted.");
            } catch (S3Exception e) {
                System.err.println(e.awsErrorDetails().errorMessage());
                System.exit(1);
            }
        }
    }

    // Deletes all the objects inside the specified S3 bucket
    public static void deleteObjectsInBucket(S3Client s3Client, String bucketName) {
        ListObjectsV2Request listObjectsV2Request = ListObjectsV2Request.builder().bucket(bucketName).build();
        ListObjectsV2Response listObjectsV2Response;

        do {
            listObjectsV2Response = s3Client.listObjectsV2(listObjectsV2Request);
            for (S3Object s3Object : listObjectsV2Response.contents()) {
                System.out.println("Deleting object : " + s3Object.key());
                s3Client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(bucketName)
                        .key(s3Object.key())
                        .build());
            }
            listObjectsV2Request = ListObjectsV2Request.builder().bucket(bucketName)
                    .continuationToken(listObjectsV2Response.nextContinuationToken())
                    .build();
        } while(listObjectsV2Response.isTruncated());
    }

    // Deletes all the created SQS queues
    public static void deleteSQSQueues(SqsClient sqsClient) {
        ListQueuesRequest listQueuesRequest = ListQueuesRequest.builder().build();
        ListQueuesResponse listQueuesResponse = sqsClient.listQueues(listQueuesRequest);

        for (String queueUrl : listQueuesResponse.queueUrls()) {
            try {
                DeleteQueueRequest deleteQueueRequest = DeleteQueueRequest.builder()
                        .queueUrl(queueUrl)
                        .build();
                System.out.println("Deleting queue : " + queueUrl);
                sqsClient.deleteQueue(deleteQueueRequest);
            } catch (SqsException e) {
                System.err.println(e.awsErrorDetails().errorMessage());
                System.exit(1);
            }
        }
    }

    // Deletes all the created EC2 instances
    public static void deleteEc2Instances(Ec2Client ec2Client) {
        System.out.println("Deleting EC2 instances...");
        List<String> instanceIds = getRunningEc2InstanceIds(ec2Client);
        TerminateInstancesRequest terminateInstancesRequest = TerminateInstancesRequest.builder()
        .instanceIds(instanceIds)
        .build();
        TerminateInstancesResponse response = ec2Client.terminateInstances(terminateInstancesRequest);
    }

    // Retrieves all the running EC2 instances
    public static List<String> getRunningEc2InstanceIds(Ec2Client ec2Client) {
        Filter runningFilter = Filter.builder().name("instance-state-name").values("running", "pending", "stopped").build();
        DescribeInstancesRequest describeInstancesRequest = DescribeInstancesRequest.builder().filters(runningFilter).build();
        DescribeInstancesResponse describeInstanceResponse = ec2Client.describeInstances(describeInstancesRequest);
        
        List<String> appInstanceIds = new ArrayList<String>();
        for (Reservation reservation : describeInstanceResponse.reservations()) {
            for (Instance instance : reservation.instances()) {
                appInstanceIds.add(instance.instanceId());
            }
        }
        return appInstanceIds;
    }
}
