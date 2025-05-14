package com.example;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class S3FileProcessor {
    private static final Logger logger = LoggerFactory.getLogger(S3FileProcessor.class);

    public static void main(String[] args) {
        String bucketName = System.getenv("S3_BUCKET_NAME");
        String fileKey = System.getenv("FILE_KEY");
        String region = System.getenv("AWS_REGION");
        String eventType = System.getenv("EVENT_TYPE");

        if (bucketName == null || fileKey == null || region == null || eventType == null) {
            logger.error("Required environment variables are missing");
            System.exit(1);
        }

        try {
            processFile(bucketName, fileKey, region, eventType);
        } catch (Exception e) {
            logger.error("Error processing file: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    private static void processFile(String bucketName, String fileKey, String region, String eventType) throws IOException {
        logger.info("Processing file: {} from bucket: {} (Event: {})", fileKey, bucketName, eventType);

        // Create S3 client
        AmazonS3 s3Client = AmazonS3ClientBuilder.standard()
                .withRegion(region)
                .build();

        // Create temporary file
        Path tempFile = Files.createTempFile("s3-processor-", "-temp");
        File localFile = tempFile.toFile();

        try {
            // Download file from S3
            logger.info("Downloading file from S3");
            S3Object s3Object = s3Client.getObject(new GetObjectRequest(bucketName, fileKey));
            
            try (InputStream inputStream = s3Object.getObjectContent();
                 FileOutputStream outputStream = new FileOutputStream(localFile)) {
                
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            }

            // Process file based on event type
            switch (eventType) {
                case "ObjectCreated:Put":
                case "ObjectCreated:CompleteMultipartUpload":
                    logger.info("Processing new file upload");
                    processNewFile(localFile);
                    break;
                case "ObjectModified:Put":
                case "ObjectModified:CompleteMultipartUpload":
                    logger.info("Processing file update");
                    processFileUpdate(localFile);
                    break;
                default:
                    logger.warn("Unhandled event type: {}", eventType);
                    processFile(localFile);
            }

            logger.info("Successfully processed file: {}", fileKey);

        } finally {
            // Clean up temporary file
            if (localFile.exists()) {
                localFile.delete();
            }
        }
    }

    private static void processNewFile(File file) throws IOException {
        // TODO: Add your new file processing logic here
        processFile(file);
    }

    private static void processFileUpdate(File file) throws IOException {
        // TODO: Add your file update processing logic here
        processFile(file);
    }

    private static void processFile(File file) throws IOException {
        // TODO: Add your common file processing logic here
        // This is the default processing logic used for all file operations
    }
}