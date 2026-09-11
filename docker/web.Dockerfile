# syntax=docker/dockerfile:1

# ---------------------------------------------------------------- Bauen
FROM node:24-alpine AS build

WORKDIR /build

COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend/ ./
RUN npm run build

# ---------------------------------------------------------------- Ausliefern
FROM nginx:alpine

COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist/frontend/browser /usr/share/nginx/html

# nginx:alpine bringt den unprivilegierten Benutzer bereits mit. Der Container lauscht
# dadurch auf 8080 statt 80 -- Ports unter 1024 braeuchten Wurzelrechte.
RUN chown -R nginx:nginx /usr/share/nginx/html /var/cache/nginx \
 && touch /var/run/nginx.pid && chown nginx:nginx /var/run/nginx.pid

USER nginx

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:8080/ || exit 1
