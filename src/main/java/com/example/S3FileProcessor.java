package com.example;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.example.config.DatabaseConfig;
import com.example.model.CsvRecord;
import com.example.service.ErrorReportingService;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class S3FileProcessor {
    private static final Logger logger = LoggerFactory.getLogger(S3FileProcessor.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static ErrorReportingService errorReportingService;

    public static void main(String[] args) {
        String bucketName = System.getenv("S3_BUCKET_NAME");
        String fileKey = System.getenv("FILE_KEY");
        String region = System.getenv("AWS_REGION");
        String eventType = System.getenv("EVENT_TYPE");
        String errorReportsPrefix = System.getenv("ERROR_REPORTS_PREFIX");

        if (bucketName == null || fileKey == null || region == null || eventType == null) {
            logger.error("Required environment variables are missing");
            System.exit(1);
        }

        // Initialize error reporting service
        errorReportingService = new ErrorReportingService(
                region,
                bucketName,
                errorReportsPrefix != null ? errorReportsPrefix : "error-reports"
        );

        try {
            processFile(bucketName, fileKey, region, eventType);
        } catch (Exception e) {
            logger.error("Error processing file: {}", e.getMessage(), e);
            reportError(fileKey, eventType, e, null);
            System.exit(1);
        } finally {
            DatabaseConfig.close();
        }
    }

    private static void processFile(String bucketName, String fileKey, String region, String eventType) throws IOException {
        logger.info("Processing file: {} from bucket: {} (Event: {})", fileKey, bucketName, eventType);

        // Create S3 client
        AmazonS3 s3Client = AmazonS3ClientBuilder.standard()
                .withRegion(region)
                .build();

        // Create temporary file
        File localFile = File.createTempFile("s3-processor-", "-temp");

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

        } catch (Exception e) {
            Map<String, Object> additionalInfo = new HashMap<>();
            additionalInfo.put("bucketName", bucketName);
            additionalInfo.put("region", region);
            reportError(fileKey, eventType, e, additionalInfo);
            throw e;
        } finally {
            // Clean up temporary file
            if (localFile.exists()) {
                localFile.delete();
            }
        }
    }

    private static void processNewFile(File file) throws IOException {
        try {
            List<CsvRecord> records = parseCsvFile(file);
            saveToDatabase(records, false);
        } catch (Exception e) {
            Map<String, Object> additionalInfo = new HashMap<>();
            additionalInfo.put("processingType", "newFile");
            reportError(file.getName(), "ObjectCreated", e, additionalInfo);
            throw e;
        }
    }

    private static void processFileUpdate(File file) throws IOException {
        try {
            List<CsvRecord> records = parseCsvFile(file);
            saveToDatabase(records, true);
        } catch (Exception e) {
            Map<String, Object> additionalInfo = new HashMap<>();
            additionalInfo.put("processingType", "fileUpdate");
            reportError(file.getName(), "ObjectModified", e, additionalInfo);
            throw e;
        }
    }

    private static void processFile(File file) throws IOException {
        try {
            List<CsvRecord> records = parseCsvFile(file);
            saveToDatabase(records, false);
        } catch (Exception e) {
            Map<String, Object> additionalInfo = new HashMap<>();
            additionalInfo.put("processingType", "default");
            reportError(file.getName(), "Unknown", e, additionalInfo);
            throw e;
        }
    }

    private static List<CsvRecord> parseCsvFile(File file) throws IOException {
        List<CsvRecord> records = new ArrayList<>();
        
        try (Reader reader = new FileReader(file, StandardCharsets.UTF_8);
             CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT
                     .withFirstRecordAsHeader()
                     .withIgnoreHeaderCase()
                     .withTrim())) {

            for (CSVRecord csvRecord : csvParser) {
                try {
                    CsvRecord record = new CsvRecord();
                    record.setId(csvRecord.get("id"));
                    record.setName(csvRecord.get("name"));
                    record.setDescription(csvRecord.get("description"));
                    record.setAmount(Double.parseDouble(csvRecord.get("amount")));
                    record.setTimestamp(LocalDateTime.parse(csvRecord.get("timestamp"), DATE_FORMATTER));
                    record.setStatus(csvRecord.get("status"));
                    records.add(record);
                } catch (Exception e) {
                    Map<String, Object> additionalInfo = new HashMap<>();
                    additionalInfo.put("recordNumber", csvParser.getCurrentLineNumber());
                    additionalInfo.put("recordData", csvRecord.toMap());
                    reportError(file.getName(), "CSVParsing", e, additionalInfo);
                    throw e;
                }
            }
        }

        return records;
    }

    private static void saveToDatabase(List<CsvRecord> records, boolean isUpdate) {
        String sql = isUpdate ?
                "UPDATE csv_records SET name = ?, description = ?, amount = ?, timestamp = ?, status = ? WHERE id = ?" :
                "INSERT INTO csv_records (id, name, description, amount, timestamp, status) VALUES (?, ?, ?, ?, ?, ?)";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            conn.setAutoCommit(false);
            int batchSize = 1000;
            int count = 0;

            for (CsvRecord record : records) {
                try {
                    if (isUpdate) {
                        pstmt.setString(1, record.getName());
                        pstmt.setString(2, record.getDescription());
                        pstmt.setDouble(3, record.getAmount());
                        pstmt.setObject(4, record.getTimestamp());
                        pstmt.setString(5, record.getStatus());
                        pstmt.setString(6, record.getId());
                    } else {
                        pstmt.setString(1, record.getId());
                        pstmt.setString(2, record.getName());
                        pstmt.setString(3, record.getDescription());
                        pstmt.setDouble(4, record.getAmount());
                        pstmt.setObject(5, record.getTimestamp());
                        pstmt.setString(6, record.getStatus());
                    }

                    pstmt.addBatch();
                    count++;

                    if (count % batchSize == 0) {
                        pstmt.executeBatch();
                        conn.commit();
                    }
                } catch (SQLException e) {
                    Map<String, Object> additionalInfo = new HashMap<>();
                    additionalInfo.put("recordId", record.getId());
                    additionalInfo.put("isUpdate", isUpdate);
                    reportError("DatabaseOperation", "SQLException", e, additionalInfo);
                    throw e;
                }
            }

            // Execute remaining batch
            if (count % batchSize != 0) {
                pstmt.executeBatch();
                conn.commit();
            }

            logger.info("Successfully processed {} records", records.size());
        } catch (SQLException e) {
            logger.error("Error saving to database: {}", e.getMessage(), e);
            throw new RuntimeException("Database error", e);
        }
    }

    private static void reportError(String fileKey, String eventType, Exception error, Map<String, Object> additionalInfo) {
        if (errorReportingService != null) {
            errorReportingService.reportError(fileKey, eventType, error, additionalInfo);
        } else {
            logger.error("Error reporting service not initialized. Error details: {}", error.getMessage(), error);
        }
    }
}