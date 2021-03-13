#!/bin/bash

echo export SQS_REQUEST_QUEUE_URL=$SQS_REQUEST_QUEUE_URL >> /etc/profile
echo export SQS_RESPONSE_QUEUE_URL=$SQS_RESPONSE_QUEUE_URL >> /etc/profile
echo export S3_INPUT_BUCKET_NAME=$S3_INPUT_BUCKET_NAME >> /etc/profile
echo export S3_OUTPUT_BUCKET_NAME=$S3_OUTPUT_BUCKET_NAME >> /etc/profile

export SQS_REQUEST_QUEUE_URL=$SQS_REQUEST_QUEUE_URL
export SQS_RESPONSE_QUEUE_URL=$SQS_RESPONSE_QUEUE_URL
export S3_INPUT_BUCKET_NAME=$S3_INPUT_BUCKET_NAME
export S3_OUTPUT_BUCKET_NAME=$S3_OUTPUT_BUCKET_NAME

sleep 1m
IP=$(curl http://169.254.169.254/latest/meta-data/public-ipv4)

sed -i 's|requestqueueurl|'$SQS_REQUEST_QUEUE_URL'|g' /usr/share/nginx/www/assets/config/config.json
sed -i 's|responsequeueurl|'$SQS_RESPONSE_QUEUE_URL'|g' /usr/share/nginx/www/assets/config/config.json
sed -i 's|ipaddress|'$IP'|g' /usr/share/nginx/www/assets/config/config.json
sed -i 's|outputs3url|'$S3_OUTPUT_BUCKET_NAME'|g' /usr/share/nginx/www/assets/config/config.json
sed -i 's|inputs3url|'$S3_INPUT_BUCKET_NAME'|g' /usr/share/nginx/www/assets/config/config.json
systemctl restart nginx
pm2 start /root/nodejsapp/dist/app.js 
java -jar /root/controller-1.0-SNAPSHOT-jar-with-dependencies.jar start 