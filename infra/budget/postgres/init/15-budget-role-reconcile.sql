\getenv bootstrap_user POSTGRES_USER
\getenv bootstrap_password POSTGRES_PASSWORD
\getenv property_runtime_password PROPERTY_RUNTIME_DB_PASSWORD
\getenv property_migrator_password PROPERTY_MIGRATOR_DB_PASSWORD
\getenv property_importer_password PROPERTY_IMPORTER_DB_PASSWORD
\getenv ai_property_reader_password AI_PROPERTY_READER_DB_PASSWORD
\getenv user_runtime_password USER_RUNTIME_DB_PASSWORD
\getenv user_migrator_password USER_MIGRATOR_DB_PASSWORD
\getenv admin_runtime_password ADMIN_RUNTIME_DB_PASSWORD
\getenv admin_migrator_password ADMIN_MIGRATOR_DB_PASSWORD
\getenv ai_runtime_password AI_DATA_RUNTIME_DB_PASSWORD
\getenv ai_migrator_password AI_DATA_MIGRATOR_DB_PASSWORD
\getenv ai_importer_password AI_DATA_IMPORTER_DB_PASSWORD
\getenv backup_password BACKUP_DB_PASSWORD

BEGIN;
SELECT format('ALTER ROLE %I PASSWORD %L', :'bootstrap_user', :'bootstrap_password') \gexec
SELECT format('ALTER ROLE home_search_property_runtime PASSWORD %L', :'property_runtime_password') \gexec
SELECT format('ALTER ROLE home_search_property_migrator PASSWORD %L', :'property_migrator_password') \gexec
SELECT format('ALTER ROLE home_search_property_importer PASSWORD %L', :'property_importer_password') \gexec
SELECT format('ALTER ROLE home_search_ai_reader PASSWORD %L', :'ai_property_reader_password') \gexec
SELECT format('ALTER ROLE home_search_user_runtime PASSWORD %L', :'user_runtime_password') \gexec
SELECT format('ALTER ROLE home_search_user_migrator PASSWORD %L', :'user_migrator_password') \gexec
SELECT format('ALTER ROLE home_search_admin_runtime PASSWORD %L', :'admin_runtime_password') \gexec
SELECT format('ALTER ROLE home_search_admin_migrator PASSWORD %L', :'admin_migrator_password') \gexec
SELECT format('ALTER ROLE home_search_ai_runtime PASSWORD %L', :'ai_runtime_password') \gexec
SELECT format('ALTER ROLE home_search_ai_migrator PASSWORD %L', :'ai_migrator_password') \gexec
SELECT format('ALTER ROLE home_search_ai_importer PASSWORD %L', :'ai_importer_password') \gexec
SELECT format('ALTER ROLE home_search_backup PASSWORD %L', :'backup_password') \gexec
COMMIT;
