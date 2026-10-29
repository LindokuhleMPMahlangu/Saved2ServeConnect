#!/bin/bash
set -eu

dnf install -y nginx unzip

curl --fail --location --silent --show-error \
  https://github.com/LindokuhleMPMahlangu/Saved2ServeConnect/archive/refs/heads/main.zip \
  --output /tmp/saved2serve-main.zip
unzip -q /tmp/saved2serve-main.zip -d /tmp/saved2serve-source

install -d -m 0755 /var/www/saved2serve
cp -a /tmp/saved2serve-source/Saved2ServeConnect-main/web/. /var/www/saved2serve/

rm -f /etc/nginx/conf.d/default.conf
cat > /etc/nginx/conf.d/saved2serve.conf <<'NGINX'
server {
    listen 80 default_server;
    server_name _;
    root /var/www/saved2serve;
    index index.html;

    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "DENY" always;
    add_header Referrer-Policy "no-referrer" always;
    add_header Permissions-Policy "camera=(), microphone=(), geolocation=()" always;

    location / {
        try_files $uri $uri/ =404;
    }
}
NGINX

nginx -t
systemctl enable --now nginx
