# Stage 1: Build JAR using Maven
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build

# Copy Maven POM and wrapper files for layer caching
COPY pom.xml ./
COPY .mvn .mvn
COPY mvnw ./
RUN chmod +x mvnw || true

# Copy source code and build executable jar
COPY src ./src
RUN ./mvnw clean package -DskipTests || mvn clean package -DskipTests

# Stage 2: Runtime image
FROM eclipse-temurin:21-jre
WORKDIR /app

# Create non-root system group and user for security
RUN groupadd -r chronosgroup && useradd -r -g chronosgroup chronosuser

# Copy JAR artifact from build stage
COPY --from=builder /build/target/chronos-engine-*.jar /app/chronos-engine.jar

# Set ownership
RUN chown -R chronosuser:chronosgroup /app

USER chronosuser

EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["java", "-jar", "/app/chronos-engine.jar"]
