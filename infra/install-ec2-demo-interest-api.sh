#!/bin/bash
set -eu

if [ "$(id -u)" -ne 0 ]; then
  echo "Run this installer with sudo." >&2
  exit 1
fi

dnf install -y unzip
curl --fail --location --silent --show-error \
  https://github.com/LindokuhleMPMahlangu/Saved2ServeConnect/archive/refs/heads/main.zip \
  --output /tmp/saved2serve-main.zip
rm -rf /tmp/saved2serve-source
unzip -q /tmp/saved2serve-main.zip -d /tmp/saved2serve-source

release_dir=/tmp/saved2serve-source/Saved2ServeConnect-main
install -d -m 0755 /var/www/saved2serve /opt/saved2serve
cp -a "$release_dir/web/." /var/www/saved2serve/
install -m 0644 "$release_dir/infra/ec2_demo_interest_api.py" \
  /opt/saved2serve/ec2_demo_interest_api.py

cat > /var/www/saved2serve/config.js <<'CONFIG'
window.SAVED2SERVE_CONFIG = Object.freeze({
  apiBaseUrl: "",
  demoMode: true,
  bedrockDraftsEnabled: false,
  churchesPath: "/v1/churches",
  devotionsPath: "/v1/devotions",
  cognito: Object.freeze({
    domain: "",
    clientId: "",
    redirectUri: "",
    logoutRedirectUri: "",
    scopes: "saved2serve/leader.read saved2serve/leader.write"
  })
});
CONFIG

cat > /etc/systemd/system/saved2serve-demo-api.service <<'UNIT'
[Unit]
Description=Save2Serve non-persistent demonstration API
After=network.target

[Service]
Type=simple
ExecStart=/usr/bin/python3 /opt/saved2serve/ec2_demo_interest_api.py
Restart=on-failure
RestartSec=3
User=nginx
Group=nginx
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true

[Install]
WantedBy=multi-user.target
UNIT

cat > /etc/nginx/conf.d/saved2serve.conf <<'NGINX'
limit_req_zone $binary_remote_addr zone=interest_demo:10m rate=5r/m;

server {
    listen 80 default_server;
    server_name _;
    root /var/www/saved2serve;
    index index.html;
    access_log off;

    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "DENY" always;
    add_header Referrer-Policy "no-referrer" always;
    add_header Permissions-Policy "camera=(), microphone=(), geolocation=()" always;

    location = /v1/interests {
        limit_req zone=interest_demo burst=3 nodelay;
        limit_req_status 429;
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
    }

    location = /healthz {
        proxy_pass http://127.0.0.1:8081;
    }

    location / {
        try_files $uri $uri/ =404;
    }
}
NGINX

nginx -t
systemctl daemon-reload
systemctl enable --now saved2serve-demo-api
systemctl reload nginx
curl --fail --silent --show-error http://127.0.0.1/healthz >/dev/null
echo "Demo API installed. It validates and discards requests; it does not save submissions."
