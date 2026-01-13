#!/bin/bash
set -e
cd "$(dirname "$0")"

echo "Building project..."
mvn -DskipTests package -q

APP_JAR="target/hanoi-map-routing-1.0.0-SNAPSHOT-jar-with-dependencies.jar"

echo "Starting server..."
echo "Access the map at: http://localhost:4567"
java -jar "$APP_JAR"
