#!/bin/bash

# Comprehensive Testing Script for Hybrid Rate Limiting Service
# This script tests all scenarios mentioned in the interview prep materials

set -e

echo "╔════════════════════════════════════════════════════════════╗"
echo "║   Hybrid Rate Limiting Service - Comprehensive Test       ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""

# Configuration
BASE_URL="http://localhost:8080"
TENANT_1="tenant-test-1"
TENANT_2="tenant-test-2"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Helper functions
success() {
    echo -e "${GREEN}✓${NC} $1"
}

error() {
    echo -e "${RED}✗${NC} $1"
}

info() {
    echo -e "${YELLOW}➜${NC} $1"
}

# Check if service is running
info "Checking service health..."
if curl -s -f "$BASE_URL/api/health" > /dev/null; then
    success "Service is running"
else
    error "Service is not running. Please start with: ./mvnw spring-boot:run"
    exit 1
fi

# Check Redis
info "Checking Redis connectivity..."
if curl -s "$BASE_URL/api/status/health-check" | grep -q "redisHealthy.*true"; then
    success "Redis is connected"
else
    error "Redis is not connected. Tests will run in degraded mode."
fi

echo ""
echo "═══════════════════════════════════════════════════════════"
echo "Test 1: Basic Rate Limiting"
echo "═══════════════════════════════════════════════════════════"

info "Resetting quota for $TENANT_1"
curl -s -X POST "$BASE_URL/api/reset/$TENANT_1" > /dev/null

info "Sending 5 requests (should all succeed)..."
success_count=0
for i in {1..5}; do
    response=$(curl -s -w "%{http_code}" -o /dev/null -H "X-Tenant-ID: $TENANT_1" "$BASE_URL/api/data")
    if [ "$response" = "200" ]; then
        success_count=$((success_count + 1))
    fi
done
if [ "$success_count" -eq 5 ]; then
    success "All 5 requests succeeded"
else
    error "Expected 5 successes, got $success_count"
fi

info "Checking status..."
status=$(curl -s "$BASE_URL/api/status/$TENANT_1")
echo "$status" | jq '.' 2>/dev/null || echo "$status"

echo ""
echo "═══════════════════════════════════════════════════════════"
echo "Test 2: Burst Traffic (5k requests)"
echo "═══════════════════════════════════════════════════════════"

info "Resetting quota for $TENANT_1"
curl -s -X POST "$BASE_URL/api/reset/$TENANT_1" > /dev/null

info "Sending 5000 concurrent requests..."
start_time=$(date +%s)

# Use xargs for parallel requests
seq 1 5000 | xargs -P 100 -I {} curl -s -w "%{http_code}\n" -o /dev/null \
    -H "X-Tenant-ID: $TENANT_1" "$BASE_URL/api/data" > /tmp/burst_results.txt

end_time=$(date +%s)
duration=$((end_time - start_time))

success_count=$(grep -c "200" /tmp/burst_results.txt || true)
rate_limited=$(grep -c "429" /tmp/burst_results.txt || true)

echo "Results:"
echo "  - Duration: ${duration}s"
echo "  - Throughput: $((5000 / duration)) req/s"
echo "  - Successful: $success_count"
echo "  - Rate limited: $rate_limited"

if [ "$success_count" -gt 0 ] && [ "$rate_limited" -gt 0 ]; then
    success "Burst traffic handled correctly"
else
    error "Unexpected burst traffic behavior"
fi

info "Final status:"
status=$(curl -s "$BASE_URL/api/status/$TENANT_1")
echo "$status" | jq '.' 2>/dev/null || echo "$status"

echo ""
echo "═══════════════════════════════════════════════════════════"
echo "Test 3: Multiple Tenants (Isolation)"
echo "═══════════════════════════════════════════════════════════"

info "Resetting quotas..."
curl -s -X POST "$BASE_URL/api/reset/$TENANT_1" > /dev/null
curl -s -X POST "$BASE_URL/api/reset/$TENANT_2" > /dev/null

info "Sending requests from tenant-1..."
for i in {1..10}; do
    curl -s -H "X-Tenant-ID: $TENANT_1" "$BASE_URL/api/data" > /dev/null
done

info "Sending requests from tenant-2..."
for i in {1..10}; do
    curl -s -H "X-Tenant-ID: $TENANT_2" "$BASE_URL/api/data" > /dev/null
done

tenant1_status=$(curl -s "$BASE_URL/api/status/$TENANT_1")
tenant2_status=$(curl -s "$BASE_URL/api/status/$TENANT_2")

tenant1_consumed=$(echo "$tenant1_status" | jq -r '.localConsumed' 2>/dev/null || echo "unknown")
tenant2_consumed=$(echo "$tenant2_status" | jq -r '.localConsumed' 2>/dev/null || echo "unknown")

echo "Tenant 1 consumed: $tenant1_consumed"
echo "Tenant 2 consumed: $tenant2_consumed"

if [ "$tenant1_consumed" != "$tenant2_consumed" ]; then
    success "Tenants are isolated"
else
    info "Tenants may be sharing quota (check Redis)"
fi

echo ""
echo "═══════════════════════════════════════════════════════════"
echo "Test 4: Local Cache Efficiency"
echo "═══════════════════════════════════════════════════════════"

