package com.example.myapp;

import java.util.Objects;

public class ProjectInstances {
    private final String inputBucketName;
    private final String outputBucketName;
    private final String requestQueueUrl;
    private final String responseQueueUrl;
    private final String webInstanceId;
    private final String controllerInstanceId;
    private final String appInstanceId;

    public ProjectInstances(ProjectInstancesBuilder builder) {
        this.inputBucketName = builder.inputBucketName;
        this.outputBucketName = builder.outputBucketName;
        this.requestQueueUrl = builder.requestQueueUrl;
        this.responseQueueUrl = builder.responseQueueUrl;
        this.webInstanceId = builder.webInstanceId;
        this.controllerInstanceId = builder.controllerInstanceId;
        this.appInstanceId = builder.appInstanceId;
    }

    public String getInputBucketName() {
        return this.inputBucketName;
    }

    public String getOutputBucketName() {
        return this.outputBucketName;
    }

    public String getRequestQueueUrl() {
        return this.requestQueueUrl;
    }

    public String getResponseQueueUrl() {
        return this.responseQueueUrl;
    }

    public String getWebInstanceId() {
        return this.webInstanceId;
    }

    public String getControllerInstanceId() {
        return this.controllerInstanceId;
    }

    public String getAppInstanceId() {
        return this.appInstanceId;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this)
            return true;
        if (!(o instanceof ProjectInstances)) {
            return false;
        }
        ProjectInstances projectInstances = (ProjectInstances) o;
        return Objects.equals(inputBucketName, projectInstances.inputBucketName) && Objects.equals(outputBucketName, projectInstances.outputBucketName) && Objects.equals(requestQueueUrl, projectInstances.requestQueueUrl) && Objects.equals(responseQueueUrl, projectInstances.responseQueueUrl) && Objects.equals(webInstanceId, projectInstances.webInstanceId) && Objects.equals(controllerInstanceId, projectInstances.controllerInstanceId) && Objects.equals(appInstanceId, projectInstances.appInstanceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(inputBucketName, outputBucketName, requestQueueUrl, responseQueueUrl, webInstanceId, controllerInstanceId, appInstanceId);
    }

    @Override
    public String toString() {
        return "{" +
            " inputBucketName='" + getInputBucketName() + "'" +
            ", outputBucketName='" + getOutputBucketName() + "'" +
            ", requestQueueUrl='" + getRequestQueueUrl() + "'" +
            ", responseQueueUrl='" + getResponseQueueUrl() + "'" +
            ", webInstanceId='" + getWebInstanceId() + "'" +
            ", controllerInstanceId='" + getControllerInstanceId() + "'" +
            ", appInstanceId='" + getAppInstanceId() + "'" +
            "}";
    }

    public static class ProjectInstancesBuilder {
        private String inputBucketName;
        private String outputBucketName;
        private String requestQueueUrl;
        private String responseQueueUrl;
        private String webInstanceId;
        private String controllerInstanceId;
        private String appInstanceId;

        public ProjectInstancesBuilder inputBucketName(String inputBucketName) {
            this.inputBucketName = inputBucketName;
            return this;
        }

        public ProjectInstancesBuilder outputBucketName(String outputBucketName) {
            this.outputBucketName = outputBucketName;
            return this;
        }
    
        public ProjectInstancesBuilder requestQueueUrl(String requestQueueUrl) {
            this.requestQueueUrl = requestQueueUrl;
            return this;
        }

        public ProjectInstancesBuilder responseQueueUrl(String responseQueueUrl) {
            this.responseQueueUrl = responseQueueUrl;
            return this;
        }

        public ProjectInstancesBuilder webInstanceId(String webInstanceId) {
            this.webInstanceId = webInstanceId;
            return this;
        }

        public ProjectInstancesBuilder controllerInstanceId(String controllerInstanceId) {
            this.controllerInstanceId = controllerInstanceId;
            return this;
        }

        public ProjectInstancesBuilder appInstanceId(String appInstanceId) {
            this.appInstanceId = appInstanceId;
            return this;
        }

        public ProjectInstances build() {
            ProjectInstances projectInstances =  new ProjectInstances(this);
            return projectInstances;
        }
    }
}
