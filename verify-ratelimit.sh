#!/bin/bash

echo "================================================"
echo "Rate Limiting Troubleshooting & Test Script"
echo "================================================"
echo ""

# Check if Redis is running
echo "1. Checking Redis connection..."
if command -v redis-cli &> /dev/null; then
    if redis-cli ping &> /dev/null; then
        echo "   ✅ Redis is running and accessible"
    else
        echo "   ❌ Redis is not responding"
        echo "   Run: brew services start redis"
        echo "   Or: docker run -d --name redis-ratelimit -p 6379:6379 redis:latest"
        exit 1
    fi
elif docker ps | grep -q redis; then
    echo "   ✅ Redis is running in Docker"
else
    echo "   ❌ Redis is not running"
    echo "   Run: docker run -d --name redis-ratelimit -p 6379:6379 redis:latest"
    exit 1
fi

# Clear Redis rate limit keys
echo ""
echo "2. Clearing old rate limit keys from Redis..."
if command -v redis-cli &> /dev/null; then
    redis-cli --scan --pattern "rl:*" | xargs -r redis-cli del &> /dev/null
    echo "   ✅ Redis keys cleared"
else
    docker exec redis-ratelimit redis-cli --scan --pattern "rl:*" | xargs -r docker exec -i redis-ratelimit redis-cli del &> /dev/null
    echo "   ✅ Redis keys cleared (Docker)"
fi

# Check if application is running
echo ""
echo "3. Checking if application is running..."
if curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/data &> /dev/null; then
    echo "   ✅ Application is running on port 8080"
else
    echo "   ⚠️  Application is not responding"
    echo "   Start it with: ./mvnw spring-boot:run"
    echo ""
    read -p "   Do you want to start the application now? (y/n) " -n 1 -r
    echo ""
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        echo "   Starting application..."
        ./mvnw spring-boot:run &
        echo "   Waiting for application to start..."
        sleep 10
    else
        exit 1
    fi
fi

# Test rate limiting
echo ""
echo "4. Testing Rate Limiting (5 requests allowed per 10 seconds)..."
echo "================================================"
echo ""

success_count=0
ratelimit_count=0

for i in {1..10}; do
  response=$(curl -s -w "\nHTTP_CODE:%{http_code}" http://localhost:8080/api/data 2>&1)
  http_code=$(echo "$response" | grep HTTP_CODE | cut -d':' -f2)

  if [ -z "$http_code" ]; then
    echo "Request #$i: ❌ ERROR - Application not responding"
    continue
  fi

  if [ "$http_code" = "200" ]; then
    echo "Request #$i: ✅ 200 OK - Request allowed"
    ((success_count++))
  elif [ "$http_code" = "429" ]; then
    echo "Request #$i: 🚫 429 TOO MANY REQUESTS - Rate limited!"
    ((ratelimit_count++))
  else
    echo "Request #$i: ⚠️  $http_code - Unexpected response"
  fi

  sleep 0.1
done

echo ""
echo "================================================"
echo "Results Summary:"
echo "================================================"
echo "✅ Allowed:      $success_count requests"
echo "🚫 Rate Limited: $ratelimit_count requests"
echo ""

if [ "$success_count" -eq 5 ] && [ "$ratelimit_count" -eq 5 ]; then
    echo "🎉 SUCCESS! Rate limiting is working correctly!"
    echo "   - First 5 requests passed (as expected)"
    echo "   - Last 5 requests were blocked (as expected)"
elif [ "$success_count" -eq 10 ]; then
    echo "⚠️  PROBLEM: All requests passed - Rate limiting NOT working!"
    echo ""
    echo "Troubleshooting steps:"
    echo "1. Make sure you restarted the application after changing config"
    echo "2. Check application logs for errors"
    echo "3. Verify Redis connection in the logs"
    echo "4. Check application.properties has: rate.limit.capacity=5"
elif [ "$ratelimit_count" -eq 10 ]; then
    echo "⚠️  PROBLEM: All requests blocked - Configuration may be too strict"
else
    echo "⚠️  UNEXPECTED RESULT"
    echo "   This could mean:"
    echo "   - Application restarted during test"
    echo "   - Network issues"
    echo "   - Timing issues (bucket refilled during test)"
fi

echo ""
echo "To view Redis keys:"
if command -v redis-cli &> /dev/null; then
    echo "  redis-cli KEYS 'rl:*'"
else
    echo "  docker exec redis-ratelimit redis-cli KEYS 'rl:*'"
fi

echo ""
echo "To monitor Redis in real-time:"
if command -v redis-cli &> /dev/null; then
    echo "  redis-cli MONITOR"
else
    echo "  docker exec -it redis-ratelimit redis-cli MONITOR"
fi

echo ""