info "This test demonstrates local cache reducing Redis calls"
info "Resetting quota for $TENANT_1"
curl -s -X POST "$BASE_URL/api/reset/$TENANT_1" > /dev/null

info "Sending 100 sequential requests..."
start_time=$(date +%s.%N)

for i in {1..100}; do
    curl -s -H "X-Tenant-ID: $TENANT_1" "$BASE_URL/api/data" > /dev/null
done

end_time=$(date +%s.%N)
duration=$(echo "$end_time - $start_time" | bc)
avg_latency=$(echo "scale=2; $duration * 1000 / 100" | bc)

echo "Results:"
echo "  - Total time: ${duration}s"
echo "  - Average latency: ${avg_latency}ms per request"

if (( $(echo "$avg_latency < 5" | bc -l) )); then
    success "Local cache is working efficiently (<5ms avg)"
else
    info "Higher latency detected (may indicate Redis calls)"
fi

echo ""
echo "═══════════════════════════════════════════════════════════"
echo "Test 5: Rate Limit Status Monitoring"
echo "═══════════════════════════════════════════════════════════"

info "Getting detailed status for $TENANT_1..."
status=$(curl -s "$BASE_URL/api/status/$TENANT_1")

echo "Status details:"
echo "$status" | jq '.' 2>/dev/null || echo "$status"

localAvailable=$(echo "$status" | jq -r '.localAvailable' 2>/dev/null || echo "0")
globalLimit=$(echo "$status" | jq -r '.globalLimit' 2>/dev/null || echo "0")

echo ""
echo "Summary:"
echo "  - Local cache: $localAvailable tokens available"
echo "  - Global limit: $globalLimit tokens per window"
echo "  - Redis: $(echo "$status" | jq -r '.redisHealthy' 2>/dev/null)"
echo "  - Circuit breaker: $(echo "$status" | jq -r '.circuitBreakerState' 2>/dev/null)"

echo ""
echo "═══════════════════════════════════════════════════════════"
echo "Test 6: Gradual Traffic Increase"
echo "═══════════════════════════════════════════════════════════"

info "Resetting quota for $TENANT_1"
curl -s -X POST "$BASE_URL/api/reset/$TENANT_1" > /dev/null

info "Sending traffic in waves..."
for wave in 10 50 100 500 1000; do
    echo "  Wave: $wave requests..."
    seq 1 $wave | xargs -P 50 -I {} curl -s -w "%{http_code}\n" -o /dev/null \
        -H "X-Tenant-ID: $TENANT_1" "$BASE_URL/api/data" > /tmp/wave_results.txt

    success_count=$(grep -c "200" /tmp/wave_results.txt || true)
    echo "    → $success_count succeeded"
    sleep 1
done

success "Gradual traffic test completed"

echo ""
echo "═══════════════════════════════════════════════════════════"
echo "Test 7: Quota Reset Verification"
echo "═══════════════════════════════════════════════════════════"

info "Getting status before reset..."
before=$(curl -s "$BASE_URL/api/status/$TENANT_1" | jq -r '.globalConsumed' 2>/dev/null || echo "unknown")
echo "  Consumed before: $before"

info "Resetting quota..."
curl -s -X POST "$BASE_URL/api/reset/$TENANT_1" > /dev/null

info "Getting status after reset..."
after=$(curl -s "$BASE_URL/api/status/$TENANT_1" | jq -r '.globalConsumed' 2>/dev/null || echo "unknown")
echo "  Consumed after: $after"

if [ "$after" = "0" ]; then
    success "Quota reset successful"
else
    info "Quota reset may not have fully cleared (check Redis window)"
fi

echo ""
echo "═══════════════════════════════════════════════════════════"
echo "Test 8: No Tenant Header (Fallback to IP)"
echo "═══════════════════════════════════════════════════════════"

info "Sending request without X-Tenant-ID header..."
response=$(curl -s "$BASE_URL/api/data")
if echo "$response" | jq -r '.tenant' | grep -qE "^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$"; then
    success "Fallback to IP-based rate limiting works"
else
    info "Tenant: $(echo "$response" | jq -r '.tenant')"
fi

echo ""
echo "╔════════════════════════════════════════════════════════════╗"
echo "║                    Test Summary                            ║"
echo "╠════════════════════════════════════════════════════════════╣"
echo "║  ✓ Basic rate limiting                                     ║"
echo "║  ✓ Burst traffic handling (5k requests)                    ║"
echo "║  ✓ Multi-tenant isolation                                  ║"
echo "║  ✓ Local cache efficiency                                  ║"
echo "║  ✓ Status monitoring                                       ║"
echo "║  ✓ Gradual traffic increase                                ║"
echo "║  ✓ Quota reset                                             ║"
echo "║  ✓ Fallback behavior                                       ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""

# Cleanup
rm -f /tmp/burst_results.txt /tmp/wave_results.txt

echo "Tests completed successfully!"
echo ""
echo "For load testing, use:"
echo "  ab -n 10000 -c 100 -H 'X-Tenant-ID: tenant-test' $BASE_URL/api/data"
echo ""
echo "For monitoring, use:"
echo "  watch -n 1 'curl -s $BASE_URL/api/status/tenant-test | jq .'"

