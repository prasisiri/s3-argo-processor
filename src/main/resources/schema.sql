CREATE TABLE IF NOT EXISTS csv_records (
    id VARCHAR(50) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    amount DECIMAL(15,2) NOT NULL,
    timestamp TIMESTAMP NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Create index on timestamp for better query performance
CREATE INDEX IF NOT EXISTS idx_csv_records_timestamp ON csv_records(timestamp);

-- Create index on status for filtering
CREATE INDEX IF NOT EXISTS idx_csv_records_status ON csv_records(status);

-- Add trigger to update the updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_csv_records_updated_at
    BEFORE UPDATE ON csv_records
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column(); 