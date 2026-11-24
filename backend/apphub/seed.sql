-- Shopping List seed: the one operator-owned secret row, the join-code pepper. WRITTEN, NOT APPLIED (README.md ledger).
--
--     sudo mysql <database> < seed.sql
--
-- THE VALUE BELOW IS A PLACEHOLDER. The operator must REPLACE it with a random value, and the real value must NEVER be committed,
-- pasted into chat, put on a command line or kept in a file in this repository. The triggers refuse every code operation
-- (sl:server_not_ready) while the value starts with REPLACE, is shorter than 32 characters, or the row is missing, so forgetting the
-- replacement fails closed instead of hashing codes with a value that is public in git.
--
-- INSERT IGNORE: re-running this file never overwrites a pepper that was already replaced.
--
-- Replace it like this (provision.md step 7). The value is generated on the server and travels only through the shell's own
-- builtin printf and a pipe into mysql's standard input, so it is in no command line (ps) and no shell history; SET sql_log_bin = 0
-- keeps it out of the binary log; the general log must be OFF while you do it (provision.md check S9):
--     printf "SET SESSION sql_log_bin = 0;\nUPDATE app_secrets SET \`value\` = '%s' WHERE \`name\` = 'code_pepper';\n" "$(openssl rand -hex 32)" | sudo mysql <database>
--     sudo mysql -N -e "SELECT CHAR_LENGTH(\`value\`), \`value\` LIKE 'REPLACE%' FROM <database>.app_secrets WHERE \`name\` = 'code_pepper'"    # must print: 64  0
-- (Do not SELECT the value itself.) Rotating the pepper later invalidates every active code (they live 24 hours at most); nothing else
-- depends on it. A database dump contains the pepper next to the hashes: guard the dump like the database.

INSERT IGNORE INTO app_secrets (`name`, `value`) VALUES ('code_pepper', 'REPLACE-ME-the-operator-sets-a-random-64-hex-value-here-never-commit-it');
