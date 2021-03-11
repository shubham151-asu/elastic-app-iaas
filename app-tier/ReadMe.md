App-tier instance(EC2-instance) receives message from SQS request queue and extracts information from the message
The message tell which file to download from input S3 bucket, It downloads the file and process it using the 
image-classification module stored on the instance. After processing it uploads the results
into the output S3 bucket and updates the SQS response queue to notify completion of the image-classification tasks


The steps are as follows:- 
- Step1 - fetch messages from request queue
- Step2 - parse messages
- Step3 - download file from S3
- Step4 - run classification task and get output
- Step5 - store output data in a file to locally
- Step6 - upload output file to S3
- Step7 - change read permission of S3
- Step8 - send messages to response queue to notify front-end on which file to read
- Step9 - safely delete messages from the request queue
- Step10 - clean local data