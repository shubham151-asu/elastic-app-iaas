import os
import random
import boto3
import time
from botocore.exceptions import ClientError
import logging as logger
from subprocess import Popen, PIPE


MAX_NUM = 1
REGION_NAME = 'us-east-2'
REQUEST_QUEUE_URL = os.environ.get("SQS_REQUEST_QUEUE_URL")
RESPONSE_QUEUE_URL = os.environ.get("SQS_RESPONSE_QUEUE_URL")
S3_INPUT_BUCKET = os.environ.get("S3_INPUT_BUCKET_NAME")
S3_OUTPUT_BUCKET = os.environ.get("S3_OUTPUT_BUCKET_NAME")
RANDOM_SLEEP_TIME = [0.05,0.1,0.15,0.2,0.25]


INPUT_LOCAL_FILE_DIR = "/home/ubuntu/classifier/input/"
OUTPUT_LOCAL_FILE_DIR = "/home/ubuntu/classifier/output/"

if not os.path.exists(INPUT_LOCAL_FILE_DIR):
    os.makedirs(INPUT_LOCAL_FILE_DIR)

if not os.path.exists(OUTPUT_LOCAL_FILE_DIR):
    os.makedirs(OUTPUT_LOCAL_FILE_DIR)


logger.basicConfig(filename='apptier.log', level=logger.ERROR)

logger.info("Request queue name : {0}".format(REQUEST_QUEUE_URL))
logger.info("Response queue name : {0}".format(RESPONSE_QUEUE_URL))
logger.info("S3 input bucket name : {0}".format(S3_INPUT_BUCKET))
logger.info("S3 output bucket name : {0}".format(S3_OUTPUT_BUCKET))



