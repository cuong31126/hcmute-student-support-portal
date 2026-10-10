# ==========================================
# GIAI ĐOẠN 1: Biên dịch và Đóng gói file JAR
# ==========================================
FROM maven:3.9.6-eclipse-temurin-17-alpine AS builder

WORKDIR /build

# 1. Tận dụng Docker Cache cho dependencies (pom.xml)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 2. Copy toàn bộ mã nguồn và biên dịch đóng gói
COPY src ./src
RUN mvn clean package -DskipTests -B

# ==========================================
# GIAI ĐOẠN 2: Runtime image siêu nhẹ (<180MB)
# ==========================================
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Tạo user không có đặc quyền root để bảo mật
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Copy file JAR đã đóng gói từ Stage 1
COPY --from=builder /build/target/*.jar app.jar

# Biến môi trường JVM tối ưu cho máy chủ t3.micro (1GB RAM + 2GB Swap)
ENV JAVA_OPTS="-Xms200m -Xmx400m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
ENV SERVER_PORT=8080

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
