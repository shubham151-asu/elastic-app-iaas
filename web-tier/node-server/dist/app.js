"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    Object.defineProperty(o, k2, { enumerable: true, get: function() { return m[k]; } });
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || function (mod) {
    if (mod && mod.__esModule) return mod;
    var result = {};
    if (mod != null) for (var k in mod) if (k !== "default" && Object.hasOwnProperty.call(mod, k)) __createBinding(result, mod, k);
    __setModuleDefault(result, mod);
    return result;
};
var __awaiter = (this && this.__awaiter) || function (thisArg, _arguments, P, generator) {
    function adopt(value) { return value instanceof P ? value : new P(function (resolve) { resolve(value); }); }
    return new (P || (P = Promise))(function (resolve, reject) {
        function fulfilled(value) { try { step(generator.next(value)); } catch (e) { reject(e); } }
        function rejected(value) { try { step(generator["throw"](value)); } catch (e) { reject(e); } }
        function step(result) { result.done ? resolve(result.value) : adopt(result.value).then(fulfilled, rejected); }
        step((generator = generator.apply(thisArg, _arguments || [])).next());
    });
};
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.Queue = exports.Storage = void 0;
const express_1 = __importDefault(require("express"));
const fs = __importStar(require("fs"));
const aws_sdk_1 = require("aws-sdk");
const client_sqs_1 = require("@aws-sdk/client-sqs");
const aws_sdk_2 = require("aws-sdk");
const app = express_1.default();
const bodyParser = require("body-parser"); // Parses request body
const multer = require('multer'); // Using multer as middleware for file upload
const homedir = require('os').homedir();
const directory = homedir + '/uploads/';
if (!fs.existsSync(directory)) {
    fs.mkdirSync(directory);
}
const storage = multer.diskStorage({
    destination: function (req, file, cb) {
        cb(null, directory);
    },
    filename: function (req, file, cb) {
        cb(null, file.originalname);
    }
});
const upload = multer({ storage: storage });
const port = 3000;
const accessKeyId = "AKIAYAZP4H4PTE4DQM2P";
const secretAccessKey = "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";
const region = "us-east-2";
const resQueueURL = "https://sqs.us-east-2.amazonaws.com/551466843935/autoscaler-response-queue";
const reqQueueURL = "https://sqs.us-east-2.amazonaws.com/551466843935/autoscaler-request-queue";
const bucketInput = "autoscaler-image-bucket-input";
const cred = new aws_sdk_2.Credentials({
    secretAccessKey: secretAccessKey,
    accessKeyId: accessKeyId
});
const cors = require('cors'); //Set CORS policy for the app
app.use(cors());
app.options('*', cors());
app.use(bodyParser.json());
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
            storeObj.uploadFile(req.files[i].filename);
        }
        return res.json({
            'message': 'Files uploaded succesfully.'
        });
    }
    catch (err) {
        console.log(err);
        res.send(err);
    }
});
/*
End point to fetch messages from response queue
*/
app.get('/fetch', function (req, res) {
    return __awaiter(this, void 0, void 0, function* () {
        try {
            let queueObj = new Queue();
            let message = [];
            yield queueObj.readResponse().then(messages => {
                message = messages;
            });
            if (message.length > 0) {
                message.forEach(msg => {
                    queueObj.deleteFromQueue(msg.ReceiptHandle);
                });
            }
            res.send(message);
        }
        catch (err) {
            console.log(err);
            res.send(err);
        }
    });
});
/*
End point to delete messages from response queue
*/
app.post('/delete', function (req, res) {
    return __awaiter(this, void 0, void 0, function* () {
        try {
            let queueObj = new Queue();
            yield queueObj.deleteFromQueue(req.body.receiptHandle).then(messages => {
            });
        }
        catch (err) {
            console.log(err);
            res.send(err);
        }
    });
});
class Storage {
    uploadFile(fileName) {
        var readStream = fs.createReadStream(directory + fileName);
        const bucket = new aws_sdk_1.S3({
            accessKeyId: accessKeyId,
            secretAccessKey: secretAccessKey,
            region: region
        });
        const params = {
            Bucket: bucketInput,
            Key: fileName,
            Body: readStream,
            ACL: 'public-read'
        };
        bucket.upload(params, this.cbFn.bind(this));
    }
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
exports.Storage = Storage;
class Queue {
    constructor() {
        this.sqs = new client_sqs_1.SQSClient({
            region: region,
            credentials: cred
        });
    }
    addToQueue(reqDetails) {
        return __awaiter(this, void 0, void 0, function* () {
            const params = {
                MessageBody: reqDetails,
                QueueUrl: reqQueueURL,
            };
            try {
                yield this.sqs.send(new client_sqs_1.SendMessageCommand(params));
            }
            catch (err) {
                console.log(err);
            }
        });
    }
    readResponse() {
        return __awaiter(this, void 0, void 0, function* () {
            let messages = [];
            const params = {
                MaxNumberOfMessages: 10,
                QueueUrl: resQueueURL,
                VisibilityTimeout: 10,
                WaitTimeSeconds: 2,
            };
            try {
                let data = yield this.sqs.send(new client_sqs_1.ReceiveMessageCommand(params));
                if (data.Messages) {
                    messages = data.Messages;
                }
            }
            catch (err) {
                console.log("Receive Error", err);
            }
            return messages;
        });
    }
    deleteFromQueue(receiptHandle) {
        return __awaiter(this, void 0, void 0, function* () {
            var deleteParams = {
                QueueUrl: resQueueURL,
                ReceiptHandle: receiptHandle,
            };
            try {
                yield this.sqs.send(new client_sqs_1.DeleteMessageCommand(deleteParams));
            }
            catch (err) {
                console.log("Error", err);
            }
        });
    }
}
exports.Queue = Queue;
//# sourceMappingURL=app.js.map