import { Component, OnInit } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { MatTableDataSource } from '@angular/material/table';

@Component({
  selector: 'app-file-upload',
  templateUrl: './file-upload.component.html',
  styleUrls: ['./file-upload.component.css']
})
export class FileUploadComponent implements OnInit {

  filesToUpload: FileList = null; // Variables to store where the files will be uploaded
  timeOut; // Timeout object
  displayedColumns: string[] = ['index', 'picture', 'result']; //Table column configuration
  nodeBaseURL = ''; // Node app URL, it is taken from config.json
  dataSource: MatTableDataSource<ImageClassificationResult> = new MatTableDataSource(); // Table data source, image results are appended to this
  processedMessages = []; // Processed message array to filter out duplicates
  config = null; // Config variable to store the json config
  inputS3Url = ''; // S3 input bucket link
  outputS3Url = ''; // S3 output bucket link
  retryCount = 0;
  constructor(private httpClient: HttpClient) {
    this.httpClient.get("assets/config/config.json").subscribe(data => { // Get json config and set values
      this.config = data;
      this.inputS3Url = this.config.inputS3Url;
      this.outputS3Url = this.config.outputS3Url;
      this.nodeBaseURL = this.config.nodeBaseURL;
    });
  }

  // Function to get files from the html control and set it to the typescript variable
  handleFileInput(files: FileList) {
    this.filesToUpload = files; 
  }

  // Repoll for response messages
  rePoll() {
    this.retryCount = 0;
    this.getResponse();
  }

  // Upload Function called on click of the upload button in the UI
  uploadFile() {
    this.processedMessages = [];
    this.dataSource.data = [];
    this.dataSource.data = this.dataSource.data; // Reset the table to empty
    const formData = new FormData();
    if (this.filesToUpload) {
      for (let i = 0; i < this.filesToUpload.length; i++) {
        formData.append("photo", this.filesToUpload[i], this.filesToUpload[i].name);
      }
      this.httpClient.post(this.nodeBaseURL + this.config.uploadEndpoint, formData).subscribe(); // Call made to the backend upload method
      this.getResponse(); // Make call to listen to response queue
    }
  }

  // Check if a message has been processed or not (Check for duplicate)
  isProcessed(message: string): boolean {
    return this.processedMessages.includes(message);
  }

  // Get messages in response queue, delete them after displaying and handle duplicate responses
  getResponse() {
    if (this.retryCount <= 25) { // Setting a threshold for recursive call, if no result is received in 25 api calls(25 seconds), polling terminates
      this.httpClient.get(this.nodeBaseURL + this.config.getEndpoint) // Gets messa
        .subscribe((messages: any[]) => {
          if (messages.length > 0) {
            this.retryCount = 0;
            messages.forEach(message => {
              if (!this.isProcessed(message.Body)) { // Not duplicate
                this.processedMessages.push(message.Body); // Add to processed
                const values = message.Body.trim().split(':'); // Get the input and output file name
                const row = new ImageClassificationResult();
                let result = "";
                row.picture = this.inputS3Url + values[0]; // Get the input image from input bucket and display
                this.httpClient.get(this.outputS3Url + values[1], { responseType: 'text' }).subscribe(data => { //Read the content of output file
                  const res = data.split(",")
                  row.result = data;
                  this.dataSource.data.push(row); // Push a new row to the table with the image and its corresponding result.
                  this.dataSource.data = this.dataSource.data;
                }, error => {
                  // give error message 
                });

              } else {
                this.retryCount += 1; // As the result was duplicate, increase retry count by 1
              }
            })
          } else {
            this.retryCount += 1; // As the result was empty, increase retry count by 1
          }
        });
      this.timeOut = setTimeout(this.getResponse.bind(this), 1000); // Calls the function recursively every 1 second.
    }
  }

  ngOnInit(): void {
  }
}

// Model for the results table
export class ImageClassificationResult {
  picture: string;
  result: string;
}
