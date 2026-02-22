# Quick Start Guide - Testing Rate Limiting

## Prerequisites

### 1. Start Redis

**Option A: Using Docker (Recommended - Fastest)**
```bash
docker run -d --name redis-ratelimit -p 6379:6379 redis:latest

# Verify Redis is running
docker exec -it redis-ratelimit redis-cli ping
# Should return: PONG
```

**Option B: Install Redis with Homebrew (macOS)**
```bash
brew install redis
brew services start redis

# Verify Redis is running
redis-cli ping
# Should return: PONG
```

**Option C: Download Redis manually**
```bash
# Download and run Redis
curl -O http://download.redis.io/redis-stable.tar.gz
tar xzvf redis-stable.tar.gz
cd redis-stable
make
src/redis-server &
```

### 2. Start the Application

```bash
cd /Users/vishalkumarbg/Documents/codebases/ratelimit_demo/demo

# Start the Spring Boot application
./mvnw spring-boot:run
```

Wait for the log message: **"Started DemoApplication in X seconds"**

## Testing Rate Limiting

### Option 1: Use the Test Script (Automated)

In a new terminal:
```bash
cd /Users/vishalkumarbg/Documents/codebases/ratelimit_demo/demo
./test-ratelimit.sh
```

**Expected Output:**
```
Request #1: 200 OK ✓
Request #2: 200 OK ✓
Request #3: 200 OK ✓
Request #4: 200 OK ✓
Request #5: 200 OK ✓
Request #6: 429 Too Many Requests ✅
Request #7: 429 Too Many Requests ✅
Request #8: 429 Too Many Requests ✅
Request #9: 429 Too Many Requests ✅
Request #10: 429 Too Many Requests ✅
```

### Option 2: Manual Testing with curl

**Single request:**
```bash
curl http://localhost:8080/api/data
# Response: Here is the protected data!
```

**Test rate limit (10 rapid requests):**
```bash
for i in {1..10}; do
  echo "Request $i:"
  curl -w " - HTTP %{http_code}\n" http://localhost:8080/api/data
done
```

**Test with detailed output:**
```bash
for i in {1..10}; do
  echo "========== Request #$i =========="
  curl -i http://localhost:8080/api/data
  echo ""
  sleep 0.1
done
```

## Current Rate Limit Settings

```properties
rate.limit.capacity=5           # 5 tokens in bucket
rate.limit.refill.tokens=5      # Refill 5 tokens
rate.limit.refill.duration=10s  # Every 10 seconds
```

**This means:**
- You can make **5 requests immediately**
- Then you must wait **10 seconds** for the bucket to refill
- After 10 seconds, you get **5 more tokens**

## Understanding the Results

### ✅ SUCCESS (200 OK)
```bash
$ curl http://localhost:8080/api/data
Here is the protected data!
```
- Token consumed successfully
- Request processed
- Remaining tokens decreased

### ❌ RATE LIMITED (429 Too Many Requests)
```bash
$ curl http://localhost:8080/api/data
{"error":"Too many requests"}
```
- No tokens available
- Request blocked
- Must wait for refill

## Verify Rate Limiting is Working

### Check Redis Keys

While the app is running, open another terminal:

```bash
# If using Docker
docker exec -it redis-ratelimit redis-cli

# If using local Redis
redis-cli
```

Then in Redis CLI:
```redis
# List all rate limit keys
KEYS rl:*

# Check a specific bucket (replace with your IP)
GET rl:127.0.0.1

# Monitor Redis operations in real-time
MONITOR
# (Press Ctrl+C to stop)
```

You should see keys like: `rl:127.0.0.1`

### Check Application Logs

In the Spring Boot console, you should see:
- Redis connection established
- Filter processing requests
- No errors

## Troubleshooting

### Issue: All requests return 200 (no 429)

**Possible causes:**

1. **Application not restarted after config change**
   ```bash
   # Stop the app (Ctrl+C) and restart
   ./mvnw spring-boot:run
   ```

2. **Redis not connected**
   - Check Redis is running: `redis-cli ping`
   - Check application logs for Redis connection errors
   - Verify Redis config in `application.properties`

3. **Different IP addresses**
   - Rate limiting is per IP
   - Each IP has its own bucket
   - Solution: Use same client or reduce capacity to 1

4. **Timing issue (requests too slow)**
   - If requests are >2 seconds apart, bucket may refill
   - Solution: Use the test script or make requests faster

### Issue: Connection refused to Redis

```bash
# Start Redis
docker run -d --name redis-ratelimit -p 6379:6379 redis:latest

# Or with Homebrew
brew services start redis
```

### Issue: Cannot find /api/data endpoint

Check if ApiController has the endpoint:
```bash
grep -r "api/data" src/main/java/
```

If not found, the endpoint might be `/api/greet` instead:
```bash
curl http://localhost:8080/api/greet
```

## Change Rate Limit Settings

Edit `src/main/resources/application.properties`:

### Very restrictive (for testing):
```properties
rate.limit.capacity=2
rate.limit.refill.tokens=2
rate.limit.refill.duration=30s
```
*(2 requests per 30 seconds)*

### Moderate (default):
```properties
rate.limit.capacity=5
rate.limit.refill.tokens=5
rate.limit.refill.duration=10s
```
*(5 requests per 10 seconds = 30/minute)*

### High throughput (production):
```properties
rate.limit.capacity=1000
rate.limit.refill.tokens=1000
rate.limit.refill.duration=1s
```
*(1000 requests per second)*

**Important:** After changing settings, restart the application!

## Advanced Testing

### Test Multiple IPs (Distributed Rate Limiting)

```bash
# Simulate different clients using X-Forwarded-For header
for ip in 192.168.1.{1..3}; do
  echo "Testing IP: $ip"
  for i in {1..7}; do
    curl -H "X-Forwarded-For: $ip" http://localhost:8080/api/data
  done
  echo "---"
done
```

Each IP should have its own rate limit bucket.

### Performance Testing

```bash
# Install Apache Bench
brew install ab

# Test with 100 requests, 10 concurrent
ab -n 100 -c 10 http://localhost:8080/api/data

# Check how many got 200 vs 429
```

## Summary Commands

```bash
# 1. Start Redis (Docker)
docker run -d --name redis-ratelimit -p 6379:6379 redis:latest

# 2. Start Application
./mvnw spring-boot:run

# 3. Test Rate Limiting (in new terminal)
./test-ratelimit.sh

# 4. Clean up
docker stop redis-ratelimit
docker rm redis-ratelimit
```

That's it! You should now see rate limiting in action with 429 errors after the 5th request.

