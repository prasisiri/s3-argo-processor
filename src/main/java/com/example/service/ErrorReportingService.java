package com.example.service;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

public class ErrorReportingService {
    private static final Logger logger = LoggerFactory.getLogger(ErrorReportingService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
    private final AmazonS3 s3Client;
    private final String bucketName;
    private final String errorReportsPrefix;
    private final ObjectMapper objectMapper;

    public ErrorReportingService(String region, String bucketName, String errorReportsPrefix) {
        this.s3Client = AmazonS3ClientBuilder.standard()
                .withRegion(region)
                .build();
        this.bucketName = bucketName;
        this.errorReportsPrefix = errorReportsPrefix;
        this.objectMapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public void reportError(String fileKey, String eventType, Exception error, Map<String, Object> additionalInfo) {
        try {
            Map<String, Object> errorReport = new HashMap<>();
            errorReport.put("timestamp", LocalDateTime.now().toString());
            errorReport.put("fileKey", fileKey);
            errorReport.put("eventType", eventType);
            errorReport.put("errorType", error.getClass().getName());
            errorReport.put("errorMessage", error.getMessage());
            errorReport.put("stackTrace", getStackTraceAsString(error));
            
            if (additionalInfo != null) {
                errorReport.putAll(additionalInfo);
            }

            String reportJson = objectMapper.writeValueAsString(errorReport);
            String reportKey = String.format("%s/%s-error-report.json",
                    errorReportsPrefix,
                    LocalDateTime.now().format(DATE_FORMATTER));

            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType("application/json");
            metadata.setContentLength(reportJson.getBytes().length);

            PutObjectRequest putRequest = new PutObjectRequest(
                    bucketName,
                    reportKey,
                    new ByteArrayInputStream(reportJson.getBytes()),
                    metadata
            );

            s3Client.putObject(putRequest);
            logger.info("Error report uploaded to s3://{}/{}", bucketName, reportKey);
        } catch (Exception e) {
            logger.error("Failed to generate error report: {}", e.getMessage(), e);
        }
    }

    private String getStackTraceAsString(Exception e) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : e.getStackTrace()) {
            sb.append(element.toString()).append("\n");
        }
        return sb.toString();
    }
} 