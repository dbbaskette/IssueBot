-- V13: Operator-defined local verification commands per repo (#60)

ALTER TABLE watched_repos ADD COLUMN verification_commands CLOB;
ALTER TABLE iterations ADD COLUMN local_check_result VARCHAR(20);
