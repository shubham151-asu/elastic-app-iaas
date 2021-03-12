package com.project1.controller;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

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
import software.amazon.awssdk.services.s3.S3Client;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;


public class App {
    final static Region REGION = Region.US_EAST_2;
    final static String APPROXIMATE_NUMBER_OF_MESSAGES_QUEUE_PROPERTY = "ApproximateNumberOfMessages";
    final static String REQUEST_QUEUE_URL = System.getenv("SQS_REQUEST_QUEUE_URL");
    final static String RESPONSE_QUEUE_URL = System.getenv("SQS_RESPONSE_QUEUE_URL");
    final static String S3_INPUT_BUCKET_NAME = System.getenv("S3_INPUT_BUCKET_NAME");
    final static String S3_OUTPUT_BUCKET_NAME = System.getenv("S3_OUTPUT_BUCKET_NAME");
    final static String SECURITY_GROUP_NAME = "autoscaler-us-east-2";
    final static String SSH_KEY_PAIR_NAME = "test-key-pair";
    final static String APP_TIER_AMI_ID = "ami-002afa9bfad06d387";
    final static String APP_USER_DATA_FILE_PATH = "controller.sh";
    final static String S3_IAM_ROLE = "s3_sqs_ec2_access_role";
    final static String[] QUEUE_ATTRIBUTE_LIST = new String[]{"All"};
    final static String CONTROLLER_USER_DATA_FILE_PATH = "controller.sh";

    final static double ACCEPTABLE_LATENCY_PER_REQUEST_IN_SEC = 2;
    final static double AVERAGE_PROCESSING_TIME_PER_REQUEST = 0.88;
    final static int ACCEPTABLE_BACKLOG_PER_INSTANCE = (int)(ACCEPTABLE_LATENCY_PER_REQUEST_IN_SEC/AVERAGE_PROCESSING_TIME_PER_REQUEST);
    final static int MAXIMUM_NUM_APP_TIER_INSTANCES = 19;
    final static int NUM_POINTS_IN_MOVING_AVERAGE = 3;
    static int MOVING_AVERAGE_SUM = 0;
    static int count = 0;

    public static void main(String[] args) throws IOException, InterruptedException {
        if(args.length == 0) {
            System.out.println("Exiting...");
            return;
        }
        String option = args[0];
        SqsClient sqsClient = SqsClient.builder().region(REGION).build();
        Ec2Client ec2Client = Ec2Client.builder().region(REGION).build();

        VelocityEngine ve = new VelocityEngine();
        ve.setProperty(RuntimeConstants.RESOURCE_LOADER, "classpath"); 
        ve.setProperty("classpath.resource.loader.class", ClasspathResourceLoader.class.getName());
        ve.init();

        switch(option) {
            case "start":
                            launchController(ec2Client, sqsClient, ve, REQUEST_QUEUE_URL);
                            break;
        }
        
        System.out.println("Exiting...");
    }

    public static void launchController(Ec2Client ec2Client, SqsClient sqsClient, VelocityEngine ve, String requestQueueUrl) throws IOException, InterruptedException {

        GetQueueAttributesRequest getQueueAttributesRequest = GetQueueAttributesRequest.builder()
            .attributeNamesWithStrings(QUEUE_ATTRIBUTE_LIST)
            .queueUrl(requestQueueUrl)
            .build();

        while(true) {
            Thread.sleep(2000);
            System.out.println("========================================");
            GetQueueAttributesResponse getQueueAttributesResponse = sqsClient.getQueueAttributes(getQueueAttributesRequest);
            Map<String, String> map = getQueueAttributesResponse.attributesAsStrings();
            for (String attributeKey : map.keySet()) {
                try {
                    System.out.println(attributeKey + " : " + map.get(attributeKey));
                } catch (S3Exception e) {
                    System.err.println(e.awsErrorDetails().errorMessage());
                    System.exit(1);
                }
            }
            MOVING_AVERAGE_SUM += Integer.parseInt(map.get(APPROXIMATE_NUMBER_OF_MESSAGES_QUEUE_PROPERTY));
            count++;
            if(count < NUM_POINTS_IN_MOVING_AVERAGE) {
                continue;
            }
            int requestQueueLength = MOVING_AVERAGE_SUM/NUM_POINTS_IN_MOVING_AVERAGE;
            count = 0;
            MOVING_AVERAGE_SUM = 0;
            List<String[]> appInstanceIds = getRunningEc2InstanceIds(ec2Client);
            Collections.sort(appInstanceIds, new Comparator<String[]>(){
                @Override
                public int compare(String[] a, String[] b) {
                    return a[1].compareTo(b[1]);
                }
            });
            int curNumsInstances = appInstanceIds.size();
            int desirableNumInstances = Math.max(1, Math.min(requestQueueLength/ACCEPTABLE_BACKLOG_PER_INSTANCE, MAXIMUM_NUM_APP_TIER_INSTANCES));
            System.out.println("Current number of instances :" + curNumsInstances);
            System.out.println("Desirable number of instances :" + desirableNumInstances);
            autoScale(ec2Client, ve, desirableNumInstances, curNumsInstances, appInstanceIds);
            System.out.println();
        }
    }

