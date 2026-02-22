#!/bin/bash

echo "=========================================="
echo "Redis + Rate Limiting Setup & Test"
echo "=========================================="
echo ""

# Step 1: Start Redis
echo "Step 1: Starting Redis..."
echo "----------------------------------------"

# Check if Redis container already exists
if docker ps -a | grep -q redis-ratelimit; then
    echo "Redis container exists. Removing old container..."
    docker stop redis-ratelimit 2>/dev/null
    docker rm redis-ratelimit 2>/dev/null
fi

# Start fresh Redis container
echo "Starting new Redis container..."
docker run -d --name redis-ratelimit -p 6379:6379 redis:latest

# Wait for Redis to be ready
echo "Waiting for Redis to start..."
sleep 3

# Verify Redis is running
echo "Verifying Redis connection..."
if docker exec redis-ratelimit redis-cli ping | grep -q PONG; then
    echo "✅ Redis is running and responding on port 6379"
else
    echo "❌ Redis failed to start!"
    exit 1
fi

# Clear any old rate limit data
echo "Clearing old rate limit keys..."
docker exec redis-ratelimit redis-cli FLUSHALL > /dev/null
echo "✅ Redis cache cleared"

echo ""
echo "Step 2: Application Configuration"
echo "----------------------------------------"
echo "Current rate limit settings:"
grep "rate.limit" src/main/resources/application.properties | grep -v "^#"
echo ""

# Step 3: Restart Application
echo "Step 3: Application Status"
echo "----------------------------------------"
if curl -s http://localhost:8080/api/data > /dev/null 2>&1; then
    echo "⚠️  Application is already running"
    echo "   Please RESTART the application to apply new settings:"
    echo "   1. Stop the app (Ctrl+C in the terminal running it)"
    echo "   2. Run: ./mvnw spring-boot:run"
    echo ""
    read -p "Press Enter when application is restarted..."
else
    echo "Application is not running"
    echo "Start it with: ./mvnw spring-boot:run"
    exit 1
fi

echo ""
echo "Step 4: Testing Rate Limiting"
echo "=========================================="
echo "Configuration: 5 requests per 10 seconds"
echo "Expected: First 5 succeed (200), next 5 fail (429)"
echo "=========================================="
echo ""

success=0
failed=0

for i in {1..10}; do
    http_code=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/data)

    if [ "$http_code" = "200" ]; then
        echo "Request #$i: ✅ 200 OK"
        ((success++))
    elif [ "$http_code" = "429" ]; then
        echo "Request #$i: 🚫 429 RATE LIMITED"
        ((failed++))
    else
        echo "Request #$i: ⚠️  $http_code (unexpected)"
    fi

    sleep 0.2
done

echo ""
echo "=========================================="
echo "RESULTS"
echo "=========================================="
echo "✅ Successful: $success"
echo "🚫 Rate Limited: $failed"
echo ""

if [ "$success" -eq 5 ] && [ "$failed" -eq 5 ]; then
    echo "🎉 SUCCESS! Rate limiting is working correctly!"
elif [ "$success" -eq 10 ]; then
    echo "❌ FAILED! Rate limiting is NOT working"
    echo ""
    echo "Troubleshooting:"
    echo "1. Make sure you restarted the application after config change"
    echo "2. Check application logs for the line:"
    echo "   'Rate Limit Config - Capacity: 5, Refill: 5 tokens per 10s'"
    echo "3. If you see 'Capacity: 1' or other values, restart the app"
    echo ""
    echo "Redis Status:"
    docker exec redis-ratelimit redis-cli --scan --pattern "rl:*"
    echo ""
else
    echo "⚠️  Partial success ($success passed, $failed blocked)"
    echo "This might indicate timing issues or app restart during test"
fi

echo ""
echo "To view Redis keys:"
echo "  docker exec redis-ratelimit redis-cli KEYS 'rl:*'"
echo ""
echo "To monitor Redis in real-time:"
echo "  docker exec -it redis-ratelimit redis-cli MONITOR"
echo ""