class AppTier(object):
    def __init__(self):
        self.requestQueue = self.getQueue(REQUEST_QUEUE_URL)
        self.responseQueue = self.getQueue(RESPONSE_QUEUE_URL)
        self.max_number = 1
        self.wait_time = 0
        self.s3Connection = boto3.resource("s3",region_name=REGION_NAME)

    def getQueue(self,queueUrl):
        logger.debug("In method: getQueue")
        try:
            sqs = boto3.resource("sqs",region_name=REGION_NAME)
            queue = sqs.Queue(queueUrl)
            logger.info("getQueue : Got queue {0} with URL".format(queue.url))
        except ClientError as error:
            logger.error("getQueue : Couldn't get queue named {0}".format(queueUrl))
            raise error
        else:
            return queue


    def s3downloadFile(self,fileName):
        logger.info("In method : S3downloadFile")
        dest = INPUT_LOCAL_FILE_DIR + fileName
        logger.debug("S3downloadFile: S3 input file download fileName - {0} , destination - {1} ".format(fileName,dest))
        try:
            response = self.s3Connection.meta.client.download_file(S3_INPUT_BUCKET, fileName, dest)

        except ClientError as e:
            logger.error("S3downloadFile : unable to downloadfile - error - {0)".format(e))
            return False
        else:
            return dest


    def createFile(self,out,fileName):
        logger.debug("In method : createFile")
        fileName = OUTPUT_LOCAL_FILE_DIR + fileName
        logger.debug("createFile : S3 output file local location {0}".format(fileName))
        with open(fileName,"w") as file:
            file.write(out)
        file.close()


    def s3uploadFile(self,fileName):
        logger.debug("In method : s3uploadFile")
        dest = OUTPUT_LOCAL_FILE_DIR + fileName
        logger.debug("s3uploadFile : S3 output file local destination - {0}, fileName - {1}".format(dest,fileName))
        try:
            response = self.s3Connection.meta.client.upload_file(dest, S3_OUTPUT_BUCKET, fileName)
            logger.debug("s3uploadFile : S3 file upload successful - fileName - {0}".format(fileName))
        except ClientError as e:
            logger.error("s3uploadFile : unable to upload file - error - {0)".format(e))
            return False
        return True


    def delete_message(self,message):
        logger.info("In method : delete_message")
        try:
            message.delete()
            logger.debug("Deleted message: {0}".format(message.message_id))
        except ClientError as error:
            logger.error("delete_message : Couldn't delete message: %s", message.message_id)
            raise error

    def runAppLibraryCode(self,fileName):
        logger.debug("In method : runAppLibraryCode")
        cmd1 = "python3"
        cmd2 = "/home/ubuntu/classifier/image_classification.py"
        cmd3 = INPUT_LOCAL_FILE_DIR + fileName
        process = Popen([cmd1, cmd2, cmd3], stdout=PIPE)
        (output, err) = process.communicate()
        exit_code = process.wait()
        if not err:
            response = output.decode("utf-8")
            logger.debug("runAppLibraryCode : Success - Output of Library Code - {0}".format(response))
            return response
        else:
            logger.error("runAppLibraryCode : Failed -  Output of Library Code - {0}".format(err))
            return False


    def formatOutput(self,input,output):
        logger.debug("In method : formatOutput")
        inputString = input[:-4]
        string = (inputString , output)
        output = str(string)
        logger.debug("formatOutput : Output string- {0}".format(output))
        return output

    def cleanUp(self,inputFile,outputFile):
        try:
            os.remove(INPUT_LOCAL_FILE_DIR+inputFile)
            os.remove(OUTPUT_LOCAL_FILE_DIR+outputFile)
        except:
            pass

    def changePermission(self,fileName):
        logger.debug("In method : changePermission")
        response = self.s3Connection.Bucket(S3_OUTPUT_BUCKET).Object(fileName)
        response = response.Acl().put(ACL='public-read')
        if response['ResponseMetadata']['HTTPStatusCode'] == 200:
            logger.debug("changePermission : Successfully changed the permissions for file - {0}".format(fileName))
        else:
            logger.error("changePermission : Failed to changed the permissions for file - {0}".format(fileName))

    def execute(self):
        logger.debug("In method : execute")
        while True:
            messages = self.receiveMessage()
            logger.info("execute : step1 - fetch  messages from request queue")
            for message in messages:
                logger.debug("execute : step2 - Parse message")
                infileName =  message.body
                logger.info("execute : inputFileName - {0}, obtained from SQS queue - {1}".format(infileName,REQUEST_QUEUE_URL))

                logger.info("execute : step3 - download file from S3")
                self.s3downloadFile(infileName)

                logger.info("execute : step4 - run classification task")
                output = self.runAppLibraryCode(infileName)

                if output:
                    outputString = self.formatOutput(infileName,output)

                    outfileName = infileName[:-4] +"_out"
                    logger.info("execute : outfileName - {0}".format(outfileName))

                    logger.info("execute : step5 - store output data to locally")
                    self.createFile(outputString,outfileName)

                    logger.info("execute : step6 - upload output file to S3")
                    self.s3uploadFile(outfileName)


                    logger.info("execute : step7 - change read permission of S3")
                    self.changePermission(outfileName)

                    messageString = infileName+ ":"+ outfileName

                    logger.info("execute : output message for SQS queue - {0}".format(messageString))

                    outmessage = self.packMessage(messageString)

                    logger.info("execute : step8 - send message to response queue")
                    self.sendMessage([outmessage])

                    logger.info("execute : step9 - safely remove from request queue ")
                    self.delete_message(message)

                    logger.info("execute : step10 - clean data")
                    self.cleanUp(infileName,outfileName)

                    time.sleep(random.choice(RANDOM_SLEEP_TIME))


    def receiveMessage(self):
        logger.debug("In method : receiveMessage")
        try:
            messages = self.requestQueue.receive_messages(
                MessageAttributeNames=['All'],
                MaxNumberOfMessages=self.max_number,
                WaitTimeSeconds=self.wait_time
            )
            for msg in messages:
                logger.info("receiveMessage : Received message: %s: %s", msg.message_id, msg.body)
        except ClientError as error:
            logger.error("receiveMessage : Couldn't receive messages from queue: %s", REQUEST_QUEUE_URL)
            raise error
        else:
            return messages


    def packMessage(self,msg_body):
        return {
            'body': msg_body,
        }


    def sendMessage(self,messages):
        logger.debug("In method : sendMessage")
        try:
            entries = [{
                'Id': str(ind),
                'MessageBody': msg['body'],
                # 'MessageAttributes': msg['attributes']
                } for ind,msg in enumerate(messages)]
            response = self.responseQueue.send_messages(Entries=entries)
            if 'Successful' in response:
                for msg_meta in response['Successful']:
                    logger.info(
                        "Message sent: %s: %s",
                        msg_meta['MessageId'],
                        messages[int(msg_meta['Id'])]['body']
                    )
            if 'Failed' in response:
                for msg_meta in response['Failed']:
                    logger.warning(
                        "Failed to send: %s: %s",
                        msg_meta['MessageId'],
                        messages[int(msg_meta['Id'])]['body']
                    )
        except ClientError as error:
            logger.error("sendMessage : Send messages failed to queue: %s", RESPONSE_QUEUE_URL)
            raise error
        else:
            return response



obj = AppTier()
obj.execute()