    public static List<String[]> getRunningEc2InstanceIds(Ec2Client ec2Client) {
        Filter runningFilter = Filter.builder().name("instance-state-name").values("running", "pending").build();
        Filter nameFilter = Filter.builder().name("tag:Name").values("app-tier*").build();
        DescribeInstancesRequest describeInstancesRequest = DescribeInstancesRequest.builder().filters(runningFilter, nameFilter).build();
        DescribeInstancesResponse describeInstanceResponse = ec2Client.describeInstances(describeInstancesRequest);
        
        List<String[]> appInstanceIds = new ArrayList<String[]>();
        for (Reservation reservation : describeInstanceResponse.reservations()) {
            for (Instance instance : reservation.instances()) {
                appInstanceIds.add(new String[]{instance.instanceId(),instance.tags().get(0).value()});
            }
        }
        return appInstanceIds;
    }

    public static void autoScale(Ec2Client ec2Client, VelocityEngine ve, int desirableNumInstances, int curNumsInstances, List<String[]> appInstanceIds) throws IOException, InterruptedException {
        if(desirableNumInstances > curNumsInstances) {
            System.out.println("Scaling out by " + Integer.toString(desirableNumInstances - curNumsInstances) + " instances...");
            scaleOut(ec2Client, ve, desirableNumInstances - curNumsInstances, appInstanceIds);
        } else if(desirableNumInstances < curNumsInstances){
            System.out.println("Scaling in by " + Integer.toString(curNumsInstances - desirableNumInstances) + " instances...");
            scaleIn(ec2Client, ve, curNumsInstances - desirableNumInstances, appInstanceIds);
        }
    }

    public static void scaleOut(Ec2Client ec2Client, VelocityEngine ve, int numInstances, List<String[]> appInstanceIds) throws IOException, InterruptedException {
        int curLength = appInstanceIds.size();
        for(int i=1; i<=numInstances; i++) {
            String instanceName = "app-tier-" + Integer.toString(curLength+i);
            createEc2Instance(ec2Client, APP_TIER_AMI_ID, instanceName, 
            readUserDataFile(ve, CONTROLLER_USER_DATA_FILE_PATH, S3_INPUT_BUCKET_NAME, S3_OUTPUT_BUCKET_NAME, REQUEST_QUEUE_URL, RESPONSE_QUEUE_URL), S3_IAM_ROLE);
        }
        Thread.sleep(5000);
    }

    public static void scaleIn(Ec2Client ec2Client, VelocityEngine ve, int numInstances, List<String[]> appInstanceIds) {
        List<String> instancesToDelete = new ArrayList<String>();
        for(int i=0, j=appInstanceIds.size()-1; i<numInstances; i++, j--) {
            instancesToDelete.add(appInstanceIds.get(j)[0]);
        }
        deleteEc2Instances(ec2Client, instancesToDelete);
    }

    public static String readUserDataFile(VelocityEngine ve, String filePath, String inputBucketName, String outputBucketName,
    String requestQueueUrl, String responseQueueUrl) throws IOException {
        return Base64.getEncoder().encodeToString(replaceValuesInTemplate(ve, filePath,
            inputBucketName, outputBucketName, requestQueueUrl, responseQueueUrl).getBytes());
    }

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
            System.out.println();
            return instanceId;
        } catch (Ec2Exception e) {
            System.err.println(e.awsErrorDetails().errorMessage());
            System.exit(1);
        }
        return "";
    }

    public static void deleteEc2Instances(Ec2Client ec2Client, List<String> appTierInstanceIds) {
        TerminateInstancesRequest terminateInstancesRequest = TerminateInstancesRequest.builder()
        .instanceIds(appTierInstanceIds)
        .build();
        TerminateInstancesResponse response = ec2Client.terminateInstances(terminateInstancesRequest);
    }
}
