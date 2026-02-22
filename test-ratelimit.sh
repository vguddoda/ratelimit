#!/bin/bash

# Test Rate Limiting - Should see 429 errors after 5 requests

echo "=========================================="
echo "Testing Rate Limit (5 requests per 10s)"
echo "=========================================="
echo ""

for i in {1..10}; do
  echo "Request #$i:"
  response=$(curl -s -w "\nHTTP_STATUS:%{http_code}" http://localhost:8080/api/data)
  http_code=$(echo "$response" | grep HTTP_STATUS | cut -d':' -f2)
  body=$(echo "$response" | sed '/HTTP_STATUS/d')

  echo "  Status: $http_code"
  echo "  Response: $body"

  if [ "$http_code" = "429" ]; then
    echo "  ✅ Rate limit triggered!"
  else
    echo "  ✓ Request allowed"
  fi

  echo ""
  sleep 0.2
done

echo "=========================================="
echo "Summary: First 5 should pass, rest should be 429"
echo "=========================================="

