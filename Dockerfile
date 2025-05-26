# Use Java 21 base image
FROM eclipse-temurin:21-jdk

# Set working directory inside the container
WORKDIR /app

# Copy everything from your repo to the container
COPY . .

# Make Maven wrapper executable and build the project
RUN chmod +x ./mvnw && ./mvnw clean install -DskipTests

# Run the app
CMD ["java", "-jar", "target/IntFitout-Backend-0.0.1-SNAPSHOT.jar"]
