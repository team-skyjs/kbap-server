#!/usr/bin/env python3
import base64, hashlib, hmac, json, os, sys, time

secret = os.environ.get("JWT_SECRET") or sys.exit("env JWT_SECRET required")
member_id = sys.argv[1] if len(sys.argv) > 1 else sys.exit("usage: mint-token.py <memberId> [ttl_hours]")
ttl_hours = int(sys.argv[2]) if len(sys.argv) > 2 else 2

def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()

now = int(time.time())
header = b64(json.dumps({"alg": "HS256"}).encode())
payload = b64(json.dumps({
    "sub": member_id,
    "token_type": "ACCESS",
    "role": "USER",
    "iat": now,
    "exp": now + ttl_hours * 3600,
}).encode())
sig = b64(hmac.new(secret.encode(), f"{header}.{payload}".encode(), hashlib.sha256).digest())
print(f"{header}.{payload}.{sig}")
