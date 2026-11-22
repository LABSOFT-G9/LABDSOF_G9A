#!/bin/bash
# Local MySQL only (compose.local.yaml): one database and one user per
# service, as on the database host. Run by the mysql image on the first start
# with an empty volume. The passwords come from .env.
set -euo pipefail

# The image's temporary init server listens on the local socket only. The root
# password goes through the environment, never the command line.
export MYSQL_PWD="${MYSQL_ROOT_PASSWORD}"

for name in issue dispatch notification keycloak; do
  var="${name^^}_DB_PASSWORD"
  password="${!var}"
  # Quote for SQL: double any single quote.
  password="${password//\'/\'\'}"
  echo "bmc: creating database and user ${name}"
  mysql --protocol=socket -uroot <<SQL
CREATE DATABASE IF NOT EXISTS \`${name}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS '${name}'@'%' IDENTIFIED BY '${password}';
GRANT ALL PRIVILEGES ON \`${name}\`.* TO '${name}'@'%';
SQL
done
