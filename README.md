# Rate Limiting Demo with Bucket4j and Redis

A Spring Boot application demonstrating distributed rate limiting using Bucket4j and Redis.

## Features

- ✅ **Distributed Rate Limiting**: Uses Redis for rate limit state storage, enabling rate limiting across multiple application instances
- ✅ **Per-Client Rate Limiting**: Each client IP address gets its own rate limit bucket
- ✅ **Configurable Limits**: Easily adjust rate limit capacity and refill rate via application properties
- ✅ **Production-Ready**: Handles proxy headers (X-Forwarded-For, X-Real-IP) for accurate client IP detection

## Prerequisites

- Java 17 or higher
- Maven 3.6+
- Redis server (running on localhost:6379 or configured in application.properties)

## Quick Start

### 1. Start Redis

**Using Docker:**
```bash
docker run -d -p 6379:6379 redis:latest
```

**Using Homebrew (macOS):**
```bash
brew install redis
brew services start redis
```

**Using apt (Ubuntu/Debian):**
```bash
sudo apt-get install redis-server
sudo systemctl start redis
```

### 2. Build and Run the Application

```bash
# Clean and install dependencies
./mvnw clean install

# Run the application
./mvnw spring-boot:run
```

The application will start on `http://localhost:8080`

## Configuration

Edit `src/main/resources/application.properties` to customize settings:

```properties
# Redis Configuration
spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.data.redis.timeout=60000

# Rate Limit Configuration
rate.limit.capacity=5              # Maximum tokens in bucket
rate.limit.refill.tokens=5         # Number of tokens to refill
rate.limit.refill.duration=1m      # Refill interval (s=seconds, m=minutes, h=hours)
```

## Testing the Rate Limit

### Test with curl:

```bash
# Make multiple requests to hit the rate limit
for i in {1..10}; do
  curl -i http://localhost:8080/api/greet
  echo ""
done
```

### Expected Behavior:

- **First 5 requests**: Return `200 OK` with response body
- **Requests 6-10**: Return `429 Too Many Requests` with error message:
  ```json
  {"error": "Too many requests. Please try again later."}
  ```

### Monitor Redis:

```bash
# Connect to Redis CLI
redis-cli

# List all keys
KEYS *

# View a specific rate limit bucket
GET rate_limit:127.0.0.1
```

## API Endpoints

### GET /api/greet
Returns a greeting message if rate limit is not exceeded.

**Success Response:**
```
Status: 200 OK
Body: Hello! This is a rate-limited API.
```

**Rate Limit Exceeded:**
```
Status: 429 Too Many Requests
Body: {"error": "Too many requests. Please try again later."}
```

## Architecture

### Components

1. **RateLimitConfig**: Configures Redis client and ProxyManager for distributed bucket management
2. **RateLimitingFilter**: Servlet filter that intercepts requests and applies rate limiting per client IP
3. **ApiController**: Simple REST controller with rate-limited endpoints

### How It Works

1. Each incoming request passes through `RateLimitingFilter`
2. The filter extracts the client IP address (considering proxy headers)
3. A unique Redis key is created: `rate_limit:{client_ip}`
4. Bucket4j checks the Redis bucket for available tokens
5. If tokens are available, the request proceeds; otherwise, returns 429

### Benefits of Redis-backed Rate Limiting

- **Distributed**: Works across multiple application instances
- **Persistent**: Rate limit state survives application restarts
- **Scalable**: Centralized state management
- **Accurate**: All instances share the same view of rate limits

## Dependencies

- Spring Boot 3.3.3
- Bucket4j 8.10.1 (Core + Redis)
- Spring Data Redis
- Lettuce (Redis client)

## Troubleshooting

### Redis Connection Error
```
Error: Unable to connect to Redis at localhost:6379
```
**Solution**: Ensure Redis is running and accessible:
```bash
redis-cli ping
# Should return: PONG
```

### Rate Limit Not Working
- Check Redis connection in application logs
- Verify Redis keys are being created: `redis-cli KEYS rate_limit:*`
- Check application.properties configuration

## Development

### Run Tests
```bash
./mvnw test
```

### Build JAR
```bash
./mvnw clean package
java -jar target/ratelimit-0.0.1-SNAPSHOT.jar
```

## License

This is a demo project for educational purposes.

