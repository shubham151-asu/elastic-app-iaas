import express, { request } from 'express';
import * as fs from "fs";
import { S3 } from 'aws-sdk';
import { SQSClient, SendMessageCommand, ReceiveMessageCommand, DeleteMessageCommand } from '@aws-sdk/client-sqs';
import { Credentials } from 'aws-sdk';
import { Message, ReceiveMessageRequest } from 'aws-sdk/clients/sqs';

const app = express();
const bodyParser = require("body-parser"); // Parses request body
const multer = require('multer'); // Using multer as middleware for file upload
const homedir = require('os').homedir();
const directory = homedir + '/uploads/';
if (!fs.existsSync(directory)) {
  fs.mkdirSync(directory);
}

const storage = multer.diskStorage({ // To retain the original filename when uploaded
  destination: function (req, file, cb) {
    cb(null, directory)
  },
  filename: function (req, file, cb) {
    cb(null, file.originalname)
  }
})

const upload = multer({ storage: storage })
const port = 3000;


const accessKeyId = ""; // Add the access key id here
const secretAccessKey = ""; // Add the secret access key here
const region = ""; // Add your region here
const resQueueURL = "https://sqs.us-east-2.amazonaws.com/551466843935/autoscaler-response-queue";
const reqQueueURL = "https://sqs.us-east-2.amazonaws.com/551466843935/autoscaler-request-queue";
const bucketInput = "autoscaler-image-bucket-input";

const cred = new Credentials({
  secretAccessKey: secretAccessKey,
  accessKeyId: accessKeyId
});

const cors = require('cors'); //Set CORS policy for the app
app.use(cors()); 
app.options('*', cors());

app.use(bodyParser.json()); // Body parser for post messages
app.use(bodyParser.urlencoded({
  extended: true
}));


app.listen(port, () => {
  return console.log(`server is listening on ${port}`);
});

/*
Uploads the files from the UI and saves it to S3
*/
app.post('/upload', upload.array('photo', 10000), function (req, res) {
  try {
    let storeObj = new Storage();
    for (let i = 0; i < req.files.length; i++) {
      storeObj.uploadFile(req.files[i].filename)
    }

    return res.json({
      'message': 'Files uploaded succesfully.'
    });
  } catch (err) {
    console.log(err);
    res.send(err);
  }
})

/*
End point to fetch messages from response queue
*/
app.get('/fetch', async function (req, res) {
  try {
    let queueObj = new Queue();
    let message = [];
    await queueObj.readResponse().then(messages => {
      message = messages;
    });

    if (message.length > 0) {
      message.forEach(msg => {
        queueObj.deleteFromQueue(msg.ReceiptHandle);
      });
    }
    res.send(message);
  } catch (err) {
    console.log(err);
    res.send(err);
  }
})

/*
End point to delete messages from response queue
*/
app.post('/delete', async function (req, res) {
  try {
    let queueObj = new Queue();
    await queueObj.deleteFromQueue(req.body.receiptHandle).then(messages => {
    });
  } catch (err) {
    console.log(err);
    res.send(err);
  }
})

/*
Handles all the transactions to the S3 bucket from the application
*/
export class Storage {

  /*
  Upload file to S3 bucket
  */
  uploadFile(fileName: string) {
    var readStream = fs.createReadStream(directory + fileName);
    const bucket = new S3(
      {
        accessKeyId: accessKeyId,
        secretAccessKey: secretAccessKey,
        region: region
      }
    );

    const params = {
      Bucket: bucketInput,
      Key: fileName,
      Body: readStream,
      ACL: 'public-read'
    };

    bucket.upload(params, this.cbFn.bind(this));
  }

  // Call back function for the call to add on the S3 bucket, on success, a call is made to add it to the request queue
  cbFn(err, data) {
    if (err) {
      console.log('There was an error uploading your file: ', err);
      return false;
    }
    let queueObj = new Queue();
    queueObj.addToQueue(data.key);
    return true;
  }
}

/*
Handles all the transactions to the SQS queues from the application
*/
export class Queue {
  sqs = new SQSClient({
    region: region,
    credentials: cred
  });

  async addToQueue(reqDetails: string) {
    const params = {
      MessageBody: reqDetails,
      QueueUrl: reqQueueURL,
    };

    try {
      await this.sqs.send(new SendMessageCommand(params));
    } catch (err) {
      console.log(err);
    }
  }

  /*
  Read response messages from the queue, gets a maximum of 10 messages at once
  */
  async readResponse(): Promise<Message[]> {
    let messages: Message[] = [];

    const params: ReceiveMessageRequest = {
      MaxNumberOfMessages: 10,
      QueueUrl: resQueueURL,
      VisibilityTimeout: 10,
      WaitTimeSeconds: 2,
    };

    try {
      let data = await this.sqs.send(new ReceiveMessageCommand(params));
      if (data.Messages) {
        messages = data.Messages;
      }
    } catch (err) {
      console.log("Receive Error", err);
    }
    return messages;
  }

  /*
  Deletes message from queue after processing
  */
  async deleteFromQueue(receiptHandle) {
    var deleteParams = {
      QueueUrl: resQueueURL,
      ReceiptHandle: receiptHandle,
    };
    try {
      await this.sqs.send(new DeleteMessageCommand(deleteParams));
    } catch (err) {
      console.log("Error", err);
    }
  }
}