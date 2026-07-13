CREATE SCHEMA IF NOT EXISTS users;
REVOKE CREATE ON DATABASE home_search_user FROM PUBLIC;
GRANT CREATE ON DATABASE home_search_user TO home_search_user_migrator;
