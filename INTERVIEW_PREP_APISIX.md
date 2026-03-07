# APISIX Configuration for Rate Limiting Service
## Interview Prep Material - Gateway Integration

---

## Table of Contents
1. [APISIX Architecture](#apisix-architecture)
2. [Plugin Chain Configuration](#plugin-chain-configuration)
3. [Consistent Hashing Setup](#consistent-hashing-setup)
4. [Kubernetes Service Configuration](#kubernetes-service-configuration)
5. [Testing APISIX Setup](#testing-apisix-setup)

---

## APISIX Architecture

```
┌────────────────────────────────────────────────────────────┐
│                    APISIX Gateway                          │
│                                                            │
│  ┌──────────────────────────────────────────────────────┐ │
│  │  Plugin Chain (Order Matters!)                       │ │
│  │                                                      │ │
│  │  1. jwt-auth              ─── Validate JWT token    │ │
│  │  2. serverless-pre-func   ─── Extract tenant ID    │ │
│  │  3. proxy-rewrite         ─── Set X-Tenant-ID      │ │
│  │  4. chash (upstream)      ─── Consistent hashing   │ │
│  └──────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────┘
                          │
                          │ X-Tenant-ID: tenant-123
                          │
                          ▼
          ┌───────────────────────────────┐
          │  Kubernetes Service           │
          │  (Session Affinity)           │
          └───────────────────────────────┘
                          │
        ┌─────────────────┼─────────────────┐
        │                 │                 │
        ▼                 ▼                 ▼
    ┌────────┐        ┌────────┐        ┌────────┐
    │ Pod 1  │        │ Pod 2  │        │ Pod 3  │
    │ tenant │        │ tenant │        │ tenant │
    │  -123  │        │  -456  │        │  -789  │
    └────────┘        └────────┘        └────────┘
```

---

## Plugin Chain Configuration

### Step 1: JWT Authentication Plugin

**Purpose:** Validate JWT token and extract claims

```yaml
# APISIX Route Configuration
routes:
  - uri: /api/*
    name: rate-limit-service
    plugins:
      # Step 1: Authenticate with JWT
      jwt-auth:
        header: Authorization
        query: jwt
        cookie: jwt
        # JWT secret configured in consumer
```

**JWT Payload Example:**
```json
{
  "sub": "user-12345",
  "tenant_id": "tenant-123",
  "exp": 1678901234,
  "iat": 1678897634
}
```

---

### Step 2: Extract Tenant ID (Serverless Function)

**Purpose:** Extract tenant_id from JWT claims and set as header

```yaml
plugins:
  serverless-pre-function:
    phase: rewrite
    functions:
      - |
        return function(conf, ctx)
          local core = require("apisix.core")
          
          -- Get JWT claims from context
          local jwt_obj = ctx.var.jwt_obj
          
          if jwt_obj and jwt_obj.tenant_id then
            -- Set tenant ID in request context
            ctx.var.tenant_id = jwt_obj.tenant_id
            
            core.log.info("Extracted tenant ID: ", jwt_obj.tenant_id)
          else
            core.log.warn("No tenant_id in JWT claims")
            -- Return 401 if tenant_id missing
            return 401, {error = "Missing tenant_id in token"}
          end
        end
```

**Alternative: Simple Lua Script**
```lua
-- apisix/plugins/extract-tenant.lua
local core = require("apisix.core")

local plugin_name = "extract-tenant"

local schema = {
    type = "object",
    properties = {
        header_name = {
            type = "string",
            default = "X-Tenant-ID"
        }
    }
}

local _M = {
    version = 0.1,
    priority = 2500,  -- Execute after jwt-auth
    name = plugin_name,
    schema = schema,
}

function _M.rewrite(conf, ctx)
    local jwt_obj = ctx.var.jwt_obj
    
    if not jwt_obj then
        return 401, {error = "No JWT token"}
    end
    
    local tenant_id = jwt_obj.tenant_id
    if not tenant_id then
        return 401, {error = "Missing tenant_id in token"}
    end
    
    -- Set header for upstream
    core.request.set_header(ctx, conf.header_name, tenant_id)
    
    -- Store in context for consistent hashing
    ctx.var.tenant_id = tenant_id
    
    return
end

return _M
```

---

### Step 3: Proxy Rewrite (Set Header)

**Purpose:** Ensure X-Tenant-ID header is sent to upstream

```yaml
plugins:
  proxy-rewrite:
    headers:
      set:
        X-Tenant-ID: $tenant_id  # Use variable from context
```

---

## Consistent Hashing Setup

### Method 1: APISIX chash Balancer

**Configuration:**
```yaml
# APISIX Upstream Configuration
upstreams:
  - name: rate-limit-pods
    type: chash  # Consistent hashing
    hash_on: vars  # Hash based on variable
    key: tenant_id  # Use tenant_id variable
    nodes:
      "ratelimit-service.default.svc.cluster.local:8080": 1
    # OR individual pod IPs:
    # nodes:
    #   "10.0.1.10:8080": 1  # Pod 1
    #   "10.0.1.11:8080": 1  # Pod 2
    #   "10.0.1.12:8080": 1  # Pod 3
    
    checks:
      active:
        healthy:
          interval: 2
          successes: 2
        unhealthy:
          interval: 1
          http_failures: 2
```

**How it works:**
```python
# Pseudo-code
pod_index = hash(tenant_id) % num_pods

# Example:
hash("tenant-123") = 0xA3F2
0xA3F2 % 3 = 0  → Pod 1

hash("tenant-456") = 0xB8C1
0xB8C1 % 3 = 2  → Pod 3
```

---

### Method 2: Kubernetes Service Session Affinity

**Use APISIX for routing, K8s for stickiness**

```yaml
# k8s-service.yaml
apiVersion: v1
kind: Service
metadata:
  name: ratelimit-service
  namespace: default
spec:
  selector:
    app: ratelimit
  ports:
    - protocol: TCP
      port: 8080
      targetPort: 8080
  sessionAffinity: ClientIP  # Enable session affinity
  sessionAffinityConfig:
    clientIP:
      timeoutSeconds: 10800  # 3 hours
```

**APISIX Configuration:**
```yaml
upstreams:
  - name: rate-limit-pods
    type: roundrobin  # K8s handles stickiness
    service_name: ratelimit-service.default.svc.cluster.local
    discovery_type: dns
```

**Issue with this approach:**
- Session affinity based on client IP, not tenant ID
- Multiple tenants from same IP → Not ideal
- **Recommendation:** Use Method 1 (chash)

---

### Method 3: Custom Lua Logic

**For complex routing logic:**

```lua
-- apisix/plugins/tenant-router.lua
local core = require("apisix.core")
local crc32 = require("apisix.core.crc32")

function _M.balance(conf, ctx)
    local tenant_id = ctx.var.tenant_id
    
    if not tenant_id then
        return nil, "No tenant_id"
    end
    
    -- Get list of upstream nodes
    local nodes = ctx.upstream_nodes
    local node_count = #nodes
    
    -- Consistent hash
    local hash = crc32(tenant_id)
    local index = (hash % node_count) + 1
    
    -- Select node
    local selected_node = nodes[index]
    
    core.log.info("Routing tenant ", tenant_id, " to node ", selected_node.host)
    
    return selected_node
end
```

---

## Kubernetes Service Configuration

### Deployment

```yaml
# ratelimit-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ratelimit-service
  namespace: default
spec:
  replicas: 3
  selector:
    matchLabels:
      app: ratelimit
  template:
    metadata:
      labels:
        app: ratelimit
    spec:
      containers:
      - name: ratelimit
        image: your-registry/ratelimit-service:latest
        ports:
        - containerPort: 8080
        env:
        - name: SPRING_DATA_REDIS_HOST
          value: "redis-cluster.default.svc.cluster.local"
        - name: SPRING_DATA_REDIS_PORT
          value: "6379"
        - name: RATE_LIMIT_DEFAULT_LIMIT
          value: "10000"
        - name: RATE_LIMIT_CHUNK_PERCENT
          value: "10"
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
        livenessProbe:
          httpGet:
            path: /api/health
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /api/health
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 5
```

### Service (Headless for Direct Pod Access)

```yaml
# ratelimit-service.yaml
apiVersion: v1
kind: Service
metadata:
  name: ratelimit-service
  namespace: default
spec:
  clusterIP: None  # Headless service for direct pod access
  selector:
    app: ratelimit
  ports:
  - protocol: TCP
    port: 8080
    targetPort: 8080
```

**Why headless?**
- APISIX can discover individual pod IPs
- Better for consistent hashing
- No extra hop through kube-proxy

---

## Complete APISIX Configuration

```yaml
# apisix-config.yaml
routes:
  - id: ratelimit-route
    uri: /api/*
    name: rate-limit-service
    methods:
      - GET
      - POST
      - PUT
      - DELETE
    plugins:
      # 1. JWT Authentication
      jwt-auth:
        header: Authorization
        query: jwt
        cookie: jwt
      
      # 2. Extract tenant ID
      serverless-pre-function:
        phase: rewrite
        functions:
          - |
            return function(conf, ctx)
              local core = require("apisix.core")
              local jwt_obj = ctx.var.jwt_obj
              
              if jwt_obj and jwt_obj.tenant_id then
                ctx.var.tenant_id = jwt_obj.tenant_id
                core.request.set_header(ctx, "X-Tenant-ID", jwt_obj.tenant_id)
              else
                return 401, {error = "Missing tenant_id"}
              end
            end
      
      # 3. Request logging (optional)
      http-logger:
        uri: http://log-service:8080/logs
        batch_max_size: 100
        inactive_timeout: 5
    
    upstream_id: ratelimit-upstream

upstreams:
  - id: ratelimit-upstream
    name: rate-limit-pods
    type: chash
    hash_on: vars
    key: tenant_id
    
    # Service discovery
    service_name: ratelimit-service.default.svc.cluster.local
    discovery_type: dns
    
    # Health checks
    checks:
      active:
        type: http
        http_path: /api/health
        healthy:
          interval: 2
          successes: 2
        unhealthy:
          interval: 1
          http_failures: 2
      passive:
        healthy:
          http_statuses:
            - 200
            - 201
            - 204
        unhealthy:
          http_statuses:
            - 500
            - 503
          http_failures: 3

# JWT Consumer (for testing)
consumers:
  - username: test-user
    plugins:
      jwt-auth:
        key: test-key
        secret: your-secret-key-here
        algorithm: HS256
```

---

## Testing APISIX Setup

### 1. Generate JWT Token

```bash
# Using jwt.io or a script
# Payload:
{
  "sub": "user-123",
  "tenant_id": "tenant-123",
  "exp": 1999999999
}

# Example token:
TOKEN="eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyLTEyMyIsInRlbmFudF9pZCI6InRlbmFudC0xMjMiLCJleHAiOjE5OTk5OTk5OTl9.xxx"
```

### 2. Test Request

```bash
# Send request through APISIX
curl -H "Authorization: Bearer $TOKEN" \
     http://apisix-gateway:9080/api/data

# Expected response:
{
  "message": "Here is the protected data!",
  "tenant": "tenant-123",
  "timestamp": 1678901234567
}
```

### 3. Verify Consistent Hashing

```bash
# Send multiple requests from same tenant
for i in {1..10}; do
  curl -H "Authorization: Bearer $TOKEN" \
       http://apisix-gateway:9080/api/data
done

# Check APISIX logs to verify same pod handles all requests
kubectl logs -l app=apisix -n apisix | grep "tenant-123"

# Should show same pod IP for all requests:
# Routing tenant-123 to 10.0.1.10:8080
# Routing tenant-123 to 10.0.1.10:8080
# Routing tenant-123 to 10.0.1.10:8080
```

### 4. Test Different Tenants

```bash
# Tenant 1
TOKEN1="eyJ...tenant-123..."
curl -H "Authorization: Bearer $TOKEN1" http://apisix:9080/api/data

# Tenant 2
TOKEN2="eyJ...tenant-456..."
curl -H "Authorization: Bearer $TOKEN2" http://apisix:9080/api/data

# Verify different pods:
# tenant-123 → Pod 1
# tenant-456 → Pod 3
```

### 5. Verify Rate Limiting

```bash
# Burst 100 requests
TENANT_TOKEN="eyJ...tenant-test..."
for i in {1..100}; do
  curl -H "Authorization: Bearer $TENANT_TOKEN" \
       http://apisix:9080/api/data &
done
wait

# After hitting limit, should get:
# HTTP 429 Too Many Requests
# {"error":"Too many requests","tenant":"tenant-test"}
```

### 6. Check Rate Limit Status

```bash
# Get status for tenant
curl http://ratelimit-service:8080/api/status/tenant-123

# Response:
{
  "tenantId": "tenant-123",
  "localAvailable": 850,
  "localConsumed": 150,
  "globalConsumed": 2340,
  "globalLimit": 10000,
  "redisHealthy": true,
  "circuitBreakerState": "CLOSED"
}
```

---

## APISIX Consistent Hash Verification

### Check APISIX Routing Table

```bash
# Connect to APISIX pod
kubectl exec -it apisix-pod -n apisix -- /bin/sh

# Check upstream configuration
curl http://127.0.0.1:9180/apisix/admin/upstreams/ratelimit-upstream

# Response shows hash distribution
{
  "type": "chash",
  "hash_on": "vars",
  "key": "tenant_id",
  "nodes": {
    "10.0.1.10:8080": 1,
    "10.0.1.11:8080": 1,
    "10.0.1.12:8080": 1
  }
}
```

### Monitor APISIX Logs

```bash
# Enable debug logging
kubectl edit configmap apisix-config -n apisix

# Set log level to debug:
apisix:
  log_level: debug

# Tail logs
kubectl logs -f apisix-pod -n apisix | grep tenant_id

# Look for:
# [debug] tenant_id: tenant-123
# [debug] selected node: 10.0.1.10:8080
```

---

## Key Interview Points

### 1. **Why APISIX for routing?**
**Answer:**
- Centralized authentication (JWT validation)
- Consistent hashing at gateway level
- Reduces load on backend services
- Easier to change routing logic without code deployment
- Built-in monitoring and observability

---

### 2. **Why consistent hashing instead of round-robin?**
**Answer:**

**Round-robin problem:**
```
Request 1 (tenant-123) → Pod 1 → Local cache miss → Redis allocate 1000
Request 2 (tenant-123) → Pod 2 → Local cache miss → Redis allocate 1000
Request 3 (tenant-123) → Pod 3 → Local cache miss → Redis allocate 1000

Total allocated: 3000 tokens
Limit: 10000
Efficiency: 33% (each pod allocated separately)
```

**Consistent hashing solution:**
```
Request 1 (tenant-123) → Pod 1 → Local cache miss → Redis allocate 1000
Request 2 (tenant-123) → Pod 1 → Local cache hit → No Redis call
Request 3 (tenant-123) → Pod 1 → Local cache hit → No Redis call

Total allocated: 1000 tokens
Limit: 10000
Efficiency: 90%+ (local cache reuse)
```

---

### 3. **What happens during pod scaling?**

**Scale up (3 → 5 pods):**
```
Before: hash(tenant-123) % 3 = 1 → Pod 1
After:  hash(tenant-123) % 5 = 3 → Pod 3

Impact:
- tenant-123 moves from Pod 1 to Pod 3
- Pod 1 local cache becomes unused (garbage collected)
- Pod 3 starts fresh, allocates from Redis
- No data loss (Redis has global state)
```

**Mitigation:**
```
Use consistent hash ring instead of simple modulo
Only ~1/N tenants are remapped (N = new pod count)
```

---

### 4. **APISIX vs Application-level routing?**

**APISIX (Gateway):**
- ✅ Centralized logic
- ✅ No code changes
- ✅ JWT validation at edge
- ✅ Built-in observability
- ❌ Extra network hop

**Application-level:**
- ✅ No extra hop
- ✅ More control
- ❌ JWT parsing in every pod
- ❌ Harder to change logic
- ❌ Need custom implementation

**Recommendation:** Use APISIX for clean separation of concerns

---

## Summary

```
┌────────────────────────────────────────────────────────┐
│  APISIX Configuration Checklist                        │
├────────────────────────────────────────────────────────┤
│  ✓ JWT authentication plugin                          │
│  ✓ Extract tenant_id from JWT claims                  │
│  ✓ Set X-Tenant-ID header                             │
│  ✓ Configure consistent hashing (chash)               │
│  ✓ K8s headless service for pod discovery             │
│  ✓ Health checks for upstream pods                    │
│  ✓ Logging for debugging                              │
└────────────────────────────────────────────────────────┘
```

---

End of APISIX Configuration Guide

