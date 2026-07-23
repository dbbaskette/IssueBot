UPDATE processing_control
SET state = 'STOPPED'
WHERE state = 'PAUSED';
